# Phase 1 Nginx edge verification

Status: local black-box and signed-token run passed on 15 October 2026. This is a localhost test, not a deployed TLS or production-network verification.

The test stack used `compose.yaml` plus `infra/compose.test.yaml` under the isolated Compose project `occ-phase1-auth-ci`, with `.env.example` values and scheduled external-provider work disabled. No application or database host ports were published. A temporary self-signed certificate and private key, ephemeral ES256 signing key, role JWTs and public JWKS lived only in the OS temporary directory. The test JWKS issuer ran on the isolated Compose egress network; the project URL, issuer and JWKS URL agreed. Nginx used the pinned `nginx:1.30.6-alpine` image at digest `sha256:750f6c5456af731b47295d124fbed55664eeb1daab4f6bac0b092b7406235091`, running as UID/GID 101 with a read-only root filesystem, dropped capabilities, and writable tmpfs paths. Only the local test port `127.0.0.1:18443` was published to Nginx's unprivileged container port 8443.

Command:

```text
python tests/system/nginx_edge_smoke.py --base-url https://127.0.0.1:18443 --token-dir <temporary-fixture-directory>
```

All 18 route/security and JWT assertions passed: edge health 204; existing `/demo.html` 200; protected API without a token 401; forged peer header still 401; internal, actuator, OpenAPI, Swagger, auth-test and unknown paths 404; encoded traversal and double-slash internal paths 404; POST to an internal path 404; valid ADMIN and CONTROLLER JWTs reached Ingestion and returned 200; valid DRIVER and UNASSIGNED JWTs returned 403; and a token with a modified ES256 signature returned 401. A 100-request concurrent unauthenticated API burst returned 52 HTTP 401 and 48 HTTP 429 in this run, confirming the configured edge limiter is active. Exact counts may vary with request timing; the test requires both outcomes.

The first production-image startup attempt exposed that the unprivileged Nginx image needs correctly owned writable cache/runtime directories; `infra/compose.production.yaml` now provides those through UID/GID-owned tmpfs mounts. The first host-port attempt exposed that an `internal: true` Docker bridge does not provide the intended public port publishing; the `edge` network is now a regular bridge while the database network remains internal and backend/database ports remain unpublished. The demo-page check also found and fixed an Ingestion security-chain omission: its current public demo page now succeeds through Gateway and Nginx while the demo control API remains protected.

The same script and an ephemeral ES256/JWKS fixture generator are wired into `.github/workflows/ci.yml`; the test creates disposable credentials and cleans them up while preserving named volumes. The CI workflow has been syntax/configuration checked locally; no hosted Actions run has occurred yet. This evidence does not prove the configured Supabase project's JWKS availability, provider-stub egress, graceful shutdown, production certificates, deployment TLS, restore, recovery, capacity, or soak behavior. Those remain separate Phase 1 gates.
