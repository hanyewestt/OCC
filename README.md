# OCC (Semi-autonomous Operations Control Center)

OCC supports Singapore public bus operations by bringing together transit
telemetry, road incidents, weather hazards and rail disruptions to support
incident diagnosis and recovery planning.

## About app

Controllers monitor fleet arrivals and disruptions, review diagnoses and recovery
recommendations, issue instructions to captains, and manage public service notices.
Simulation tools support testing bunching scenarios and reviewing outcomes before
issuing instructions. Live observations and simulation data are kept distinct.

Administrators manage accounts and review application audit history.

Captains view instructions for their assigned simulation bus, acknowledge them,
and follow route progress and pacing information. The public notice board shows
published service advisories without requiring sign-in.

## Pages and access

| Main page | Purpose | Access |
| --- | --- | --- |
| `/controller` | Fleet monitoring, disruption review and recovery planning | `CONTROLLER` |
| `/admin` | Account administration and application audit history | `ADMIN` |
| `/captain` | Assigned bus route, pacing and instruction acknowledgement | `CAPTAIN` |
| `/notices` | Published public service advisories | Public; no sign-in required |

Sign in at `/login`. Role pages enforce account permissions, and backend APIs
independently enforce access to operational data and actions.

## App components

| Component | Responsibility |
| --- | --- |
| Web frontend | Role-based operations pages, sign-in and public service notices |
| Nginx | Serves the website and routes public API requests directly to the owning service |
| Ingestion | Transit and external-provider data ingestion; session checks and public frontend configuration |
| Disruption | Disruption detection and event handling |
| Planning | Diagnosis and recovery recommendations |
| Supabase Auth | Account sign-in and session lifecycle |

## Documentation

- [Development](docs/DEVELOPMENT.md): setup, startup commands, local routes, frontend development and checks.
- [Authentication](docs/AUTHENTICATION.md): role checks and session behavior.
- [Testing](docs/TESTING.md): automated tests and CI checks.
- [Service conventions](docs/CONVENTIONS.md) and [frontend conventions](docs/frontend-convention.md): engineering standards.
