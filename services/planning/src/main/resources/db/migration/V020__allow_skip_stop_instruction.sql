ALTER TABLE planning.issued_instruction DROP CONSTRAINT ck_issued_instruction_type;
ALTER TABLE planning.issued_instruction ADD CONSTRAINT ck_issued_instruction_type
    CHECK (type IN ('BE_CAUTIOUS', 'REDUCE_SPEED', 'SLOW_DOWN_NEAR_STOPS', 'HOLD', 'DWELL_ADJUSTMENT', 'SKIP_STOP'));
