# OCC Engineering Conventions

This is the canonical engineering-conventions file for the OCC repository. It applies to every backend service, the frontend, infrastructure definitions, database migrations, tests, and documentation.

The current architecture and delivery decisions live in [project-v2.md](project-v2.md). Detailed Week 1 structure and contracts live in [docs/plans/week-01/README.md](docs/plans/week-01/README.md). If implementation and documentation disagree, stop and resolve the conflict through a reviewed change; do not silently invent a third convention.

Stack: **Java 25**, **Spring Boot 4.1.1**, **Gradle 9.7.1 through per-service Wrappers**, **PostgreSQL 18.6 with PostGIS**, **Flyway**, **Testcontainers**, **Next.js with TypeScript**, **Amazon Cognito**, and **Docker Compose**.

---

## 1. Repository and service isolation

Every backend service is a complete, independently buildable and deployable Spring Boot project under `services/<name>`:

```text
OCC/
├─ apps/
│  └─ web/
├─ services/
│  ├─ ingestion/
│  ├─ operations/
│  └─ planning/
├─ infra/
│  ├─ local/
│  ├─ caddy/
│  └─ aws/
├─ docs/
├─ scripts/
│  ├─ verify-all.ps1
│  └─ verify-all.sh
├─ .editorconfig
├─ .gitattributes
├─ .gitignore
└─ README.md
```

There is no root Gradle multi-project build. Each service owns its Gradle Wrapper, settings, version catalog, dependency locks, OpenAPI contract, Flyway migrations, Dockerfile, tests, and README:

```text
services/<name>/
├─ gradle/
│  ├─ wrapper/
│  │  ├─ gradle-wrapper.jar
│  │  └─ gradle-wrapper.properties
│  └─ libs.versions.toml
├─ openapi/
│  └─ <name>-v1.yaml
├─ src/
│  ├─ main/
│  │  ├─ java/sg/edu/smu/occ/<name>/
│  │  └─ resources/
│  │     ├─ application.yml
│  │     └─ db/migration/
│  └─ test/
│     ├─ java/sg/edu/smu/occ/<name>/
│     └─ resources/
├─ .dockerignore
├─ Dockerfile
├─ README.md
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle.properties
├─ gradlew
└─ gradlew.bat
```

Rules:

- A service must build from its own directory using its own Wrapper.
- Do not add local project dependencies between services.
- Do not create a shared domain-model JAR, shared JPA entities, or a shared database-access library.
- Duplicate a small stable primitive rather than coupling two deployments through a shared implementation artifact.
- Communicate across service boundaries through versioned HTTP APIs and committed OpenAPI contracts.
- Root verification scripts may invoke service wrappers but contain no compilation or dependency logic.
- Local Compose orchestrates services; it does not merge them into one application.

## 2. Service ownership

| Service | Package root | Owns |
|---|---|---|
| Ingestion | `sg.edu.smu.occ.ingestion` | DataMall adapters, polling, raw observations, normalization, reference imports, feed health and freshness |
| Operations | `sg.edu.smu.occ.operations` | Profiles, scenario and route configuration, resolver, forecasting, simulation, resources, directives, execution and audit |
| Planning | `sg.edu.smu.occ.planning` | Change assessment, worker orchestration, manager decisions, model calls, proposal persistence and coverage status |

The Ingestion service is the only service that calls DataMall. Planning never reads another service's tables and never receives database credentials. Planning obtains operational facts through typed Operations APIs. Operations is the only service allowed to reserve resources, create assignments, or execute directives.

One PostgreSQL/PostGIS instance may host the three schemas, but credentials and ownership remain separate:

| Schema | Writer |
|---|---|
| `ingestion` | Ingestion service only |
| `operations` | Operations service only |
| `planning` | Planning service only |

Cross-schema writes are forbidden. Approved reporting views may be read-only.

## 3. Spring package structure

Use package-by-feature. Add layers inside a feature only when the feature needs them:

```text
sg.edu.smu.occ.<service>/
├─ <feature>/
│  ├─ api/
│  ├─ application/
│  ├─ domain/
│  └─ infrastructure/
└─ shared/
   ├─ config/
   ├─ error/
   └─ security/
```

Initial feature roots:

- Operations: `profile`, `identity`, `audit`, later `scenario`, `routeprofile`, `resolver`, `forecast`, `simulation`, `resource`, `directive`.
- Ingestion: `polling`, `datamall`, `busreference`, `busarrival`, `feedhealth`, later other feed-specific features.
- Planning: `changeassessment`, `worker`, `manager`, `model`, `coverage`.

Layering direction:

```text
api -> application -> domain
          ^             ^
          |             |
     infrastructure ----+
```

- `api` maps HTTP input/output and calls application use cases. It never calls a repository directly.
- `application` coordinates use cases, transactions, ports, and authorization decisions.
- `domain` contains deterministic business rules and service-owned value types. Keep it free of HTTP, AWS SDK, database, and LLM-provider types.
- `infrastructure` implements persistence and external-system ports.
- Provider-specific types stay inside their provider adapter, such as `infrastructure/datamall`, `infrastructure/cognito`, `infrastructure/onemap`, or `infrastructure/gemini`.
- The `@SpringBootApplication` class is the only type directly in the service root package.
- Add an ArchUnit test for these dependency rules before creating the second feature in a service.

## 4. Naming

### Packages and files

- Java package names are lowercase, singular, and contain no underscores.
- Java types use `PascalCase`; variables, methods, JSON fields, and TypeScript members use `camelCase`.
- Database schemas, tables, columns, indexes, and constraints use `snake_case`.
- Environment variables use uppercase `OCC_<SERVICE>_<AREA>_<NAME>` names.
- Configuration keys use kebab-case under `occ.<service>`, for example `occ.ingestion.datamall.base-url`.

### Domain and API types

| Kind | Pattern | Example |
|---|---|---|
| Command | `<Verb><Noun>Command` | `UpdateProfileCommand` |
| Query | `<Noun>Query` | `LatestArrivalsQuery` |
| Application use case | `<Verb><Noun>UseCase` | `ResolveAffectedRoutesUseCase` |
| Pure rule | `<Noun>Policy` | `DirectiveEligibilityPolicy` |
| Port | `<Capability>Port` | `ModelInvocationPort` |
| Adapter | `<Provider><Capability>Adapter` | `DataMallBusArrivalAdapter` |
| Controller | `<Resource>Controller` | `ProfileController` |
| Request DTO | `<Verb><Noun>Request` | `UpdateProfileRequest` |
| Response DTO | `<Noun>Response` | `ProfileResponse` |
| Configuration | `<Area>Properties` | `DataMallProperties` |
| Exception | `<Problem>Exception` | `ProfileVersionConflictException` |

Avoid meaningless suffixes and names: `Util`, `Helper`, `Manager`, `Data`, `Info`, and `Impl`. `Manager` is acceptable only for the explicit planning-agent role named `Manager`; it is not a generic service-class suffix.

### Transport identifiers

- Use `serviceId` and numeric `directionId` everywhere.
- Use `stopCode` as a string so leading zeroes survive.
- Never create fields or classes named after Services 190 or 972. Those are seeded data, not schema.
- A route key is the documented combination of service, operator when required, and direction. Do not guess a direction when source evidence is missing.
- External source identifiers remain strings unless their numeric semantics are guaranteed.

### Methods and tests

- Commands use imperative names: `activateProfile()`, `reservePair()`.
- Queries name their result: `latestSuccessfulObservation()`, not `getLatestSuccessfulObservation()`.
- Booleans read as assertions: `isStale()`, `permitsDetour()`.
- Avoid boolean parameters at call sites; use a descriptive enum or value object.
- Test methods are camelCase behavioral sentences without a `test` prefix.
- Unit and slice test classes end in `Test`; tests requiring containers end in `IT`.

## 5. Java conventions

- Prefer records for HTTP DTOs, configuration properties, commands, queries, events, and immutable value objects.
- Do not use Lombok.
- Use constructor injection only. Do not use field injection or `@Autowired` on fields.
- Inject `Clock`; do not call `Instant.now()` inside application or domain logic.
- Represent moments as `Instant` and store them as `timestamptz`. Convert to `Asia/Singapore` only at the presentation boundary.
- Use `Duration` for elapsed time and timeout configuration.
- Use `BigDecimal` where decimal rounding affects demand, distance, or capacity results. Declare rounding mode explicitly.
- Use `Optional` only as a return value for an expected absence. Do not use it in entities, request DTOs, or method parameters.
- Never return `null` from domain logic. A nullable persistence field requires an explanation in schema documentation.
- Pure `*Policy` classes have no Spring annotations, I/O, database access, or clock access.
- Do not catch `Exception` merely to log and continue. Convert known failures at a boundary; let unknown failures reach centralized handling.
- Do not use mutable global state. Quota counters, locks, idempotency and reservations belong in persistent storage.

## 6. Spring Boot conventions

- The main class sits in `sg.edu.smu.occ.<service>` and uses `@ConfigurationPropertiesScan`.
- Typed configuration uses validated `@ConfigurationProperties` records. Do not scatter `@Value` fields.
- Each service exposes Actuator liveness and readiness endpoints. Expose no sensitive configuration or environment endpoint publicly.
- Disable automatic ORM schema creation and mutation in every environment. Flyway owns schema changes.
- Use one transaction at the application-use-case boundary. Avoid remote HTTP or LLM calls while holding a database transaction or row lock.
- Map persistence entities to API responses explicitly; never serialize a JPA entity.
- Schedulers are disabled by default in tests and enabled through typed configuration.
- Scheduled jobs use a database lock or lease when duplicate execution across instances would be harmful.
- Network clients declare connection, response, and overall timeouts. Retries are bounded and limited to safe transient failures.
- Every service defines an application name, port, schema, and health behavior in its own README.

## 7. API and OpenAPI conventions

- External endpoints use `/api/v1/...`; service-only endpoints use `/internal/v1/...`.
- Resource paths are plural lowercase nouns with hyphens where needed.
- JSON fields use `camelCase`.
- Timestamps use ISO 8601 UTC.
- List responses are objects containing `items` and pagination metadata; do not return top-level bare arrays.
- Validate request records with Jakarta Validation and `@Valid`.
- Use RFC 9457 `application/problem+json` for errors.
- Problem responses include `type`, `title`, `status`, `detail`, `instance`, stable `code`, `requestId`, and optional field violations.
- Propagate `X-Request-Id`; generate one at the public edge when absent.
- Retryable mutations accept `Idempotency-Key` and persist the result or conflict outcome.
- Use optimistic-lock versions in editable-resource responses and commands.
- Never expose credentials, raw upstream authorization, arbitrary stack traces, JPA entities, or unrestricted raw payloads.

Each service owns `services/<name>/openapi/<name>-v1.yaml`. The implemented Spring contract and committed document must pass a CI drift check. The frontend generates TypeScript request and response types from the Operations and Ingestion contracts; do not hand-maintain duplicate API interfaces.

## 8. Authentication and authorization

- Cognito authenticates users. Backend services are OAuth2 resource servers and do not issue tokens.
- Accept access tokens only. Validate issuer, signature, expiry, allowed `client_id`, `token_use=access`, and OCC role groups.
- Supported application roles are `ADMIN`, `CONTROLLER`, and `DRIVER`.
- Reject a token with no OCC role or multiple conflicting OCC roles.
- Every protected backend endpoint enforces authorization independently.
- CORS and hidden navigation are not security controls.
- The Next.js application uses Auth.js with Cognito OIDC and authorization code with PKCE.
- Auth.js sessions use secure HTTP-only cookies. Never store access or refresh tokens in `localStorage`, expose them to client components, or include them in logs.
- Next.js server components, server actions, or same-origin route handlers attach access tokens to backend requests server-side.
- The user may update only permitted profile fields. Subject, verified email, role, assignment, and permissions are protected.
- Cognito groups are the role authority. Any database role field is an audit/display projection and must be reconciled with Cognito.

## 9. Database and migration conventions

- Each service owns its Flyway history and migration directory.
- Migration names use `V<zero-padded-number>__<snake_case_description>.sql`, for example `V001__create_user_profile.sql`.
- Never edit an applied migration. Add a later migration.
- Migrations must permit one-release rollback where practical: expand, backfill, then contract in a later release.
- Use singular table names matching the domain, such as `user_profile` and `poll_run`.
- Constraint and index names use `pk_`, `uq_`, `fk_`, `ck_`, and `ix_` prefixes.
- Put concurrency invariants in database constraints, not check-then-write application logic.
- Primary keys use UUID unless a source-owned stable natural key, such as `stop_code`, is the actual identity.
- Mutable records include `created_at`, `updated_at`, and optimistic-lock `version` when concurrent editing matters.
- PostGIS geometry uses SRID 4326 unless a migration and ADR justify another projection.
- Preserve historical configuration by versioning. Records referenced by a simulation or audit are not destructively updated or deleted.
- Use Testcontainers with PostgreSQL/PostGIS for integration tests. Do not use H2.

## 10. Ingestion conventions

- Only the Ingestion service calls DataMall.
- Every dynamic poll records a poll run even when it fails.
- Preserve the raw response, request parameters, checksum, fetch time, source time where supplied, and HTTP outcome before or alongside normalization.
- Normalized records reference their raw observation and poll run.
- Dynamic live data is classified `OBSERVATION_ONLY` in the MVP. It cannot initiate planning or alter simulation inputs unless the architecture document is explicitly revised.
- Use deterministic idempotency keys for scheduled work and caller-provided keys for manual triggers.
- Prevent overlapping polls for the same source and scope with a database lock or lease.
- Validate response structure and size before normalization.
- A failed poll updates feed health but never replaces last-known-good state with empty data.
- Compute staleness from server time and `last_success_at`; do not persist a drifting `stale` boolean.
- Static imports are staged and validated before an active reference version changes.
- Missing or ambiguous route direction remains unknown. Never infer it solely to make a downstream workflow proceed.
- Automated tests use WireMock or committed fixtures. CI never requires a live DataMall key.

## 11. Deterministic operations and AI boundaries

- Affected-route resolution, stop ranking, demand allocation, simulation, bunching classification, routing validation, resource eligibility, reservations, and execution policies are deterministic code.
- The resolver considers only active, validated route-profile versions in the MVP.
- Use collections keyed by `serviceId` and `directionId`; no formula or schema may contain route-specific fields.
- Store formula, topology, profile, scenario, input-snapshot, and rule versions with every result needed for replay.
- Gemini may interpret evidence, compare tool-produced alternatives, explain uncertainty, and propose a plan.
- Gemini may not select affected routes, invent measurements, alter coefficients, classify bunching, validate safety, reserve resources, or mutate operational state.
- Planning calls Operations through typed APIs and receives stable evidence IDs.
- Model output is untrusted input: deserialize into versioned records, validate all fields, permit at most one repair attempt, and fail visibly.
- Partial worker coverage is explicit. Never omit a failed, timed-out, or quota-skipped route silently.

## 12. Frontend conventions

- Use Next.js App Router and TypeScript strict mode.
- Organize by feature under `apps/web/src/features`; keep route files thin.
- Put genuinely reusable primitives in `components/ui`; do not create a generic component abstraction after one use.
- Generated API clients are server-only by default. UI components consume feature functions, server actions, or same-origin route handlers rather than calling backend URLs directly.
- Validate environment variables at startup. Only intentionally public values use `NEXT_PUBLIC_`.
- Every data surface implements loading, success, empty, stale/partial, forbidden, and failure states where applicable.
- Display `requestId` for supportable failures.
- Role-aware navigation improves UX but never replaces backend checks.
- Label live transport data **Observation only — not used for simulated planning** while that MVP rule remains active.
- Preserve stop codes and service IDs as strings in forms and tables.
- Do not encode severity or status through color alone. Keyboard navigation, focus visibility, form labels, and error association are required.

## 13. Testing

### Backend

| Tier | Purpose | Spring context | External dependencies |
|---|---|---|---|
| Unit | Domain policies, mappers, validators, formulas | No | Fakes only |
| Slice | Controller routing, JSON, validation and authorization | Focused slice | Mocked ports |
| Integration | Migrations, constraints, repositories, wiring | Full | Testcontainers/WireMock |
| Contract | OpenAPI compatibility and client expectations | As needed | Deterministic fixtures |

- Unit tests do not start Spring.
- Use `MockMvcTester` or the supported Spring Boot 4 MVC test API for controller slices.
- Integration tests end in `IT` and use the same PostgreSQL major and PostGIS behavior as deployment.
- Each authorization rule has both allow and deny tests.
- External adapters test success, timeout, malformed response, rate limit, and server failure.
- A mocked provider test is not evidence that the real provider integration works; record one explicit deployed smoke test separately.
- CI uses no production credential and no live upstream dependency.

### Frontend

- Run lint, TypeScript type-check, unit/component tests, production build, and Playwright smoke tests.
- Test generated clients against committed OpenAPI.
- Test protected-route redirect, forbidden state, profile editing, stale feed display, and backend problem mapping.
- E2E fixtures must be deterministic and must not depend on a live disruption.

Run backend verification independently:

```text
services/operations/gradlew clean check
services/ingestion/gradlew clean check
services/planning/gradlew clean check
```

## 14. Logging, metrics, and audit

- Operational logs and audit records are separate concerns and do not share a persistence path.
- Use SLF4J parameterized logging. Do not concatenate sensitive or high-volume payloads.
- Include service, request ID, operation, outcome, duration, and stable entity/source IDs where relevant.
- Never log passwords, tokens, authorization headers, DataMall keys, database credentials, verification codes, full raw upstream payloads, or unnecessary personal data.
- Metrics use stable low-cardinality labels. Never use request IDs, user IDs, stop codes, or exception messages as metric labels without a reviewed bounded design.
- Audit records capture actor, action, target type and ID, time, outcome, reason, and request ID.
- A successful operational mutation must not lose its required audit event. Use the same transaction or a transactional outbox where the audit destination is external.
- Telemetry retention and audit/business retention are configured separately.

## 15. Configuration and secrets

- Commit `application.yml` defaults and redacted `.env.example` files only.
- Do not commit `.env`, cloud credentials, OAuth secrets, DataMall keys, database passwords, certificates, or generated token files.
- Local development may inject developer-specific secrets through an ignored environment file.
- Deployed services receive only the secrets they require through AWS Secrets Manager and narrowly scoped IAM roles.
- Never bake secrets into images or expose them through Actuator, API responses, logs, frontend bundles, or `NEXT_PUBLIC_` variables.
- Validate required configuration at startup and fail with a non-secret diagnostic message.
- Timeouts, retry counts, stale thresholds, and scheduler intervals are typed configuration, not unexplained literals.

## 16. Git and documentation workflow

- Every code contribution uses a GitHub pull request linked to a Jira item and reviewed by at least one teammate.
- Branch names use the team's Jira-key convention; Codex-created branches use the required `codex/` prefix.
- Commit and PR titles start with the Jira key, followed by a concise scoped description.
- Keep pull requests small enough to review. Do not mix unrelated formatting, schema, infrastructure, and feature changes.
- Record one-way or expensive-to-reverse decisions in `docs/adr/` before implementation.
- Update OpenAPI, migrations, tests, runbooks, and relevant plans in the same pull request as the behavior change.
- Before requesting review, run the affected service's own Wrapper checks and the relevant frontend checks. State anything not verified in the PR description.
- Never rewrite or delete another contributor's work to make a branch clean. Resolve overlapping changes explicitly.

