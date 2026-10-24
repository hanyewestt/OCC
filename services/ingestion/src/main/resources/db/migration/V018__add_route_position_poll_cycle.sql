ALTER TABLE ingestion.poll_run
    ADD COLUMN route_position_poll_cycle_id UUID;

CREATE INDEX ix_poll_run_route_position_cycle
    ON ingestion.poll_run (route_position_poll_cycle_id, started_at DESC);
