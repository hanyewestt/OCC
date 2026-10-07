DROP INDEX IF EXISTS recommendation.ix_model_invocation_created_at;
ALTER TABLE recommendation.model_invocation_record DROP COLUMN created_at;
