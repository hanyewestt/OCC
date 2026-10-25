-- Expand only: retain historical scores, accept new evidence-only results, permit application rollback.
ALTER TABLE planning.diagnosis_result ALTER COLUMN confidence DROP NOT NULL;
ALTER TABLE planning.diagnosis_result ALTER COLUMN evidence_support_score DROP NOT NULL;
ALTER TABLE planning.diagnosis_result ALTER COLUMN confidence_components DROP NOT NULL;
COMMENT ON COLUMN planning.diagnosis_result.confidence IS 'Deprecated; null for new evidence-only diagnoses.';
COMMENT ON COLUMN planning.diagnosis_result.evidence_support_score IS 'Deprecated; null for new evidence-only diagnoses.';
COMMENT ON COLUMN planning.diagnosis_result.confidence_components IS 'Historical heuristic components only; null for new results.';
