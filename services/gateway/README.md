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
for a host-native run. The local Compose file publishes all four application
ports for development and integration checks; a deployed edge exposes the
Gateway and keeps backend services on private network ports.

The first API authorization rule allows `ADMIN` and `CONTROLLER` roles. `DRIVER`
and `UNASSIGNED` receive `403`; missing or invalid tokens receive `401`. Health
probes remain available at `/actuator/health`. Peer `/internal/v1/**` calls are
not routed through the public Gateway.

The local password-grant test page is served at
`http://localhost:6770/auth-test/index.html` when
`OCC_GATEWAY_AUTH_TEST_ENABLED=true`. It is disabled by default in the application
configuration and should stay disabled in deployed environments. The page keeps
the access token in memory and sends protected API requests through this Gateway.
