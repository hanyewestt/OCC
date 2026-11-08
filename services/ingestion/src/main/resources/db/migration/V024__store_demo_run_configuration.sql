ALTER TABLE ingestion.demo_run
    ADD COLUMN service_id VARCHAR(20) NOT NULL DEFAULT '190',
    ADD COLUMN direction_id SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN bus_count SMALLINT NOT NULL DEFAULT 3,
    ADD COLUMN dispatch_gap_seconds BIGINT NOT NULL DEFAULT 90,
    ADD COLUMN simulation_scenario VARCHAR(20) NOT NULL DEFAULT 'BUNCHING',
    ADD CONSTRAINT ck_demo_run_direction CHECK (direction_id > 0),
    ADD CONSTRAINT ck_demo_run_bus_count CHECK (bus_count BETWEEN 2 AND 20),
    ADD CONSTRAINT ck_demo_run_dispatch_gap CHECK (dispatch_gap_seconds BETWEEN 15 AND 900),
    ADD CONSTRAINT ck_demo_run_simulation_scenario CHECK (simulation_scenario IN ('NORMAL', 'BUNCHING'));
