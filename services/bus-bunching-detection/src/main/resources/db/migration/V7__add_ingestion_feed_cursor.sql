CREATE TABLE bunching.ingestion_feed_cursor (
    id INTEGER NOT NULL,
    last_sequence BIGINT NOT NULL,
    CONSTRAINT pk_ingestion_feed_cursor PRIMARY KEY (id),
    CONSTRAINT ck_ingestion_feed_cursor_singleton CHECK (id = 1),
    CONSTRAINT ck_ingestion_feed_cursor_sequence CHECK (last_sequence >= 0)
);

INSERT INTO bunching.ingestion_feed_cursor (id, last_sequence) VALUES (1, 0);
