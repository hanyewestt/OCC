# Implementation Task Tracker (TASK.md)

This tracker breaks the development of the **Autonomous OCC** into atomic, checkable tasks aligned with the 16 user stories and architectural specs.

> **AI Working Rule:**  
> Pick **one** sub-task at a time. Implement the code, verify it with tests, mark it `[x]`, and pause for user review before moving to the next item.

---

## Phase 1: Infrastructure & Data Foundations
- [ ] **1.1 Database & PostGIS Setup**
  - [ ] Create `docker-compose.yml` with `postgis/postgis:16-3.4` container.
  - [ ] Initialize DDL schema scripts (`bus_telemetry`, `route_geometries`, `traffic_incidents`, `mitigation_directives`, `controller_audit_trail`).
- [ ] **1.2 Spring Boot 4.1 Project Scaffolding**
  - [ ] Initialize Spring Boot 4.1 project with Java 25 (`spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-websocket`, `hibernate-spatial`).
  - [ ] Add Spring AI (or LangChain4j) dependency and configure model provider.
- [ ] **1.3 Mock LTA DataMall Data Seeder**
  - [ ] Create synthetic Singapore bus route geometries (e.g. Route 190 and Route 858 polylines and stops).
  - [ ] Seed sample traffic incidents (e.g. fallen tree along Stevens Rd) and train service alerts.

---

## Phase 2: Telemetry Poller & Noise Gatekeeper (US-1, US-7, US-16)
- [ ] **2.1 Scheduled Telemetry Ingestion Worker**
  - [ ] Implement `@Scheduled(fixedRate = 60000)` poller service writing to `bus_telemetry` table.
  - [ ] Ensure DB ingestion wraps in transactional batches.
- [ ] **2.2 Deterministic Noise Gatekeeper Service**
  - [ ] Implement headway bunching (<90s) and gapping (>20m) calculation logic ([US-1](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md#user-content-epic-1-anomaly-detection)).
  - [ ] Implement PostGIS spatial corridor buffer check (`ST_DWithin` 500m of route polyline).
  - [ ] Add bypass logic: Normal runs commit to Postgres and terminate without LLM invocation ([US-7](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md#user-content-epic-1-anomaly-detection)).
  - [ ] Write JUnit 5 tests verifying gatekeeper filtering with 100% threshold accuracy.

---

## Phase 3: AI Agent Tool Chaining & Context Correlation (US-2, US-3)
- [ ] **3.1 PostGIS Tool Suite Implementation**
  - [ ] `@Tool getRouteGeometry(serviceNo, direction)`: Returns polyline coordinates and stop sequence.
  - [ ] `@Tool calculateDetourPath(serviceNo, blockedPoint, isDoubleDecker)`: Computes alternate road segment avoiding bottleneck and verifies double-decker height clearance.
  - [ ] `@Tool getNearbyDepotStandbyBuses(region)` / `queryAvailableDrivers(depotId)`: Evaluates available fleet buffer.
  - [ ] `@Tool simulateTradeoffs(action)`: Calculates projected passenger-minute savings.
- [ ] **3.2 Reasoning & Semantic Parsing Agent**
  - [ ] Implement prompt templates parsing unstructured incident descriptions.
  - [ ] Correlate incident location with fleet passenger load statuses (`SEA`, `SDA`, `LSD`).
  - [ ] Formulate structured `MitigationDirective` with confidence score and root-cause hypothesis ([US-2](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md#user-content-epic-2-ai-context-correlation), [US-3](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md#user-content-epic-3-mitigation-planning)).

---

## Phase 4: OCC Controller Console & HITL Decision Queue (US-4, US-5, US-6, US-8)
- [ ] **4.1 Real-Time WebSocket Infrastructure**
  - [ ] Configure Spring WebSocket with STOMP message broker (`/topic/occ/anomalies`, `/topic/occ/fleet-status`).
- [ ] **4.2 React Frontend Scaffolding (Vite + TypeScript + Tailwind)**
  - [ ] Initialize modern React frontend with Leaflet (`react-leaflet`).
  - [ ] Implement dark-mode command center layout with real-time bus locations ([US-8](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md#user-content-epic-2-ai-context-correlation)).
- [ ] **4.3 Intelligent Incident Card Component**
  - [ ] Render 4-step card: *What happened → Why it happened → Who is affected → What can be done*.
  - [ ] Implement 1-click **Approve** and **Reject** buttons (<2s latency) ([US-4](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md#user-content-epic-4-human-in-the-loop-hitl-decision-queue)).
  - [ ] Implement **Override Modal** capturing controller rationale into `controller_audit_trail` ([US-5](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md#user-content-epic-4-human-in-the-loop-hitl-decision-queue)).
- [ ] **4.4 Evidence Drawer Component**
  - [ ] Render auditable evidence factors: headway deviation graphs, CCTV camera snapshot links, and downstream impact models.

---

## Phase 5: Bus Captain In-Cockpit Terminal (IVU) & Passenger Alerts (US-9 – US-15)
- [ ] **5.1 In-Cockpit Driver Terminal UI**
  - [ ] Build high-contrast, distraction-free driver interface (`/ivu/:plate`).
  - [ ] Implement **State A**: Dynamic Headway Pacing Module (`HOLD DWELL TIME +45s`, Alighting-Only notice) ([US-9](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md#user-content-epic-5-bus-captain-communication-in-vehicle-unit--ivu), [US-11](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md#user-content-epic-5-bus-captain-communication-in-vehicle-unit--ivu)).
  - [ ] Implement **State B**: React-Leaflet Mini-Map Detour Mode (Red blocked road segment vs. Green detour path) ([US-10](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md#user-content-epic-5-bus-captain-communication-in-vehicle-unit--ivu)).
- [ ] **5.2 Driver One-Tap Acknowledgment & Status Synchronization**
  - [ ] Add large, reachable "Acknowledge Directive" touch button ([US-13](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md#user-content-epic-5-bus-captain-communication-in-vehicle-unit--ivu)).
  - [ ] Push acknowledgment back to OCC console, updating controller status within 10s ([US-6](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md#user-content-epic-4-human-in-the-loop-hitl-decision-queue)).
- [ ] **5.3 Downstream Passenger Alert Automation**
  - [ ] Non-destructive passenger announcement publisher triggered automatically ([US-14](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md#user-content-epic-6-passenger-notifications), [US-15](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md#user-content-epic-6-passenger-notifications)).

---

## Phase 6: End-to-End Replay Testing & Polish
- [ ] **6.1 Historical Replay Testing Framework**
  - [ ] Build test runner that replays historical snapshots into PostgreSQL to verify agent outputs deterministically.
- [ ] **6.2 System Runbook & Documentation**
  - [ ] Document operating procedures for OCC controllers and runbook for prompt tuning.
