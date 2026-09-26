CREATE TABLE ingestion.demo_runtime_setting (
    id INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_demo_runtime_setting PRIMARY KEY (id),
    CONSTRAINT ck_demo_runtime_setting_singleton CHECK (id = 1)
);

INSERT INTO ingestion.demo_runtime_setting (id, enabled, changed_at)
VALUES (1, FALSE, NOW());
