CREATE TABLE planning.bus_assignment_guard (
    demo_run_id UUID NOT NULL,
    bus_id VARCHAR(80) NOT NULL,
    pending_operation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (demo_run_id, bus_id)
);

CREATE INDEX ix_bus_assignment_guard_pending
    ON planning.bus_assignment_guard (pending_operation_id)
    WHERE pending_operation_id IS NOT NULL;

CREATE TABLE planning.bus_assignment_operation (
    id UUID PRIMARY KEY,
    actor_subject VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    demo_run_id UUID NOT NULL,
    bus_id VARCHAR(80) NOT NULL,
    captain_user_id UUID,
    expected_version BIGINT NOT NULL CHECK (expected_version >= 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'REJECTED')),
    result JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_bus_assignment_operation_actor_key UNIQUE (actor_subject, idempotency_key)
);

CREATE INDEX ix_bus_assignment_operation_pending
    ON planning.bus_assignment_operation (created_at, id)
    WHERE status = 'PENDING';

INSERT INTO planning.bus_assignment_guard (demo_run_id, bus_id)
SELECT demo_run_id, bus_id FROM planning.advisory_proposal
UNION
SELECT demo_run_id, bus_id FROM planning.issued_instruction
ON CONFLICT (demo_run_id, bus_id) DO NOTHING;
