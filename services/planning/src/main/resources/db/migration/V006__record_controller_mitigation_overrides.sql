ALTER TABLE planning.recommendation_decision
    ADD COLUMN override_instructions JSONB,
    ADD COLUMN controller_rationale VARCHAR(1000);

UPDATE planning.recommendation_decision
   SET override_instructions = '[]'::jsonb
 WHERE override_instructions IS NULL;

ALTER TABLE planning.recommendation_decision
    ALTER COLUMN override_instructions SET NOT NULL,
    ADD CONSTRAINT ck_recommendation_decision_override_instructions CHECK (
        jsonb_typeof(override_instructions) = 'array'
        AND jsonb_array_length(override_instructions) BETWEEN 0 AND 3),
    ADD CONSTRAINT ck_recommendation_decision_override_shape CHECK (
        (action = 'APPROVED'
            AND jsonb_array_length(override_instructions) = 0
            AND controller_rationale IS NULL)
        OR (action = 'REJECTED'
            AND ((jsonb_array_length(override_instructions) = 0 AND controller_rationale IS NULL)
                OR (jsonb_array_length(override_instructions) BETWEEN 1 AND 3
                    AND length(trim(controller_rationale)) > 0))));

COMMENT ON COLUMN planning.recommendation_decision.override_instructions IS
    'Controller-authored replacement instructions for one to three buses when the AI recommendation is rejected.';
COMMENT ON COLUMN planning.recommendation_decision.controller_rationale IS
    'Controller explanation for rejecting and replacing the AI recommendation.';
