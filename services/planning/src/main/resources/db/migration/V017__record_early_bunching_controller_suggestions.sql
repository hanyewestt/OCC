CREATE TABLE planning.early_bunching_controller_suggestion (
    request_id UUID NOT NULL,
    instructions JSONB NOT NULL,
    controller_rationale VARCHAR(1000) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    suggested_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_early_bunching_controller_suggestion PRIMARY KEY (request_id),
    CONSTRAINT fk_early_bunching_controller_suggestion_request FOREIGN KEY (request_id)
        REFERENCES planning.planning_request (id),
    CONSTRAINT ck_early_bunching_controller_suggestion_instructions CHECK (
        jsonb_typeof(instructions) = 'array' AND jsonb_array_length(instructions) BETWEEN 1 AND 3),
    CONSTRAINT ck_early_bunching_controller_suggestion_rationale CHECK (length(trim(controller_rationale)) > 0),
    CONSTRAINT ck_early_bunching_controller_suggestion_actor CHECK (length(trim(actor_subject)) > 0)
);

COMMENT ON TABLE planning.early_bunching_controller_suggestion IS
    'First controller-authored per-bus mitigation suggestion recorded before an AI diagnosis or recommendation is available.';
