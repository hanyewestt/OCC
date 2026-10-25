# OCC web app

A minimal React/TypeScript app using Tailwind and shadcn/ui. It provides
email/password sign-in, exclusive controller and captain pages, and shared
401/403/404 error screens. Nginx serves the static build; Gateway verifies
session and role access before protected content appears.

The Captain page includes the OBU sample pacing display, scenario controls and
day/night mode. Pacing remains built-in sample data. A separate mock-OBU panel
polls Planning for controller-issued instructions for simulated bus `SBS5898U`
and gives the captain one confirmation button. The first acknowledgement stores
the authenticated subject and server time; a page refresh retains that state.

The Controller dashboard includes simulation controls and bunching detections.
Planning & mitigation presents every pending recommendation in an oldest-first
split queue that controllers can filter by service number. Controllers can
approve the AI recommendation or replace it with instructions for up to three
buses and a rationale. The audit log shows the authenticated controller, saved
decision, replacement instructions and rationale from the Planning database.
The simulation supplies a two-bus service 190 event and a three-bus service 972
event so both controller-recommendation forms can be exercised in one run.

See the [app overview and route table](../README.md#pages-and-access) for page
behavior and permissions. Setup, Vite development, builds, environment settings
and test commands are in [the development guide](../docs/DEVELOPMENT.md).
