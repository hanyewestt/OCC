-- Expand existing assignments; trip identity is the stable scenario bus identity.
ALTER TABLE ingestion.demo_vehicle_assignment ADD COLUMN bus_id VARCHAR(80);
ALTER TABLE ingestion.demo_vehicle_assignment ADD COLUMN captain_user_id UUID;
COMMENT ON COLUMN ingestion.demo_vehicle_assignment.captain_user_id IS
    'Nullable: synthetic buses need not be assigned to an authenticated account.';
UPDATE ingestion.demo_vehicle_assignment SET bus_id = trip_id;
ALTER TABLE ingestion.demo_vehicle_assignment ADD CONSTRAINT ck_demo_vehicle_assignment_bus_id
    CHECK (bus_id IS NULL OR length(trim(bus_id)) > 0);
CREATE UNIQUE INDEX uq_demo_vehicle_assignment_bus ON ingestion.demo_vehicle_assignment (demo_run_id, bus_id);
CREATE UNIQUE INDEX uq_demo_vehicle_assignment_user ON ingestion.demo_vehicle_assignment (demo_run_id, captain_user_id)
    WHERE captain_user_id IS NOT NULL;
-- Null bus_id remains accepted for one release so an old application can roll back.
