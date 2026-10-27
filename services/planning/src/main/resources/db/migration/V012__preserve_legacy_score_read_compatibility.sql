-- The previous application release reads these columns as non-null numeric values.
ALTER TABLE planning.diagnosis_result ADD COLUMN confidence_scores_available BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE planning.diagnosis_result
SET confidence_scores_available = confidence IS NOT NULL
        AND evidence_support_score IS NOT NULL
        AND confidence_components IS NOT NULL,
    confidence = COALESCE(confidence, 0),
    evidence_support_score = COALESCE(evidence_support_score, 0),
    confidence_components = COALESCE(confidence_components, '{}'::jsonb);

ALTER TABLE planning.diagnosis_result ALTER COLUMN confidence SET NOT NULL;
ALTER TABLE planning.diagnosis_result ALTER COLUMN evidence_support_score SET NOT NULL;
ALTER TABLE planning.diagnosis_result ALTER COLUMN confidence_components SET NOT NULL;
COMMENT ON COLUMN planning.diagnosis_result.confidence_scores_available IS
    'False when legacy score columns contain compatibility values for an evidence-only diagnosis.';
