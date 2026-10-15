# Phase 1 authentication smoke test

Run date: 2026-10-15

The local Compose stack was configured against the supplied Supabase project and its public JWKS endpoint. Authentication was exercised through the local Gateway using the temporary test accounts. No credential, bearer token, or user identifier is recorded here.

| Case | Result | What it verifies |
| --- | --- | --- |
| ADMIN token to protected Gateway route | `200` | Supabase signature validation and ADMIN authorization |
| CONTROLLER token to protected Gateway route | `200` | Supabase signature validation and CONTROLLER authorization |
| DRIVER token to controller-only route | `403` | Valid identity with insufficient role is rejected |
| No token to protected route | `401` | Missing authentication is rejected |
| Valid JWT without an OCC role | `403` in `GatewayAuthorizationTest` | Role hook output that lacks `occ_role` cannot access phase-one APIs |

The supplied UNASSIGNED account could not be authenticated during the live smoke test; the sanitized Supabase error code was `invalid_credentials` with HTTP `400`. That live account case therefore does not establish a post-login authorization result. The no-role Gateway behavior is verified deterministically by the test listed above.

The latest four-role results and `.env` usage are recorded in [phase1-supabase-live-auth.md](phase1-supabase-live-auth.md); this file retains the original local auth-smoke details.

All four Compose service readiness endpoints returned `200`. The local-only authentication test page returned `200` at `/auth-test/index.html`. Runtime OpenAPI snapshots were exported for Ingestion, Disruption, and Planning after an authenticated Gateway check of each service's API docs.

The smoke test used temporary local configuration. It did not create or modify Supabase users or role assignments.
