# Week 1 Implementation Plan

## Outcome

By the end of Week 1, an authenticated user can sign in through Cognito, open the deployed Next.js application, view and update a basic profile, and view persisted bus-feed status and arrival data obtained by the Ingestion service. OpenAPI, database migrations, tests, local Compose, and CI verify this walking skeleton.

This week is split into three parallel plans:

1. [Platform foundation and authentication](01-platform-auth.md)
2. [Frontend UI](02-frontend-ui.md)
3. [Bus-data polling service](03-bus-data-polling.md)

Plan 1 owns shared repository scaffolding and the authentication contract. Plans 2 and 3 start from those frozen contracts and can proceed in parallel using generated types and fixtures.

## Week 1 scope

### Required

- Standard repository structure for three Spring Boot microservices and one Next.js application.
- An independent Gradle Wrapper, version catalog, Java 25 toolchain, Spring Boot 4.1.1 build, formatting, tests, and CI job for each Spring Boot service.
- PostgreSQL/PostGIS local environment with service-owned Flyway migrations.
- Cognito email/password and Google federation configuration.
- `ADMIN`, `CONTROLLER`, and `DRIVER` authorization enforced by Spring Security.
- Basic profile view and permitted profile update.
- Read-only DataMall bus reference import and one-minute Bus Arrival polling for configured stops.
- Raw and normalized ingestion storage with idempotency and feed health.
- Authenticated frontend shell, profile page, observation dashboard, and role-aware navigation.
- Versioned OpenAPI specifications and generated frontend API types.
- One local and one deployed end-to-end proof.

### Not in Week 1

- Gemini calls, manager or route-worker orchestration.
- Scenario CRUD, affected-route resolution, demand forecasting, simulation, or operational directives.
- Full depot, vehicle, driver, or roster CRUD.
- Live incidents initiating planning.
- Production-quality visual maps.

## Standard repository layout

```text
OCC/
├─ apps/
│  └─ web/                         # Next.js application
├─ services/
│  ├─ ingestion/                   # isolated Spring Boot project
│  ├─ operations/                  # isolated Spring Boot project
│  └─ planning/                    # isolated Spring Boot project; scaffold in Week 1
├─ infra/
│  ├─ local/
│  │  ├─ compose.yaml
│  │  └─ .env.example
│  ├─ caddy/
│  │  └─ Caddyfile
│  └─ aws/
│     └─ README.md                 # environment inventory and manual bootstrap notes
├─ docs/
│  ├─ adr/
│  ├─ api/
│  └─ plans/
├─ scripts/
│  ├─ verify-all.ps1
│  └─ verify-all.sh
├─ .editorconfig
├─ .gitattributes
├─ .gitignore
└─ README.md
```

Each directory under `services/<name>` is a complete, isolated Spring Boot project. It has its own Gradle Wrapper, settings, version catalog, dependency locks, source tree, tests, migrations, OpenAPI document, Docker build, README, and CI job. A developer can copy or check out that directory and build it without any root Gradle project or another service's source code.

The three projects begin from the same reviewed service template, but the template is copied rather than consumed as a local build dependency. Keep versions aligned through dependency-update automation and an explicit ADR, not a shared domain or build JAR. The root verification scripts only invoke each service's public wrapper command; they contain no compilation logic.

## Spring service structure

Use package-by-feature, with layers inside each feature only when needed:

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
│  │  │  ├─ <feature>/
│  │  │  │  ├─ api/
│  │  │  │  ├─ application/
│  │  │  │  ├─ domain/
│  │  │  │  └─ infrastructure/
│  │  │  └─ shared/
│  │  │     ├─ config/
│  │  │     ├─ error/
│  │  │     └─ security/
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

Week 1 feature roots are:

| Service | Package root | Initial features |
|---|---|---|
| Operations | `sg.edu.smu.occ.operations` | `profile`, `identity`, `audit`, `shared` |
| Ingestion | `sg.edu.smu.occ.ingestion` | `polling`, `datamall`, `busreference`, `busarrival`, `feedhealth`, `shared` |
| Planning | `sg.edu.smu.occ.planning` | `health`, `shared`; planning features begin later |

Features must not reach directly into another feature's repository implementation. Cross-feature work goes through an application service or explicit port. Planning accesses Operations through typed HTTP APIs and never imports Operations classes or writes its tables.

## Database ownership

One PostgreSQL/PostGIS instance contains three schemas with separate application users:

| Schema | Owner | Week 1 responsibility |
|---|---|---|
| `ingestion` | Ingestion service | polling, raw observations, feed status, bus reference and arrivals |
| `operations` | Operations service | user profile and application audit records |
| `planning` | Planning service | empty baseline migration and connectivity proof |

Rules:

- Every schema change is a forward-only Flyway migration owned by its service.
- No service receives write permission to another service's schema.
- JPA/Hibernate automatic DDL mutation is disabled in every environment.
- Database names use `snake_case`; primary keys use UUID unless the source has a meaningful stable key such as a bus-stop code.
- Mutable rows include `created_at`, `updated_at`, and an optimistic-lock `version` where concurrent edits matter.
- Store timestamps as UTC `timestamptz`; convert to `Asia/Singapore` only for display.
- PostGIS coordinates use SRID 4326.

## API conventions

- Base path: `/api/v1`.
- JSON fields: `camelCase`.
- Java types: `PascalCase` classes and `camelCase` members.
- Route identity: `serviceId` plus numeric `directionId`; never introduce `service190` or `service972` fields.
- Bus-stop codes remain strings so leading zeroes are preserved.
- Dates and timestamps use ISO 8601; APIs emit UTC timestamps.
- Errors use RFC 9457 `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, `code`, `requestId`, and optional field violations.
- Propagate `X-Request-Id`; generate one at the edge when absent.
- Mutating command endpoints that may be retried accept `Idempotency-Key`.
- Spring-generated OpenAPI is checked against the committed contract in CI.
- Frontend request/response types are generated from committed OpenAPI; do not hand-maintain duplicate DTO types.
- Never return JPA entities directly from controllers.

## Security conventions

- Cognito authenticates users and issues tokens; Spring Security accepts access tokens only and validates issuer, signature, expiry, allowed `client_id`, `token_use=access`, and required groups independently in every exposed backend service.
- The Next.js application uses Auth.js with Cognito's OIDC provider and authorization code with PKCE. Sessions use secure HTTP-only cookies; access and refresh tokens must not be stored in `localStorage` or exposed to client components.
- Server components, server actions, or same-origin route handlers call backend APIs through generated clients and attach the Cognito access token server-side.
- Cognito groups are the authorization source for `ADMIN`, `CONTROLLER`, and `DRIVER`.
- `operations.user_profile.application_role` is a non-user-editable projection for display and audit; it must match the validated token group before profile data is returned.
- Browser navigation guards improve UX but are not authorization controls.
- Secrets use environment variables locally and Secrets Manager when deployed. Only redacted `.env.example` files are committed.
- Logs must not contain access tokens, refresh tokens, passwords, DataMall keys, database credentials, or raw authorization headers.

## Source and test conventions

- Branches use `codex/` for Codex-created work; team branches otherwise use the agreed Jira-key convention.
- Commit and PR titles start with the Jira issue key.
- All code changes use pull requests and one peer review.
- Java formatting and static checks run through each service's own Gradle Wrapper. CI uses a service-directory matrix, and the root verification scripts invoke those wrappers for convenience.
- TypeScript uses strict mode, ESLint, and a formatter enforced in CI.
- Unit-test names describe behavior; integration tests end in `IT` and use Testcontainers.
- External HTTP integrations use recorded fixtures or WireMock in automated tests; CI never requires live DataMall or Cognito availability.
- Every externally visible endpoint has authorization, success, validation, and failure tests.

## Shared Week 1 schemas

### Error response

```json
{
  "type": "https://occ.example/problems/validation-error",
  "title": "Request validation failed",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "instance": "/api/v1/profile",
  "code": "VALIDATION_ERROR",
  "requestId": "d969ca82-213f-4f26-9103-dc944b75b0dc",
  "violations": [
    { "field": "displayName", "message": "must not be blank" }
  ]
}
```

### Feed status response

```json
{
  "source": "BUS_ARRIVAL_V3",
  "scopeKey": "stop:44251",
  "status": "HEALTHY",
  "lastAttemptAt": "2026-09-18T01:01:00Z",
  "lastSuccessAt": "2026-09-18T01:01:00Z",
  "consecutiveFailures": 0,
  "stale": false,
  "message": null
}
```

## Cross-plan integration order

1. Plan 1 commits repository scaffolding, security contract, local Compose, and the initial Operations OpenAPI.
2. Plan 3 adds its service behind `/api/v1/ingestion/**` and publishes fixture-backed OpenAPI examples immediately, so Plan 2 can integrate before live polling is complete.
3. Plan 2 generates its clients from the committed contracts and delivers the profile and feed-status screens.
4. Plans merge daily after CI is green; do not maintain long-lived integration branches.
5. The final Week 1 gate runs against one deployed environment with real Cognito and a configured DataMall key.

## Definition of done

- `services/operations/gradlew clean check`, `services/ingestion/gradlew clean check`, and `services/planning/gradlew clean check` pass independently.
- The web application's install, lint, type-check, unit-test, build, and smoke E2E commands pass.
- Local Compose starts PostgreSQL/PostGIS, Caddy, Operations, Ingestion, and the scaffold Planning service.
- Email/password and Google sign-in both complete through Cognito.
- Unauthenticated API calls return `401`; authenticated wrong-role calls return `403`.
- Profile changes persist and users cannot change protected identity or role fields.
- A configured stop is polled, raw and normalized rows persist idempotently, and the UI displays feed status and arrivals.
- A failed poll retains the last successful state and marks it stale instead of replacing it with empty data.
- OpenAPI contracts and implementation pass CI drift checks.
- The public backend and frontend deployment are demonstrable and documented.
