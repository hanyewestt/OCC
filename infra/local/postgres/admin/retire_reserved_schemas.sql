-- Administrator-only cleanup for upgraded volumes, after backup and external-reader review.
-- RESTRICT is intentional: unexpected contents/ownership must abort the transaction.
BEGIN;
DROP SCHEMA IF EXISTS operations RESTRICT;
DROP SCHEMA IF EXISTS planning RESTRICT;
DROP ROLE IF EXISTS occ_operations;
DROP ROLE IF EXISTS occ_planning;
COMMIT;
