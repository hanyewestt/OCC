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
page can continue calling `/api/v1/...` paths. The static `/demo.html` page is
served through Gateway without making its API calls public.

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

The local password-grant test page is served at
`http://localhost:6770/auth-test/index.html` when
`OCC_GATEWAY_AUTH_TEST_ENABLED=true`. It is disabled by default in the application
configuration and should stay disabled in deployed environments. The page keeps
the access token in memory and sends protected API requests through this Gateway.

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
