ALTER TABLE recommendation.model_invocation_record
    ALTER COLUMN recommendation_id DROP NOT NULL,
    ADD COLUMN correlation_id UUID,
    ADD COLUMN input_snapshot TEXT,
    ADD COLUMN generated_response TEXT;
UPDATE recommendation.model_invocation_record SET correlation_id = recommendation_id;
ALTER TABLE recommendation.model_invocation_record ALTER COLUMN correlation_id SET NOT NULL;
COMMENT ON COLUMN recommendation.model_invocation_record.generated_response IS
    'Actual generated text, including decoding failures; NULL if no response or for legacy attempts.';
