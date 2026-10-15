# Phase 1 live Supabase authentication verification

Verified 15 October 2026 against the development Supabase project and a disposable local Compose stack. The local Gateway used a separate test port. Project URL/JWKS settings and app configuration came from the user's `.env`; the publishable key and temporary test-account credentials were supplied to the runner process. No secret values were printed or written to evidence, and access/refresh tokens remained in process memory.

The project's published JWKS advertised ES256 on P-256. The live smoke runner verified each issued/refreshed token's ES256 signature, issuer, configured audience, subject and expiry before sending it to the public Gateway API.

| Account label | Password sign-in | Verified role | Gateway with initial token | Gateway after refresh | Sign-out |
| --- | --- | --- | ---: | ---: | --- |
| ADMIN | Pass | `ADMIN` | 200 | 200 | Pass |
| CONTROLLER | Pass | `CONTROLLER` | 200 | 200 | Pass |
| DRIVER | Pass | `DRIVER` | 403 | 403 | Pass |
| UNASSIGNED | Failed with HTTP 400 `invalid_credentials` | Not issued | Not tested | Not tested | Not applicable |

A request without a bearer token returned 401. The unassigned test account's supplied mock credentials did not authenticate, so this run does not prove its intended successful sign-in followed by 403. Confirm or reset that account in the development Auth dashboard, leaving it without an OCC role assignment, then rerun the live check. No token, password, subject UUID, refresh token, or response body is retained here.

The live Supabase run called the local Gateway directly over loopback. Nginx TLS, routing, role boundaries, forged credentials and rate limiting are covered separately by [the synthetic Nginx edge smoke evidence](phase1-nginx-edge-smoke-test.md); this live run did not pass a Supabase token through Nginx.

Repeat with the opt-in [`supabase_live_auth_smoke.mjs`](../../../tests/system/supabase_live_auth_smoke.mjs) runner and its blank credential inputs in the root `.env.example`. Ordinary CI remains independent of Supabase and these accounts.

The ADMIN account also completed the existing simulated bunching workflow through Gateway with the test Compose provider overrides; see [functional workflow evidence](phase1-functional-workflow.md). This confirms local auth integration while keeping DataMall and Gemini provider behavior deterministic for that workflow run.

On 15 October 2026, I repeated the live role check after restoring the `.env`-backed Compose application and database. The project URL/JWKS came from `.env`; the publishable key and temporary account fixtures were process-only inputs from the user-provided development setup. No credential or token values were printed or saved, and `.env` was not changed. Missing bearer returned 401. ADMIN and CONTROLLER passed sign-in, ES256/issuer/audience/role validation, initial Gateway read (200), refresh, refreshed Gateway read (200), and sign-out. DRIVER passed the same token checks and received 403 on both reads, then signed out. This time the protected read traversed Gateway to the running Disruption service and its database rather than a stub. The UNASSIGNED account was not included; its last observed password sign-in remains HTTP 400 `invalid_credentials`, so its expected successful sign-in followed by 403 is still open. This was a loopback Gateway check, not an Nginx TLS test; the separate synthetic edge test remains recorded above.
