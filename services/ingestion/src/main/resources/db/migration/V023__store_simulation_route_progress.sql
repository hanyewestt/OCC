ALTER TABLE ingestion.bus_arrival_observation
    ADD COLUMN route_stop_sequence INTEGER;

ALTER TABLE ingestion.bus_arrival_observation
    ADD CONSTRAINT ck_bus_arrival_simulation_route_progress CHECK (
        route_stop_sequence IS NULL OR (classification = 'SIMULATION' AND route_stop_sequence > 0));

COMMENT ON COLUMN ingestion.bus_arrival_observation.route_stop_sequence IS
    'Selected route stop occurrence for synthetic bus progress; live arrival predictions do not provide vehicle position.';
