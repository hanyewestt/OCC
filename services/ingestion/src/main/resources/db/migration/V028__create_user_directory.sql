-- Provider-owned identities are projected locally for reads, never used to grant permissions.
CREATE TABLE ingestion.occ_user (
    id UUID CONSTRAINT pk_occ_user PRIMARY KEY,
    display_name TEXT NOT NULL,
    email TEXT NOT NULL,
    role VARCHAR(20) NOT NULL,
    available BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_sign_in_at TIMESTAMPTZ,
    synchronized_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_occ_user_role CHECK (role IN ('ADMIN', 'CONTROLLER', 'CAPTAIN', 'UNASSIGNED'))
);
COMMENT ON COLUMN ingestion.occ_user.last_sign_in_at IS 'Nullable: account has never signed in.';
COMMENT ON COLUMN ingestion.occ_user.email IS 'Empty for provider accounts without an email address; never used as identity or a role source.';
COMMENT ON COLUMN ingestion.occ_user.observed_at IS 'Logical observation time: full-scan start or direct provider confirmation. Distinct from publication time so overlapping scans refresh correctly.';
-- Email is deliberately not unique: an unavailable historical UUID may share a reused email with a new UUID.
CREATE INDEX ix_occ_user_role_available ON ingestion.occ_user (role, available);

CREATE TABLE ingestion.directory_synchronization (
    id SMALLINT CONSTRAINT pk_directory_synchronization PRIMARY KEY,
    scan_started_at TIMESTAMPTZ,
    synchronized_at TIMESTAMPTZ,
    CONSTRAINT ck_directory_synchronization_singleton CHECK (id = 1),
    CONSTRAINT ck_directory_synchronization_complete CHECK ((scan_started_at IS NULL) = (synchronized_at IS NULL))
);
COMMENT ON COLUMN ingestion.directory_synchronization.scan_started_at IS 'Nullable until a complete scan succeeds; prevents older overlapping scans replacing newer data.';
COMMENT ON COLUMN ingestion.directory_synchronization.synchronized_at IS 'Nullable until a complete scan succeeds; last successful full directory publication.';
INSERT INTO ingestion.directory_synchronization (id) VALUES (1);
