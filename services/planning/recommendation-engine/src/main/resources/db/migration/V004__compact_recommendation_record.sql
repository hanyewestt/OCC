DROP INDEX recommendation.ix_recommendation_record_diagnosis_id;
DROP INDEX recommendation.ix_recommendation_record_bunching_event_id;

ALTER TABLE recommendation.recommendation_record
    DROP CONSTRAINT uq_recommendation_record_idempotency_key,
    DROP CONSTRAINT ck_recommendation_record_identity,
    DROP CONSTRAINT ck_recommendation_record_evidence_ids,
    DROP COLUMN idempotency_key,
    DROP COLUMN request_id,
    DROP COLUMN diagnosis_id,
    DROP COLUMN classification,
    DROP COLUMN service_id,
    DROP COLUMN monitored_stop_code,
    DROP COLUMN mitigation_type,
    DROP COLUMN recommendation_status,
    DROP COLUMN rationale,
    DROP COLUMN target_bus_reference,
    DROP COLUMN control_stop_code,
    DROP COLUMN evidence_ids;

ALTER TABLE recommendation.recommendation_record
    ADD CONSTRAINT ck_recommendation_record_bunching_event_projection CHECK (
        jsonb_typeof(request_payload #> '{bunchingEvent,bunchingEventId}') = 'string'
        AND request_payload #>> '{bunchingEvent,bunchingEventId}' = bunching_event_id::text
    ),
    ADD CONSTRAINT ck_recommendation_record_direction_projection CHECK (
        jsonb_typeof(request_payload #> '{bunchingEvent,directionId}') = 'number'
        AND request_payload #>> '{bunchingEvent,directionId}' IN ('1', '2')
        AND direction_id = (request_payload #>> '{bunchingEvent,directionId}')::smallint
    ),
    ADD CONSTRAINT ck_recommendation_record_adjustment_projection CHECK (
        jsonb_typeof(response_payload -> 'adjustmentSeconds') = 'number'
        AND response_payload ->> 'adjustmentSeconds' ~ '^[0-9]+$'
        AND adjustment_seconds = (response_payload ->> 'adjustmentSeconds')::smallint
    );

CREATE INDEX ix_recommendation_record_bunching_event_created_at
    ON recommendation.recommendation_record (bunching_event_id, created_at DESC);
