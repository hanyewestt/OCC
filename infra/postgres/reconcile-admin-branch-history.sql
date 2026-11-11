-- One-time reconciliation for local databases created from the pre-main admin branch.
-- Matches only the admin migration filenames; canonical main history is untouched.
-- Stop application services first. Apply with ON_ERROR_STOP, then run the merged
-- db-setup once with FLYWAY_OUT_OF_ORDER=true to apply main's earlier migrations.
-- No application rows or migration checksums are changed.
BEGIN;
LOCK TABLE ingestion.flyway_schema_history IN EXCLUSIVE MODE;
UPDATE ingestion.flyway_schema_history
SET version = '033', script = 'V033__persist_demo_assignment_revision_and_receipt.sql'
WHERE version IN ('32', '032') AND script = 'V032__persist_demo_assignment_revision_and_receipt.sql' AND success;
UPDATE ingestion.flyway_schema_history
SET version = '032', script = 'V032__index_application_audit_filters.sql'
WHERE version IN ('31', '031') AND script = 'V031__index_application_audit_filters.sql' AND success;
UPDATE ingestion.flyway_schema_history
SET version = '031', script = 'V031__add_application_audit_retention.sql'
WHERE version IN ('30', '030') AND script = 'V030__add_application_audit_retention.sql' AND success;
UPDATE ingestion.flyway_schema_history
SET version = '030', script = 'V030__create_application_audit_event.sql'
WHERE version IN ('29', '029') AND script = 'V029__create_application_audit_event.sql' AND success;
UPDATE ingestion.flyway_schema_history
SET version = '029', script = 'V029__create_provisioning_operation.sql'
WHERE version IN ('28', '028') AND script = 'V028__create_provisioning_operation.sql' AND success;
UPDATE ingestion.flyway_schema_history
SET version = '028', script = 'V028__create_user_directory.sql'
WHERE version IN ('27', '027') AND script = 'V027__create_user_directory.sql' AND success;
LOCK TABLE disruption.flyway_schema_history IN EXCLUSIVE MODE;
UPDATE disruption.flyway_schema_history
SET version = '013', script = 'V013__create_application_audit_store.sql'
WHERE version IN ('12', '012') AND script = 'V012__create_application_audit_store.sql' AND success;
LOCK TABLE planning.flyway_schema_history IN EXCLUSIVE MODE;
UPDATE planning.flyway_schema_history
SET version = '024', script = 'V024__schedule_assignment_operation_recovery.sql'
WHERE version IN ('21', '021') AND script = 'V021__schedule_assignment_operation_recovery.sql' AND success;
UPDATE planning.flyway_schema_history
SET version = '023', script = 'V023__add_guarded_bus_assignment_operations.sql'
WHERE version IN ('20', '020') AND script = 'V020__add_guarded_bus_assignment_operations.sql' AND success;
UPDATE planning.flyway_schema_history
SET version = '022', script = 'V022__create_application_audit_store.sql'
WHERE version IN ('19', '019') AND script = 'V019__create_application_audit_store.sql' AND success;
COMMIT;
