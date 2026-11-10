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

## Provisioning the EC2 CD deployment

The deployment runs on one **t3.small (2 GiB)** in **us-east-1** behind a
CloudFront distribution that serves browser HTTPS with the AWS-provided
`*.cloudfront.net` certificate. Browser TLS terminates at the edge; the EC2
origin speaks plain HTTP restricted by two independent controls: the
security group only admits the AWS-managed
`com.amazonaws.global.cloudfront.origin-facing` prefix list, and every origin
request must carry the deployment's `X-OCC-Origin-Secret` header, checked at
Nginx. The origin header value never reaches the applications or the browser,
and viewer identity (client address, protocol) is taken only from
CloudFront's unforgeable injected headers.

`infra/aws/ec2-cd.yaml` provisions the VPC, host, encrypted root and retained
data volumes (20 GiB each, standard T3 credits), Elastic IP, encrypted and
versioned private S3 release bucket, five immutable ECR repositories,
Secrets Manager deployment and origin secrets, repository-scoped OIDC roles,
and the CloudFront distribution with caching and error caching fully
disabled. `scripts/cd/bootstrap.ps1` drives it as a reviewed change set:
it verifies AWS and GitHub identities first, resolves the existing OIDC
provider and the CloudFront prefix list, imports the existing root `.env`
through a temporary payload file (values are never printed, logged or
committed; the local file stays unchanged), records the secret version, and
configures the main-only GitHub `production` environment with the publish and
deploy role ARNs without per-push reviewers.

`scripts/cd/bootstrap-host.sh` prepares the host through SSM: Docker and
Compose, the durable data volume mounted by validated UUID before Docker or
PostgreSQL can start (a missing mount blocks startup — there is no root-disk
fallback, and an existing retained volume is never reformatted), root-only
secret files, and the ECR login.

Deployment layers the Compose files as `compose.yaml` +
`infra/compose.images.yaml` (immutable digest pins, no source/dist mounts)
plus either `infra/compose.production.yaml` (direct TLS, unchanged behavior)
or `infra/compose.cloudfront.yaml` (CloudFront origin). The CloudFront overlay
requires no TLS-file configuration. All Nginx caching and error-caching TTLs
start at zero, required verbs plus Authorization/cookies/query forwarding are
preserved, and the pacing SSE streams stay unbuffered; the origin read
timeout (120s) is set above the measured local heartbeat gap, and real
CloudFront rollout evidence for long-lived streams remains a
deployment-time check.

Actual provisioning requires verified AWS permissions, current cost review
and explicit authorization; nothing in this repository creates cloud
resources on its own.
