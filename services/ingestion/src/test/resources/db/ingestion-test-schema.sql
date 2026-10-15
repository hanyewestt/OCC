-- Test-only schema owner so this module can exercise Flyway migrations in-process.
-- Production bootstrap and privilege checks are covered by DatabasePrivilegeBoundaryIT.
CREATE ROLE occ_ingestion LOGIN PASSWORD 'synthetic-ingestion';
CREATE SCHEMA ingestion AUTHORIZATION occ_ingestion;
