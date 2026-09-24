-- Nullable for poll runs written by the previous release. New pollers always set a lease.
ALTER TABLE ingestion.poll_run ADD COLUMN lease_expires_at TIMESTAMPTZ;
CREATE INDEX ix_poll_run_running_scope ON ingestion.poll_run (source, scope_key) WHERE outcome = 'RUNNING';

-- Audit has independent retention. Deliberately no FKs to 24-hour telemetry tables.
CREATE TABLE ingestion.telemetry_audit (
    id UUID NOT NULL,
    poll_run_id UUID NOT NULL,
    actor VARCHAR(80) NOT NULL,
    action VARCHAR(40) NOT NULL,
    source VARCHAR(40) NOT NULL,
    scope_key VARCHAR(120) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    classification VARCHAR(30) NOT NULL,
    raw_observation_id UUID, -- Absent at start and when no usable response was received.
    payload_checksum CHAR(64), -- Absent when the response could not be fully received.
    http_status INTEGER, -- Absent for timeouts, transport failures and expired leases.
    records_received INTEGER NOT NULL,
    reason VARCHAR(80), -- Absent when there is no failure.
    CONSTRAINT pk_telemetry_audit PRIMARY KEY (id),
    CONSTRAINT uq_telemetry_audit_poll_action UNIQUE (poll_run_id, action),
    CONSTRAINT ck_telemetry_audit_action CHECK (action IN ('POLL_STARTED', 'POLL_COMPLETED')),
    CONSTRAINT ck_telemetry_audit_outcome CHECK (
        (action = 'POLL_STARTED' AND outcome = 'RUNNING') OR
        (action = 'POLL_COMPLETED' AND outcome IN ('SUCCESS', 'FAILED'))),
    CONSTRAINT ck_telemetry_audit_classification CHECK (classification = 'OBSERVATION_ONLY'),
    CONSTRAINT ck_telemetry_audit_records CHECK (records_received >= 0)
);
CREATE INDEX ix_telemetry_audit_scope_time ON ingestion.telemetry_audit (source, scope_key, occurred_at);
