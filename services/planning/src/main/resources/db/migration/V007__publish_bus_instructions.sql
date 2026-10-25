CREATE TABLE planning.bus_instruction (
    instruction_id UUID NOT NULL,
    recommendation_id UUID NOT NULL,
    bus_reference VARCHAR(80) NOT NULL,
    instruction VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    acknowledged_by VARCHAR(255),
    CONSTRAINT pk_bus_instruction PRIMARY KEY (instruction_id),
    CONSTRAINT fk_bus_instruction_decision FOREIGN KEY (recommendation_id)
        REFERENCES planning.recommendation_decision (recommendation_id),
    CONSTRAINT uq_bus_instruction_recommendation_bus UNIQUE (recommendation_id, bus_reference),
    CONSTRAINT ck_bus_instruction_bus_reference CHECK (length(trim(bus_reference)) > 0),
    CONSTRAINT ck_bus_instruction_text CHECK (length(trim(instruction)) > 0),
    CONSTRAINT ck_bus_instruction_status CHECK (status IN ('SENT', 'ACKNOWLEDGED')),
    CONSTRAINT ck_bus_instruction_acknowledgement CHECK (
        (status = 'SENT' AND acknowledged_at IS NULL AND acknowledged_by IS NULL)
        OR (status = 'ACKNOWLEDGED' AND acknowledged_at IS NOT NULL
            AND acknowledged_by IS NOT NULL AND length(trim(acknowledged_by)) > 0
            AND acknowledged_at >= sent_at))
);

CREATE INDEX ix_bus_instruction_bus_status_sent
    ON planning.bus_instruction (bus_reference, status, sent_at DESC);

COMMENT ON TABLE planning.bus_instruction IS
    'Simulation bus instructions published by an immutable controller decision for the mock OBU flow.';
COMMENT ON COLUMN planning.bus_instruction.bus_reference IS
    'Simulation vehicle registration/targetBusReference; not a canonical production fleet identifier.';

INSERT INTO planning.bus_instruction (
    instruction_id, recommendation_id, bus_reference, instruction, status, sent_at
)
SELECT gen_random_uuid(), decision.recommendation_id,
       recommendation.response_payload ->> 'targetBusReference',
       CASE recommendation.response_payload ->> 'mitigationType'
           WHEN 'HOLD' THEN 'Hold for '
               || (recommendation.response_payload ->> 'adjustmentSeconds')
               || ' seconds at stop '
               || (recommendation.response_payload ->> 'controlStopCode') || '.'
           WHEN 'DWELL_ADJUSTMENT' THEN 'Adjust dwell by '
               || (recommendation.response_payload ->> 'adjustmentSeconds')
               || ' seconds at stop '
               || (recommendation.response_payload ->> 'controlStopCode') || '.'
           WHEN 'STANDBY_DISPATCH' THEN 'Dispatch standby support for '
               || (recommendation.response_payload ->> 'targetBusReference')
               || ' at stop '
               || (recommendation.response_payload ->> 'controlStopCode') || '.'
       END,
       'SENT', decision.decided_at
  FROM planning.recommendation_decision decision
 JOIN planning.recommendation recommendation
    ON recommendation.recommendation_id = decision.recommendation_id
 WHERE decision.action = 'APPROVED'
   AND length(trim(recommendation.response_payload ->> 'targetBusReference')) > 0
   AND length(trim(recommendation.response_payload ->> 'controlStopCode')) > 0
   AND recommendation.response_payload ->> 'adjustmentSeconds' IS NOT NULL
   AND recommendation.response_payload ->> 'mitigationType'
       IN ('HOLD', 'DWELL_ADJUSTMENT', 'STANDBY_DISPATCH');

INSERT INTO planning.bus_instruction (
    instruction_id, recommendation_id, bus_reference, instruction, status, sent_at
)
SELECT gen_random_uuid(), replacement.recommendation_id,
       replacement.value ->> 'busReference', replacement.value ->> 'instruction',
       'SENT', replacement.decided_at
  FROM (
      SELECT DISTINCT ON (decision.recommendation_id, item.value ->> 'busReference')
             decision.recommendation_id, decision.decided_at, item.value
        FROM planning.recommendation_decision decision
       CROSS JOIN LATERAL jsonb_array_elements(decision.override_instructions)
            WITH ORDINALITY AS item(value, ordinal)
       WHERE decision.action = 'REJECTED'
         AND length(trim(item.value ->> 'busReference')) > 0
         AND length(trim(item.value ->> 'instruction')) > 0
       ORDER BY decision.recommendation_id, item.value ->> 'busReference', item.ordinal
  ) replacement;
