# OCC (Semi-autonomous Operations Control Center)

OCC supports Singapore public bus operations by bringing together transit
telemetry, road incidents, weather hazards and rail disruptions to support
incident diagnosis and recovery planning.

## Current app

The web app currently provides sign-in and a minimal page for each supported
operating role. These pages verify authentication and role access; operational
controls, incident views and dispatch workflows are not yet part of the frontend.

## Pages and access

| Route | Purpose | Access |
| --- | --- | --- |
| `/` | Opens sign-in or the account's own page | Depends on session and role |
| `/login` | Email/password sign-in | Public |
| `/controller` | Controller page | `CONTROLLER` only |
| `/captain` | Captain page | `DRIVER` only; displayed as Captain |
| `/401` | Sign-in required screen with a sign-in action | Public error screen |
| `/403` | Access denied screen with available recovery actions | Public error screen |
| Unknown website URL | Page not found with a return-home action | HTTP 404 |


## App components

| Component | Responsibility |
| --- | --- |
| Web frontend | Sign-in, role pages and shared error screens |
| Nginx | Serves the website and forwards public API requests |
| Gateway | Verifies access tokens, checks page permissions and routes APIs |
| Ingestion | Transit and external-provider data ingestion |
| Disruption | Disruption detection and event handling |
| Planning | Diagnosis and recovery recommendations |
| Supabase Auth | Account sign-in and session lifecycle |

Existing operational APIs remain restricted independently of frontend page
access. Access to the captain page does not grant access to controller APIs.

## Documentation

- [Development](docs/DEVELOPMENT.md): setup, startup commands, local routes, frontend development and checks.
- [Authentication](docs/AUTHENTICATION.md): role checks and session behavior.
