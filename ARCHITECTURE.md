# System Architecture & Technical Specifications

## 1. High-Level Architecture

The OCC platform is designed as an **event-driven, closed-loop decision architecture** separating high-frequency data ingestion from agentic reasoning.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            EXTERNAL TELEMETRY                               │
│       LTA DataMall v3 (BusArrival, Traffic, Rail) & PUB Weather APIs         │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ 60-second cycle
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SPRING BOOT 4.1 (JAVA 25) BACKEND                        │
│                                                                             │
│  ┌─────────────────────────┐         ┌───────────────────────────────────┐  │
│  │ Scheduled Poller Worker │ ──────▶ │ Deterministic Noise Gatekeeper    │  │
│  └─────────────────────────┘         └─────────────────┬─────────────────┘  │
│                                                        │ Breach detected?    │
│             ┌──────────────────────────────────────────┴─────────┐          │
│             │ NO (Normal)                                        │ YES       │
│             ▼                                                    ▼          │
│  ┌───────────────────────┐                           ┌───────────────────┐  │
│  │ PostgreSQL + PostGIS  │ ◀──────────────────────── │ Spring AI Agent   │  │
│  │ (Telemetry & Geoms)   │   Deterministic Tools     │ Reasoning Loop    │  │
│  └───────────────────────┘                           └─────────┬─────────┘  │
│             ▲                                                  │ Directives │
│             │ Audit Trail                                      ▼            │
│             │                                        ┌───────────────────┐  │
│             └─────────────────────────────────────── │ HITL Queue & STOMP│  │
│                                                      └─────────┬─────────┘  │
└────────────────────────────────────────────────────────────────┼────────────┘
                                                                 │ WebSocket
                                   ┌─────────────────────────────┴────────────┐
                                   ▼                                          ▼
                      ┌────────────────────────┐                 ┌────────────────────────┐
                      │ OCC Controller Console │                 │ Bus Captain Terminal   │
                      │ (Incident Map & Queue) │                 │ (IVU Dynamic Pacing)   │
                      └────────────────────────┘                 └────────────────────────┘
```

---

## 2. PostgreSQL + PostGIS Database Schema

PostGIS is critical for computing spatial buffers (500m route corridors) and double-decker clearance validations.

```sql
-- Enable PostGIS extension
CREATE EXTENSION IF NOT EXISTS postgis;

-- 1. Bus Fleet Telemetry Snapshot
CREATE TABLE bus_telemetry (
    id BIGSERIAL PRIMARY KEY,
    service_no VARCHAR(10) NOT NULL,
    bus_plate VARCHAR(15) NOT NULL,
    current_stop_id VARCHAR(20),
    next_stop_id VARCHAR(20),
    headway_seconds INT,
    load_status VARCHAR(10), -- 'SEA' (Seats Available), 'SDA' (Standing Available), 'LSD' (Limited Standing)
    vehicle_type VARCHAR(10), -- 'SD' (Single Deck), 'DD' (Double Deck), 'BD' (Bendy)
    location GEOMETRY(Point, 4326),
    recorded_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_bus_telemetry_geom ON bus_telemetry USING GIST(location);
CREATE INDEX idx_bus_telemetry_service ON bus_telemetry(service_no);

-- 2. Route Geometries & Downstream Stops
CREATE TABLE route_geometries (
    service_no VARCHAR(10) NOT NULL,
    direction INT NOT NULL,
    route_polyline GEOMETRY(LineString, 4326),
    corridor_buffer_500m GEOMETRY(Polygon, 4326), -- Precomputed ST_Buffer(geom::geography, 500)
    stop_sequences JSONB, -- Array of ordered bus stop IDs and coordinates
    PRIMARY KEY(service_no, direction)
);
CREATE INDEX idx_route_buffer ON route_geometries USING GIST(corridor_buffer_500m);

-- 3. Traffic Incidents & Road Obstructions
CREATE TABLE traffic_incidents (
    incident_id VARCHAR(50) PRIMARY KEY,
    incident_type VARCHAR(30), -- 'ACCIDENT', 'ROADWORK', 'VEHICLE_BREAKDOWN', 'WEATHER_HAZARD'
    message TEXT NOT NULL,
    location GEOMETRY(Point, 4326),
    estimated_clearance_time TIMESTAMP WITH TIME ZONE,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_incidents_geom ON traffic_incidents USING GIST(location);

-- 4. Train Service Alerts (Disruptions)
CREATE TABLE train_service_alerts (
    alert_id VARCHAR(50) PRIMARY KEY,
    line VARCHAR(30) NOT NULL, -- 'EWL', 'NSL', 'DTL', 'NEL', 'TEL', 'CCL'
    affected_stations JSONB,   -- e.g. ["Jurong East", "Clementi", "Buona Vista"]
    status VARCHAR(20),        -- 'DELAYS', 'NO_SERVICE'
    shuttle_bus_activated BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 5. Staged Mitigation Directives (HITL Queue)
CREATE TABLE mitigation_directives (
    directive_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_no VARCHAR(10) NOT NULL,
    bus_plate VARCHAR(15),
    incident_id VARCHAR(50) REFERENCES traffic_incidents(incident_id),
    action_type VARCHAR(30) NOT NULL, -- 'HEADWAY_PACE', 'ALIGHTING_ONLY', 'DYNAMIC_DETOUR', 'BRIDGING_BUS'
    confidence_score NUMERIC(5, 2),   -- e.g., 94.00
    root_cause_hypothesis TEXT,
    tradeoff_summary JSONB,           -- e.g., {"passenger_mins_saved": 420, "donor_route_impact": 1.2}
    payload JSONB NOT NULL,           -- Detailed instruction parameters / detour polylines
    status VARCHAR(20) DEFAULT 'PENDING_APPROVAL', -- 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'EXECUTED'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 6. Controller Audit Trail & Overrides
CREATE TABLE controller_audit_trail (
    audit_id BIGSERIAL PRIMARY KEY,
    directive_id UUID REFERENCES mitigation_directives(directive_id),
    controller_id VARCHAR(50) NOT NULL,
    decision VARCHAR(20) NOT NULL, -- 'APPROVED', 'OVERRIDDEN', 'REJECTED'
    override_reason TEXT,
    action_timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 7. Driver Acknowledgments
CREATE TABLE driver_acknowledgments (
    ack_id BIGSERIAL PRIMARY KEY,
    directive_id UUID REFERENCES mitigation_directives(directive_id),
    bus_plate VARCHAR(15) NOT NULL,
    acknowledged_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

---

## 3. The Deterministic Noise Gatekeeper

The backend poller runs every 60 seconds. Rather than feeding raw feeds to an LLM, the gatekeeper evaluates three deterministic conditions:

```java
public boolean shouldTriggerAgentLoop(BusTelemetry currentBus, BusTelemetry trailingBus, List<TrafficIncident> nearbyIncidents) {
    // 1. Headway Bunching check (< 90 seconds)
    if (trailingBus != null && trailingBus.getHeadwaySeconds() < 90) {
        return true;
    }
    // 2. Headway Gapping check (> 20 minutes / 1200 seconds)
    if (currentBus.getHeadwaySeconds() > 1200) {
        return true;
    }
    // 3. Spatial Route Obstruction check (Incident within 500m of corridor)
    if (!nearbyIncidents.isEmpty()) {
        return true;
    }
    // 4. Driver SOS flag
    if (currentBus.isSosTriggered()) {
        return true;
    }
    // All normal -> bypass LLM
    return false;
}
```

---

## 4. Multi-Agent Tool Contracts (Spring AI `@Tool`)

Agents never invoke third-party APIs. They query the local database through schema-constrained tools:

### Tool 1: `getRouteGeometry`
```json
{
  "name": "getRouteGeometry",
  "description": "Retrieves active route polyline coordinates, direction, and ordered stop sequences from PostGIS.",
  "parameters": {
    "type": "object",
    "properties": {
      "serviceNo": { "type": "string", "description": "e.g. 190 or 858" },
      "direction": { "type": "integer", "description": "1 or 2" }
    },
    "required": ["serviceNo", "direction"]
  }
}
```

### Tool 2: `calculateDetourPath`
```json
{
  "name": "calculateDetourPath",
  "description": "Computes an alternate bypass route avoiding blocked road segments while validating vehicle height clearance for double-decker buses.",
  "parameters": {
    "type": "object",
    "properties": {
      "serviceNo": { "type": "string" },
      "blockedLat": { "type": "number" },
      "blockedLng": { "type": "number" },
      "isDoubleDecker": { "type": "boolean" }
    },
    "required": ["serviceNo", "blockedLat", "blockedLng", "isDoubleDecker"]
  }
}
```

### Tool 3: `getNearbyDepotStandbyBuses` & `queryAvailableDrivers`
```json
{
  "name": "getNearbyDepotStandbyBuses",
  "description": "Evaluates idle standby buffer buses in nearby depots and verifies driver rest-time compliance.",
  "parameters": {
    "type": "object",
    "properties": {
      "region": { "type": "string", "description": "e.g. 'NORTH', 'WEST', 'CENTRAL'" },
      "capacityRequirement": { "type": "string", "enum": ["SINGLE_DECK", "DOUBLE_DECK"] }
    },
    "required": ["region"]
  }
}
```

### Tool 4: `simulateTradeoffs`
```json
{
  "name": "simulateTradeoffs",
  "description": "Simulates passenger-minute savings on the target disrupted route against delay additions on donor routes.",
  "parameters": {
    "type": "object",
    "properties": {
      "actionType": { "type": "string", "enum": ["HOLD_DWELL", "SKIP_STOP", "INJECT_BUS", "DETOUR"] },
      "serviceNo": { "type": "string" },
      "targetBusPlate": { "type": "string" }
    },
    "required": ["actionType", "serviceNo"]
  }
}
```

---

## 5. WebSocket STOMP Messaging Topics

| Destination Topic | Producer | Consumer | Payload Description |
| :--- | :--- | :--- | :--- |
| `/topic/occ/anomalies` | Gatekeeper / Agent | Controller Dashboard | Emits staged Incident Cards (US-1, US-2, US-3) |
| `/topic/occ/fleet-status` | Backend Poller | Controller Dashboard | Live map telemetry coordinates for active fleet (US-8) |
| `/topic/ivu/{busPlate}` | HITL Approval Service | Bus Captain Terminal | Dispatches approved pacing instructions or detours (US-9, US-10) |
| `/app/ivu/acknowledge` | Bus Captain Terminal | Backend / Audit Service | Bus captain 1-tap acknowledgment payload (US-13, US-6) |
| `/app/occ/review-action` | Controller Dashboard | Backend Directives Service | Controller 1-click Approve or Override decision (US-4, US-5) |

---

## 6. Frontend View Architecture

### 6.1 OCC Controller Dashboard (`/console`)
- **Left Panel (Event Queue):** Ranked anomalous incident cards. Displays root-cause hypothesis, confidence score badge (e.g. `94%`), and 1-click action buttons.
- **Center Canvas (Geospatial Map):** React-Leaflet base map rendering active bus positions (color-coded by load: SEA, SDA, LSD), route corridors, traffic incidents, and proposed detour polylines.
- **Right Drawer (Auditable Evidence):** Historical headway movement charts, traffic camera snapshots, and downstream impact projections.

### 6.2 Bus Captain Terminal (`/ivu/:plate`)
- Clean, high-contrast, distraction-free interface.
- **Dynamic Pacing Banner:** Amber/Blue indicators (`HOLD DWELL TIME +45s`).
- **Interactive Leaflet Mini-Map:** Highlighted red blocked road segment vs. green bypass path with turn-by-turn guidance.
- **One-Tap Confirm Button:** Big, accessible acknowledgment button committing to the audit trail.