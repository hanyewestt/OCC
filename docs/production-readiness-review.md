# Production-readiness review

This document preserves the baseline review findings recorded on 2026-09-28. They describe the state before the remediation work below, not the current state of the checkout. See **Implementation status** for what changed and what remains open.

The remaining production-readiness gaps in the agreed scope are:

1. Authentication and authorization are not implemented; explicitly deferred for this pass.
2. Historical operations migrations still need an explicit ownership/decommission decision; untouched in this pass.
3. Provider/consumer contract verification and a Compose end-to-end pipeline test are still absent.
4. Metrics, distributed traces, correlated logs and operational alerting remain deferred; health probes are implemented.

## Implementation status (2026-09-28)

| Baseline finding | Status | Implementation / remaining work |
|---|---|---|
| 1. Authentication/authorization | Deferred | Explicitly excluded from this implementation. The APIs remain unauthenticated; production exposure still requires a separate security change. |
| 2–3. Unbounded diagnosis/recommendation inputs | Implemented | Recommendation HTTP bodies, DTO/domain fields and aggregate prompts are bounded; diagnosis evidence fields, collections, peer response bodies and model prompts have validated budgets. |
| 4–5. Diagnosis workflow and durable dispatcher coverage | Implemented | Processor, idempotency, lease recovery, concurrent claims, atomic completion/outbox, retry and dispatcher behavior now have unit and PostgreSQL integration tests. |
| 6. Synchronous HTTP deadlines/retries | Implemented | Explicit typed timeouts and bounded retry/backoff with jitter are configured for the affected clients. |
| 7. Service relocation safety | Resolved in checkout | The services are present at their current domain paths. This pass did not stage or commit anything. |
| 8. Provider/consumer contracts and end-to-end test | Partial | OpenAPI artifacts for all four services are present and validated/diffed in CI. Pact/Spring Cloud Contract verification and the Compose pipeline test remain follow-up work. |
| 9–10. Recommendation/diagnosis OpenAPI artifacts | Implemented | Generated from the running services and stored under each service's `openapi/` directory. |
| 11–12. Mandatory integration gates / Windows wrapper | Implemented | `verify` requires nonzero Failsafe tests; CI runs the Windows wrapper smoke check. |
| 13. Health and telemetry | Partial | Actuator liveness/readiness groups and Compose health checks are aligned. Metrics, traces, correlated logging and alerting are deferred. |
| 14–15. Ingestion schedule and DataMall credential | Implemented | Schedule and HTTP deadlines use typed environment-backed properties; the API key has no mock production fallback. |
| 16. Historical operations migrations | Deferred | No historical operations migrations were changed; decide ownership and a safe migration/decommission path separately. |
| 17. Recommendation image port | Implemented | Docker image metadata exposes the recommendation container's internal port `8003`. |
| 18. Architecture/coverage regression gates | Implemented | ArchUnit rules run in all four services; JaCoCo gates protect the ingestion polling and bunching dispatcher paths. |

Verification on Java 25 / Spring Boot 4.1.1 completed with `mvnw verify` in all services:

| Service | Unit tests | PostgreSQL-backed integration tests | Result |
|---|---:|---:|---|
| Ingestion | 53 | 4 | Passed; JaCoCo line and branch gates met |
| Bus-bunching detection | 52 | 22 | Passed; dispatcher coverage gates met |
| Recommendation engine | 52 | 12 | Passed |
| Root-cause diagnosis | 14 | 7 | Passed |

No Git commit was created and no changes were staged. Authentication/authorization, historical operations migrations, full telemetry, provider/consumer contract tests and full-stack end-to-end testing remain outstanding.

## Research baseline

| Area | Established practice | Context-dependent recommendation |
|---|---|---|
| Structure and naming | Put the application class in a root reverse-domain package; keep cohesive components together and enforce dependencies between modules. | Package-by-feature/domain is usually clearer for services, but layered packaging can work in small applications. Spring does not mandate one layout. |
| Configuration | Externalize deployment-specific values, bind them with typed `@ConfigurationProperties`, validate them at startup, and keep secrets out of source control and images. Use profiles for environment variants, not arbitrary application logic. | Vault/KMS/Secrets Manager choice and profile strategy depend on the deployment platform. |
| Dependencies | Use the Spring Boot parent or BOM, prefer Boot starters, and avoid overriding managed transitive versions without a documented compatibility reason. | Upgrade cadence depends on support policy, but supported patch releases should be adopted regularly. |
| APIs | Apply HTTP semantics consistently, validate at the boundary, return RFC 9457 problem details, publish OpenAPI, and evolve contracts additively. | URI versioning, header versioning, pagination style, and formal deprecation windows are organizational decisions. |
| Service boundaries | A service should own its state and public contract. Avoid cross-service writes and shared implementation DTOs. | Synchronous HTTP, asynchronous messaging, or database read models should be selected according to consistency, latency, and coupling requirements. |
| Resilience and consistency | Set connection and response deadlines. Retry only safe/idempotent operations, with bounded attempts and backoff. Keep ACID transactions local; use outbox/idempotency/sagas when workflows cross services. | Circuit breakers, bulkheads, sagas, and brokers are useful only where failure and load characteristics warrant them. |
| Security | Authenticate every non-public API, validate token signature, issuer, audience, expiry and scopes, and apply least privilege to service identities and databases. | Gateway placement, mTLS and identity provider are platform decisions. |
| Observability | Produce structured correlated logs, metrics and traces; expose protected liveness/readiness endpoints; define actionable service-level alerts. | Telemetry backend and exact SLOs depend on the operating environment. |
| Testing | Combine domain unit tests, MVC slices, real-database integration tests, provider/consumer contracts, architecture rules, and a small number of critical-path end-to-end tests. | A universal coverage percentage is not useful; coverage gates should ratchet around high-risk behavior. |
| Data and deployment | Give each service explicit data ownership, apply immutable versioned migrations, use backward-compatible expand/contract changes, and gate releases on tests, migrations, probes, security and rollback readiness. | Separate databases versus schemas on one instance depends on isolation and operational requirements. |

Primary references include the official Spring documentation for [code structure](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html), [external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html), [profiles](https://docs.spring.io/spring-boot/reference/features/profiles.html), [dependency management](https://docs.spring.io/spring-boot/reference/using/build-systems.html), [validation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html), [problem details](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html), [transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction.html), [Spring Security JWT resource servers](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [Actuator](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html), [observability](https://docs.spring.io/spring-boot/reference/actuator/observability.html), [testing](https://docs.spring.io/spring-boot/reference/testing/), [Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html), and [Spring Cloud Contract](https://docs.spring.io/spring-cloud-contract/reference/index.html). Relevant standards are [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html), [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html), [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html), and [Jakarta Bean Validation](https://jakarta.ee/specifications/bean-validation/3.1/apidocs/jakarta/validation/constraints/size).

## Architecture summary

```text
LTA DataMall / reference data
              │
              ▼
        Ingestion service
        ingestion schema
          │          │
 HTTP APIs/views     │ demo-facing proxy APIs
          ▼          │
 Bus-bunching detection
 bunching schema + diagnosis outbox
          │
          ▼
 Root-cause diagnosis ─────► Gemini
 diagnosis schema + recommendation outbox
          │
          ▼
 Recommendation engine ────► Gemini
 recommendation schema
```

The four core services are:

- **Ingestion:** polls and normalizes DataMall/reference data, owns the ingestion schema, and exposes operational/demo APIs.
- **Bus-bunching detection:** reads ingestion data through HTTP and restricted database views, detects incidents, persists alerts, and places diagnosis work in a durable outbox.
- **Root-cause diagnosis:** retrieves incident facts and contextual evidence, calls Gemini, persists diagnostic records, and creates durable recommendation-delivery work.
- **Recommendation engine:** creates and validates mitigation candidates, applies deterministic policy, and persists model/audit records idempotently.

The services share a PostgreSQL instance but use distinct schemas and roles. Cross-service workflows use a mixture of synchronous HTTP, read-only database views, and transactional outboxes.

## Original findings (baseline, ordered by severity)

### 1. Critical — APIs are reachable without authentication or authorization

Examples include the externally published mutation endpoints in [DemoModeController.java](../services/ingestion/src/main/java/com/csd/occ/ingestion/api/DemoModeController.java), [DemoRunController.java](../services/ingestion/src/main/java/com/csd/occ/ingestion/api/DemoRunController.java), and internal selection endpoint [ConsumerSelectionController.java](../services/operations/bus-bunching-detection/src/main/java/com/csd/occ/bunching/api/ConsumerSelectionController.java). No service contains a Spring Security dependency or security configuration.

Compose publishes every service on a host port, including internal diagnosis and recommendation APIs: [compose.yaml](../infra/local/compose.yaml), [diagnosis publication](../infra/local/compose.yaml), and [recommendation publication](../infra/local/compose.yaml).

**Risk:** An unauthenticated caller can change runtime state, trigger expensive AI work, access diagnostic data, and potentially abuse internal workflows.

**Remediation:** Add Spring Security OAuth2 resource-server support, validate issuer and audience, map scopes to public/internal/admin operations, and use distinct service identities. Restrict internal endpoints at the network layer and protect or disable documentation and management endpoints in production. See the [Spring Security JWT guidance](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

### 2. P0/High — Root-cause diagnosis can construct unbounded AI context

The final prompt sink is [GeminiRootCauseModelAdapter.java](../services/planning/root-cause-diagnosis/src/main/java/com/csd/occ/diagnosis/infrastructure/gemini/GeminiRootCauseModelAdapter.java). Although [RootCauseDiagnosisProcessor.java](../services/planning/root-cause-diagnosis/src/main/java/com/csd/occ/diagnosis/domain/RootCauseDiagnosisProcessor.java) limits some collection counts and the incident message, individual evidence IDs, rule versions and remote context fields are not comprehensively bounded.

The peer-service adapters accept those values directly in [HttpBunchingEventFactsAdapter.java](../services/planning/root-cause-diagnosis/src/main/java/com/csd/occ/diagnosis/infrastructure/bunching/HttpBunchingEventFactsAdapter.java) and [HttpBunchingContextEvidenceAdapter.java](../services/planning/root-cause-diagnosis/src/main/java/com/csd/occ/diagnosis/infrastructure/ingestion/HttpBunchingContextEvidenceAdapter.java).

**Risk:** A compromised or faulty peer can cause extremely large prompts, high model cost, memory pressure and prolonged worker occupation. The values are also persisted, amplifying the problem.

**Remediation:** Apply per-field, list-count, request-body and aggregate prompt budgets at both the HTTP boundary and domain constructors. Reject oversized peer responses before persistence or model invocation and test the maximum permitted context.

### 3. P0/High — Recommendation prompts accept unbounded list elements

[BunchingEventRequest.java](../services/planning/recommendation-engine/src/main/java/com/csd/occ/planning/recommendationengine/api/dto/BunchingEventRequest.java) validates evidence elements only with `@NotBlank`; [BunchingRootCauseDiagnosisRequest.java](../services/planning/recommendation-engine/src/main/java/com/csd/occ/planning/recommendationengine/api/dto/BunchingRootCauseDiagnosisRequest.java) similarly lacks usable size budgets. The domain objects copy rather than constrain them, and the values reach the prompt at [GeminiMitigationCandidateAdapter.java](../services/planning/recommendation-engine/src/main/java/com/csd/occ/planning/recommendationengine/infrastructure/gemini/GeminiMitigationCandidateAdapter.java).

**Remediation:** Add `@Size` to collections and strings, repeat the invariant in the domain layer, cap the aggregate serialized prompt, and configure a server request-body limit. Jakarta defines these constraints in [`@Size`](https://jakarta.ee/specifications/bean-validation/3.1/apidocs/jakarta/validation/constraints/size).

### 4. High — Root-cause diagnosis’s critical workflow is effectively untested

The primary workflow spans [RootCauseDiagnosisProcessor.java](../services/planning/root-cause-diagnosis/src/main/java/com/csd/occ/diagnosis/domain/RootCauseDiagnosisProcessor.java), [DiagnosisCompletionService.java](../services/planning/root-cause-diagnosis/src/main/java/com/csd/occ/diagnosis/domain/DiagnosisCompletionService.java), and [DiagnosisWorker.java](../services/planning/root-cause-diagnosis/src/main/java/com/csd/occ/diagnosis/infrastructure/scheduling/DiagnosisWorker.java).

Only three mocked MVC tests exist in [DiagnosisRequestControllerTest.java](../services/planning/root-cause-diagnosis/src/test/java/com/csd/occ/diagnosis/api/DiagnosisRequestControllerTest.java).

**Risk:** Claiming, lease expiry, retries, concurrency, idempotency, atomic completion and outbox creation can regress without detection.

**Remediation:** Add focused processor and completion tests plus Testcontainers-backed PostgreSQL integration tests covering concurrent claims, lease recovery, model failure, rollback, idempotent replay and atomic diagnosis/outbox persistence. Configure Failsafe to execute them during `verify`.

### 5. High — Detection-to-diagnosis durable delivery has no direct test coverage

[BunchingDiagnosisDispatcher.java](../services/operations/bus-bunching-detection/src/main/java/com/csd/occ/bunching/infrastructure/scheduling/BunchingDiagnosisDispatcher.java) controls durable delivery, status transitions, retry behavior and failure handling, but no tests reference it.

**Remediation:** Add database-backed tests for claiming, successful acknowledgement, transient failure, backoff, maximum attempts, lease recovery and duplicate/idempotent delivery.

### 6. High — Important synchronous HTTP clients have no explicit deadlines

[DataMallAdapter.java](../services/ingestion/src/main/java/com/csd/occ/ingestion/infrastructure/datamall/DataMallAdapter.java) builds a client without connection or response timeouts. The ingestion client in [BunchingConfig.java](../services/operations/bus-bunching-detection/src/main/java/com/csd/occ/bunching/infrastructure/config/BunchingConfig.java) has the same issue.

**Risk:** A slow dependency can occupy scheduled workers indefinitely and prevent timely processing or shutdown.

**Remediation:** Introduce typed, validated connection and response timeout properties, expose them through environment variables and `.env.example`, and document them. Add bounded retry only for safe operations; use a circuit breaker or bulkhead only after defining the failure policy.

### 7. High — The active service moves are unsafe to commit partially

Git currently sees the old bus-bunching and root-diagnosis trees as tracked deletions and their new locations as untracked trees, while Compose already references the new locations at [compose.yaml](../infra/local/compose.yaml) and [compose.yaml](../infra/local/compose.yaml).

**Risk:** A tracked-only commit such as `git commit -am` can delete the services while leaving Compose pointed at paths absent from the commit.

**Remediation:** Stage the relocations atomically with `git add -A`, inspect `git diff --cached --stat` and `git status`, and build from the exact staged state. The moves themselves are mostly byte-identical and do not appear to introduce runtime contract breaks.

### 8. Medium — No consumer/provider contract or full pipeline test protects service interfaces

Several services duplicate HTTP DTO expectations, including [HttpBunchingEventFactsAdapter.java](../services/planning/root-cause-diagnosis/src/main/java/com/csd/occ/diagnosis/infrastructure/bunching/HttpBunchingEventFactsAdapter.java), [HttpBunchingRecommendationAdapter.java](../services/planning/root-cause-diagnosis/src/main/java/com/csd/occ/diagnosis/infrastructure/recommendation/HttpBunchingRecommendationAdapter.java), and [HttpRecommendationLookupAdapter.java](../services/ingestion/src/main/java/com/csd/occ/ingestion/infrastructure/recommendation/HttpRecommendationLookupAdapter.java).

**Risk:** Provider changes can compile independently but fail at runtime.

**Remediation:** Generate clients from committed OpenAPI contracts or add Spring Cloud Contract/Pact provider-consumer verification. Add one Compose-based end-to-end test covering ingestion → detection → diagnosis → recommendation. See [Spring Cloud Contract](https://docs.spring.io/spring-cloud-contract/reference/index.html).

### 9. Medium — Recommendation APIs have no committed OpenAPI contract

The controller exposes operations at [BunchingRecommendationController.java](../services/planning/recommendation-engine/src/main/java/com/csd/occ/planning/recommendationengine/api/BunchingRecommendationController.java) and line 134, but the module has neither a generated contract nor a generation/verification profile in [pom.xml](../services/planning/recommendation-engine/pom.xml).

**Remediation:** Generate and commit the OpenAPI document, validate it in CI, and run a compatibility diff against the last released contract.

### 10. Medium — Root-cause diagnosis configures OpenAPI generation but has no artifact

The APIs are declared in [DiagnosisRequestController.java](../services/planning/root-cause-diagnosis/src/main/java/com/csd/occ/diagnosis/api/DiagnosisRequestController.java). Its [pom.xml](../services/planning/root-cause-diagnosis/pom.xml) configures generation, but no generated contract is present.

**Remediation:** Make contract generation part of the build or release workflow, commit the result, and fail CI when implementation and contract differ.

### 11. Medium — Integration tests can silently disappear when Docker is unavailable

Important test classes use `disabledWithoutDocker`, including [IngestionStartupIT.java](../services/ingestion/src/test/java/com/csd/occ/ingestion/integration/IngestionStartupIT.java), [BunchingRecordingIT.java](../services/operations/bus-bunching-detection/src/test/java/com/csd/occ/bunching/integration/BunchingRecordingIT.java), and [BunchingRecommendationFlowIT.java](../services/planning/recommendation-engine/src/test/java/com/csd/occ/planning/recommendationengine/integration/BunchingRecommendationFlowIT.java).

**Risk:** A release build can report success without executing its database tests.

**Remediation:** Keep optional skipping for developer convenience, but create a mandatory release-CI job where Docker/Testcontainers availability is a prerequisite and zero integration tests is a failure.

### 12. Medium — Recommendation’s Windows Maven wrapper is broken

[mvnw.cmd](../services/planning/recommendation-engine/mvnw.cmd) indexes `.Target[0]` without null safety at line 92. Running `mvnw.cmd verify` fails with `Cannot index into a null array`.

**Remediation:** Regenerate the wrapper from the Maven Wrapper plugin or copy a known-good wrapper generated with the same version. Add a Windows wrapper smoke check.

### 13. Medium — Health and telemetry are inconsistent across services

Root diagnosis and recommendation expose limited Actuator health/info configuration, but ingestion and bus-bunching do not include Actuator. No repository configuration establishes exported metrics, distributed trace propagation or consistent log correlation. Compose has no application-service health checks.

**Remediation:** Add Actuator consistently, enable liveness/readiness probes, expose metrics through Prometheus or OTLP, propagate trace context across HTTP calls, add low-cardinality business metrics, and configure Compose/orchestrator health checks. Management endpoints must be protected. See [Actuator endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html) and [Spring observability](https://docs.spring.io/spring-boot/reference/actuator/observability.html).

### 14. Medium — A polling schedule is hard-coded instead of externalized

[BusArrivalSchedule.java](../services/ingestion/src/main/java/com/csd/occ/ingestion/infrastructure/config/BusArrivalSchedule.java) hard-codes both the 60-second rate and initial delay.

This violates the repository’s explicit rule that schedules and polling intervals are typed environment-backed configuration.

**Remediation:** Bind both values through a validated `@ConfigurationProperties` record, add environment placeholders, update root `.env.example`, and document them in the ingestion README.

### 15. Medium — The default DataMall credential silently falls back to `mock_key`

[application.yml](../services/ingestion/src/main/resources/application.yml) supplies `${DATAMALL_API_KEY:mock_key}`, while [DataMallProperties.java](../services/ingestion/src/main/java/com/csd/occ/ingestion/infrastructure/config/DataMallProperties.java) does not reject blank or placeholder credentials.

**Risk:** A production deployment can start with invalid credentials and repeatedly call the external service before the configuration error is discovered.

**Remediation:** Require the credential with no production fallback, validate it on startup, and put mock credentials only in an explicit local/test profile.

### 16. Medium — Three operations Flyway migrations are being deleted without a visible migration path

The changed tree removes:

- `services/operations/src/main/resources/db/migration/V001__create_user_profile.sql` (deleted from the current working tree)
- `services/operations/src/main/resources/db/migration/V002__add_user_preferences.sql` (deleted from the current working tree)
- `services/operations/src/main/resources/db/migration/V003__add_notification_settings.sql` (deleted from the current working tree)

**Risk:** Removing historical migrations can prevent clean deployments from reproducing the schema and can obscure ownership of existing production data. Applied Flyway migrations should remain immutable; changes are normally rolled forward with new versions. See [Flyway versioned migrations](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html).

**Remediation:** Separate this deletion from the service relocation. Either retain the migrations, move them atomically to the new owning service, or document and test a deliberate decommission/migration procedure.

### 17. Low — Recommendation image metadata advertises the wrong port

[Dockerfile](../services/planning/recommendation-engine/Dockerfile) declares `EXPOSE 8002`, while its application and Compose configuration use port `8003`.

`EXPOSE` is informational, but incorrect metadata misleads image consumers and automated tooling.

**Remediation:** Change it to `8003`, ideally backed by the same documented invariant used by deployment configuration.

### 18. Low — No architecture or coverage-regression gates exist

No module configures ArchUnit or JaCoCo. This is not a defect by itself, but the repository’s hexagonal layering and service boundaries currently depend on reviewer discipline.

**Remediation:** Add a small ArchUnit suite for adapter/application/domain dependency direction and service package boundaries. Add informational JaCoCo reporting first, then ratchet coverage on critical workflows rather than imposing a blanket percentage.

## Strengths

The repository already implements several production-grade patterns correctly:

- Typed record-based `@ConfigurationProperties` with `@ConfigurationPropertiesScan`.
- Reverse-domain packages and generally clear adapter/application/domain separation.
- Versioned public and internal API paths.
- PostgreSQL/PostGIS Testcontainers tests rather than H2 substitutes.
- Separate schemas and roles, with restricted read access to ingestion views.
- Flyway-managed schemas and meaningful database constraints.
- Transactional outboxes using locking patterns such as `FOR UPDATE SKIP LOCKED`.
- Idempotency keys and deterministic replay behavior in critical flows.
- Injected clocks, improving deterministic testing.
- Structured AI outputs, secret redaction, disabled prompt/tool logging and explicit output-token limits.
- Deterministic post-model validation rather than trusting generated recommendations directly.
- Existing ingestion and bus-bunching OpenAPI contracts appear aligned with their current controllers.
- The large apparent diff is mainly relocation: 158 moved files are byte-identical and the three remaining moved files contain only minor path/comment substitutions. Effective authored change size is about 415 lines, below the review thresholds.

## Testing and coverage snapshot before implementation

I executed the Maven verification builds:

| Service | Result |
|---|---|
| Ingestion | Passed: 46 unit tests and 4 integration tests |
| Bus-bunching | Passed: 43 unit tests and 18 integration tests |
| Root-cause diagnosis | Passed: only 3 MVC tests; no integration tests |
| Recommendation | Module wrapper failed; using the repository’s working wrapper passed 45 unit and 12 integration tests |

Additional observations:

- Bus-bunching tests emitted scheduled-worker database errors after Testcontainers shutdown and the fork took roughly 30 seconds to terminate. Disable or explicitly manage scheduled workers in integration-test lifecycle.
- There are no consumer/provider contract tests.
- There is no whole-pipeline end-to-end test.
- Root diagnosis has no database concurrency, lease, rollback or idempotency verification.
- The diagnosis outbox dispatcher has no direct tests.
- CI configuration was not found, so mandatory execution of Docker-backed tests, OpenAPI compatibility checks and image scanning could not be verified.
- Security tests are absent because security is not implemented.

## Original prioritized remediation plan

1. **Secure the trust boundaries:** implement JWT-based authentication, scoped authorization and network isolation for internal endpoints.
2. **Bound all AI and HTTP inputs:** enforce per-field, collection, body and aggregate prompt limits before persistence and invocation.
3. **Add explicit HTTP deadlines:** externalize and validate connection/response timeout settings.
4. **Close diagnosis test gaps:** add PostgreSQL integration tests for claims, leases, concurrency, idempotency, rollback and outbox delivery.
5. **Make the active relocation atomic:** stage all deletions and replacements together and separate the operations migration deletion.
6. **Establish release gates:** require Docker-backed integration tests, contract generation/diffing, security checks and image builds.
7. **Complete API governance:** commit diagnosis and recommendation OpenAPI contracts and add consumer/provider contract verification.
8. **Add operational signals:** Actuator across every service, protected probes, exported metrics, trace propagation, correlated logs and service-level alerts.
9. **Finish configuration compliance:** externalize the polling schedule and remove the production `mock_key` fallback.
10. **Add architecture and coverage ratchets:** introduce ArchUnit and risk-based JaCoCo reporting after the critical gaps are tested.

## Assumptions and unverified areas

- This review treats the Compose stack and APIs as candidates for production deployment. If the stack is strictly isolated classroom/demo software, the exposure risk is reduced, though the missing security boundary remains incompatible with a production-grade target.
- Cloud infrastructure, ingress rules, secret-store configuration and external monitoring may exist outside this repository and therefore could not be verified.
- No live identity provider, Gemini account, DataMall account or deployed environment was exercised.
- Database upgrade behavior against a long-lived production dataset was not tested.
- The OpenAPI findings are based on repository artifacts; generated contracts may exist in an external pipeline, but no such pipeline was present here.
- The large relocation should be reviewed from the final staged commit, because the current working tree is not yet a safely reproducible change set.
