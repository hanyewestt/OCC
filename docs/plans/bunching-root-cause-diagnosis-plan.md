# Bunching Root Cause Diagnosis Plan

## 1. Goal

When the bus-bunching detector opens a confirmed bunching episode, create a durable,
idempotent diagnosis that ranks likely contributing causes, reports calibrated
confidence, preserves its evidence and model version, and can safely abstain when the
available evidence is insufficient.

The output is decision support. It must say **likely contributing cause**, not claim
that correlation proves causation. A diagnosis must never change the deterministic
bunching result, open an operational incident, or issue a driver directive.

## 2. Repository findings that shape the design

- `bus-bunching-detection` already persists an `OPENED`/`CLEARED` `alert_event` in the
  same transaction as the evaluated evidence. This is the correct upstream event.
- PostgreSQL `NOTIFY bunching_alert` is deliberately only a wake-up. It is lossy and
  is not a durable cross-service contract.
- The detector currently has no HTTP server or alert-event API. It is configured as
  `web-application-type: none`.
- The ingestion service already demonstrates the desired cross-service pattern: a
  versioned internal HTTP API, DTOs, pagination, a durable cursor, and a domain port in
  the consumer rather than cross-schema database access.
- Runtime ingestion currently supplies bus-arrival observations and topology. Traffic
  incidents, traffic speed, weather, schedules and stable fleet telemetry are not yet
  implemented as diagnosis-ready service contracts.
- `SimulatedData/route190` contains useful traffic-incident and fleet-telemetry
  fixtures, but only one labeled breakdown/bunching narrative is not a training set.
- The detector explicitly does not have stable vehicle identity. The diagnosis model
  must not invent bus, driver or trip identity from arrival slots.
- Existing classifications are `OBSERVATION_ONLY`. Diagnosis must preserve that
  classification and must not mix simulation evidence with live evidence.

## 3. Recommended architecture

Create a new Spring Boot service named `services/bunching-diagnosis` with its own
`diagnosis` PostgreSQL schema and database role.

```text
arrival snapshots
    -> deterministic bunching detection
    -> bunching.alert_event (OPENED)
    -> transactional diagnosis-trigger outbox
    -> authenticated internal HTTP request
    -> diagnosis request persisted (202 Accepted)
    -> feature snapshot assembled from versioned evidence APIs
    -> DiagnosticModelPort
    -> calibrated confidence + abstention policy
    -> diagnosis.bunching_diagnosis
    -> read API / controller UI integration
```

### Why use a new service

Detection and diagnosis have different failure modes and release cadences. Detection
must remain deterministic and available even when model loading, contextual data, or
inference fails. The diagnosis service also needs model/version lifecycle, feature
snapshots, confidence evaluation and potentially heavier dependencies that do not
belong in the detector.

### Trigger design

Use an outbox-backed HTTP handoff for the first production slice:

1. In the same transaction that inserts an `OPENED` `alert_event`, insert one outbox
   row keyed by `alert_event_id`. Do not enqueue `CLEARED` as a new root-cause job.
2. A detector-side scheduled dispatcher sends an authenticated request to
   `POST /internal/v1/diagnosis-requests`.
3. The diagnosis service persists the request before returning `202 Accepted`.
4. A uniqueness constraint on `alert_event_id` makes retries idempotent.
5. The detector marks the outbox row delivered only after an accepted/idempotent
   response. Exponential retry with jitter handles outages; exhausted deliveries stay
   visible for operations rather than being deleted.
6. Diagnosis happens asynchronously after acceptance, so slow inference never holds
   the detector transaction or dispatcher connection.

`LISTEN/NOTIFY` may later reduce latency, but it must not be the only delivery path.
Directly reading the `bunching` schema from the new service is prohibited. If the
project adopts Kafka or RabbitMQ later, the outbox publisher can change without
changing detection or diagnosis domain contracts.

The detector must also expose versioned internal read endpoints for alert evidence and
any required evaluation details. Provider/database types must not cross the API.

## 4. Diagnosis taxonomy

Treat the following as a proposed version-1 taxonomy requiring controller/domain-owner
sign-off and labeled examples before training:

| Cause | Evidence needed | Current readiness |
| --- | --- | --- |
| `TRAFFIC_INCIDENT` | Incident overlaps route corridor and causal time window; speed/headway degrades after onset | Simulation fixture only |
| `RECURRENT_CONGESTION` | Low corridor speeds without a matching discrete incident, compared with route/time baseline | Not ingested |
| `VEHICLE_FAULT` | Vehicle fault/SOS/breakdown evidence tied to a stable trip or vehicle | Simulation fixture only; stable live identity absent |
| `PROLONGED_DWELL_OR_CROWDING` | Stop dwell and occupancy/load evidence preceding headway collapse | Partial arrival load may be available; dwell history is absent |
| `DISPATCH_OR_SCHEDULE_IRREGULARITY` | Scheduled versus actual dispatch/headway evidence | Not ingested |
| `UPSTREAM_OPERATIONAL_INTERVENTION` | A known hold, diversion or approved control action precedes the event | Operations/planning integration absent |
| `MULTIPLE_CONTRIBUTING` | Two independently strong, non-exclusive causes | Requires multi-label training/evaluation |
| `UNKNOWN` | Evidence missing, contradictory, out of distribution, or below confidence threshold | Required from day one |

Weather should be added only when a weather observation source and labeled cases exist.
Do not create classes merely because they sound plausible: every class needs an
observable definition, required inputs, negative examples and an owner.

For an MVP, prefer a single primary class plus ranked alternatives. Multi-label
classification is a later extension; `MULTIPLE_CONTRIBUTING` is otherwise likely to
become an untestable catch-all.

## 5. Model strategy

### Recommended path

Use an interpretable supervised classifier exported as a versioned ONNX artifact and
loaded in Java behind a domain `DiagnosticModelPort`.

- Train offline with pinned Python tooling (pandas, scikit-learn and an ONNX exporter).
- Start with multinomial logistic regression as the required baseline. Compare a
  small gradient-boosted tree model only if scenario-level validation shows a material
  gain. Prefer the simpler model when performance is comparable.
- Run inference inside the Spring service through the ONNX Runtime Java binding after
  a short Java 25 compatibility spike. Keep inference behind a port so an external
  model server can replace it if native-runtime compatibility or model size becomes a
  problem.
- Commit model metadata containing model version, SHA-256, feature-schema version,
  class order, training-data version, calibration method, thresholds and metrics.
- Load and validate the artifact at startup. A checksum/schema mismatch must fail the
  inference component visibly, not silently substitute another model.

Do not use an LLM as the primary classifier. It is useful for producing a controller-
friendly explanation from already selected evidence, but its self-reported confidence
is not calibrated and it must not invent missing causal evidence. An LLM explanation
can be an optional later adapter with a schema-validated response and deterministic
fallback text.

### Training data

The current repository does not contain enough independent labeled cases. Build a
versioned dataset before claiming an AI result:

1. Extend the simulator to generate many scenarios for every taxonomy class plus
   normal/ambiguous counterexamples.
2. Vary route, direction, stop, time, severity, incident distance, traffic speed,
   occupancy, missing fields, stale fields and simultaneous causes.
3. Record the true injected cause separately from the features presented to the
   model.
4. Split train/calibration/test data by whole scenario and preferably by route/time,
   never by individual telemetry row. Row-level splitting would leak near-identical
   observations.
5. Keep synthetic and real/controller-reviewed labels distinct. A model trained only
   on simulation must be displayed and documented as simulation-trained.
6. Add a controller correction workflow later; reviewed diagnoses can become a
   separately versioned real-world evaluation set only after data-governance approval.

### Candidate features

- Current, preceding and following headway; headway/expected-headway ratio; rate of
  headway collapse; following service-gap ratio; consecutive abnormal observations.
- Incident type, temporal overlap, along-route distance to the affected segment and
  whether degradation starts after the incident onset.
- Corridor speed relative to route/time baseline and change over the evidence window.
- Dwell duration, occupancy/load, passenger count and change when a stable observation
  contract exists.
- Scheduled-versus-actual dispatch deviation when schedule data exists.
- Known holds/diversions/control actions preceding the event.
- Missingness, source age, source failure state and evidence-window coverage as
  explicit features; do not impute “normal” for missing operational data.

Identifiers such as plate, trip ID, service number or stop code must not be allowed to
become shortcuts. Use them only for joins/grouping, or encode generalized route
properties. Hold out routes during evaluation to reveal memorization.

## 6. Confidence and abstention

Do not invent confidence by multiplying arbitrary heuristic weights, and do not expose
an LLM's self-rating as probability.

The API should return these separate concepts:

- `modelProbability`: calibrated probability of the selected class.
- `alternativeCauses`: other classes and calibrated probabilities.
- `evidenceQuality`: a deterministic `COMPLETE`, `PARTIAL`, `STALE`, `CONFLICTING` or
  `OUT_OF_DISTRIBUTION` assessment with reason codes.
- `confidenceLevel`: `HIGH`, `MEDIUM`, `LOW` or `ABSTAINED` derived by a versioned
  decision policy.
- `abstained`: true when the system returns `UNKNOWN` rather than a causal assertion.

Algorithm:

1. Produce raw class probabilities from the model.
2. Calibrate them on a held-out calibration set (temperature/Platt scaling for the
   logistic baseline; compare isotonic calibration only when enough calibration data
   exists).
3. Let `p1` and `p2` be the two highest calibrated probabilities.
4. Apply hard evidence gates first: required inputs fresh, feature schema compatible,
   no unsupported identity join, and input within the training distribution.
5. Abstain when a hard gate fails, `p1` is below the model-version threshold, or
   `p1 - p2` is below the ambiguity margin. Store all values and reason codes.
6. Map non-abstained `p1` to display tiers using thresholds stored in model metadata.
   Store the exact metadata/model version with every diagnosis.

`confidenceScore` may alias calibrated `p1` for UI convenience, but documentation must
state that it is model confidence conditional on the known taxonomy and evidence, not
proof of physical causation. Evidence quality must remain a separate field.

Before release, report per-class precision/recall, macro F1, confusion matrix, Brier
score, expected calibration error, abstention coverage and error rate at each confidence
tier. Set thresholds from the validation objective (for example, minimum precision for
auto-highlight), not from round numbers chosen in code.

## 7. Service structure and data ownership

Follow `docs/CONVENTIONS.md` exactly:

```text
services/bunching-diagnosis/
  pom.xml
  Dockerfile
  src/main/java/com/csd/occ/diagnosis/
    BunchingDiagnosisApplication.java
    api/
      DiagnosisRequestController.java
      DiagnosisController.java
      dto/
      error/GlobalExceptionHandler.java
    domain/
      BunchingDiagnosis.java
      DiagnosisRequest.java
      DiagnosisService.java
      DiagnosisPolicy.java
      DiagnosticModelPort.java
      BunchingEvidencePort.java
    infrastructure/
      bunching/HttpBunchingEvidenceAdapter.java
      model/OnnxDiagnosticModelAdapter.java
      config/DiagnosisProperties.java
      config/DiagnosisSchedule.java
      config/OpenApiConfig.java
      security/DiagnosisSecurityConfig.java
  src/main/resources/
    application.yml
    application-local.yml
    db/migration/
  src/test/java/com/csd/occ/diagnosis/
    domain/
    api/
    infrastructure/
    integration/
```

Suggested `diagnosis.bunching_diagnosis` fields:

- IDs: `id`, unique `alert_event_id`, `alert_id`.
- Provenance: service, stop, direction, classification, detection/evidence timestamps.
- Lifecycle: `PENDING`, `COMPLETED`, `ABSTAINED`, `FAILED_RETRYABLE`,
  `FAILED_TERMINAL`; attempt and next-attempt metadata in a separate queue table.
- Result: primary cause, calibrated probability, confidence level, abstention and
  reason codes, ranked alternatives.
- Reproducibility: immutable feature snapshot, source evidence IDs/versions, feature
  schema version, model name/version/checksum, calibration/policy version and exact
  thresholds used.
- Explanation: structured supporting and contradicting evidence. Store deterministic
  evidence references separately from optional generated prose.
- Time: requested, diagnosed and updated `TIMESTAMPTZ` values, all supplied through an
  injected `Clock`.

Use JSONB only for variable-length evidence/alternatives; keep fields used for
filtering and invariants as typed columns. Never expose JPA entities as API DTOs.

## 8. Contracts

Minimum internal endpoints:

- Detector: `GET /internal/v1/bunching-alert-events/{eventId}` for immutable event and
  evidence provenance.
- Diagnosis: `POST /internal/v1/diagnosis-requests`, idempotent by `alertEventId`.
- Diagnosis: `GET /internal/v1/bunching-diagnoses/{alertId}`.
- Diagnosis: paginated `GET /api/v1/bunching-diagnoses` for authorized controllers, or
  expose the same information through a future operations/backend-for-frontend service.

Define DTOs before implementation and generate OpenAPI 3.1 from the controllers.
Document `202`, duplicate request behavior, `401/403`, validation failures, retryable
dependency failures and `application/problem+json` responses. Use service credentials
with an explicit audience/authority for internal calls; a Compose-only network is not
authentication.

## 9. Configuration and local infrastructure

Every runtime setting must be a typed `@ConfigurationProperties` record, expressed as
an environment placeholder in YAML, documented in root `.env.example`, and described
in the service README. Expected settings include:

- public and internal ports (allocate the next free values from the repository-wide
  port inventory; do not reuse host and container variables),
- diagnosis database URL/user/password,
- detector internal URL,
- service authentication issuer/audience/client settings,
- consumer enabled flag, batch size, polling/worker interval,
- HTTP connect/read timeouts,
- retry initial/max delay and maximum attempts,
- evidence lookback window and maximum source age,
- model artifact location/checksum and inference timeout.

Model class thresholds and calibration values belong to immutable versioned model
metadata for reproducibility. If an operational override is allowed, persist the exact
override with the diagnosis and inventory it as an environment variable.

Add the diagnosis DB role/schema init, service container, health check and dependencies
to `infra/local/compose.yaml`. Compose traffic must use service DNS and the internal
port. Add no secrets to Git.

## 10. Delivery plan

### Phase 0 — Definition and data-readiness gate

1. Agree on taxonomy, observable definitions, evidence windows, `UNKNOWN` behavior and
   which classes are permitted with live versus simulation evidence.
2. Inventory implemented sources against every candidate feature.
3. Define measurable acceptance targets for accuracy, calibration, abstention and
   trigger latency.
4. Decide whether the first evaluated claim is simulation-only. Do not proceed with a
   “live root cause” claim without live labels and sources.

**Exit:** signed taxonomy/data dictionary, label guide, feature schema v1 and evaluation
protocol; each class has positive, negative and ambiguous examples.

### Phase 1 — Production tracer: durable trigger to abstained diagnosis

1. Add the detector's alert DTO/read endpoint and transactional diagnosis outbox.
2. Add the idempotent dispatcher with retry/audit state; detection commits even when
   diagnosis is unavailable.
3. Scaffold `bunching-diagnosis`, its schema, typed configuration, security and OpenAPI.
4. Persist an accepted request and produce `UNKNOWN/ABSTAINED` with reason
   `MODEL_NOT_READY` or `INSUFFICIENT_EVIDENCE`.
5. Prove one `OPENED` event creates one diagnosis request despite duplicate delivery;
   `CLEARED` creates none.

**Exit:** a real detected event reaches a durable diagnosis record end-to-end, retries
after outage, and never rolls back or blocks detection.

### Phase 2 — Evidence contracts and deterministic feature pipeline

1. Implement diagnosis-ready ingestion contracts for traffic incidents and speed
   bands first; add schedule, occupancy/dwell, telemetry and intervention evidence only
   where stable identity/provenance is available.
2. Build `BunchingEvidencePort` adapters and persist the immutable feature snapshot.
3. Implement pure feature extraction and evidence-quality policies with explicit
   missing/stale/conflicting states.
4. Add fixture-based contract tests so training and Java inference use identical
   feature order, units, enum encoding and missing-value representation.

**Exit:** the same evidence always produces the same versioned feature vector, with no
cross-schema reads and no simulation/live mixing.

### Phase 3 — Dataset and baseline evaluation

1. Extend scenario generation to cover all approved classes and counterexamples.
2. Create versioned train/calibration/test manifests split by scenario/route.
3. Train a majority baseline and multinomial logistic model; calibrate probabilities.
4. Produce a checked-in model card/report with per-class and confidence metrics.
5. Stop if performance is not materially better than the baseline or if key classes
   lack support; merge those classes or collect more data instead of hiding the gap.

**Exit:** reproducible artifact and evaluation meet the Phase 0 thresholds.

### Phase 4 — Model integration and confidence policy

1. Implement `OnnxDiagnosticModelAdapter` and startup artifact validation.
2. Implement pure `DiagnosisPolicy` for quality gates, ambiguity margin, tiers and
   abstention using model metadata.
3. Persist result, alternatives, supporting/contradicting evidence and full provenance.
4. Add retry/terminal failure handling and operational metrics.

**Exit:** golden feature vectors produce the same class/probabilities in Python and
Java within a defined tolerance; corrupt/mismatched artifacts fail safely; low-quality
or OOD cases abstain.

### Phase 5 — API, UI and operational hardening

1. Add authorized controller read APIs and UI presentation of cause, confidence,
   evidence quality, alternatives, model version and limitations.
2. Add controller feedback/correction as an audited record; never overwrite the
   original model result.
3. Add dashboards/alerts for queue age, delivery failures, inference latency, failure
   rate, abstention rate, class drift, missing features and model-load state.
4. Run outage, backlog, concurrent duplicate, schema compatibility, rollback and load
   tests. Document deployment and rollback of both code and model artifact.

**Exit:** controller can distinguish detected fact from inferred cause; diagnosis
failures are visible; rollback to the prior model does not require data loss or schema
rollback.

## 11. Verification matrix

- **Domain unit tests:** feature calculations, freshness boundaries, quality gates,
  probability ordering, exact threshold/margin boundaries and abstention.
- **Adapter unit tests:** HTTP mapping/failures, ONNX input/output mapping, artifact
  metadata/checksum and configuration validation.
- **API slice tests:** validation, idempotency, roles, RFC 9457 errors and documented
  response shape.
- **PostgreSQL integration tests:** Flyway migrations, unique request constraint,
  retry locking, outbox delivery, transaction rollback and immutable provenance using
  PostgreSQL 18 Testcontainers (never H2).
- **Cross-service test:** synthetic arrival -> detector `OPENED` -> outbox retry ->
  request -> diagnosis, including diagnosis-service downtime and restart.
- **ML tests:** scenario-level leakage checks, golden vectors, calibration metrics,
  class imbalance, missing features, OOD cases and route holdout.
- **OpenAPI CI:** parse generated specs, reject duplicate operation IDs and compare
  representative responses/errors with the contract.

## 12. Principal trade-offs

| Choice | Benefit | Cost / mitigation |
| --- | --- | --- |
| Separate diagnosis service | Isolates probabilistic failures and model releases from deterministic detection | More deployment/configuration; justify with independent schema and health |
| Outbox + HTTP | Durable and fits current architecture without a broker | Producer has a consumer-specific adapter; keep a generic domain event/outbox shape so a broker can replace it |
| Embedded ONNX | Low latency, no separate Python serving tier | Native/runtime compatibility and artifact rollout; isolate behind a port and complete a Java 25 spike |
| Logistic baseline first | Interpretable, calibratable, works with smaller data | May underfit nonlinear interactions; compare against boosting using the same held-out scenarios |
| Synthetic training first | Reproducible labels and edge cases | Simulation-to-reality gap; label all outputs simulation-trained and abstain on OOD/live evidence |
| Calibrated probability + abstention | Honest uncertainty and safer controller use | Lower coverage; measure coverage/error instead of forcing every event into a class |
| Optional LLM explanation only | Better prose without controlling facts | Added latency/cost/non-determinism; schema validation, evidence citations and deterministic fallback required |

## 13. Explicit non-goals for the first release

- No automatic intervention, replanning or driver instruction from a diagnosis.
- No physical bus/driver identification unless a stable, authorized source supplies it.
- No cross-service database reads.
- No diagnosis based only on a single incident's geographic proximity.
- No claim of real-world causal accuracy from simulation-only training.
- No forced classification when evidence is missing or unfamiliar.

## 14. Decisions needed before Phase 1 ends

1. Final taxonomy and whether version 1 is single-label or multi-label.
2. Simulation-only versus live observational claim.
3. Service-to-service authentication mechanism and audience.
4. Repository location and release process for training code/model artifacts.
5. Accuracy/calibration/coverage targets and who approves model promotion.
6. Retention period for requests, feature snapshots, diagnoses and controller feedback.

