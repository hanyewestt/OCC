-- Apply manually only after exporting/verifying the existing trusted role source and
-- backfilling auth.users.raw_app_meta_data.occ_role from that source. See the rollout guide.
-- This file is NOT an OCC Flyway migration and is never applied by Compose or tests.
BEGIN;

CREATE OR REPLACE FUNCTION public.occ_custom_access_token_hook(event jsonb)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
STABLE
SET search_path = ''
AS $$
DECLARE
    claims jsonb := event -> 'claims';
    canonical_role text;
BEGIN
    -- Auth supplies the trusted user_id. Stale/event-supplied metadata cannot choose the canonical role.
    SELECT upper(trim(coalesce(account.raw_app_meta_data ->> 'occ_role', '')))
      INTO canonical_role
      FROM auth.users AS account
     WHERE account.id = (event ->> 'user_id')::uuid;
    canonical_role := coalesce(canonical_role, '');
    IF canonical_role NOT IN ('ADMIN', 'CONTROLLER', 'CAPTAIN', 'UNASSIGNED') THEN
        canonical_role := 'UNASSIGNED';
    END IF;
    -- Retain every provider claim; replace only the OCC role derived from server-managed metadata.
    claims := jsonb_set(claims, '{occ_role}', to_jsonb(canonical_role), true);
    RETURN jsonb_set(event, '{claims}', claims, true);
END;
$$;

GRANT USAGE ON SCHEMA public TO supabase_auth_admin;
GRANT USAGE ON SCHEMA auth TO supabase_auth_admin;
GRANT SELECT (id, raw_app_meta_data) ON auth.users TO supabase_auth_admin;
REVOKE ALL ON FUNCTION public.occ_custom_access_token_hook(jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.occ_custom_access_token_hook(jsonb) TO supabase_auth_admin;

COMMIT;
