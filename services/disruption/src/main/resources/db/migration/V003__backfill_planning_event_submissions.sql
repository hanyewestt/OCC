DO $migration$
DECLARE
    incomplete_count BIGINT;
BEGIN
    SELECT count(*)
      INTO incomplete_count
      FROM disruption.planning_outbox work
      JOIN disruption.disruption_event event ON event.id = work.event_id
     WHERE work.status IN ('PENDING', 'PROCESSING')
       AND (work.schema_version IS DISTINCT FROM 'planning-event-v1' OR work.payload_json IS NULL)
       AND (event.kind <> 'BUNCHING'
            OR event.action <> 'OPENED'
            OR event.classification <> 'SIMULATION'
            OR event.demo_run_id IS NULL
            OR event.service_id IS NULL
            OR event.stop_code IS NULL
            OR event.direction_id IS NULL
            OR event.expected_headway_seconds IS NULL
            OR event.first_observed_headway_seconds IS NULL
            OR event.second_observed_headway_seconds IS NULL
            OR event.first_confirming_at IS NULL
            OR event.second_confirming_at IS NULL
            OR event.first_leading_observation_id IS NULL
            OR event.first_following_observation_id IS NULL
            OR event.second_leading_observation_id IS NULL
            OR event.second_following_observation_id IS NULL
            OR event.detection_rule_version IS NULL
            OR event.detection_rule_version = '');

    IF incomplete_count > 0 THEN
        RAISE EXCEPTION 'Cannot migrate % pending Planning outbox rows: immutable bunching event facts are incomplete',
            incomplete_count;
    END IF;
END;
$migration$;

UPDATE disruption.planning_outbox work
   SET payload_json = payload.submission::text,
       payload_fingerprint = NULL,
       schema_version = 'planning-event-v1'
  FROM (
      SELECT work.id,
             jsonb_build_object(
                 'schemaVersion', 'planning-event-v1',
                 'eventType', 'BUS_BUNCHING',
                 'producer', 'disruption',
                 'eventId', event.id,
                 'disruptionId', event.disruption_id,
                 'classification', event.classification,
                 'demoRunId', event.demo_run_id,
                 'detectedAt', event.triggered_at,
                 'detectionRuleVersion', event.detection_rule_version,
                 'evidenceIds', evidence.ids,
                 'eventSnapshot', jsonb_build_object(
                     'eventId', event.id,
                     'bunchingEventId', event.disruption_id,
                     'classification', event.classification,
                     'demoRunId', event.demo_run_id,
                     'serviceId', event.service_id,
                     'directionId', event.direction_id,
                     'monitoredStopCode', event.stop_code,
                     'leadingObservationId', event.leading_observation_id,
                     'followingObservationId', event.following_observation_id,
                     'status', 'CONFIRMED',
                     'expectedHeadwaySeconds', event.expected_headway_seconds,
                     'observedHeadwaySeconds', jsonb_build_array(
                         event.first_observed_headway_seconds, event.second_observed_headway_seconds),
                     'firstConfirmedAt', event.first_confirming_at,
                     'confirmingObservationTimes', jsonb_build_array(
                         event.first_confirming_at, event.second_confirming_at),
                     'confirmingObservationIds', jsonb_build_array(
                         event.first_leading_observation_id, event.first_following_observation_id,
                         event.second_leading_observation_id, event.second_following_observation_id),
                     'evidenceIds', evidence.ids,
                     'detectionRuleVersion', event.detection_rule_version)) AS submission
        FROM disruption.planning_outbox work
        JOIN disruption.disruption_event event ON event.id = work.event_id
        CROSS JOIN LATERAL (
            SELECT jsonb_build_array(
                'bunching-alert-event:' || event.id::text,
                'bus-arrival:' || event.first_leading_observation_id::text,
                'bus-arrival:' || event.first_following_observation_id::text,
                'bus-arrival:' || event.second_leading_observation_id::text,
                'bus-arrival:' || event.second_following_observation_id::text) AS ids
        ) evidence
       WHERE work.status IN ('PENDING', 'PROCESSING')
         AND (work.schema_version IS DISTINCT FROM 'planning-event-v1' OR work.payload_json IS NULL)
  ) payload
 WHERE work.id = payload.id;

COMMENT ON COLUMN disruption.planning_outbox.payload_fingerprint IS
    'Canonical semantic SHA-256 of the typed Planning event envelope; migrated pending rows are initialized before first delivery.';
