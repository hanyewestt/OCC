ALTER TABLE ingestion.bus_arrival_observation
    DROP CONSTRAINT ck_bus_arrival_observation_visit_order,
    ADD CONSTRAINT ck_bus_arrival_observation_visit_order CHECK (
        (classification = 'OBSERVATION_ONLY' AND visit_order BETWEEN 1 AND 3)
        OR (classification = 'SIMULATION' AND visit_order BETWEEN 1 AND 20)
    );
