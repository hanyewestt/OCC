CREATE TABLE disruption.hazard_feed_health (
    id UUID PRIMARY KEY,
    kind VARCHAR(40) NOT NULL,
    source VARCHAR(100) NOT NULL,
    scope VARCHAR(100) NOT NULL,
    classification VARCHAR(40) NOT NULL,
    demo_run_id UUID,
    last_attempt_at TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    last_outcome VARCHAR(20) NOT NULL,
    last_complete BOOLEAN NOT NULL,
    stale_after_seconds BIGINT NOT NULL CHECK (stale_after_seconds > 0),
    CONSTRAINT ck_hazard_feed_health_outcome CHECK (last_outcome IN ('SUCCESS','PARTIAL','FAILED'))
);
CREATE UNIQUE INDEX uq_hazard_feed_health_identity ON disruption.hazard_feed_health
    (kind, source, scope, classification, COALESCE(demo_run_id, '00000000-0000-0000-0000-000000000000'::uuid));
