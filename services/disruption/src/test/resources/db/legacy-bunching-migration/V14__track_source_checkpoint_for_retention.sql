ALTER TABLE bunching.source_processing_state ADD COLUMN source_sequence BIGINT;
ALTER TABLE bunching.source_processing_state ADD CONSTRAINT ck_source_processing_state_sequence
    CHECK (source_sequence IS NULL OR source_sequence >= 0);
