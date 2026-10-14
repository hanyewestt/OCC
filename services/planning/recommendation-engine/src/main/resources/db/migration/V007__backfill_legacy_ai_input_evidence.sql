UPDATE recommendation.model_invocation_record attempt
SET input_snapshot = jsonb_build_object('request', result.request_payload,
    'mitigationContext', result.mitigation_context)::text
FROM recommendation.recommendation_record result
WHERE result.recommendation_id = attempt.recommendation_id AND attempt.input_snapshot IS NULL;
COMMENT ON COLUMN recommendation.model_invocation_record.candidate_payload IS
    'Structured generated candidate; malformed text is retained separately in generated_response.';
