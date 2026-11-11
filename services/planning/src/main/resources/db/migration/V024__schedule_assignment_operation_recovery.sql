ALTER TABLE planning.bus_assignment_operation
    ADD COLUMN last_dispatch_at timestamptz;

CREATE INDEX ix_bus_assignment_operation_recovery
    ON planning.bus_assignment_operation (last_dispatch_at NULLS FIRST, created_at, id)
    WHERE status = 'PENDING';
