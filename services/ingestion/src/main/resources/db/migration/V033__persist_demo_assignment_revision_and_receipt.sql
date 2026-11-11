ALTER TABLE ingestion.demo_vehicle_assignment
    ADD COLUMN assignment_revision BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_demo_vehicle_assignment_revision CHECK (assignment_revision >= 0);

-- Receipts deliberately do not reference operational run/assignment rows: retries remain replayable after cleanup.
CREATE TABLE ingestion.demo_assignment_receipt (
    operation_id UUID CONSTRAINT pk_demo_assignment_receipt PRIMARY KEY,
    demo_run_id UUID NOT NULL,
    bus_id VARCHAR(80) NOT NULL,
    captain_user_id UUID,
    expected_version BIGINT NOT NULL CHECK (expected_version >= 0),
    actor_subject VARCHAR(255) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    http_status SMALLINT NOT NULL,
    previous_captain_user_id UUID,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_demo_assignment_receipt_run_bus ON ingestion.demo_assignment_receipt
    (demo_run_id, bus_id, created_at DESC, operation_id DESC);
