# Development Supabase setup for integration testing

Prepared 15 October 2026. Set up a real **development** Auth project now; the temporary login harness and backend integration belong to phase 1. Product login pages, authoritative vehicle/captain identity, captain binding, approval and instructions stay in phase 2. Offline CI still uses synthetic JWT/JWKS fixtures.

This checklist and SQL are prepared artifacts. No Supabase project has been created or modified, and the SQL has not been executed against Supabase. The dashboard setup is performed by the project owner; application integration is the next code task.

## 1. Create a dedicated development project

In the [Supabase Dashboard](https://supabase.com/dashboard), create a project named `occ-dev` in your organization and select an appropriate nearby region (Singapore if available). Store its generated database password privately; OCC does not need that password for JWT validation.

Use this project for Auth and its small role-assignment table only. Do not move OCC's ingestion/disruption/planning schemas into it. Wait for project provisioning to finish.

From the project's Connect dialog or Settings -> API Keys, collect:

- Project URL, e.g. `https://PROJECT_REF.supabase.co`.
- **Publishable** API key, beginning `sb_publishable_`.
- Project reference and selected region.

The browser uses the publishable key. Spring resource servers verify user tokens with public signing keys; no Supabase secret/server key is needed for this integration. Do not share database passwords, signing secrets, server keys, account passwords or access/refresh tokens in chat. [Official API-key guidance](https://supabase.com/docs/guides/getting-started/api-keys)

## 2. Configure development sign-in and URLs

In Authentication settings/providers, enable email/password authentication. Keep anonymous sign-in disabled. For this controlled test project, disable public registration and create the test users administratively. Leave production email/registration policy decisions for the later product login work.

Use this proposed local-only harness origin:

```text
Site URL:             http://localhost
Allowed redirect URL: http://localhost/auth-test/callback
Temporary page:       http://localhost/auth-test/
```

These URLs are the planned Nginx development entry point, not a page already running. If host port 80 is unavailable, choose the actual local Nginx port and use that same origin/port consistently in all three values and the application configuration. Do not use a backend service's direct port as the browser's end-to-end gateway test origin.

Direct email/password sign-in returns a session without an OAuth callback. The redirect entry prepares confirmation/recovery or future redirect flows; it does not require a full product UI now. Use exact callbacks, not a broad production wildcard. Later add the actual deployed Site URL/callbacks. [Password authentication](https://supabase.com/docs/guides/auth/passwords), [redirect URL configuration](https://supabase.com/docs/guides/auth/redirect-urls)

## 3. Verify asymmetric JWT signing

Open Authentication -> Signing Keys (the dashboard may label this JWT Signing Keys). Confirm the **current/in-use** signing key is asymmetric; prefer ES256 for this setup. Record the actual algorithm. A standby key being visible in JWKS does not mean new tokens use it.

For a fresh project, retain a suitable asymmetric key already in use. If the project uses a legacy/shared-secret signing key, follow Supabase's documented migration/rotation workflow; do not revoke a key blindly. The backend algorithm allowlist must match the current signing algorithm. [Signing-key setup and rotation](https://supabase.com/docs/guides/auth/signing-keys)

The standard endpoints are:

```text
Issuer: https://PROJECT_REF.supabase.co/auth/v1
JWKS:   https://PROJECT_REF.supabase.co/auth/v1/.well-known/jwks.json
```

Open the JWKS URL in a browser: expect a JSON `keys` array with asymmetric public keys. It contains no private signing key. The expected audience for ordinary Supabase Auth user tokens is `authenticated`; verify that against a newly issued token during integration. Do not change Supabase's built-in `role` claim to ADMIN/CONTROLLER/DRIVER.

## 4. Install the trusted OCC role claim

In the **development project's SQL Editor**, run the adjacent [supabase-development-role-setup.sql](supabase-development-role-setup.sql) once. Review the script before running it; it creates only:

- `public.occ_user_role`, containing one trusted OCC role per Auth user.
- A row-security policy allowing Auth's hook role to read those assignments.
- `public.occ_custom_access_token_hook(jsonb)`, adding the separate `occ_role` claim.

Browser `anon`/`authenticated` roles cannot read/write assignments or execute the hook. Users cannot promote themselves through profile metadata. There is no default OCC role for an unassigned user. This table is identity-provider authorization metadata; it is not the deferred captain-binding feature or an operational business schema.

In Authentication -> Hooks, enable **Custom Access Token Hook**, choose the Postgres function `public.occ_custom_access_token_hook`, and save. Select the token hook, not a signup hook. Auth runs it when tokens are issued/refreshed; failure here can prevent sign-in, so the live login test is part of setup verification. [Official custom-claim/RBAC pattern](https://supabase.com/docs/guides/api/custom-claims-and-role-based-access-control-rbac), [token-hook contract](https://supabase.com/docs/guides/auth/auth-hooks/custom-access-token-hook)

## 5. Create four controlled test users and assign roles

In Authentication -> Users, use the administrative Add/Create User flow to create password users with email addresses you control. Use unique passwords stored privately. Confirm these test users through the administrative flow so a login test does not depend on email delivery. Do not disable production confirmation globally just to test these accounts. [User management](https://supabase.com/docs/guides/auth/managing-user-data)

| Test account | Assignment | Expected OCC access |
| --- | --- | --- |
| Administrator | ADMIN | Defined admin operations and operational reads |
| Controller | CONTROLLER | Operational reads and agreed demo controls |
| Driver | DRIVER | Denied phase-1 controller/admin operations; no instruction feature yet |
| Unassigned user | No row | Auth sign-in succeeds; OCC protected operations deny |

Copy the first three user UUIDs from the dashboard. Run this separate assignment query in SQL Editor after replacing placeholders with those UUIDs (not email addresses or passwords):

```sql
INSERT INTO public.occ_user_role (user_id, role)
VALUES
    ('ADMIN_USER_UUID'::uuid, 'ADMIN'),
    ('CONTROLLER_USER_UUID'::uuid, 'CONTROLLER'),
    ('DRIVER_USER_UUID'::uuid, 'DRIVER')
ON CONFLICT (user_id) DO UPDATE SET role = EXCLUDED.role;

SELECT user_id, role FROM public.occ_user_role ORDER BY role;
```

Leave the fourth account unassigned. Existing JWTs do not instantly change when this table changes: sign in again or refresh the session to obtain a new claim. Immediate account/role revocation is a separate application session-policy decision, not a property of stateless JWT verification.

### Supplied development accounts for agent verification

The project owner explicitly approved recording these temporary mock credentials for development testing. Use them only against `https://lldbezcwwotvtytnddgf.supabase.co`. Account creation, confirmation and role assignments are owner-reported; live sign-in and claim outcomes still require verification.

| Account | Email | Temporary password | Expected `occ_role` |
| --- | --- | --- | --- |
| Admin | admin@occ.com | abcd1234 | ADMIN |
| Controller | controller1@occ.com | abcd1234 | CONTROLLER |
| Driver | driver1@occ.com | abcd1234 | DRIVER |
| Unassigned | testuser@email.com | abcd1234 | Absent |

Development project settings: publishable key `sb_publishable_5MVyFbf0il3Go2tTrTKMGg_1Dl46EAf`, issuer `https://lldbezcwwotvtytnddgf.supabase.co/auth/v1`, JWKS `https://lldbezcwwotvtytnddgf.supabase.co/auth/v1/.well-known/jwks.json`, algorithm `ES256`, Site URL `http://localhost`, callback `http://localhost/auth-test/callback`, hook `public.occ_custom_access_token_hook`, claim `occ_role`. The JWKS endpoint was fetched successfully and advertised an EC P-256 / ES256 public key; that alone does not verify issued tokens or hook execution.

For the opt-in live check, sign in with each account using the publishable key and password authentication. Verify signatures and issuer/audience/subject, then assert the expected claim above. Call the same protected operational read through Nginx/gateway: ADMIN and CONTROLLER should succeed; DRIVER and UNASSIGNED should receive 403. Verify a missing bearer token receives 401, refresh preserves the expected claim, and sign-out clears the harness session. Keep tokens in memory and record only account labels, assertions and HTTP outcomes. Configure the test runner through documented environment variables; do not hard-code these values into application code or production defaults. Ordinary CI remains deterministic and offline.

## 6. Send back the public setup details

Provide the following handoff in chat or a non-secret setup note:

```text
Project URL: https://PROJECT_REF.supabase.co
Publishable key: sb_publishable_...
Current signing algorithm: ES256 (or the actual algorithm)
Site URL: http://localhost (or the actual selected origin)
Callback URL: http://localhost/auth-test/callback
Role hook: public.occ_custom_access_token_hook — enabled
Role claim: occ_role
Test users: ADMIN, CONTROLLER, DRIVER, UNASSIGNED created
Assignments: first three role rows inserted; fourth unassigned
```

User UUIDs are optional for troubleshooting. Keep passwords locally; you can enter them into the temporary page yourself. Do not send account tokens or server keys. The project URL/publishable key are enough to configure the browser integration, and the issuer/JWKS/algorithm/claims are enough for Spring verification.

## 7. Application work to implement next

The implementing agent will:

1. Bind canonical issuer/JWKS/audience/algorithm settings through typed validated `@ConfigurationProperties`, documenting every supported environment variable in `.env.example`. Add public Supabase URL/key and local harness URL through allowlisted runtime config; these keys are proposed until the bindings exist.
2. Implement gateway/backend resource-server validation and trusted `occ_role` mapping. Keep existing internal service identities distinct. Fail startup for incomplete required production identity config.
3. Serve a development-only `/auth-test/` page through local Nginx. Supabase SDK client: `persistSession: false`, `autoRefreshToken: true`, `detectSessionInUrl: false` for direct password login. Store the session only in memory; never display/log tokens/passwords. A future redirect callback needs its own correctly implemented session handling.
4. Provide email/password sign-in, sign-out, current role display and a button invoking a verified existing read-only API through Nginx -> gateway -> service. The request uses the user's `access_token` as bearer token, not the publishable key. No service/secret key or token-minting private key reaches the browser.
5. Check fresh live tokens have correct `iss`, `aud`, `sub`, algorithm and `occ_role` without sharing token contents. Verify ADMIN/CONTROLLER allow cases, DRIVER/unassigned deny cases, missing/expired/invalid tokens, refresh and role-change behavior. Do not claim logout invalidates every issued JWT immediately; the harness stops using it, while server acceptance follows token expiry/revocation policy.
6. Keep deterministic synthetic JWT/JWKS tests in ordinary CI. Real Supabase integration is a separate opt-in development check using the owner-approved temporary accounts above; ordinary PRs never depend on test account passwords or the external service.
7. Exclude the temporary page from production builds/routes. Replace it with phase-2 product login pages; preserve verified backend authentication behavior.

Only public project settings are needed for initial integration. The agent may use the supplied temporary development accounts for live sign-in verification once the harness/test runner exists, without requesting credentials again. If sign-in reports a hook error, check function EXECUTE, table SELECT and its RLS policy for `supabase_auth_admin`; do not make the role table publicly readable to fix it.
