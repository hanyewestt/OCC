# Authentication

The React app is served by Nginx; the Java services host APIs only.
Open `/login` using the configured Nginx host/port. Sign in with an existing
Supabase email/password account. Public self-registration and password reset are outside
this app. When the optional identity integration is enabled after its role migration,
ADMIN can provision immediately usable CONTROLLER or CAPTAIN accounts through the backend.

## Role pages

- `ADMIN` can open `/admin` only.
- `CONTROLLER` can open `/controller` only.
- `CAPTAIN` is displayed as Captain and can open `/captain` only.
- `UNASSIGNED` and unsupported claims receive an access message and sign-out.
  No role overrides another role’s page permissions.

React waits for Ingestion's session verification before rendering role content.
Static HTML/JavaScript are public assets; route guards are not backend access
control. Each backend independently verifies token signature, issuer, audience,
expiry and subject and normalizes the trusted top-level `occ_role` claim.

`GET /api/v1/auth/session` returns `{subject, role}` for an authenticated token;
missing/unrecognized roles normalize to `UNASSIGNED`. The admin/controller/captain
page check `GET /api/v1/auth/access/{page}` returns the same identity only for
the matching role. Wrong roles receive 403, unsupported page names 404, and
missing/invalid/expired tokens 401. Ingestion owns these routes and Nginx
routes them directly. Operational reads permit ADMIN and CONTROLLER. Operational writes require
CONTROLLER; the explicit admin namespaces require ADMIN. CAPTAIN retains only
the owner-scoped route, instruction and acknowledgement permissions.

The Admin workspace provides `/admin/audit`, `/admin/accounts`,
`/admin/accounts/new` and `/admin/assignments`. Controllers can also browse the
read-only `/controller/captains` directory; it contains every captain and does
not apply controller ownership filters. Initial simulation setup keeps the
existing `captainUserIds` request shape while selecting captains from this
directory.

Audit history is stored independently by each service and is retained for
`OCC_AUDIT_RETENTION` (default `90d`), regardless of simulation cleanup. It does
not include infrastructure, provider authentication or deployment logs.

## Administrator provisioning

`POST /api/v1/admin/users` accepts displayName, email, role and password with a
required actor-scoped `Idempotency-Key` (1–200 characters). Name/email are trimmed,
email is lowercased, and only CONTROLLER/CAPTAIN may be created. Name is limited
to 200 characters and email to 254 after trimming. Password is preserved exactly
and must contain 15–128 Unicode code points; no composition rules are added.
Provider email confirmation makes a confirmed creation immediately usable.
Passwords and password-derived values are never stored locally or audited.

Confirmed creation returns 201 with the safe account and a directory Location.
Uncertain creation returns 202 with an operation Location; any verified ADMIN
can inspect `/api/v1/admin/provisioning-operations/{id}`. Responses are no-store.
Reusing a completed key returns its original safe result and never changes the
password, even if a different password is submitted on replay. Changed normalized
name/email/role under the same key conflicts. An unrelated existing account is
never adopted.

Recovery scans match only server-managed operation IDs and never retry creation.
A complete no-match scan yields PASSWORD_REQUIRED: submit a freshly entered
password using a NEW key to create a new operation. A timed-out original creation
may still win later; if its original operation then completes, use the original
attempt password. The newer attempt cannot adopt that account or claim its new
password applied. Keep the original operation reference until resolved.
At the Nginx edge, these Ingestion-owned endpoints use the existing alias prefix
`/api/v1/ingestion/`, for example `/api/v1/ingestion/admin/users`. The Admin
Accounts page can request immediate directory synchronization; failed scans
retain the last complete projection and checkpoint.

## Persistent browser sessions

The Supabase SDK handles email/password sign-in, browser-storage persistence
and token refresh. Reloading/restoring the app requires session verification
before role content appears. A failed verification grants no access and offers
retry/sign-out. Session changes clear prior verified content and pending results.

Sign-out uses Supabase's `local` scope, clears app state and updates other open
tabs. Other devices remain signed in. If provider sign-out fails, the app hides
protected content and offers retry rather than claiming logout succeeded.
Browser storage contains session credentials and must never be committed or
logged. Sessions use browser storage rather than HttpOnly cookies or server-side
session storage. No OAuth redirects,
MFA, registration or password recovery are added.

## Configuration

Use root `.env.example` as the single inventory. Ingestion publishes only
`SUPABASE_PROJECT_URL` and the public `SUPABASE_SB_PUBLISHABLE` key through
`GET /frontend-config`. Private/service-role keys must never reach the browser.
JWT validation uses the separate JWKS URL, allowed algorithms and audience.

The optional backend identity directory reads `OCC_IDENTITY_SECRET_KEY` from the
ignored root `.env`, passed only to Ingestion by Compose. It also supports an
Ingestion-only mounted Docker secret file using `infra/compose.identity.yaml`. It remains disabled until the existing trusted roles have
been migrated to server-managed `app_metadata.occ_role` and the provider hook has
been verified. See [identity rollout](../infra/supabase/README.md) before enabling
it. Directory snapshots never grant permissions or replace JWT validation.
`OCC_IDENTITY_SECRET_SOURCE_FILE` points to the host-side secret source, and
`OCC_IDENTITY_SECRET_KEY_FILE` points to the fixed in-container mount. A nonblank
`OCC_IDENTITY_SECRET_KEY` takes precedence over that file; clear it to use mounted
key rotation. Environment keys are visible to administrators through container
inspection, so use the file option when that exposure is unsuitable.

See [development guide](DEVELOPMENT.md) for builds, Vite proxy settings and
browser tests. Nginx serves the React build. Simulation controls and
recommendation decisions use the dashboard's verified persistent session;
backend APIs independently enforce operational reads for ADMIN/CONTROLLER and
operational writes for CONTROLLER. The retired
`/demo.html`, `/auth.js` and `/auth-test` routes return 404.

## Verification

Ingestion tests cover the role matrix and JWT rejection. Browser tests mock
Supabase/API responses and cover UI navigation and session lifecycle.
For an opt-in live check using configured test accounts:

```shell
node --env-file=.env tests/system/supabase_live_auth_smoke.mjs
```

This contacts real Supabase, validates JWTs, checks edge and service permissions,
refreshes and signs out the test accounts locally. It does not run by default
and does not print tokens/passwords. Point `OCC_SUPABASE_TEST_EDGE_URL` at the
Nginx edge (the legacy `OCC_SUPABASE_TEST_GATEWAY_URL` name is still read).
Never use production operator accounts for automated tests.

Live checks use only `OCC_SUPABASE_TEST_CAPTAIN_EMAIL` and
`OCC_SUPABASE_TEST_CAPTAIN_PASSWORD`. Use the `CAPTAIN` account label when
invoking the smoke script.
