-- CHECK treats NULL as success: require each projection comparison to be true.
ALTER TABLE recommendation.recommendation_record
    DROP CONSTRAINT ck_recommendation_record_bunching_event_projection,
    DROP CONSTRAINT ck_recommendation_record_direction_projection,
    DROP CONSTRAINT ck_recommendation_record_adjustment_projection,
    ADD CONSTRAINT ck_recommendation_record_bunching_event_projection CHECK (
        (jsonb_typeof(request_payload #> '{bunchingEvent,bunchingEventId}') = 'string'
         AND request_payload #>> '{bunchingEvent,bunchingEventId}' = bunching_event_id::text) IS TRUE),
    ADD CONSTRAINT ck_recommendation_record_direction_projection CHECK (
        (jsonb_typeof(request_payload #> '{bunchingEvent,directionId}') = 'number'
         AND request_payload #>> '{bunchingEvent,directionId}' = direction_id::text) IS TRUE),
    ADD CONSTRAINT ck_recommendation_record_adjustment_projection CHECK (
        (jsonb_typeof(response_payload -> 'adjustmentSeconds') = 'number'
         AND response_payload ->> 'adjustmentSeconds' = adjustment_seconds::text) IS TRUE);
