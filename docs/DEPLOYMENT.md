# Deployment

OCC deploys as one Docker Compose stack on a single host: PostgreSQL/PostGIS,
the three JVM services (ingestion, disruption, planning), the combined
db-setup migration image and the Nginx edge. This document describes the
deployment model and the commit triggers that feed it. Infrastructure
provisioning, lifecycle controls and recovery procedures are added as the
EC2 CD work lands.

## Deployment triggers

Deployments are driven by `main` commits and classified by behavior, not file
extension:

| Changed input | Consequence |
| --- | --- |
| One service's source, POM or committed OpenAPI contract | That service's image rebuilds |
| A service's migration resources | That service's image and the combined db-setup image rebuild |
| `infra/flyway/**`, `infra/flyway.Dockerfile`, `infra/db-setup.sh`, `infra/postgres/bootstrap.sql` | The combined db-setup image rebuilds |
| Root POM, Maven wrapper or shared Swagger UI | All three service images rebuild |
| Frontend production inputs or Nginx templates | The web edge image rebuilds |
| Compose, CloudFront or deployment runtime wiring | A release runs; only images containing changed inputs rebuild |
| Docs, tests or local launchers only | Applicable checks run; no release or deployment |

The pipeline builds exactly five OCC images per release: `ingestion`,
`disruption`, `planning`, `db-setup` and `web`. PostgreSQL/PostGIS is a
separately pinned upstream image and is never built by OCC.

Deployment-affecting commits — including workflow, build-tooling, runtime
configuration, migration and CD-script changes with no service edits — must
run CI/CD. Only exclusively documentation, agent-prose or local-launcher
commits carry `[skip ci]`; see [commit convention](commit-convention.md) for
the full rule and for recovery when a skip marker is used by mistake.
