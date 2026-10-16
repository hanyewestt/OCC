DO $migration$
DECLARE
    current_hash CHAR(64);
BEGIN
    IF to_regclass('bunching.source_processing_state') IS NULL THEN
        INSERT INTO disruption.consumer_state (id, mode, demo_run_id, last_sequence, updated_at, version)
        VALUES (1, 'LIVE', NULL, 0, CURRENT_TIMESTAMP, 0)
        ON CONFLICT (id) DO NOTHING;
        RETURN;
    END IF;

    INSERT INTO disruption.source_processing_state
        (raw_observation_id, status, next_attempt_at, processed_at, rejected_at, reason, demo_run_id,
         evaluated_at, threshold_seconds, bunched_group_count, source_sequence, version)
    SELECT raw_observation_id, status, next_attempt_at, processed_at, rejected_at, reason, demo_run_id,
           evaluated_at, threshold_seconds, bunched_group_count, source_sequence, version
      FROM bunching.source_processing_state
    ON CONFLICT (raw_observation_id) DO NOTHING;

    INSERT INTO disruption.consumer_state (id, mode, demo_run_id, last_sequence, updated_at, version)
    SELECT id, mode, demo_run_id, last_sequence, updated_at, version
      FROM bunching.consumer_state
    ON CONFLICT (id) DO UPDATE SET mode = EXCLUDED.mode, demo_run_id = EXCLUDED.demo_run_id,
        last_sequence = EXCLUDED.last_sequence, updated_at = EXCLUDED.updated_at, version = EXCLUDED.version;

    INSERT INTO disruption.consumer_state (id, mode, demo_run_id, last_sequence, updated_at, version)
    SELECT 1, 'LIVE', NULL, 0, CURRENT_TIMESTAMP, 0
     WHERE NOT EXISTS (SELECT 1 FROM disruption.consumer_state WHERE id = 1)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO disruption.disruption (id, kind, classification, demo_run_id, status, opened_at, updated_at, cleared_at)
    SELECT source.alert_id,
           'BUNCHING',
           (array_agg(source.classification ORDER BY source.triggered_at, source.id))[1],
           (array_agg(source.demo_run_id ORDER BY source.triggered_at, source.id))[1],
           CASE (array_agg(source.action ORDER BY source.triggered_at DESC, source.id DESC))[1]
               WHEN 'CLEARED' THEN 'CLEARED' ELSE 'ACTIVE' END,
           COALESCE(min(source.triggered_at) FILTER (WHERE source.action = 'OPENED'), min(source.triggered_at)),
           max(source.triggered_at),
           max(source.triggered_at) FILTER (WHERE source.action = 'CLEARED')
      FROM bunching.alert_event source
     GROUP BY source.alert_id
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO disruption.disruption_event
        (id, disruption_id, kind, action, classification, service_id, stop_code, direction_id,
         raw_observation_id, poll_run_id, source_observation_id, fetched_at, triggered_at, evidence,
         demo_run_id, leading_observation_id, following_observation_id, expected_headway_seconds,
         first_observed_headway_seconds, second_observed_headway_seconds, first_confirming_at,
         second_confirming_at, first_leading_observation_id, first_following_observation_id,
         second_leading_observation_id, second_following_observation_id, detection_rule_version)
    SELECT id, alert_id, 'BUNCHING', action, classification, service_id, stop_code, direction_id,
           raw_observation_id, poll_run_id, NULL, fetched_at, triggered_at, evidence, demo_run_id,
           leading_observation_id, following_observation_id, expected_headway_seconds,
           first_observed_headway_seconds, second_observed_headway_seconds, first_confirming_at,
           second_confirming_at, first_leading_observation_id, first_following_observation_id,
           second_leading_observation_id, second_following_observation_id, detection_rule_version
      FROM bunching.alert_event
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO disruption.event_evidence (event_id, evidence_kind, source_id, evidence_snapshot, evidence_at)
    SELECT source.id, 'BUS_ARRIVAL', evidence.source_id, source.evidence, source.triggered_at
      FROM bunching.alert_event source
      CROSS JOIN LATERAL (VALUES
          (source.first_leading_observation_id),
          (source.first_following_observation_id),
          (source.second_leading_observation_id),
          (source.second_following_observation_id)
      ) AS evidence(source_id)
     WHERE evidence.source_id IS NOT NULL
    ON CONFLICT (event_id, evidence_kind, source_id) DO NOTHING;

    INSERT INTO disruption.disruption_processing_state
        (id, demo_run_id, service_id, stop_code, direction_id, last_fetched_at, active_disruption_id,
         leading_observation_id, following_observation_id, pending_headway_seconds, pending_detected_at,
         pending_leading_observation_id, pending_following_observation_id, pending_raw_observation_id, pending_poll_run_id)
    SELECT id, demo_run_id, service_id, stop_code, direction_id, last_fetched_at, active_alert_id,
           leading_observation_id, following_observation_id, pending_headway_seconds, pending_detected_at,
           pending_leading_observation_id, pending_following_observation_id, pending_raw_observation_id, pending_poll_run_id
      FROM bunching.alert_processing_state
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO disruption.planning_outbox
        (id, event_id, status, attempt_count, next_attempt_at, lease_until, last_http_status, failure_code,
         created_at, delivered_at, payload_json, payload_fingerprint, schema_version)
    SELECT work.id, work.event_id, work.status, work.attempt_count, work.next_attempt_at, work.lease_until,
           work.last_http_status, work.failure_code, work.created_at, work.delivered_at,
           jsonb_build_object('eventType', 'BUS_BUNCHING', 'producer', 'disruption', 'eventId', event.id)::text,
           encode(sha256(convert_to(jsonb_build_object('eventType', 'BUS_BUNCHING', 'producer', 'disruption',
               'eventId', event.id)::text, 'UTF8')), 'hex'), 'planning-request-v1'
      FROM bunching.diagnosis_outbox work
      JOIN disruption.disruption_event event ON event.id = work.event_id
    ON CONFLICT (id) DO NOTHING;

    IF to_regclass('bunching.source_processing_state') IS NOT NULL THEN
        INSERT INTO disruption.legacy_import_ledger
            (source_schema, source_table, source_primary_key, target_primary_key, source_row_hash, import_version, status)
        SELECT 'bunching', 'source_processing_state', source.raw_observation_id::text,
               source.raw_observation_id::text,
               encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'), 'phase1-v1', 'APPLIED'
          FROM bunching.source_processing_state source
        ON CONFLICT (source_schema, source_table, source_primary_key) DO NOTHING;
    END IF;
    IF to_regclass('bunching.consumer_state') IS NOT NULL THEN
        INSERT INTO disruption.legacy_import_ledger
            (source_schema, source_table, source_primary_key, target_primary_key, source_row_hash, import_version, status)
        SELECT 'bunching', 'consumer_state', source.id::text, source.id::text,
               encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'), 'phase1-v1', 'APPLIED'
          FROM bunching.consumer_state source
        ON CONFLICT (source_schema, source_table, source_primary_key) DO NOTHING;
    END IF;
    IF to_regclass('bunching.alert_event') IS NOT NULL THEN
        INSERT INTO disruption.legacy_import_ledger
            (source_schema, source_table, source_primary_key, target_primary_key, source_row_hash, import_version, status)
        SELECT 'bunching', 'alert_event', source.id::text, source.id::text,
               encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'), 'phase1-v1', 'APPLIED'
          FROM bunching.alert_event source
        ON CONFLICT (source_schema, source_table, source_primary_key) DO NOTHING;
        INSERT INTO disruption.legacy_import_ledger
            (source_schema, source_table, source_primary_key, target_primary_key, source_row_hash, import_version, status)
        SELECT 'bunching', 'disruption', source.alert_id::text, source.alert_id::text,
               encode(sha256(convert_to(jsonb_agg(to_jsonb(source) ORDER BY source.triggered_at, source.id)::text, 'UTF8')),
                      'hex'), 'phase1-v1-derived', 'APPLIED'
          FROM bunching.alert_event source
         GROUP BY source.alert_id
        ON CONFLICT (source_schema, source_table, source_primary_key) DO NOTHING;
    END IF;
    IF to_regclass('bunching.alert_processing_state') IS NOT NULL THEN
        INSERT INTO disruption.legacy_import_ledger
            (source_schema, source_table, source_primary_key, target_primary_key, source_row_hash, import_version, status)
        SELECT 'bunching', 'alert_processing_state', source.id::text, source.id::text,
               encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'), 'phase1-v1', 'APPLIED'
          FROM bunching.alert_processing_state source
        ON CONFLICT (source_schema, source_table, source_primary_key) DO NOTHING;
    END IF;
    IF to_regclass('bunching.diagnosis_outbox') IS NOT NULL THEN
        INSERT INTO disruption.legacy_import_ledger
            (source_schema, source_table, source_primary_key, target_primary_key, source_row_hash, import_version, status)
        SELECT 'bunching', 'diagnosis_outbox', source.id::text, source.id::text,
               encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'), 'phase1-v1', 'APPLIED'
          FROM bunching.diagnosis_outbox source
        ON CONFLICT (source_schema, source_table, source_primary_key) DO NOTHING;
    END IF;

    FOR current_hash IN
        SELECT source_row_hash FROM disruption.legacy_import_ledger WHERE source_schema = 'bunching'
    LOOP
        IF current_hash !~ '^[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'Legacy import ledger contains an invalid source row hash';
        END IF;
    END LOOP;
END;
$migration$;
