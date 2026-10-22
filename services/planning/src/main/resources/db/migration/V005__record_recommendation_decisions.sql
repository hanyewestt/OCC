CREATE TABLE planning.recommendation_decision (
    recommendation_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_recommendation_decision PRIMARY KEY (recommendation_id),
    CONSTRAINT fk_recommendation_decision_recommendation FOREIGN KEY (recommendation_id)
        REFERENCES planning.recommendation (recommendation_id),
    CONSTRAINT ck_recommendation_decision_action CHECK (action IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_recommendation_decision_actor CHECK (length(trim(actor_subject)) > 0)
);

COMMENT ON TABLE planning.recommendation_decision IS
    'Immutable first controller decision for a recommendation; repeat clicks replay the same outcome.';
