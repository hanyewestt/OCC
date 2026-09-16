# AGENTS.md — OCC Project Instructions & Vibe Coding Guardrails

## 1. Project Mission & Identity
You are an expert full-stack AI engineer pair-programming on **OCC (Autonomous Operations Control Center)**: an event-driven, closed-loop AI multi-agent platform for Singapore public bus operations (SMRT / SBS Transit / LTA Bus 4.0).
The system shifts bus service controllers from passive CCTV monitors to strategic overseers by autonomously detecting anomalies, diagnosing root causes, and staging verified mitigation directives (headway pacing, skip-stops, dynamic detours, bridging buses).

---

## 2. Non-Negotiable Architectural Golden Rules

> [!CRITICAL]
> ### RULE 1: Agents NEVER Call External APIs Directly
> - Agent tools **must read from PostgreSQL/PostGIS**, NEVER from external LTA DataMall or PUB Weather endpoints directly.
> - A scheduled backend poller writes external telemetry into the database every 60s.
> - Reason: Direct API calls inside an LLM reasoning loop introduce unbounded latency, rate-limit failures mid-reasoning, and non-reproducible test runs. Reading from Postgres enables instantaneous tool execution and historical replay testing.

> [!CRITICAL]
> ### RULE 2: Deterministic Noise Gatekeeper Runs Before Any LLM
> - Do not invoke LLMs / AI agents for normal, compliant bus runs.
> - Anomaly evaluation must pass through deterministic code filters first:
>   - **Headway Bunching:** Consecutive buses with headway < 90 seconds.
>   - **Headway Gapping:** Headway gap exceeding 20 minutes.
>   - **Corridor Obstruction:** Spatial buffer intersection of incidents within 500m of active route polyline.
>   - **Direct Driver Flags:** SOS signals or sudden emergency transit halts.
> - If no anomaly threshold is breached, commit telemetry directly to PostgreSQL and terminate the loop with **0 LLM tokens burned**.

> [!CRITICAL]
> ### RULE 3: Human-In-The-Loop (HITL) Safety Segregation
> - **Non-destructive actions** (passenger wait-time announcements, signage updates, informational alerts) can execute automatically.
> - **Mutating operational actions** (route diversions, bridging bus dispatches, skip-stop sequences) **MUST** stage into the OCC Controller Approval Queue and require a 1-click human confirmation before dispatching to Bus Captain IVUs.

---

## 3. Technology Stack & Directory Structure

```
OCC/
├── backend/                  # Spring Boot 4.1 (Java 25)
│   ├── src/main/java/sg/gov/lta/occ/
│   │   ├── poller/           # Scheduled 60s LTA DataMall & Weather pollers
│   │   ├── gatekeeper/       # Deterministic noise filter (Headway & 500m PostGIS buffer)
│   │   ├── agent/            # Spring AI / LangChain4j multi-agent orchestrator & tools
│   │   │   ├── tools/        # Schema-constrained Java @Tool functions
│   │   │   └── prompts/      # Structured system prompts & few-shot examples
│   │   ├── model/            # JPA Entities & PostGIS geometry types
│   │   ├── repository/       # Spring Data JPA / Spatial repositories
│   │   ├── controller/       # REST API endpoints (HITL approval queue, replay)
│   │   ├── websocket/        # STOMP WebSocket handlers (/topic/occ-incidents, /topic/ivu/{plate})
│   │   └── audit/            # PostgreSQL audit logging for controller overrides
│   └── pom.xml (or build.gradle)
├── frontend/                 # React 18 / Vite + TypeScript
│   ├── src/
│   │   ├── components/
│   │   │   ├── occ/          # Controller Dashboard (Incident Cards, Evidence Drawer, Network Map)
│   │   │   └── ivu/          # Bus Captain In-Cockpit Driver Terminal (Pacing & Detour Map)
│   │   ├── hooks/            # WebSocket subscriptions & real-time telemetry
│   │   └── types/            # TypeScript interfaces mirrored from backend DTOs
└── docs/                     # PRD.md, ARCHITECTURE.md, TASK.md
```

---

## 4. Coding Conventions & Quality Standards

### Backend (Spring Boot 4.1 + Java 25)
- **Use Modern Java**: Favor `record` for immutable DTOs, pattern matching for switch, and virtual threads (`Thread.ofVirtual()`) where applicable.
- **Spatial Queries**: Use Hibernate Spatial with PostGIS `org.locationtech.jts.geom.Geometry` and `ST_DWithin` for spatial buffer matches.
- **Agent Tools Contract**: All agent tools must be strongly typed with `@Tool` annotations and detailed descriptions matching JSON schema contracts.
- **Defensive Error Handling**: Wrap external pollers in resilient retry policies with circuit breakers. Never crash the scheduled poller due to a single malformed LTA record.

### Frontend (React + TypeScript + Leaflet)
- **Aesthetic Excellence**: Clean, high-density, dark-mode command center UI for OCC controllers. High-contrast, minimal cognitive load interface for Bus Captains.
- **Map Visualizations**: Use `react-leaflet` with Singapore SVY21 or standard WGS84 coordinates. Blocked route segments must render in red (`#EF4444`); approved detours in high-visibility green (`#10B981`).
- **One-Tap Actions**: Controller approval and driver acknowledgment buttons must be large, responsive, and provide immediate visual state feedback.

---

## 5. Definition of Done (DoD) for AI Tasks
When implementing features from [TASK.md](file:///c:/Users/Vinz/Desktop/OCC/OCC/TASK.md):
1. **Atomic Execution**: Implement one checklist item at a time.
2. **Schema Integrity**: Ensure frontend TypeScript types strictly match backend DTO records.
3. **Audit Trail**: Every mutating directive must have an audit log entry in PostgreSQL capturing timestamp, action taken, and whether approved or overridden.
4. **Verification**: Run unit tests or local verification steps before declaring a task completed.
