-- Enable PostGIS extension
CREATE EXTENSION IF NOT EXISTS postgis;

-- 1. bus_telemetry: Stores real-time vehicle locations and load status.
CREATE TABLE bus_telemetry (
    id BIGSERIAL PRIMARY KEY,
    vehicle_plate VARCHAR(20) NOT NULL,
    service_no VARCHAR(10) NOT NULL,
    direction INTEGER NOT NULL CHECK (direction IN (1, 2)),
    location GEOMETRY(Point, 4326) NOT NULL,
    speed NUMERIC(5,2),
    passenger_load VARCHAR(10) CHECK (passenger_load IN ('SEA', 'SDA', 'LSD')),
    timestamp TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_bus_telemetry_location ON bus_telemetry USING GIST (location);
CREATE INDEX idx_bus_telemetry_timestamp ON bus_telemetry (timestamp);

-- 2. route_geometries: Defines bus route polylines and stop sequences.
CREATE TABLE route_geometries (
    id BIGSERIAL PRIMARY KEY,
    service_no VARCHAR(10) NOT NULL,
    direction INTEGER NOT NULL CHECK (direction IN (1, 2)),
    route_type VARCHAR(20) NOT NULL CHECK (route_type IN ('polyline', 'stop')),
    stop_sequence INTEGER,
    geometry GEOMETRY(Geometry, 4326) NOT NULL
);
CREATE INDEX idx_route_geometries_geom ON route_geometries USING GIST (geometry);
CREATE INDEX idx_route_geometries_service ON route_geometries (service_no, direction);

-- 3. traffic_incidents: Captures anomalies, road blocks, and emergencies.
CREATE TABLE traffic_incidents (
    id BIGSERIAL PRIMARY KEY,
    incident_type VARCHAR(50) NOT NULL,
    description TEXT,
    location GEOMETRY(Geometry, 4326) NOT NULL,
    severity VARCHAR(20),
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_traffic_incidents_location ON traffic_incidents USING GIST (location);
CREATE INDEX idx_traffic_incidents_status ON traffic_incidents (status);

-- 4. mitigation_directives: AI-generated action plans pending HITL approval.
CREATE TABLE mitigation_directives (
    id BIGSERIAL PRIMARY KEY,
    incident_id BIGINT REFERENCES traffic_incidents(id) ON DELETE CASCADE,
    directive_type VARCHAR(50) NOT NULL,
    confidence_score NUMERIC(4,3),
    root_cause_hypothesis TEXT,
    affected_services TEXT[],
    status VARCHAR(20) NOT NULL DEFAULT 'pending_approval' CHECK (status IN ('pending_approval', 'approved', 'rejected', 'completed')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 5. controller_audit_trail: Immutable log of OCC controller decisions.
CREATE TABLE controller_audit_trail (
    id BIGSERIAL PRIMARY KEY,
    directive_id BIGINT REFERENCES mitigation_directives(id) ON DELETE RESTRICT,
    controller_id VARCHAR(50) NOT NULL,
    action_taken VARCHAR(20) NOT NULL CHECK (action_taken IN ('approved', 'overridden', 'rejected')),
    rationale TEXT,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
