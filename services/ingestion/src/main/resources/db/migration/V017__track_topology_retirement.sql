-- A superseded topology remains usable for the retained observation window.
ALTER TABLE ingestion.reference_dataset_version ADD COLUMN retired_at TIMESTAMPTZ;

-- Old rows have no reliable supersession instant. Grant them one retention window
-- from this migration rather than deriving expiry from their import time.
UPDATE ingestion.reference_dataset_version
SET retired_at = CURRENT_TIMESTAMP
WHERE status IN ('SUPERSEDED', 'REJECTED') AND retired_at IS NULL;

CREATE INDEX ix_reference_dataset_version_retention
    ON ingestion.reference_dataset_version (status, retired_at)
    WHERE status <> 'ACTIVE';
