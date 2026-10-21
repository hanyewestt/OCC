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
the named Disruption, Planning, and Ingestion namespaces for existing API consumers. Nginx serves the React build; Gateway has no HTML
routes or static resources.

The local Compose override publishes application ports on loopback for testing.
The production Compose profile publishes only Nginx; Nginx and Gateway share the
edge network, Gateway and backends share the app network, and database access
uses a private network. Use the explicit production files in
[`docs/DEVELOPMENT.md`](../../docs/DEVELOPMENT.md) so local ports are
not merged into deployment.

`GET /api/v1/auth/session` accepts authenticated tokens and returns the verified
subject and normalized OCC role. `GET /api/v1/auth/access/controller` requires
CONTROLLER; `/api/v1/auth/access/captain` requires DRIVER. ADMIN has no page
override. Unsupported page names return 404; role denial returns 403; invalid
or missing tokens return 401. Gateway owns these endpoints; its Ingestion
catch-all excludes the auth namespace. Other operational APIs remain limited
to ADMIN/CONTROLLER. Health and internal-route rules are unchanged.

Nginx serves the React build. `GET /frontend-config` exposes only the public
Supabase project URL and publishable key, bound through `FrontendProperties`.
The SDK persists browser sessions and refreshes tokens; Gateway verifies them
before the UI renders role content. Sign-out uses local scope and propagates
across tabs. See [authentication](../../docs/AUTHENTICATION.md) and
[development guide](../../docs/DEVELOPMENT.md). Temporary demo/auth pages are retired.

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

To regenerate the Gateway OpenAPI snapshot from the running test application:

```powershell
.\mvnw.cmd -pl services/gateway -am test '-Docc.exportOpenapi=true'
```

This export uses synthetic test configuration and the real controller mappings.
