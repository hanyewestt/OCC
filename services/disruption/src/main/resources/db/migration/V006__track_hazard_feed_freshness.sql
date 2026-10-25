ALTER TABLE disruption.hazard_checkpoint
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN last_success_at TIMESTAMPTZ,
    ADD COLUMN last_outcome VARCHAR(20),
    ADD COLUMN last_complete BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN stale_after_seconds BIGINT NOT NULL DEFAULT 172800;
ALTER TABLE disruption.hazard_checkpoint ADD CONSTRAINT ck_hazard_checkpoint_outcome
    CHECK (last_outcome IS NULL OR last_outcome IN ('SUCCESS','PARTIAL','FAILED'));
ALTER TABLE disruption.hazard_checkpoint ADD CONSTRAINT ck_hazard_checkpoint_stale_after
    CHECK (stale_after_seconds > 0);
