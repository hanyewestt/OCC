CREATE TABLE planning.public_notice (
    id UUID NOT NULL, disruption_id UUID NOT NULL, classification VARCHAR(30) NOT NULL, demo_run_id UUID,
    title VARCHAR(160) NOT NULL, message TEXT NOT NULL, services JSONB NOT NULL, locations JSONB NOT NULL,
    source_evidence JSONB NOT NULL, effective_from TIMESTAMPTZ NOT NULL, effective_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL, created_by VARCHAR(200) NOT NULL, published_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL, published_at TIMESTAMPTZ, withdrawn_at TIMESTAMPTZ, replacement_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_public_notice PRIMARY KEY (id),
    CONSTRAINT ck_public_notice_scope CHECK ((classification='OBSERVATION_ONLY' AND demo_run_id IS NULL)
        OR (classification='SIMULATION' AND demo_run_id IS NOT NULL)),
    CONSTRAINT ck_public_notice_status CHECK (status IN ('DRAFT','PUBLISHED','WITHDRAWN')),
    CONSTRAINT ck_public_notice_interval CHECK (effective_until > effective_from),
    CONSTRAINT ck_public_notice_publication CHECK (status <> 'PUBLISHED' OR (published_at IS NOT NULL AND published_by IS NOT NULL))
);
COMMENT ON COLUMN planning.public_notice.demo_run_id IS 'Null for observation-origin notices; required for explicitly labelled simulation notices.';
CREATE INDEX ix_public_notice_current ON planning.public_notice(classification,demo_run_id,effective_until) WHERE status='PUBLISHED';
