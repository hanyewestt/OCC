# OCC (Autonomous Operations Control Center)

An agentic dispatch and incident mitigation system for Singapore public bus operations (SMRT / SBS Transit / LTA Bus 4.0) that correlates live transit telemetry, road incidents, weather hazards, and rail disruptions to orchestrate real-time fleet adjustments.

---

## 🧭 Spec-Driven Development & Project Blueprint

This repository is configured for **professional vibe coding** with strict architectural guardrails:

- 📋 **[PRD.md](file:///c:/Users/Vinz/Desktop/OCC/OCC/PRD.md)** — Product Requirements Document: Problem statement, personas (Jason Chong, Bus Captains), 5-stage closed loop, and User Stories US-1 through US-16 with Acceptance Criteria.
- 🏛️ **[ARCHITECTURE.md](file:///c:/Users/Vinz/Desktop/OCC/OCC/ARCHITECTURE.md)** — Technical Architecture: Spring Boot 4.1 + Java 25, PostgreSQL + PostGIS spatial schema, Spring AI tool contracts, WebSocket topics, and React-Leaflet UI architecture.
- 🛡️ **[AGENTS.md](file:///c:/Users/Vinz/Desktop/OCC/OCC/AGENTS.md)** — Agent Rules of Engagement: Strict guardrails (Agents never call DataMall directly; poller writes to Postgres; deterministic gatekeeper before LLM).
- 🚀 **[TASK.md](file:///c:/Users/Vinz/Desktop/OCC/OCC/TASK.md)** — Sprint Implementation Checklist: Phased, checkable milestones for AI pair-programming.
