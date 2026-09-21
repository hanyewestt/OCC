# Plan 1 — Platform Foundation and Authentication

## Goal

Create the shared repository and deployment skeleton, then deliver Cognito-backed authentication, role enforcement, and persistent basic profiles through the Operations service.

## Owners and dependencies

- Suggested staffing: two people.
- Starts immediately.
- Blocks final integration for Plans 2 and 3, so the repository skeleton and OpenAPI contract must merge on Day 1.

## Owned components

- The common isolated-service template and repository standards.
- `services/operations` as an independent Spring Boot project.
- `services/planning` as an independent Week 1 Spring Boot scaffold.
- PostgreSQL/PostGIS local Compose and schema users.
- Cognito configuration and Spring Security conventions.
- Caddy local routing and shared CI workflow.
- Operations OpenAPI and profile schema.

## Database schema

```sql
create table operations.user_profile (
    cognito_sub varchar(64) primary key,
    display_name varchar(100) not null,
    application_role varchar(20) not null
        check (application_role in ('ADMIN', 'CONTROLLER', 'DRIVER')),
    contact_preference varchar(20) not null default 'IN_APP'
        check (contact_preference in ('IN_APP', 'NONE')),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0
);

create table operations.audit_event (
    id uuid primary key,
    actor_sub varchar(64),
    action varchar(80) not null,
    entity_type varchar(80) not null,
    entity_id varchar(160) not null,
    request_id varchar(80) not null,
    occurred_at timestamptz not null,
    details jsonb not null default '{}'::jsonb
);

create index ix_audit_event_entity_time
    on operations.audit_event (entity_type, entity_id, occurred_at desc);
```

Do not store passwords, refresh tokens, verified-email state, or Google identity tokens. Cognito remains authoritative for those fields.

## API contract

### `GET /api/v1/profile`

Returns the caller's profile. On first successful request, create the profile from validated token claims using an idempotent insert. Reject tokens with no supported role group or more than one OCC application-role group.

```json
{
  "subject": "cognito-sub",
  "displayName": "Peter",
  "applicationRole": "CONTROLLER",
  "contactPreference": "IN_APP",
  "createdAt": "2026-09-18T01:00:00Z",
  "updatedAt": "2026-09-18T01:00:00Z",
  "version": 0
}
```

The frontend displays verified email from its server-side Cognito/Auth.js identity session. The Operations profile API neither owns nor accepts verified-email data.

### `PATCH /api/v1/profile`

Permitted body:

```json
{
  "displayName": "Peter Tan",
  "contactPreference": "IN_APP",
  "version": 0
}
```

Reject unknown or protected fields, blank names, unsupported contact preferences, and stale versions. Return `409 PROFILE_VERSION_CONFLICT` for optimistic-lock failure.

### `GET /api/v1/admin/profiles`

Admin-only paginated profile listing for the Week 1 admin screen. Query fields: `page`, `size`, `role`, and `displayName`. Cap `size` at 100.

### `PUT /api/v1/admin/profiles/{subject}/role`

Admin-only role change. The application performs a single-role replacement in Cognito, records the new role projection, and writes an audit event. If the Cognito operation fails, the database projection must not change. If projection persistence fails after Cognito succeeds, return a visible reconciliation error and repair the projection from Cognito before another role change.

Request:

```json
{
  "applicationRole": "DRIVER"
}
```

## Tasks

### Day 1 — Repository and contracts

- Create the directory structure defined in `README.md`.
- Create the isolated-service template: Gradle Wrapper, Kotlin build scripts, Java 25 toolchain, version catalog, dependency locking, formatting, tests, Dockerfile, OpenAPI directory, and README.
- Scaffold `services/operations`, `services/ingestion`, and `services/planning` as three complete independent Spring Boot projects with distinct application names and ports; do not add a root Gradle build.
- Add root `verify-all.ps1` and `verify-all.sh` convenience scripts that invoke each service's own wrapper without coupling their builds.
- Add `.editorconfig`, expanded `.gitignore`, `.gitattributes`, formatting, test, and dependency-lock conventions.
- Commit `services/operations/openapi/operations-v1.yaml` and the Planning health scaffold; Plan 3 owns `services/ingestion/openapi/ingestion-v1.yaml` and publishes it on Day 1.
- Create local Compose with PostgreSQL/PostGIS and schema-specific users; add health checks.
- Record an ADR covering schema ownership, generic route identifiers, and generated API clients.

Verification:

- Each service compiles and runs tests using only its own Gradle Wrapper and project directory.
- Each service exposes liveness and readiness health locally.
- A service cannot write another service's schema.

### Day 2 — Cognito and backend security

- Create the development Cognito user pool, a confidential web application client used only by the Auth.js server, authorization-code-with-PKCE flow, callback/logout URLs, and `ADMIN`, `CONTROLLER`, `DRIVER` groups.
- Configure email/password registration and Google federation without committing credentials.
- Implement access-token issuer, signature, expiry, allowed-`client_id`, `token_use`, and group validation in Operations and Ingestion.
- Map groups to Spring authorities; reject absent or ambiguous OCC roles.
- Configure CORS only for known frontend origins. Keep authorization independent of CORS.
- Add security annotations and OpenAPI bearer-security definitions.

Verification:

- Valid role tokens reach permitted endpoints.
- Missing, expired, wrong-client, wrong-token-use, and malformed tokens return `401`.
- Valid tokens with the wrong group return `403`.

### Day 3 — Profiles and role administration

- Add Flyway migrations for `user_profile` and `audit_event`.
- Implement profile creation-on-first-access, read, and permitted update.
- Prevent self-service updates to subject, verified email, role, driver assignment, timestamps, or permissions.
- Implement admin profile listing and role replacement with audit records.
- Add RFC 9457 error handling and request-ID propagation.
- Generate and commit the Operations OpenAPI document.

Verification:

- Profile updates persist across restarts.
- Optimistic-lock conflicts return `409`.
- Role changes are admin-only and produce an audit event.
- Controller and driver tests prove role separation.

### Day 4 — Edge, CI, and deployable artifact

- Configure local Caddy routes for `/api/v1/profile/**`, `/api/v1/admin/**`, and ingestion endpoints.
- Add container images with non-root users, health checks, and runtime-only secrets.
- Add CI jobs for Gradle checks, Testcontainers integration tests, OpenAPI drift, and secret scanning.
- Document required environment variables and Cognito setup in redacted examples.
- Deploy the minimum backend path and prove token validation against the development Cognito pool.

### Day 5 — Integration and hardening

- Support Plans 2 and 3 integration and close contract mismatches.
- Exercise email/password and Google login end to end.
- Run authorization and profile regression tests through the public API path.
- Record setup, deployment, rollback, and known limitations.
- Remove any bootstrap credentials and rotate accidentally shared development secrets.

## Required tests

- Unit tests for role-claim parsing and profile-update validation.
- MVC/security tests covering `401`, `403`, success, invalid input, and protected fields.
- Testcontainers tests for migrations, first-login idempotency, optimistic locking, and audit persistence.
- Cognito adapter contract tests with a stubbed AWS client.
- One manual deployed Cognito smoke test for email/password and one for Google federation.

## Exit criteria

- All four API endpoints match committed OpenAPI.
- Email/password and Google login work in the deployed environment.
- Backend authorization is independently enforced for all three roles.
- Profile data persists and protected fields cannot be changed by the user.
- Admin role changes are audited and reconcile Cognito with the role projection.
- Local Compose, CI, and deployed health checks are green.
