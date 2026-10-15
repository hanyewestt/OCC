# code conventions

This is the single canonical conventions file for everything under `services/<service-name>/`. Do not maintain a second copied `CONVENTIONS.md` inside the service; update this file instead so the rules cannot drift. If a rule here conflicts with a habit from another project, this file wins. If you need to break a rule, write the reason in a comment at the break.

Stack: **Java 25**, **Spring Boot 4.1.1**, Maven, PostgreSQL 18.6, Docker and Docker Compose, Flyway for integration, Testcontainers for testing, and OpenAPI (springdoc-openapi 3.1.1) for API documentation, with Swagger UI for interactive browsing and testing. Spring Boot 3.x is at end of OSS support — do not downgrade without agreeing it with the team first.

---

## 1. Folder structure

Create exactly these directories. Services may be grouped beneath a domain directory
such as `services/planning/`; do not invent other siblings without updating this file.

```
services/[<domain>/]<service-name>/
├── pom.xml                            ← dependencies and Maven build settings
├── Dockerfile                         ← instructions to package the service as a container
├── src/main/java/com/csd/occ/<servicepackage>/
│   ├── ServiceNameApplication.java    ← starts the Spring Boot application
│   ├── api/                           ← HTTP endpoints: receives requests, returns responses
│   │   ├── dto/                       ← request and response data structures
│   │   └── error/                     ← maps errors to consistent HTTP responses
│   ├── domain/                        ← JPA entities, Spring Data repositories, services, and rules
│   └── infrastructure/
│       ├── security/                  ← authentication and access-control configuration
│       └── config/                    ← typed configuration settings used by Java code
├── src/main/resources/
│   ├── application.yml                ← shared runtime settings: service name, port, logging
│   ├── application-local.yml          ← local-profile overrides: local DB connection, etc.
│   └── db/migration/                  ← versioned SQL scripts that evolve the database
└── src/test/java/com/csd/occ/<servicepackage>/
    ├── domain/                        ← tests business rules independently
    ├── api/                           ← tests endpoints, validation, and HTTP responses
    └── integration/                   ← tests components working together, including the DB
```

### Which file goes where

| You are writing                       | Put it in                          | Named                                    |
| ------------------------------------- | ---------------------------------- | ---------------------------------------- |
| The `@SpringBootApplication` class    | root package, nothing else there   | `<ServiceName>Application.java`         |
| An HTTP endpoint                      | `api/`                             | `<Resource>Controller.java`              |
| A request or response body            | `api/dto/`                         | `<Verb><Noun>Request` / `<Noun>Response` |
| Exception → HTTP mapping              | `api/error/`                       | `GlobalExceptionHandler.java`            |
| A JPA entity                          | `domain/`                          | `<Noun>.java`                            |
| An enum of domain states              | `domain/`                          | `<Noun>.java`                            |
| A pure decision rule                  | `domain/`                          | `<Noun>Policy.java`                      |
| Orchestration across entities         | `domain/`                          | `<Noun>Service.java`                     |
| A Spring Data repository interface    | `domain/`                          | `<Entity>Repository.java`                |
| A port to an external system          | `domain/`                          | `<Capability>Port.java`                  |
| The implementation of such a port     | `infrastructure/<tech>/`           | `<Tech><Capability>Adapter.java`         |
| `SecurityFilterChain`, JWT conversion | `infrastructure/security/`         | `<Purpose>Config` / `<Purpose>Converter` |
| Typed config                          | `infrastructure/config/`           | `<Area>Properties.java`                  |
| A schema change                       | `src/main/resources/db/migration/` | `V<n>__<snake_case>.sql`                 |

**Why repositories live in `domain/` and adapters do not.** JPA entities are our domain models, and Spring Data JPA interfaces are their data-access contracts. Keep both in `domain/` alongside services and rules; do not add duplicate persistence models or repository wrappers. A separate `infrastructure/persistence/` package is unnecessary unless custom persistence implementations are introduced. External integrations — such as DataMall and OneMap — get a port in `domain/` and an adapter in `infrastructure/`. Provider-specific types stay inside their adapter package and must not appear in domain contracts.

### Layering rules

```
api  →  domain  ←  infrastructure
```

- `api` may import `domain`. It must **never** touch a repository directly, and never import `infrastructure`.
- `domain` must **never** import `api` or `infrastructure`. Its only framework imports are Spring Data, Jakarta Persistence, Jakarta Validation and Spring's `@Service`/`@Transactional`.
- `infrastructure` may import `domain` — that is how an adapter implements a port.
- External-provider SDK types may appear only inside the corresponding `infrastructure/<tech>/` package. They must never appear in `api/`, `domain/`, another provider's adapter, or public port signatures. No `jakarta.servlet` import inside `domain`.

Worth adding ArchUnit later to enforce this in CI rather than by review.

---

## 2. Naming

### Packages

Lowercase, no underscores, no camelCase, **singular** (`domain`, not `domains`).

### Types

| Kind          | Pattern                                                                       | Example                            |
| ------------- | ----------------------------------------------------------------------------- | ---------------------------------- |
| Entity        | singular noun, `Occ` prefix where the bare name clashes with a framework type | `Incident`, `OccUser`            |
| Enum          | singular noun                                                                 | `Role`, `AccountStatus`            |
| Pure rule     | `<Noun>Policy`                                                                | `ReservationPolicy`                    |
| Orchestration | `<Noun>Service`                                                               | `ReservationService`        |
| Repository    | `<Entity>Repository`                                                          | `ReservationRepository`             |
| Port          | `<Capability>Port`                                                            | `IdentityProviderPort`             |
| Adapter       | `<Tech><Capability>Adapter`                                                   | `OneMapRoutingAdapter`   |
| Controller    | `<Resource>Controller`, singular class / plural URL                           | `UserController` → `/api/v1/users` |
| Request DTO   | `<Verb><Noun>Request`                                                         | `CreateUserRequest`                |
| Response DTO  | `<Noun>Response`                                                              | `UserResponse`                     |
| Config        | `<Area>Properties`                                                            | `ReservationProperties`                |
| Exception     | `<Problem>Exception`                                                          | `IncidentNotFoundException`         |

`Occ` prefix only where it earns its place — `OccUser` avoids colliding with Spring Security's `User`. Do not prefix everything.

Never use `Util`, `Helper`, `Manager`, `Handler` (except `GlobalExceptionHandler`), `Data`, `Info`, or `Impl`. A class named `Impl` means the interface was unnecessary.

### Methods

- **Booleans** read as assertions: `isExpired()`, `permitsDispatch()`, `hasAvailableCapacity()`. Never `checkX()` or `getIsX()`.
- **Commands** are imperative: `approve()`, `reserveStandbyPair()`.
- **Queries** are named for what they return: `remainingCapacity()`, not `getRemainingCapacity()`. Reserve `get` for plain accessors; records give those for free.
- **Repositories** use Spring Data derived-query naming: `findByIncidentId`, `existsByCorrelationId`.
- Avoid boolean parameters at call sites. `evaluate(status, expiresAt, now)` is fine; `dispatch(plan, true, false)` is not — use an enum.

### Tests

Method names are camelCase sentences describing behaviour, matching Spring's own style. **No** `test` prefix, **no** `should`, **no** `given_when_then` underscores.

```java
@Test
void reservationIsExpiredExactlyAtItsDeadline() { ... }

@Test
void repeatedApprovalDoesNotCreateAnotherAssignment() { ... }
```

Class names: `<ClassUnderTest>Test` for unit and slice tests, `<Feature>IT` for anything needing Docker.

---

## 3. Java style

**Records for anything that is data.** All DTOs, all `@ConfigurationProperties`, all value objects. Records are the default; a class needs a reason.

**No Lombok.** Java records plus explicit code cover it, and Lombok does not compose with records. Do not add the dependency.

**Constructor injection only.** Single constructor, no `@Autowired`, fields `private final`. No field or setter injection, ever.

```java
@Service
public class ReservationService {
    private final ReservationRepository reservations;
    private final ReservationPolicy policy;
    private final Clock clock;

    public ReservationService(ReservationRepository reservations, ReservationPolicy policy, Clock clock) { ... }
}
```

**Always inject `Clock`; never call `Instant.now()` directly.** Reservation expiry, directive validity and observation freshness must be testable at their exact boundaries. Provide a `Clock` bean (`Clock.systemUTC()`) and use `Clock.fixed(...)` in tests.

**Time is `Instant`, stored as `TIMESTAMPTZ`.** Never `Date`, never `LocalDateTime` for a moment in time. UTC everywhere; format for display in the frontend only.

**Nulls:** `Optional` for repository lookups that may miss. Never return `null` from a domain method, never accept `null` as a meaningful argument. Nullable database columns need a comment saying why.

**Immutability:** prefer `final` fields and returning new instances. JPA entities need mutable fields and a protected no-arg constructor — that is the exception, keep it contained.

**Pure where possible.** A `*Policy` class must have no Spring annotations, no I/O, no clock access, and must be deterministic — every input arrives as a parameter. That makes reservation-expiry and intervention-policy tests fast and exact.

---

## 4. Spring Boot 4.1 specifics

Verified against current Spring Boot 4.1 documentation.

**Main class in the root package**, above everything else — `@SpringBootApplication` uses its package as the component-scan and entity-scan base. Add `@ConfigurationPropertiesScan` there.

**Typed config as records with constructor binding**, which is the 4.1 idiom:

```java
@ConfigurationProperties("occ.reservation")
public record ReservationProperties(Duration expiry) {}
```

Bind config to these, never `@Value` on a field.

**Security config** as a `SecurityFilterChain` bean using the lambda DSL, in `infrastructure/security/`. Protected APIs validate tokens from the configured identity provider and enforce the required `ADMIN`, `CONTROLLER` or `DRIVER` role server-side.

**Configuration keys** are kebab-case under an `occ.` prefix: `occ.reservation.expiry`. Important Spring Boot runtime and operational values must not be hard-coded directly in `application.yml` or `application-local.yml`. Bind them through environment-variable placeholders (for example, `${OCC_INGESTION_POLL_INTERVAL}`) and define the values in the centralized environment configuration documented by `.env.example` and the deployment secret/config store. This includes connection URLs, credentials, ports, timeouts, polling intervals, thresholds, feature flags and other values that vary by environment. YAML may retain stable application structure and safe defaults only where an omitted environment value is intentionally supported and documented.

**Secrets** are supplied through the environment or the deployment's configured secret store. Never commit credentials, bake them into container images, or expose them through API responses or logs. Automated tests use synthetic values or credentials supplied by their fixtures and containers; they must never read production secrets. Nothing sensitive is committed, including in `application-local.yml`.

---

### Local configuration and build entry points

Use the Maven wrapper in each runnable service (`mvnw` / `mvnw.cmd`), pinned by `.mvn/wrapper/maven-wrapper.properties`. Build with `./mvnw verify` or `.\mvnw.cmd verify`.

Local development uses one ignored repository-root `.env`, documented by the root `.env.example`. `application-local.yml` imports that file using a path relative to its service directory: `optional:file:../../.env[.properties]` for `services/<service-name>` and `optional:file:../../../.env[.properties]` for `services/<domain>/<service-name>`. Run with the `local` profile and the service directory as working directory. Use unquoted values compatible with both Java properties and Compose. Do not put the local import in the default deployment configuration.

Run the canonical root stack with `docker compose --env-file .env up -d`; Compose also loads the root `compose.override.yaml` for local builds and host ports. Database bootstrap and per-schema Flyway jobs run before their owning applications. Runtime database credentials cannot perform DDL; deployment supplies each service's own runtime environment rather than the shared local file.

Scheduling and startup callbacks belong in `infrastructure/config`; they delegate to domain services. Domain services access external systems through ports implemented by provider adapters.

## 5. API conventions

- Public endpoints: `/api/v1/...`. Service-to-service: `/internal/v1/...`. Version in the path, always.
- Protected requests are authenticated before controller execution. Pass the authenticated actor to domain services when required for authorization and auditing; never trust an actor ID supplied in a request body.
- A controller never decides permissions. Rules live in domain policy/services. Operational mutations revalidate permissions and relevant resource state within the transaction before changing state.
- Resource paths are plural nouns, lowercase, hyphenated: `/api/v1/users`, `/internal/v1/authorization-checks`.
- Request and response bodies are records in `api/dto/`. **Never expose an entity over HTTP** — it leaks schema and risks exposing internal or protected fields.
- Validate with Jakarta Validation annotations on the request record plus `@Valid` on the parameter.
- Errors use RFC 9457 `ProblemDetail`, produced centrally in `api/error/`. Controllers throw; they do not build error bodies.
- JSON is camelCase. Lists are paginated. Never return a bare array as a top-level body.
- No endpoint returns a different shape based on caller role — filter the _set_, not the schema.

### OpenAPI and Swagger UI

OpenAPI describes the HTTP contract; Swagger UI renders it and lets developers send requests. The following are OCC conventions, informed by the official references below.

**Tooling and ownership**

- For Spring MVC services, use `org.springdoc:springdoc-openapi-starter-webmvc-ui`, pinned to the project's springdoc version. Spring Boot 4 uses springdoc 3.x; check compatibility before upgrades. Do not introduce Springfox or legacy Swagger 2 annotations.
- Generate the contract from controllers and DTOs. Update annotations with the implementation in the same PR; do not maintain a separate hand-edited copy of the generated specification.
- Put `OpenApiConfig.java` in `infrastructure/config/` for service metadata, security schemes and documentation groups. Keep operation annotations in `api/` and schema annotations in `api/dto/`; do not annotate domain entities for documentation.
- Use OpenAPI 3.1 output (`springdoc.api-docs.version=OPENAPI_3_1`). This specification version is separate from our `/api/v1` URL version and the service version in `info.version`.
- Default documentation locations are `/v3/api-docs`, `/v3/api-docs.yaml` and `/swagger-ui.html`, relative to the service context path. Use path-based groups for public `/api/v1/**` and internal `/internal/v1/**` contracts when both exist. Grouping is not access control. [springdoc documentation](https://springdoc.org/)

**What each endpoint documents**

- Give each operation a stable, unique camelCase `operationId`, such as `listIncidents` or `approvePlan`, a short summary, and a resource tag such as `Incidents`. Avoid renaming operation IDs casually because generated clients can depend on them.
- Describe path/query parameters, required request bodies, accepted media types and relevant headers. Document pagination defaults, limits, sort fields and the actual response envelope.
- Declare the actual success status and response DTO. A `201` response documents `Location` if returned; a `204` response has no body schema. List applicable failures, such as `400`, `401`, `403`, `404` and `409`, with their meaning for that operation. Do not mechanically attach every status to every endpoint.
- Use `@Operation`, `@ApiResponse`, `@Parameter` and `@Schema` from `io.swagger.v3.oas.annotations` to fill gaps in inferred documentation. Let Java types and Jakarta Validation express constraints first; verify the generated schema instead of duplicating every constraint in annotations.
- Describe field meaning, units, enum values, requiredness and nullability. Use `date-time` for serialized `Instant` values, with UTC examples. Required presence and permission to send `null` are different rules.
- Include synthetic request, success and failure examples that satisfy the schema. OCC examples must distinguish `SIMULATION` from `OBSERVATION_ONLY`; describe evidence IDs, freshness and version references where relevant.
- For approval, execution and acknowledgement operations, document the implemented idempotency mechanism, duplicate-request result and state conflicts. Describe role and assigned-driver restrictions in plain language. [OpenAPI specification](https://spec.openapis.org/oas/v3.1.1.html)

**Errors and authentication**

- Document errors as `application/problem+json` using the shared RFC 9457 contract: `type`, `title`, `status`, `detail`, `instance`, and any extensions the application actually emits. Explain validation-error and correlation-ID extensions consistently. Reuse shared schemas/responses rather than maintaining inconsistent copies.
- Verify that exception handlers and security-filter failures return the documented media type and shape. Documenting `ProblemDetail` does not make authentication failures use it automatically. Error examples must not contain stack traces, SQL or secrets. [Spring error-response guidance](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
- For JWT bearer authentication, define a named HTTP bearer security scheme and reference it on protected operations. Explicitly clear inherited security requirements for genuinely anonymous operations. Do not model `Authorization` as an ordinary header parameter or expose injected principals as client input.
- Document roles in descriptions; do not invent OAuth scopes for application roles. OpenAPI security declarations describe requirements; Spring Security and domain authorization enforce them. [OpenAPI security guidance](https://learn.openapis.org/specification/security.html)
- Keep Swagger UI authorization persistence disabled and never embed tokens or client secrets in examples or configuration. Enable documentation locally; in shared deployments, explicitly control access to both the UI and specification endpoints, including the ungrouped document. Do not relax business-endpoint security to make Swagger UI work.

**Verification before merging**

- Generate and parse the specification in CI using a test configuration with synthetic credentials. Fail on invalid references, duplicate operation IDs or missing expected endpoints.
- Compare representative HTTP responses with their documented status, content type and schema, including validation failures and unauthorized/forbidden requests. Successfully generating a specification alone does not prove the implementation matches it.
- Review the generated contract diff against the target branch for removed operations, newly required fields, changed types and changed responses. Breaking changes need an explicit compatibility/versioning decision.
- Export the generated specification as a build artifact for frontend and service consumers. When documenting these checks in a PR, distinguish checks actually run from conventions still awaiting implementation.

---

## 6. Database and migrations

- One migration per change: `V<n>__<snake_case_description>.sql`, sequential `n`, e.g. `V2__add_incident_table.sql`.
- **Never edit an applied migration.** Flyway checksums them; a change breaks every environment. Write `V<n+1>` instead.
- Migrations must be backward-compatible across one release, because a container rollback does not reverse a migration. Add columns nullable, backfill, then tighten in a later release.
- Table and column names: `snake_case`, singular table names (`incident`, `controller_action`).
- Constraint naming: `pk_`, `uq_`, `fk_`, `ck_`, `ix_` prefix then table then column(s).
- **Put invariants in the database when it can hold them.** Use appropriate constraints and transactional locking to prevent conflicting active bus or driver assignments and duplicate command processing under concurrent requests.
- Comment non-obvious constraints in the SQL. The migration is read far more often than it is written.

---

## 7. Tests

Three tiers, three locations, three speeds.

| Tier        | Location                   | Annotation        | Docker | Use for                                    |
| ----------- | -------------------------- | ----------------- | ------ | ------------------------------------------ |
| Unit        | `test/.../domain/`         | none              | no     | Policies, entities, pure rules             |
| Unit        | `test/.../infrastructure/` | none              | no     | Adapter mapping and config validation      |
| Slice       | `test/.../api/`            | `@WebMvcTest`     | no     | Controller routing, validation, auth rules |
| Integration | `test/.../integration/`    | `@SpringBootTest` | yes    | Real schema, real queries, wiring          |

- Unit tests must not start a Spring context. If a domain class needs one, it is in the wrong package.
- An `infrastructure/` unit test mirrors its subject's package (`test/.../infrastructure/config/`). It covers adapter mapping, dependency failures and startup validation in a `*Properties` record. JPA mappings and repository queries are verified against PostgreSQL in `integration/`.
- Slice tests use `MockMvcTester` (the 4.x idiom) and `@WithMockUser` for role cases.
- Integration tests use Testcontainers with `@ServiceConnection` and `@Testcontainers(disabledWithoutDocker = true)`, against the same PostgreSQL major as the application database. **Never** test against H2 — it will not reproduce partial indexes or `TIMESTAMPTZ` behaviour, and both matter here.
- Name the mocked boundary explicitly. A test using a fake DataMall response must not be reported as evidence of real DataMall behaviour.
- Every permission rule gets both an allow case and a deny case. A deny case that only checks the UI is not a test.

---

## 8. Logging and audit

**These are two different things and must not share a code path.**

- **Operational logs**: SLF4J, `private static final Logger log = LoggerFactory.getLogger(X.class)`. For diagnosing the service. Free to be lossy.
- **Audit records**: retain only AI input/output evidence and active operational recovery state. Do not create a permanent general-purpose audit trail for routine processing.

Configure diagnostic-log retention separately from operational data. Raw, normalized, closed demo, and resolved-alert history use the configured `OCC_INGESTION_RETENTION` window (12h by default); cleanup is irreversible. Retained AI response/input snapshots follow their separate long-lived policy. Label simulation and observation-only records explicitly so retrospective investigations can distinguish them.

Never log a password, token, MFA secret, verification code, or session identifier. Never log a full email at INFO. When in doubt, log the stable user ID — that is what it is for.

---

## 9. Git workflow

Follow the repository-wide [`commit-convention.md`](commit-convention.md)
for commit messages and Git workflow. Before committing service code, run
`mvn verify` and state what was or was not verified in the commit body.
