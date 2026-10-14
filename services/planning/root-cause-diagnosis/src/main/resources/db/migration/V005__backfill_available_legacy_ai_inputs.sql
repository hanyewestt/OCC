UPDATE diagnosis.model_invocation_record attempt
SET input_snapshot = result.evidence_snapshot::text
FROM diagnosis.root_cause_diagnosis result
WHERE result.request_id = attempt.request_id AND attempt.input_snapshot IS NULL;
-- Legacy failed requests without a result have no stored input/output to recover.
-- NULL remains explicit rather than fabricating model evidence.
