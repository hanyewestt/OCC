# Authentication

The current demo and auth-test pages are temporary development tools. Nginx
serves their files from `frontend/static/`; Java services host APIs only.

## Local sign-in

Open `http://localhost/demo.html` or `http://localhost/auth-test/`, adjusting the
port if `OCC_NGINX_PORT` differs from `80`. Sign in with a Supabase test account.
ADMIN and CONTROLLER can use the existing operational APIs. DRIVER and users
without an authorized OCC role receive HTTP 403 for those APIs. Missing or
invalid access tokens receive HTTP 401.

Both pages use `frontend/static/auth.js`. They send the entered credentials
directly to Supabase Auth over HTTPS, clear the password field, and retain only
the access token in page memory. They do not persist sessions or retain refresh
tokens. Reloading, changing pages or token expiry requires another sign-in.
Clear session removes the page's token; it does not revoke the Supabase session.

The helper attaches `Authorization: Bearer <access-token>` to same-origin
`/api/v1/**` requests. Typing an API URL directly into the address bar does not
attach that header. Use the auth-test page or an HTTP client for authenticated
API requests. Gateway and backend services enforce authorization independently
of the displayed role or frontend controls.

## Public configuration

Set `SUPABASE_PROJECT_URL`, `SUPABASE_JWKS_URL`, the permitted JWT algorithm,
and `SUPABASE_SB_PUBLISHABLE` in the ignored root `.env`, using the root
`.env.example` as the inventory. Gateway exposes only the project URL and public
publishable key at `/frontend-config`. The key must start with `sb_publishable_`;
service-role, secret and legacy keys are rejected for browser configuration.
A blank publishable key leaves browser sign-in unavailable.

Gateway validates token signature, issuer, audience, expiry and the trusted
`occ_role` claim. See the [Gateway guide](../services/gateway/README.md) for its
settings and the opt-in live Supabase verification command.

## Production boundary

Production Nginx blocks `/auth-test` and its descendants. It still serves the
demo and its temporary sign-in form. This is not a completed production login
or persistent multi-page session design. The separate authentication story
must settle the provider redirect flow, server-side token/session handling,
cookie and CSRF protection, refresh, logout and bookmark behavior before those
capabilities are claimed. Do not treat hiding the auth-test page as completing
that work.
