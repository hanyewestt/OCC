ALTER TABLE planning.planning_request
    ADD COLUMN policy_outcome VARCHAR(80);

ALTER TABLE planning.planning_request
    ADD CONSTRAINT ck_planning_request_policy_outcome
        CHECK (policy_outcome IS NULL OR policy_outcome IN ('TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION'));

COMMENT ON COLUMN planning.planning_request.policy_outcome IS
    'Typed policy result for event families handled without a diagnosis/recommendation job.';
