DROP INDEX IF EXISTS planning.ix_bus_assignment_operation_pending;
DROP INDEX IF EXISTS planning.ix_bus_assignment_operation_recovery;
DROP TABLE IF EXISTS planning.bus_assignment_operation;

DROP INDEX IF EXISTS planning.ix_bus_assignment_guard_pending;
ALTER TABLE planning.bus_assignment_guard DROP COLUMN IF EXISTS pending_operation_id;
