CREATE TABLE planning.issued_instruction (
    id UUID NOT NULL, proposal_id UUID NOT NULL, disruption_id UUID NOT NULL,
    demo_run_id UUID NOT NULL, bus_id VARCHAR(80) NOT NULL, captain_user_id UUID NOT NULL,
    type VARCHAR(40) NOT NULL, parameters JSONB NOT NULL, message TEXT NOT NULL,
    hazard_classification VARCHAR(30) NOT NULL, controller_subject VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL, issued_at TIMESTAMPTZ NOT NULL, acknowledgement_deadline TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ, acknowledgement_subject VARCHAR(200), applicable_until TIMESTAMPTZ,
    inactive_reason VARCHAR(80), version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_issued_instruction PRIMARY KEY (id),
    CONSTRAINT uq_issued_instruction_proposal UNIQUE (proposal_id),
    CONSTRAINT ck_issued_instruction_type CHECK (type IN ('BE_CAUTIOUS','REDUCE_SPEED','SLOW_DOWN_NEAR_STOPS','HOLD','DWELL_ADJUSTMENT')),
    CONSTRAINT ck_issued_instruction_status CHECK (status IN ('ISSUED','ACKNOWLEDGED','EXPIRED','CANCELLED')),
    CONSTRAINT ck_issued_instruction_ack CHECK ((acknowledged_at IS NULL)=(acknowledgement_subject IS NULL)),
    CONSTRAINT ck_issued_instruction_window CHECK (acknowledgement_deadline > issued_at)
);
COMMENT ON COLUMN planning.issued_instruction.applicable_until IS 'Nullable: timed speed advice starts at acknowledgement; stop-based advice ends by route progress.';
CREATE UNIQUE INDEX uq_issued_instruction_movement ON planning.issued_instruction(demo_run_id,bus_id)
    WHERE status IN ('ISSUED','ACKNOWLEDGED') AND type <> 'BE_CAUTIOUS';
CREATE INDEX ix_issued_instruction_captain ON planning.issued_instruction(captain_user_id,demo_run_id,status);
