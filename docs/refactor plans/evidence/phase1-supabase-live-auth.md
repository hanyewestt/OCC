# Phase 1 live Supabase authentication verification

Latest result, 16 October 2026: all four intended role cases now have passing
live evidence. ADMIN/CONTROLLER returned 200; DRIVER and the corrected
UNASSIGNED account returned 403. The earlier unsuccessful unassigned sign-ins
below used the previously supplied account and remain historical results.

After the extended local fault/load tests restored the healthy real `.env`
stack, the owner supplied the corrected UNASSIGNED account. The existing smoke
runner tested that account against the actual Supabase project and local
Gateway at `http://localhost:6770`. Password sign-in succeeded; ES256/JWKS
signature, issuer, audience, subject, expiry and absent OCC-role checks passed.
Gateway returned 403 for the initial token and again after refresh. Sign-out
succeeded, and missing bearer returned 401. Project/JWKS settings loaded from
`.env`; the corrected mock credentials and publishable-key fallback were
process-only inputs. No token or credentials were saved and `.env` was not
modified. This closes the formerly open UNASSIGNED live-auth case; the check
used loopback Gateway rather than Nginx TLS.

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

## Repeat against the real Supabase project

On 16 October 2026, I reran the live smoke against the configured Supabase project and the healthy local Gateway at `http://localhost:6770`. Node loaded the project URL and JWKS URL from the root `.env`; the publishable key and temporary account credentials were supplied only to the test process. The missing-bearer check returned 401. ADMIN and CONTROLLER passed password sign-in, ES256 token validation, initial and refreshed Gateway reads (200), and sign-out. DRIVER passed sign-in and token validation, received 403 on both initial and refreshed Gateway reads, and signed out. The UNASSIGNED sign-in returned HTTP 400 before a session was issued; its role claim and expected Gateway 403 remain unverified. The runner suppressed response details and retained no credentials or tokens. The root `.env` was not changed.

### 16 October direct Supabase retest

I also tested Supabase directly, using the project URL and JWKS URL from the
root `.env` and the publishable key and temporary account credentials supplied
only in the process. ADMIN, CONTROLLER, and DRIVER each passed password
sign-in, live ES256/JWKS signature and issuer/audience/expiry/role validation,
refresh-token exchange with validation of the new access token, and sign-out.
UNASSIGNED returned HTTP 400 `invalid_credentials` before issuing a session;
its role claim and expected Gateway 403 remain unverified. This direct retest
did not call Gateway; the previous loopback Gateway results above remain the
Gateway integration evidence. No tokens or credentials were retained and the
root `.env` was not changed.

### 16 October repeat using the checked-in direct mode

The saved `supabase_live_auth_smoke.mjs --supabase-only` mode repeated the live
Auth checks without depending on the local Gateway. A direct JWKS request
returned HTTP 200 and advertised one EC P-256 key using ES256. Node loaded the
project URL and JWKS URL from the root `.env`; the publishable key and temporary
account inputs were supplied only to this process because those optional test
values were absent from `.env`. ADMIN, CONTROLLER, and DRIVER passed sign-in,
ES256 signature and issuer/audience/time/role validation, refresh, and
local-session sign-out. UNASSIGNED returned HTTP 400 before a session was
issued, leaving its claim and expected 403 unverified. The check did not call
Gateway and saved no tokens, credentials, or user identifiers; `.env` was
unchanged.

### 16 October real Supabase and Gateway retry

I reran the checked-in live-auth smoke against the real Supabase project and
the healthy local Gateway at `http://localhost:6770`. The project URL and JWKS
URL loaded from the root `.env`; the publishable key and temporary test-account
inputs were provided only to this test process. Missing bearer returned 401.
ADMIN and CONTROLLER passed sign-in, ES256/JWKS and claim validation, initial
and refreshed Gateway reads (200), and sign-out. DRIVER passed token validation,
received 403 on both reads, and signed out. UNASSIGNED again failed password
sign-in with HTTP 400 before receiving a token, so its role claim and expected
Gateway 403 remain unverified. The runner withheld response details; no
credentials or tokens were written, and `.env` was not changed. The command
exited non-zero solely because the UNASSIGNED sign-in could not be completed.

### 16 October rebuilt application verification

After removing unused containers and rebuilding all four app images, the saved
runner repeated ADMIN, CONTROLLER and DRIVER against the real Supabase project
and rebuilt Gateway. Missing bearer returned 401. ADMIN and CONTROLLER passed
ES256/JWKS/claim validation, initial and refreshed protected reads (200), and
sign-out; DRIVER passed the same token/session checks with 403 on both reads.
Runtime OpenAPI export using a separate real ADMIN session matched all three
committed backend contracts. The live traffic runner also used a real ADMIN
session to read a real LTA incident and its Planning outcome before and after
Planning/Disruption restart, then signed out.

Project/JWKS URLs loaded from `.env`; the publishable key and temporary logins
were supplied only to these processes, and `.env` remained unchanged. No tokens
were saved. UNASSIGNED was not retried in this rebuild check; its last observed
sign-in remains HTTP 400, so the no-role live 403 is still open.
