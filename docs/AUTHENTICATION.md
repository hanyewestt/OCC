# Authentication

The React app is served by Nginx; Gateway and Java services host APIs only.
Open `/login` using the configured Nginx host/port. Sign in with an existing
Supabase email/password account. Registration and password reset are outside
this starter app.

## Role pages

- `CONTROLLER` can open `/controller` only.
- `DRIVER` is displayed as Captain and can open `/captain` only.
- `ADMIN`, `UNASSIGNED` and unsupported claims receive an access message and
  sign-out; ADMIN does not override these page permissions.

React waits for Gateway verification before rendering role content. Static
HTML/JavaScript are public assets; route guards are not backend access control.
Gateway independently verifies token signature, issuer, audience, expiry and
subject and normalizes the trusted top-level `occ_role` claim.

`GET /api/v1/auth/session` returns `{subject, role}` for an authenticated token;
missing/unrecognized roles normalize to `UNASSIGNED`. The controller/captain
page check `GET /api/v1/auth/access/{page}` returns the same identity only for
the matching role. Wrong roles receive 403, unsupported page names 404, and
missing/invalid/expired tokens 401. These Gateway-owned routes are excluded
from its Ingestion catch-all proxy. Existing business APIs still permit only
ADMIN/CONTROLLER; DRIVER receives no new business API permissions.

## Persistent browser sessions

The Supabase SDK handles email/password sign-in, browser-storage persistence
and token refresh. Reloading/restoring the app requires Gateway verification
before role content appears. A failed verification grants no access and offers
retry/sign-out. Session changes clear prior verified content and pending results.

Sign-out uses Supabase's `local` scope, clears app state and updates other open
tabs. Other devices remain signed in. If provider sign-out fails, the app hides
protected content and offers retry rather than claiming logout succeeded.
Browser storage contains session credentials and must never be committed or
logged. This is an explicit change from the retired memory-only demo; it is
not an HttpOnly-cookie or server-side session architecture. No OAuth redirects,
MFA, registration, password recovery or administrator dashboard are added.

## Configuration

Use root `.env.example` as the single inventory. Gateway publishes only
`SUPABASE_PROJECT_URL` and the public `SUPABASE_SB_PUBLISHABLE` key through
`GET /frontend-config`. Private/service-role keys must never reach the browser.
JWT validation uses the separate JWKS URL, allowed algorithms and audience.

See [frontend setup](../frontend/README.md) for builds, Vite proxy settings and
browser tests. Nginx serves the built artifact and returns 404 for the retired
`/demo.html`, `/auth.js` and `/auth-test` routes in local and production setups.

## Verification

Gateway tests cover the role matrix and JWT rejection. Browser tests mock
Supabase/Gateway responses and cover UI navigation and session lifecycle.
For an opt-in live check using configured test accounts:

```shell
node --env-file=.env tests/system/supabase_live_auth_smoke.mjs
```

This contacts real Supabase, validates JWTs, checks Gateway permissions,
refreshes and signs out the test accounts locally. It does not run by default
and does not print tokens/passwords. See the [Gateway README](../services/gateway/README.md)
for its environment settings. Never use production operator accounts for automated tests.
