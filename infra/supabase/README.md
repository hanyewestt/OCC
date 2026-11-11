# Identity directory rollout

The Ingestion identity adapter is disabled by default. Existing deployments need
no private key or secret mount. The browser continues using only the existing
`SUPABASE_PROJECT_URL` and public `SUPABASE_SB_PUBLISHABLE` configuration.

## Before enabling the adapter

1. An authorized project operator must inspect and export the currently deployed
   access-token hook and its trusted role source outside this repository. Confirm
   how each existing account obtains its role. Keep the export in the deployment's
   protected administrative backup store; it can contain personal data.
2. Review an explicit UUID-to-role mapping against that trusted source. Backfill
   the existing accounts' server-managed `app_metadata.occ_role` with the verified
   `ADMIN`, `CONTROLLER`, `CAPTAIN` or `UNASSIGNED` value, preserving all unrelated
   metadata. Do not infer roles from email addresses, `user_metadata`, browser
   claims, or local directory rows. Resolve unknown accounts explicitly as
   `UNASSIGNED`. Do not run an indiscriminate backfill from guessed values.
3. Review and apply [the versioned hook SQL](V001__occ_access_token_hook.sql)
   manually in the intended Supabase project. The hook retains provider claims
   and reads canonical `auth.users.raw_app_meta_data.occ_role` by Auth's trusted
   `event.user_id` before projecting it into top-level `occ_role`. Stale or spoofed
   event metadata and user-editable metadata cannot choose that role.
   It uses invoker permissions, an empty search path, and grants execution to
   `supabase_auth_admin`, revoking `PUBLIC`, `anon` and `authenticated` execution.
   The hook needs only SELECT on `auth.users.id` and `raw_app_meta_data`; its SQL
   grants those columns explicitly and never reads user-editable metadata.
   Select `public.occ_custom_access_token_hook` in Auth's Custom Access Token hook
   settings only after reviewing the existing hook's other required behavior.
   Merge any required existing behavior before switching; this file cannot infer it.
4. Disable public self registration in the project's Auth settings. Keep ADMIN
   creation out of band with authorized project operators. The application
   provisioning capability only permits CONTROLLER and CAPTAIN accounts.
5. Using dedicated test accounts, verify newly issued/refreshed JWTs preserve
   required provider claims and emit the expected top-level role for every role,
   including UNASSIGNED. Verify a user-editable metadata change cannot elevate
   the role. Existing access tokens keep their old claims until refreshed or
   expired; plan that transition deliberately.
6. Create a modern `sb_secret_...` project key using the provider's administrative
   console. For environment configuration, put it in `OCC_IDENTITY_SECRET_KEY`
   in the ignored root `.env`. Compose passes it only to Ingestion. This key is
   visible to administrators through container environment inspection. A mounted
   secret remains supported: leave the environment key empty and store the key
   in a backend-only file. Keep keys out of image layers, frontend configuration,
   logs and the repository.
7. For the environment option, set `OCC_IDENTITY_ENABLED=true` after the hook
   checks and use the normal app launcher. No identity overlay is needed.
   For the mounted-file option, set `OCC_IDENTITY_SECRET_SOURCE_FILE` to the host-side secret file,
   `OCC_IDENTITY_SECRET_KEY_FILE=/run/secrets/occ_supabase_identity_key`, and
   `OCC_IDENTITY_ENABLED=true`. `SUPABASE_PROJECT_URL` keeps its existing name
   and project. For Compose, include `infra/compose.identity.yaml` after the
   regular local or production Compose files. This overlay mounts the Docker
   secret into Ingestion only; the default stack does not require a secret file.

The configuration validates the origin, configured environment key or readable
mounted path, and positive timing
settings at startup when enabled. A nonblank environment key takes precedence over the file. With no environment
key, the adapter reads the file at request time so
mounted-key rotation does not require copying a credential into application
configuration. It accepts modern secret keys in the `apikey` header only and
does not send a bearer header. Timeout defaults to 5 seconds and full scans to
5 minutes. Bounded operational overrides are `OCC_IDENTITY_TIMEOUT` and
`OCC_IDENTITY_DIRECTORY_INTERVAL`; assignment recovery uses
`OCC_PLANNING_ASSIGNMENT_RECOVERY_INTERVAL` and
`OCC_PLANNING_ASSIGNMENT_RECOVERY_BATCH_SIZE`. Their safe defaults are listed in
`.env.example` and passed only to their owning services. Internal response limits
remain in code.

## Directory behavior and verification

The startup scan runs after readiness, and failed scheduled scans preserve the
previous complete projection. Scans fetch every provider page before starting
the publication transaction. A checkpoint row lock rejects older overlapping
scans. Internal row observation time is separate from display publication time,
so later overlapping scans can refresh previous bulk rows while preserving newer
individual confirmations. Full successful scans mark missing accounts unavailable. Banned/deleted
accounts are unavailable. Rows are keyed by provider UUID; an unavailable
historical account does not prevent a new provider UUID reusing its email.

The directory is a read projection and grants no permissions. Authorization uses
the verified token, and future assignment mutations must confirm an available
CAPTAIN directly with the provider; an unavailable provider fails closed.

- ADMIN: `GET /api/v1/admin/users`, `GET /api/v1/admin/users/{UUID}` and
  `POST /api/v1/admin/users/synchronizations`.
- ADMIN or CONTROLLER: `GET /api/v1/captains`, with only ID, display name,
  email and current Ingestion demo assignment. Default browse includes every
  projected CAPTAIN, including unavailable accounts; selectors request
  `available=true`. An explicit `available=false` filters unavailable captains.
- Lists accept zero-based `page`, `size` (20 default, 100 maximum), and literal
  case-insensitive name/email `search`; the admin list also accepts `role`.
  The captain list also accepts the optional `available` filter.
  Both return `items`, `page`, `size`, `totalItems`, `totalPages` and
  `directorySynchronizedAt`, which is null before the first complete scan.
- Current assignments include only RUNNING or REPLAY_COMPLETE runs.

Before promotion, verify these routes through the deployment edge with synthetic
or dedicated test accounts. Trigger a synchronization, inspect the safe checkpoint,
then simulate a provider failure and confirm the prior rows/checkpoint remain.
Automated adapter tests use synthetic HTTP responses and PostgreSQL tests use
disposable databases. They do not prove a particular live Supabase gateway,
role backfill or deployed hook; those checks remain with the project operator.
No script in this change executes against a live project.

The proposed hook itself is tested locally against PostgreSQL 18 with synthetic
`auth.users`, `supabase_auth_admin`, `anon` and `authenticated` fixtures. The test
applies the versioned SQL twice and checks stored canonical roles, spoofed event
metadata, retained claims, unknown roles and execution/column privileges:

```powershell
.\mvnw.cmd -pl services/ingestion test-compile failsafe:integration-test failsafe:verify '-Dit.test=SupabaseAccessTokenHookIT'
```

Disable `OCC_IDENTITY_ENABLED` to stop provider calls while retaining the local
projection for reads. Revoke/rotate the mounted key separately if required.
Restore the protected prior hook export only with the project operator's review;
disabling the adapter does not roll back provider metadata or issued tokens.

## Provider references

- [Supabase API keys](https://supabase.com/docs/guides/getting-started/api-keys)
- [Custom access token hooks](https://supabase.com/docs/guides/auth/auth-hooks/custom-access-token-hook)
- [Auth hook permissions](https://supabase.com/docs/guides/auth/auth-hooks)
- [Admin list users](https://supabase.com/docs/reference/javascript/auth-admin-listusers)
- [Admin create user](https://supabase.com/docs/reference/javascript/auth-admin-createuser)
- [General Auth configuration](https://supabase.com/docs/guides/auth/general-configuration)
