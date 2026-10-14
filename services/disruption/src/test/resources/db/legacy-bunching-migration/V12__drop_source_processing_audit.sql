-- Rejection and retry state remain in source_rejection and source_retry.
-- The append-only general failure audit was retired in the Phase 1 release.
DROP TABLE IF EXISTS bunching.source_processing_audit;
