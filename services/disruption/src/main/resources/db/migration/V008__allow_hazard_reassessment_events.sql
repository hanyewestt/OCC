ALTER TABLE disruption.disruption_event DROP CONSTRAINT ck_disruption_event_action;
ALTER TABLE disruption.disruption_event ADD CONSTRAINT ck_disruption_event_action
    CHECK (action IN ('OPENED', 'UPDATED', 'CLEARED', 'REASSESSED'));
ALTER TABLE disruption.disruption_event DROP CONSTRAINT ck_disruption_event_kind_fields;
ALTER TABLE disruption.disruption_event ADD CONSTRAINT ck_disruption_event_kind_fields CHECK (
    (kind = 'BUNCHING' AND raw_observation_id IS NOT NULL AND poll_run_id IS NOT NULL AND source_observation_id IS NULL
        AND service_id IS NOT NULL AND stop_code IS NOT NULL AND direction_id IS NOT NULL)
    OR (kind IN ('TRAFFIC_INCIDENT','FLOOD','RAIL_DISRUPTION','ROADWORKS') AND source_observation_id IS NOT NULL
        AND (poll_run_id IS NOT NULL OR action='REASSESSED')
        AND service_id IS NULL AND stop_code IS NULL AND direction_id IS NULL
        AND expected_headway_seconds IS NULL AND first_observed_headway_seconds IS NULL AND second_observed_headway_seconds IS NULL));
