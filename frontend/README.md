# OCC web app

A minimal React/TypeScript app using Tailwind and shadcn/ui. It provides
email/password sign-in, exclusive controller and captain pages, and shared
401/403/404 error screens. Nginx serves the static build; Gateway verifies
session and role access before protected content appears.

The Captain page includes the OBU sample pacing display, scenario controls and
day/night mode. It uses no live bus data.

The Controller dashboard includes simulation controls, a selectable approximate
live route strip, searchable and sortable live arrivals, and bunching detections.
Operational lists keep a bounded viewport and progressively load later pages.
Visible timestamps use SGT and a 24-hour clock. Planning & mitigation presents
pending recommendations in an oldest-first split queue that controllers can
filter by service number. Diagnosis cards show a concise possible-contributor
summary and optional source evidence. Controllers can approve an AI recommendation
or replace it with instructions for up to three buses and a rationale in a modal;
unsaved edits require explicit discard confirmation. The audit log shows the
authenticated controller, saved decision, replacement instructions and rationale
from the Planning database. It also lists the latest completed or terminally
failed hazard processing per disruption in the selected mode, with completion
times and failure codes. Pending and processing work is excluded from this log;
Disruptions and Planning & mitigation do not display a hazard processing section.
The simulation supplies a two-bus service 190 event
and a three-bus service 972 event so controller workflows can be exercised in one
run.

Public notices are drafted at `/controller/notices`. Selecting a different source
clears affected services, locations and scheduling so details from another hazard
cannot carry over. Wording suggestions remain editable and never save or publish
automatically; a failed suggestion keeps the controller's existing text. Saved
drafts can be copied into a new draft for revision without changing the original.
The copy requires a fresh validity period, and expiry must follow the start time.
Published notices can be corrected or withdrawn. A correction publishes immediately
and replaces the old notice atomically; future start times are rejected. Failed
publication or correction requests keep the current notice/draft available to retry.

The anonymous `/notices` board retains its existing notices and last successful
refresh time when a refresh fails. Retry reloads page zero for refresh errors or
the failed later page for paging errors. The notice region supports keyboard
scrolling, announces loading/results, and wraps long passenger content on narrow
screens. Public reads and board timing configuration do not require staff sign-in.

See the [app overview and route table](../README.md#pages-and-access) for page
behavior and permissions. Setup, Vite development, builds, environment settings
and test commands are in [the development guide](../docs/DEVELOPMENT.md).
