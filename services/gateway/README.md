# Gateway

The Gateway is the public API entry point. It validates Supabase access tokens,
checks the `occ_role` claim, denies public access to peer endpoints, and routes
the versioned API namespaces to the internal service URLs.

Copy the repository-root `.env.example` to `.env`, then set `SUPABASE_PROJECT_URL`
and `SUPABASE_JWKS_URL` to the current Supabase project. Keep
`SUPABASE_JWT_ALLOWED_ALGORITHMS` aligned with the project's signing algorithm
(the current setup uses `ES256`). The issuer is derived as
`${SUPABASE_PROJECT_URL}/auth/v1`; tokens also must have the configured audience
and a non-empty subject. The role converter reads only the top-level `occ_role`
claim supplied by the custom access-token hook.

In Compose, route destinations use `OCC_INGESTION_INTERNAL_URL`,
`OCC_DISRUPTION_INTERNAL_URL`, and `OCC_PLANNING_INTERNAL_URL`. The `local`
profile imports the root `.env` and uses each service's host-facing `*_PUBLIC_URL`
for a host-native run. The Gateway retains a catch-all Ingestion API route after
the named Disruption, Planning, and Ingestion namespaces so the existing demo
page can continue calling `/api/v1/...` paths. Nginx serves `/demo.html` directly; Gateway has no HTML routes or static resources.

The local Compose override publishes application ports on loopback for testing.
The production Compose profile publishes only Nginx; Nginx and Gateway share the
edge network, Gateway and backends share the app network, and database access
uses a private network. Use the explicit production files in
[`docs/LOCAL-DEVELOPMENT.md`](../../docs/LOCAL-DEVELOPMENT.md) so local ports are
not merged into deployment.

The first API authorization rule allows `ADMIN` and `CONTROLLER` roles. `DRIVER`
and `UNASSIGNED` receive `403`; missing or invalid tokens receive `401`. Health
probes remain available at `/actuator/health`. Peer `/internal/v1/**` calls are
not routed through the public Gateway.

Nginx serves the temporary password-grant page at `http://localhost/auth-test/`
and the demo at `http://localhost/demo.html`. Both use the public
`GET /frontend-config` endpoint, which returns only `SUPABASE_PROJECT_URL` and
`SUPABASE_SB_PUBLISHABLE`. The key must be an `sb_publishable_...` public key;
blank keys leave browser login unavailable, and private/legacy keys fail startup.
The settings bind through `FrontendProperties`. API authentication still uses
the separately configured issuer, JWKS, audience and allowed algorithms.
The former Java page-hosting setting `OCC_GATEWAY_AUTH_TEST_ENABLED` is removed;
Nginx's separate local/TLS configurations control auth-test availability.
Production always blocks the temporary page. Access tokens are held in page
memory only; reloads and page changes require sign-in again. Expiry or an API
401 clears the local session. A local clear does not revoke the Supabase session.

For an opt-in live check, set the optional test values listed in the root
`.env.example` in the ignored `.env`, then run from the repository root:

```powershell
node --env-file=.env tests/system/supabase_live_auth_smoke.mjs
```

The runner uses Node's environment-file option (Node 20.6 or later) without
printing the file. It signs in each temporary account, verifies the JWT against
the configured JWKS and issuer/audience/time/role claims, calls the protected
read through the Gateway, tests refresh, and signs out only the test account's
local session. It prints account labels and HTTP outcomes only. This is an
explicit live test; ordinary CI uses its
synthetic issuer and does not need these credentials. Change
`OCC_SUPABASE_TEST_GATEWAY_URL` only when testing a different local Gateway or
TLS edge origin.

To isolate Supabase Auth/JWKS from local application availability, add
`--supabase-only`. This mode does not require or call the Gateway; it still
checks password sign-in, the ES256 signature and issuer/audience/time/`occ_role`
claims, refresh, and local-session sign-out. An optional account label narrows
the run:

```powershell
node --env-file=.env tests/system/supabase_live_auth_smoke.mjs --supabase-only ADMIN
```
