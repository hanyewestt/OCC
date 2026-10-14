ALTER TABLE diagnosis.recommendation_delivery ADD COLUMN terminal_at TIMESTAMPTZ;
UPDATE diagnosis.recommendation_delivery SET terminal_at = CURRENT_TIMESTAMP WHERE status = 'FAILED_TERMINAL';
