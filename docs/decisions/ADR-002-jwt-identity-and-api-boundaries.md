# ADR-002: JWT identity and API boundaries

- Status: Accepted
- Date: 2026-10-15
- Decision owner: User

## Context

Phase 1 protects the existing read and control APIs while the product login pages remain deferred to phase 2. The development Supabase project is configured for early integration. CI must remain deterministic and offline, so it also needs a synthetic asymmetric issuer and JWKS fixture. The temporary accounts are test identities, not production users.

## Decisions

1. Supabase issues browser bearer tokens. Validate signature, issuer, the `authenticated` audience, expiry/not-before with bounded clock skew, subject, and the custom `occ_role` claim. The configured project's active signing algorithm is ES256 (P-256); the resource server obtains public signing keys from the configured JWKS URL and never receives a signing secret.
2. The trusted custom access-token hook is `public.occ_custom_access_token_hook`; it sources role assignments from the approved role table and writes `occ_role`. Never authorize from user-editable `user_metadata`. Missing, malformed, or unknown roles deny protected operations.
3. `ADMIN` and `CONTROLLER` can use the currently authorized operator APIs. `DRIVER` has no phase-1 operational API permission. No actor-to-captain mapping or assignment-based access is added in this phase.
4. Gateway and every backend public chain validate user JWTs. Internal service calls use a distinct per-peer capability token on a private Compose network. User JWTs do not authorize `/internal/**`; public edge requests cannot choose or spoof actor/service identity headers.
5. CI uses real asymmetric cryptographic verification against synthetic JWKS, including invalid signature/issuer/audience/algorithm/expiry, key rotation and role allow/deny cases. Live Supabase sign-in, hook and refresh checks are opt-in integration evidence and use the documented development accounts only. CI never depends on Supabase availability or these account passwords.
6. Development settings use the already documented `SUPABASE_PROJECT_URL`, `SUPABASE_SB_PUBLISHABLE` and `SUPABASE_JWKS_URL` inputs. The publishable key is browser-visible and is not a bearer token. The Auth connection string is not needed. Keep `.env` ignored and do not print keys, passwords, JWTs or expanded Compose environments.
7. A minimal `/auth-test/` page may run only in the local/test override, served by the same Nginx -> Gateway path as protected API requests. Store sessions in memory, avoid logging credentials or tokens, and exclude the harness from production images/routes. Phase 2 replaces it with product login pages and configured deployed callbacks.

## Consequences

- Supabase role provisioning and the temporary harness are phase-1 setup and integration work; a public deployment remains pending until the real identity provider and application roles are verified.
- Database schema roles are separate from OCC application roles and from Supabase's database JWT role.
- A local fixture pass and a live Supabase pass are reported separately. Neither a synthetic issuer nor a test account proves production identity readiness.
- Approval, rejection, captain identity binding, assigned instructions, and controller/captain product pages remain phase-2 scope.

## Verification

Use signed fixture JWTs through the actual gateway and backend filters for offline tests. For the opt-in live check, sign in with each documented test account, assert only role labels and HTTP outcomes, refresh once, and sign out. Never save or report a token, password, publishable key, or signing material.
