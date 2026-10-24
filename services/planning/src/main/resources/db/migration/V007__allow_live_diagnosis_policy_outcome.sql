-- Expand the allowed outcomes without changing existing traffic outcomes or rows.
-- Live diagnoses retain their result but deliberately issue no operational action.
ALTER TABLE planning.planning_request
    DROP CONSTRAINT ck_planning_request_policy_outcome;

ALTER TABLE planning.planning_request
    ADD CONSTRAINT ck_planning_request_policy_outcome
        CHECK (policy_outcome IS NULL OR policy_outcome IN (
            'TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION',
            'LIVE_DIAGNOSIS_NO_VALIDATED_OPERATIONAL_CONTEXT'
        ));

COMMENT ON COLUMN planning.planning_request.policy_outcome IS
    'Typed policy result for requests completed without an operational recommendation.';
