-- =============================================================================
-- OCC schema — TASK 1.1
-- Source of truth: ARCHITECTURE.md §2 "PostgreSQL + PostGIS Database Schema".
--
-- Runs automatically on first container start via /docker-entrypoint-initdb.d.
-- Everything below is copied from ARCHITECTURE.md except where marked
--   -- [ADDED]     : column/index not in ARCHITECTURE.md, with reason
--   -- [CHANGED]   : differs from ARCHITECTURE.md, with reason
-- If you disagree with an [ADDED]/[CHANGED] line, delete it — but then also
-- update ARCHITECTURE.md so the two don't drift.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS postgis;

-- -----------------------------------------------------------------------------
-- 1. Bus Fleet Telemetry Snapshot
-- -----------------------------------------------------------------------------
CREATE TABLE bus_telemetry (
    id BIGSERIAL PRIMARY KEY,
    service_no VARCHAR(10) NOT NULL,
    direction INT NOT NULL DEFAULT 1,        -- [ADDED] route_geometries is keyed on (service_no, direction)
                                             --         and headway pairing is meaningless across directions.
                                             --         DEFAULT 1 only so the current JSONL (no direction) loads.
    bus_plate VARCHAR(15) NOT NULL,
    current_stop_id VARCHAR(20),
    next_stop_id VARCHAR(20),
    headway_seconds INT,
    load_status VARCHAR(10),                 -- 'SEA' (Seats Available), 'SDA' (Standing Available), 'LSD' (Limited Standing)
    vehicle_type VARCHAR(10),                -- 'SD' (Single Deck), 'DD' (Double Deck), 'BD' (Bendy)
    location GEOMETRY(Point, 4326),
    is_sos_triggered BOOLEAN NOT NULL DEFAULT FALSE,  -- [ADDED] present in JSONL and in the gatekeeper
                                                      --         Java snippet, missing from the DDL.
    recorded_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_bus_telemetry_geom ON bus_telemetry USING GIST(location);
CREATE INDEX idx_bus_telemetry_service ON bus_telemetry(service_no);
-- [ADDED] The gatekeeper needs "most recent row for this service+direction
--         before recorded_at" to find the trailing bus. This makes that query
--         an index scan instead of a table scan once telemetry grows.
CREATE INDEX idx_bus_telemetry_service_dir_time
    ON bus_telemetry(service_no, direction, recorded_at DESC);

-- -----------------------------------------------------------------------------
-- 2. Route Geometries & Downstream Stops
-- -----------------------------------------------------------------------------
CREATE TABLE route_geometries (
    service_no VARCHAR(10) NOT NULL,
    direction INT NOT NULL,
    route_polyline GEOMETRY(LineString, 4326),
    corridor_buffer_500m GEOMETRY(Polygon, 4326),  -- Precomputed ST_Buffer(geom::geography, 500)
    stop_sequences JSONB,                          -- Array of ordered bus stop IDs and coordinates
    PRIMARY KEY(service_no, direction)
);
CREATE INDEX idx_route_buffer ON route_geometries USING GIST(corridor_buffer_500m);

-- [ADDED] ARCHITECTURE.md says corridor_buffer_500m is "precomputed" but does
--         not say by whom. This trigger makes the DB do it, so the seeder
--         (TASK 1.3) and any later route updates only ever write route_polyline
--         and can't produce a stale or missing buffer.
CREATE OR REPLACE FUNCTION compute_corridor_buffer() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.route_polyline IS NOT NULL THEN
        NEW.corridor_buffer_500m :=
            ST_Buffer(NEW.route_polyline::geography, 500)::geometry;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_route_geometries_buffer
    BEFORE INSERT OR UPDATE OF route_polyline ON route_geometries
    FOR EACH ROW EXECUTE FUNCTION compute_corridor_buffer();

-- -----------------------------------------------------------------------------
-- 3. Traffic Incidents & Road Obstructions
-- -----------------------------------------------------------------------------
CREATE TABLE traffic_incidents (
    incident_id VARCHAR(50) PRIMARY KEY,
    incident_type VARCHAR(30),               -- 'ACCIDENT', 'ROADWORK', 'VEHICLE_BREAKDOWN', 'WEATHER_HAZARD'
    message TEXT NOT NULL,
    location GEOMETRY(Point, 4326),
    estimated_clearance_time TIMESTAMP WITH TIME ZONE,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_incidents_geom ON traffic_incidents USING GIST(location);

-- -----------------------------------------------------------------------------
-- 4. Train Service Alerts (Disruptions)
-- -----------------------------------------------------------------------------
CREATE TABLE train_service_alerts (
    alert_id VARCHAR(50) PRIMARY KEY,
    line VARCHAR(30) NOT NULL,               -- 'EWL', 'NSL', 'DTL', 'NEL', 'TEL', 'CCL'
    affected_stations JSONB,                 -- e.g. ["Jurong East", "Clementi", "Buona Vista"]
    status VARCHAR(20),                      -- 'DELAYS', 'NO_SERVICE'
    shuttle_bus_activated BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 5. Staged Mitigation Directives (HITL Queue)
-- -----------------------------------------------------------------------------
CREATE TABLE mitigation_directives (
    directive_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_no VARCHAR(10) NOT NULL,
    bus_plate VARCHAR(15),
    incident_id VARCHAR(50) REFERENCES traffic_incidents(incident_id),
    action_type VARCHAR(30) NOT NULL,        -- 'HEADWAY_PACE', 'ALIGHTING_ONLY', 'DYNAMIC_DETOUR', 'BRIDGING_BUS'
    confidence_score NUMERIC(5, 2),          -- e.g., 94.00
    root_cause_hypothesis TEXT,
    tradeoff_summary JSONB,                  -- e.g., {"passenger_mins_saved": 420, "donor_route_impact": 1.2}
    payload JSONB NOT NULL,                  -- Detailed instruction parameters / detour polylines
    status VARCHAR(20) DEFAULT 'PENDING_APPROVAL',  -- 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'EXECUTED'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 6. Controller Audit Trail & Overrides
-- -----------------------------------------------------------------------------
CREATE TABLE controller_audit_trail (
    audit_id BIGSERIAL PRIMARY KEY,
    directive_id UUID REFERENCES mitigation_directives(directive_id),
    controller_id VARCHAR(50) NOT NULL,
    decision VARCHAR(20) NOT NULL,           -- 'APPROVED', 'OVERRIDDEN', 'REJECTED'
    override_reason TEXT,
    action_timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 7. Driver Acknowledgments
-- -----------------------------------------------------------------------------
CREATE TABLE driver_acknowledgments (
    ack_id BIGSERIAL PRIMARY KEY,
    directive_id UUID REFERENCES mitigation_directives(directive_id),
    bus_plate VARCHAR(15) NOT NULL,
    acknowledged_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
