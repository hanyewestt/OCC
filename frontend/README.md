# OCC web app

A minimal React/TypeScript app using Tailwind and shadcn/ui. It provides
email/password sign-in, exclusive controller and captain pages, and shared
401/403/404 error screens. Nginx serves the static build; Gateway verifies
session and role access before protected content appears.

The Captain page includes the OBU sample pacing display, scenario controls and
day/night mode. It uses no live bus data.

The standalone `/demo.html` page is also available for bunching workflow and
controller recommendation-review verification, using its own sign-in form.

See the [app overview and route table](../README.md#pages-and-access) for page
behavior and permissions. Setup, Vite development, builds, environment settings
and test commands are in [the development guide](../docs/DEVELOPMENT.md).
