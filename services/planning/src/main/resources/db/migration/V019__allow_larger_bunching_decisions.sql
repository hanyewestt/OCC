ALTER TABLE planning.recommendation_decision
    DROP CONSTRAINT ck_recommendation_decision_override_instructions,
    DROP CONSTRAINT ck_recommendation_decision_override_shape,
    ADD CONSTRAINT ck_recommendation_decision_override_instructions CHECK (
        jsonb_typeof(override_instructions) = 'array'
        AND jsonb_array_length(override_instructions) BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_recommendation_decision_override_shape CHECK (
        (action = 'APPROVED' AND jsonb_array_length(override_instructions) = 0 AND controller_rationale IS NULL)
        OR (action = 'REJECTED' AND (
            (jsonb_array_length(override_instructions) = 0 AND controller_rationale IS NULL)
            OR (jsonb_array_length(override_instructions) BETWEEN 1 AND 100
                AND (controller_rationale IS NULL OR length(trim(controller_rationale)) > 0)))));

ALTER TABLE planning.early_bunching_controller_suggestion
    DROP CONSTRAINT ck_early_bunching_controller_suggestion_instructions,
    ADD CONSTRAINT ck_early_bunching_controller_suggestion_instructions CHECK (
        jsonb_typeof(instructions) = 'array' AND jsonb_array_length(instructions) BETWEEN 1 AND 100);

COMMENT ON COLUMN planning.recommendation_decision.override_instructions IS
    'Controller-authored replacement instructions for affected buses when the AI recommendation is rejected.';
