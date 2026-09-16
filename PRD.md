# Product Requirements Document (PRD) — Autonomous OCC

**Project Name:** AI-Driven Autonomous Operations Control Center (OCC)  
**Target Domain:** Singapore Public Bus Transit Operations (LTA Bus 4.0 / SMRT / SBS Transit)  
**Document Status:** Approved Baseline  
**Target Users:** LTA OCC Service Controllers, Public Bus Captains, Commuters (Downstream)

---

## 1. Executive Summary & Problem Statement

### 1.1 Background
On 28 August 2026, LTA launched the new Mobility Command and Control Centre under the **Bus 4.0** initiative at Gali Batu Depot. The transition moved transit operations from fragmented manual monitoring to **Integrated Digital Monitoring**, giving controllers visibility across Singapore’s bus network and surfacing anomalies like bus bunching.

### 1.2 The Core Operational Pain Point
While Bus 4.0 alerts controllers that an anomaly has occurred, it leaves the most expensive, error-prone bottleneck untouched: **manual human diagnosis and intervention**.
- **Diagnosis Lag:** A controller (e.g., *Jason Chong*) must cross-examine CCTV feeds, traffic incident logs, and weather maps to answer *"why did buses bunch?"*.
- **Cognitive Overload:** With dozens of simultaneous incidents and 50+ buses per controller, mental headway calculations and radio communications become overwhelming.
- **Action Paralysis:** When an obstruction or congestion strikes, controllers are often left with no tools other than passively accepting delays.

### 1.3 The Solution Vision
The Autonomous OCC shifts the controller’s role from **manual investigator** to **strategic overseer**. It implements an intelligent operational layer that autonomously detects anomalies, cross-correlates causes, chains deterministic tools, and stages verified, one-click mitigation playbooks before a human ever touches the screen.

---

## 2. Key Personas

| Persona | Role | Primary Goals & Constraints |
| :--- | :--- | :--- |
| **Persona 1: Jason Chong** | LTA OCC Service Controller | Oversees 50–120+ buses. Needs automated root-cause diagnosis, auditable evidence, and 1-click approvals for route mitigations without UI clutter. |
| **Persona 2: Bus Captain** | Public Bus Driver | Focuses on road safety and passenger service. Needs minimal distraction, clear dynamic headway pacing (dwell times, target speeds), and turn-by-turn detours on In-Vehicle Units (IVU). |
| **Persona 3: Commuter (Implicit)** | Public Transit Passenger | Expects accurate arrival times on display boards/apps and fast bridging bus deployment during train or road disruptions. |

---

## 3. The 5-Stage Closed-Loop Decision Architecture

```
[ Stage 1: Polling & Noise Gatekeeper ] ──(Threshold breach: Headway <90s or >20m, 500m buffer)──┐
                                                                                                  ▼
[ Stage 5: Execution & Audit Logging ] ◄── [ Stage 4: HITL Approval ] ◄── [ Stage 3: Tool Chaining ] ◄── [ Stage 2: AI Parsing & Correlation ]
 (Pushed to IVU / Logged to Postgres)      (1-Click Approve/Override)      (Detour, Polyline, Standby)      (Incident text, SEA/SDA loads)
```

1. **Backend Telemetry Polling & Deterministic Noise Gatekeeper:**
   - Polls LTA DataMall (BusArrivalv3, TrafficIncidents, TrainServiceAlerts) and PUB Weather endpoints every 60 seconds.
   - Applies deterministic filters: Bunching (< 90s), Gapping (> 20m), Spatial Corridor match (500m buffer), Driver SOS.
   - Normal conditions commit to PostgreSQL and terminate loop (0 LLM cost).
2. **Autonomous Context Correlation & Semantic Parsing:**
   - Unstructured incident text (e.g. fallen tree, lane 1-2 blocked, heavy rain) is parsed by the AI agent.
   - Correlates affected route segments with bus passenger loading (`SEA`: Seats Available, `SDA`: Standing Available, `LSD`: Limited Standing) and rail disruptions.
3. **Agentic Multi-Step Tool Chaining:**
   - Runs deterministic backend tools via schema-constrained JSON calling:
     - `getRouteGeometry(serviceNo)`
     - `calculateDetourPath(blockedPoint)` (validating double-decker height clearance)
     - `getNearbyDepotStandbyBuses(region)` / `queryAvailableDrivers(depotId)`
     - `simulateTradeoffs(action)` (passenger-minute savings vs donor route impact)
4. **Staged Human-in-the-Loop (HITL) Decision Queue:**
   - Non-destructive actions (passenger announcements) execute automatically.
   - Mutating actions (detours, skip-stops, bridging bus injection) stage into an approval queue with confidence score, root cause, and operational rationale.
5. **Closed-Loop Execution & Controller Feedback:**
   - **Approve:** Directives push in real time to Bus Captain's IVU via WebSocket.
   - **Override / Reject:** Controller notes and override actions log to PostgreSQL audit trail for post-incident retrospective and prompt tuning.

---

## 4. Complete User Stories & Acceptance Criteria

### Epic 1: Anomaly Detection
- **US-1 (Priority: E.High):** As a controller, I want the system to automatically detect bus bunching so that I don't have to manually monitor hundreds of CCTV feeds.  
  *Acceptance Criteria:* When headway < 90s is detected, alert fires within 5 seconds, including route and bus IDs.
- **US-7 (Priority: Low):** As a controller, I want visibility into what the AI is filtering out (routine noise) so that I trust the system and understand its decision boundaries.  
  *Acceptance Criteria:* Noise dashboard displays current thresholds, allows threshold tuning, and logs filtering decisions.
- **US-16 (Priority: High):** As a controller, I want to be informed of real-time incidents such as traffic accidents or train breakdowns.  
  *Acceptance Criteria:* AI surfaces real-time status of road accidents and train breakdowns intersecting transit corridors.

### Epic 2: AI Context Correlation
- **US-2 (Priority: High):** As a controller, I want the system to diagnose why buses are bunching (traffic, incident, crowding) so that I can stop investigating and start solving.  
  *Acceptance Criteria:* Alert includes root cause hypothesis + confidence score (e.g. 94%), with links/thumbnails to relevant traffic camera feeds.
- **US-8 (Priority: High):** As a controller, I want to be able to see real-time feeds on the buses I am in charge of via a dashboard.  
  *Acceptance Criteria:* Interactive map displaying live bus locations, route polylines, and traffic speed bands along active sectors.

### Epic 3: Mitigation Planning
- **US-3 (Priority: High):** As a controller, I want the system to recommend mitigation actions before I ask so that I spend less time thinking about what to do.  
  *Acceptance Criteria:* Recommendation includes proposed action (e.g., Hold B12 for 90s, deploy standby bus from Depot X), expected passenger-minute savings, operational trade-offs, and 1-click approval button.

### Epic 4: Human-in-the-Loop (HITL) Decision Queue
- **US-4 (Priority: High):** As a controller, I want to approve or reject recommendations with a single click so that high-priority situations don't get delayed by UI friction.  
  *Acceptance Criteria:* Approve/reject latency < 2 seconds; directive executes and dispatches immediately upon approval.
- **US-5 (Priority: High):** As a controller, I want to override AI recommendations with my own judgment so that I maintain command during complex, high-impact situations.  
  *Acceptance Criteria:* Override modal captures controller rationale and logs to PostgreSQL audit trail for sprint retrospectives.
- **US-6 (Priority: High):** As a controller, I want to see real-time bus captain confirmations of executed directives so that I know actions are actually happening in the field.  
  *Acceptance Criteria:* Execution status in OCC UI updates within 10 seconds of bus captain acknowledgment.

### Epic 5: Bus Captain Communication (In-Vehicle Unit / IVU)
- **US-9 (Priority: High):** As a bus captain, I want to receive clear, actionable pacing instructions on my IVU so that I don't have to interpret ambiguous radio chatter during high-stress traffic.  
  *Acceptance Criteria:* IVU displays target speed, distance to next stop/landmark, dynamic dwell time adjustment (+45s hold), and reason; updates every 10 seconds.
- **US-10 (Priority: Medium):** As a bus captain, I want turn-by-turn alternate route navigation when my normal route is blocked so that I can confidently detour without guessing street names.  
  *Acceptance Criteria:* Detour polyline uploads to IVU mini-map, pre-verified for double-decker vehicle height clearances, with ETA to rejoin normal route.
- **US-11 (Priority: Medium):** As a bus captain, I want skip-stop sequences clearly communicated so that I execute load-balancing instructions safely.  
  *Acceptance Criteria:* Skip-stop instructions grouped visually on IVU (e.g., "Alighting Only at Stop #44131"), marked clearly as temporary vs. permanent.
- **US-12 (Priority: Low):** As a bus captain, I want the system to prioritize my safety and focus so that I'm not distracted by excessive notifications during driving.  
  *Acceptance Criteria:* Non-critical alerts batch silently; critical safety alerts (sudden route blockages) trigger distinct audio/tactile cues.
- **US-13 (Priority: High):** As a bus captain, I want to confirm execution of directives with a single button press so that I can keep both hands on the wheel.  
  *Acceptance Criteria:* Large, touch-friendly "Acknowledge Directive" button sending instant confirmation back to OCC.

### Epic 6: Passenger Notifications
- **US-14 (Priority: Low):** As a passenger, I want accurate wait-time announcements when my bus is delayed so that I can adjust my plans.  
  *Acceptance Criteria:* System automatically publishes adjusted wait-time updates to passenger feeds when headway bunching occurs.
- **US-15 (Priority: Low):** As a passenger, I want the system to proactively dispatch bridging buses during major disruptions so that I'm not left stranded with a 30+ minute wait.  
  *Acceptance Criteria:* When headway gap exceeds 20 minutes due to rail breakdown or major obstruction, standby depot buses are staged and dispatch ETAs broadcasted.

---

## 5. UI Specifications

### 5.1 OCC Intelligent Incident Card
When an anomaly triggers, the incident card renders four structured sections:
1. **What happened:** e.g., `Route 858 — Bus B12/B13 Bunching Detected (Headway: 42s)`
2. **Why it is happening:** e.g., `Traffic congestion following accident 1.2km ahead at CTE exit` (Confidence: 94%)
3. **Who/what is affected:** e.g., `Passenger Impact: High | Projected Headway Deviation: +8 min | Trailing Load: LSD`
4. **What can be done:** e.g., `Hold B12 for 90 sec at Stop #59011; Deploy Standby Bus from Woodlands Depot`

### 5.2 Bus Captain Terminal States
- **State A (Headway Pacing & Alighting-Only Mode):** Displays header (Bus No, Reg Plate, Next Stop), pacing status card (`HOLD DWELL TIME +45s`), and live directive (`⚠️ ACTION REQUIRED: ALIGHTING ONLY`) with a high-visibility acknowledge button.
- **State B (Dynamic Corridor Diversion):** Displays incident banner (`⚠️ DETOUR DIRECTIVE #104 - Avoid Stevens Rd`), embedded React-Leaflet mini-map with red blocked polyline and green detour route, next maneuver instruction, and one-tap acknowledgment.

---

## 6. Definition of Done (DoD)
A user story is officially **Done** only when:
- [ ] Acceptance criteria are met and covered by automated unit/integration tests.
- [ ] Code is reviewed, formatted, and merged to the main branch.
- [ ] Controller or Bus Captain can perform the workflow end-to-end in the UI.
- [ ] Telemetry and decisions are recorded in the PostgreSQL audit trail.
- [ ] Documented in the OCC operational runbook.
