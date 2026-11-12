-- The non-null default also keeps inserts from the previous application version compatible.
ALTER TABLE planning.planning_request
    ADD COLUMN processing_requested BOOLEAN NOT NULL DEFAULT FALSE;

-- Work already started before this marker was introduced must still recover
-- after a failed attempt or an expired lease when automatic bunching is off.
UPDATE planning.planning_request SET processing_requested = TRUE WHERE attempt_count > 0;
