-- Run once in the SQL Editor of the NEW OCC DEVELOPMENT Supabase project.
-- This is Supabase Auth metadata, not a migration for the OCC operational DB.
-- No passwords, API keys, signing keys or real user IDs belong in this file.
-- A repeat run deliberately fails on existing objects instead of replacing them.
-- Based on https://supabase.com/docs/guides/api/custom-claims-and-role-based-access-control-rbac

BEGIN;

CREATE TABLE public.occ_user_role (
    user_id UUID NOT NULL,
    role TEXT NOT NULL,
    CONSTRAINT pk_occ_user_role PRIMARY KEY (user_id),
    CONSTRAINT fk_occ_user_role_user FOREIGN KEY (user_id)
        REFERENCES auth.users (id) ON DELETE CASCADE,
    CONSTRAINT ck_occ_user_role_role CHECK (role IN ('ADMIN', 'CONTROLLER', 'DRIVER'))
);

ALTER TABLE public.occ_user_role ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.occ_user_role FROM PUBLIC, anon, authenticated;
GRANT USAGE ON SCHEMA public TO supabase_auth_admin;
GRANT SELECT ON TABLE public.occ_user_role TO supabase_auth_admin;

CREATE POLICY occ_auth_admin_reads_user_role
    ON public.occ_user_role
    FOR SELECT TO supabase_auth_admin
    USING (true);

CREATE FUNCTION public.occ_custom_access_token_hook(event JSONB)
RETURNS JSONB
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $$
DECLARE
    assigned_role TEXT;
    token_claims JSONB;
BEGIN
    SELECT assignment.role INTO assigned_role
    FROM public.occ_user_role AS assignment
    WHERE assignment.user_id = (event ->> 'user_id')::UUID;

    -- Preserve every original required claim, including Supabase's DB role.
    -- Only a trusted assignment can supply OCC authority.
    token_claims := (event -> 'claims') - 'occ_role';
    IF assigned_role IS NOT NULL THEN
        token_claims := jsonb_set(token_claims, '{occ_role}', to_jsonb(assigned_role));
    END IF;

    -- Unassigned users receive no OCC role; backend authorization must deny them.
    RETURN jsonb_set(event, '{claims}', token_claims);
END;
$$;

REVOKE EXECUTE ON FUNCTION public.occ_custom_access_token_hook(JSONB)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.occ_custom_access_token_hook(JSONB)
    TO supabase_auth_admin;

COMMIT;
