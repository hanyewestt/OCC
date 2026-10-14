DO $$
BEGIN
    IF to_regclass('diagnosis.diagnosis_request') IS NOT NULL THEN
        INSERT INTO planning.legacy_import_ledger
            (source_schema, source_table, source_id, target_id, source_row_hash, import_version, status, imported_at)
        SELECT 'diagnosis', 'diagnosis_request', source.id, source.id,
               encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'), 1, 'APPLIED', CURRENT_TIMESTAMP
          FROM diagnosis.diagnosis_request source
        ON CONFLICT (source_schema, source_table, source_id) DO NOTHING;

        IF EXISTS (
            SELECT 1 FROM diagnosis.diagnosis_request source
            JOIN planning.legacy_import_ledger ledger
              ON ledger.source_schema = 'diagnosis' AND ledger.source_table = 'diagnosis_request'
             AND ledger.source_id = source.id
            WHERE ledger.source_row_hash <> encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'))
        THEN RAISE EXCEPTION 'Planning import source changed after ledger capture: diagnosis.diagnosis_request'; END IF;

        INSERT INTO planning.planning_request
            (id, event_type, producer, event_id, schema_version, status, attempt_count, next_attempt_at,
             lease_until, failure_code, created_at, updated_at, version, input_snapshot)
        SELECT ledger.target_id, source.event_type, source.producer, source.event_id, source.schema_version,
               source.status, source.attempt_count, source.next_attempt_at, source.lease_until, source.failure_code,
               source.created_at, source.updated_at, source.version, source.input_snapshot
          FROM diagnosis.diagnosis_request source
          JOIN planning.legacy_import_ledger ledger
            ON ledger.source_schema = 'diagnosis' AND ledger.source_table = 'diagnosis_request'
           AND ledger.source_id = source.id
        ON CONFLICT (id) DO NOTHING;
    END IF;

    IF to_regclass('diagnosis.root_cause_diagnosis') IS NOT NULL THEN
        INSERT INTO planning.legacy_import_ledger
            (source_schema, source_table, source_id, target_id, source_row_hash, import_version, status, imported_at)
        SELECT 'diagnosis', 'root_cause_diagnosis', source.id, source.id,
               encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'), 1, 'APPLIED', CURRENT_TIMESTAMP
          FROM diagnosis.root_cause_diagnosis source
        ON CONFLICT (source_schema, source_table, source_id) DO NOTHING;

        IF EXISTS (
            SELECT 1 FROM diagnosis.root_cause_diagnosis source
            JOIN planning.legacy_import_ledger ledger
              ON ledger.source_schema = 'diagnosis' AND ledger.source_table = 'root_cause_diagnosis'
             AND ledger.source_id = source.id
            WHERE ledger.source_row_hash <> encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'))
        THEN RAISE EXCEPTION 'Planning import source changed after ledger capture: diagnosis.root_cause_diagnosis'; END IF;

        INSERT INTO planning.diagnosis_result
            (id, request_id, cause_code, cause_subtype, likely_cause, confidence, evidence_support_score,
             supporting_evidence_ids, limitations, confidence_components, evidence_snapshot,
             evidence_snapshot_hash, provider, model, prompt_version, schema_version, completed_at)
        SELECT ledger.target_id, source.request_id, source.cause_code, source.cause_subtype, source.likely_cause,
               source.confidence, source.evidence_support_score, source.supporting_evidence_ids, source.limitations,
               source.confidence_components, source.evidence_snapshot, source.evidence_snapshot_hash, source.provider,
               source.model, source.prompt_version, source.schema_version, source.completed_at
          FROM diagnosis.root_cause_diagnosis source
          JOIN planning.legacy_import_ledger ledger
            ON ledger.source_schema = 'diagnosis' AND ledger.source_table = 'root_cause_diagnosis'
           AND ledger.source_id = source.id
        ON CONFLICT (id) DO NOTHING;
    END IF;

    IF to_regclass('diagnosis.recommendation_delivery') IS NOT NULL THEN
        INSERT INTO planning.legacy_import_ledger
            (source_schema, source_table, source_id, target_id, source_row_hash, import_version, status, imported_at)
        SELECT 'diagnosis', 'recommendation_delivery', source.id, source.id,
               encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'), 1, 'APPLIED', CURRENT_TIMESTAMP
          FROM diagnosis.recommendation_delivery source
        ON CONFLICT (source_schema, source_table, source_id) DO NOTHING;

        IF EXISTS (
            SELECT 1 FROM diagnosis.recommendation_delivery source
            JOIN planning.legacy_import_ledger ledger
              ON ledger.source_schema = 'diagnosis' AND ledger.source_table = 'recommendation_delivery'
             AND ledger.source_id = source.id
            WHERE ledger.source_row_hash <> encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'))
        THEN RAISE EXCEPTION 'Planning import source changed after ledger capture: diagnosis.recommendation_delivery'; END IF;

        INSERT INTO planning.recommendation_job
            (id, diagnosis_id, payload_json, payload_fingerprint, status, attempt_count, next_attempt_at,
             lease_until, last_http_status, failure_code, response_json, created_at, delivered_at, terminal_at)
        SELECT ledger.target_id, source.diagnosis_id, source.payload_json, source.payload_fingerprint,
               source.status, source.attempt_count, source.next_attempt_at, source.lease_until,
               source.last_http_status, source.failure_code, source.response_json, source.created_at,
               source.delivered_at, source.terminal_at
          FROM diagnosis.recommendation_delivery source
          JOIN planning.legacy_import_ledger ledger
            ON ledger.source_schema = 'diagnosis' AND ledger.source_table = 'recommendation_delivery'
           AND ledger.source_id = source.id
        ON CONFLICT (id) DO NOTHING;
    END IF;

    IF to_regclass('recommendation.recommendation_record') IS NOT NULL THEN
        INSERT INTO planning.legacy_import_ledger
            (source_schema, source_table, source_id, target_id, source_row_hash, import_version, status, imported_at)
        SELECT 'recommendation', 'recommendation_record', source.recommendation_id, source.recommendation_id,
               encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'), 1, 'APPLIED', CURRENT_TIMESTAMP
          FROM recommendation.recommendation_record source
        ON CONFLICT (source_schema, source_table, source_id) DO NOTHING;

        IF EXISTS (
            SELECT 1 FROM recommendation.recommendation_record source
            JOIN planning.legacy_import_ledger ledger
              ON ledger.source_schema = 'recommendation' AND ledger.source_table = 'recommendation_record'
             AND ledger.source_id = source.recommendation_id
            WHERE ledger.source_row_hash <> encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'))
        THEN RAISE EXCEPTION 'Planning import source changed after ledger capture: recommendation.recommendation_record'; END IF;

        INSERT INTO planning.recommendation
            (recommendation_id, request_hash, bunching_event_id, direction_id, adjustment_seconds,
             request_payload, mitigation_context, response_payload, created_at)
        SELECT ledger.target_id, source.request_hash, source.bunching_event_id, source.direction_id,
               source.adjustment_seconds, source.request_payload, source.mitigation_context,
               source.response_payload, source.created_at
          FROM recommendation.recommendation_record source
          JOIN planning.legacy_import_ledger ledger
            ON ledger.source_schema = 'recommendation' AND ledger.source_table = 'recommendation_record'
           AND ledger.source_id = source.recommendation_id
        ON CONFLICT (recommendation_id) DO NOTHING;
    END IF;

    IF to_regclass('diagnosis.model_invocation_record') IS NOT NULL THEN
        INSERT INTO planning.legacy_import_ledger
            (source_schema, source_table, source_id, target_id, source_row_hash, import_version, status, imported_at)
        SELECT 'diagnosis', 'model_invocation_record', source.id,
               CASE WHEN EXISTS (SELECT 1 FROM planning.model_invocation_record target WHERE target.id = source.id)
                    THEN gen_random_uuid() ELSE source.id END,
               encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'), 1, 'APPLIED', CURRENT_TIMESTAMP
          FROM diagnosis.model_invocation_record source
        ON CONFLICT (source_schema, source_table, source_id) DO NOTHING;

        IF EXISTS (
            SELECT 1 FROM diagnosis.model_invocation_record source
            JOIN planning.legacy_import_ledger ledger
              ON ledger.source_schema = 'diagnosis' AND ledger.source_table = 'model_invocation_record'
             AND ledger.source_id = source.id
            WHERE ledger.source_row_hash <> encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'))
        THEN RAISE EXCEPTION 'Planning import source changed after ledger capture: diagnosis.model_invocation_record'; END IF;

        INSERT INTO planning.model_invocation_record
            (id, purpose, request_id, provider, model, provider_response_id, prompt_version,
             output_schema_version, evidence_snapshot_hash, attempt_type, attempt_number, started_at,
             completed_at, latency_millis, prompt_tokens, completion_tokens, total_tokens, outcome,
             failure_category, input_snapshot, generated_response)
        SELECT ledger.target_id, 'DIAGNOSIS', source.request_id, source.provider, source.configured_model,
               source.response_id, source.prompt_version, source.schema_version, source.evidence_snapshot_hash,
               source.invocation_type, source.attempt_number, source.started_at, source.completed_at,
               source.latency_millis, source.prompt_tokens, source.completion_tokens, source.total_tokens,
               source.validation_outcome, source.failure_category, source.input_snapshot, source.generated_response
          FROM diagnosis.model_invocation_record source
          JOIN planning.legacy_import_ledger ledger
            ON ledger.source_schema = 'diagnosis' AND ledger.source_table = 'model_invocation_record'
           AND ledger.source_id = source.id
        ON CONFLICT (id) DO NOTHING;
    END IF;

    IF to_regclass('recommendation.model_invocation_record') IS NOT NULL THEN
        INSERT INTO planning.legacy_import_ledger
            (source_schema, source_table, source_id, target_id, source_row_hash, import_version, status, imported_at)
        SELECT 'recommendation', 'model_invocation_record', source.invocation_id,
               CASE WHEN EXISTS (SELECT 1 FROM planning.model_invocation_record target WHERE target.id = source.invocation_id)
                          OR EXISTS (SELECT 1 FROM planning.legacy_import_ledger diagnosis_ledger
                              WHERE diagnosis_ledger.source_schema = 'diagnosis'
                                AND diagnosis_ledger.source_table = 'model_invocation_record'
                                AND diagnosis_ledger.source_id = source.invocation_id)
                    THEN gen_random_uuid() ELSE source.invocation_id END,
               encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'), 1, 'APPLIED', CURRENT_TIMESTAMP
          FROM recommendation.model_invocation_record source
        ON CONFLICT (source_schema, source_table, source_id) DO NOTHING;

        IF EXISTS (
            SELECT 1 FROM recommendation.model_invocation_record source
            JOIN planning.legacy_import_ledger ledger
              ON ledger.source_schema = 'recommendation' AND ledger.source_table = 'model_invocation_record'
             AND ledger.source_id = source.invocation_id
            WHERE ledger.source_row_hash <> encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex'))
        THEN RAISE EXCEPTION 'Planning import source changed after ledger capture: recommendation.model_invocation_record'; END IF;

        INSERT INTO planning.model_invocation_record
            (id, purpose, recommendation_id, correlation_id, provider, model, provider_response_id,
             prompt_version, output_schema_version, started_at, completed_at, latency_millis,
             prompt_tokens, completion_tokens, total_tokens, outcome, failure_category, candidate_payload,
             rejection_reason, deterministic_fallback_used, input_snapshot, generated_response)
        SELECT invocation_ledger.target_id, 'RECOMMENDATION', source.recommendation_id, source.correlation_id,
               source.provider, source.model, source.provider_response_id, source.prompt_version,
               source.output_schema_version, source.started_at, source.completed_at,
               source.latency_milliseconds, source.prompt_tokens, source.completion_tokens, source.total_tokens,
               source.outcome, source.failure_category, source.candidate_payload, source.rejection_reason,
               source.deterministic_fallback_used, source.input_snapshot, source.generated_response
          FROM recommendation.model_invocation_record source
          JOIN planning.legacy_import_ledger invocation_ledger
            ON invocation_ledger.source_schema = 'recommendation'
           AND invocation_ledger.source_table = 'model_invocation_record'
           AND invocation_ledger.source_id = source.invocation_id
        ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;
