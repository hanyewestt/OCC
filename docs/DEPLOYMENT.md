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
data volumes (20 GiB each, standard T3 credits), Elastic IP, a private S3
release bucket encrypted with a rotated customer-managed KMS key, five
immutable ECR repositories, Secrets Manager deployment and origin secrets,
repository-scoped OIDC roles, and a CloudFront distribution fronted by an
AWS WAF web ACL with the managed common rule set, with caching and error
caching fully disabled. `scripts/cd/bootstrap.ps1` drives it as a reviewed change set:
it verifies AWS and GitHub identities first, resolves the existing OIDC
provider and the CloudFront prefix list, imports the existing root `.env`
through a temporary payload file (values are never printed, logged or
committed; the local file stays unchanged), records the secret version, and
configures the main-only GitHub `production` environment with the publish and
deploy role ARNs without per-push reviewers.

The CloudFront origin explicitly uses HTTP port 8080 and forwards GET, HEAD,
OPTIONS, POST, PUT, PATCH and DELETE. The production environment's deployment
branch policy permits exactly `main`. A separate main-only, read-only OIDC
verification role can inspect release objects and ECR candidates; it cannot
write releases, invoke SSM or read deployment secrets. Its ARN, region and
bucket are repository variables; publication/deployment credentials remain
scoped to their trusted jobs. Bootstrap accepts an absolute or repository-relative
`-EnvFile` path and checks every AWS/gh command result.

`scripts/cd/bootstrap-host.sh` prepares the host through SSM: Docker and
Compose, the durable data volume mounted by validated UUID before Docker or
PostgreSQL can start (a missing mount blocks startup — there is no root-disk
fallback, and an existing retained volume is never reformatted), root-only
secret files, and the ECR login.

### Host egress allow-list

The host has no unrestricted outbound rule. Its security group admits HTTPS
(443) only to the customer-managed prefix list `occ-ec2-cd-egress`, plus DNS to
the VPC resolver inside the VPC range. `bootstrap.ps1` refuses to create a
change set unless that list exists, so the stack fails closed instead of
falling back to open egress.

Create the list once per account/region from the deployment's actual
destinations, and refresh it before bootstrap when provider ranges change:

- AWS service endpoints (ECR, S3, Secrets Manager, SSM): the `us-east-1` and
  `GLOBAL` prefixes in `https://ip-ranges.amazonaws.com/ip-ranges.json`.
- Supabase (`*.supabase.co`), LTA DataMall (`datamall2.mytransport.sg`), Gemini
  and the image/package sources (Docker Hub, GitHub, AL2023 repositories): each
  provider's published ranges, or the Cloudflare/AWS ranges that front them.

```bash
aws ec2 create-managed-prefix-list \
  --prefix-list-name occ-ec2-cd-egress --address-family IPv4 --max-entries 1000 \
  --entries Cidr=203.0.113.0/24,Description=example-provider \
            Cidr=198.51.100.0/24,Description=example-provider
```

Verify and refresh entries with `describe-managed-prefix-lists` and
`modify-managed-prefix-list`. Never add `0.0.0.0/0` or `::/0`.

## Lifecycle controls: pause, start/resume and status

**Actions → Environment control → Run workflow** dispatches `start` (alias
`resume`), `pause` or `status` against the production environment. Mutating
controls share the `occ-production-mutation` concurrency group with the
release/deploy workflow, reconcile outstanding remote operations before
acting, and are never cancelled.

`pause` records the paused intent in the bucket's `state/control.json`
before touching anything, then closes traffic, stops Nginx, the three
services and PostgreSQL gracefully through SSM, and finally stops the
instance, waiting for `stopped`. An already stopped or stopping instance
records the intent without any SSM round-trip. A failure anywhere retains
the paused intent, reports the actual instance state and never force-stops
or terminates; the retry simply runs `pause` again. While paused, pushes to
main still verify, build, publish and advance the published pointer — the
release pipeline reports deployment as deferred and never wakes the
instance. Paused EBS, Elastic IP, ECR, S3, Secrets Manager and CloudFront
charges continue; pausing is not a zero-cost state.

`start`/`resume` starts the instance, waits for EC2 and SSM readiness and
the durable data mount, keeps the origin gated, applies the latest eligible
published release through the ordinary deploy path (no rebuild; an already
applied setup/role fingerprint is skipped), and marks the environment
running only after readiness and the public smoke pass. A newer main push
whose verification failed does not block resume: the published pointer names
the last eligible release and any unverified newer changes stay pending. A
failed start retains the paused intent and the origin stays gated; uncertain
database state requires gated recovery, never a false success. `status`
reports the desired mode, actual instance state (flagging a stopped instance
while the desired mode is running as unavailable), the published and
deployed release ids, and any outstanding operation, without printing
secret values.

Actual lifecycle demonstration — two paused main updates publishing without
an EC2 wake, start of the latest verified release without rebuild, repeated
controls, a pause queued during a deploy and database continuity — is an
authorized-rollout check; local tests use synthetic AWS responses only.

## Failure recovery

The deploy pipeline persists an operation intent before every SSM dispatch
and clears it only after a verified terminal result. If an Actions runner is
lost while a command is accepted, the intent remains and blocks further
mutations. Recovery runs `scripts/cd/deploy_remote.py recover` with the same
region, instance, bucket, operation id and sequence; it looks the accepted
invocation up by its operation comment and reconciles it to a terminal
state. Ambiguous outstanding commands (more than one) block new deploys and
pauses until reconciled manually.

The host deployment is CloudFront-mode only: `deploy.sh` layers the base,
immutable-image and CloudFront overlays and requires `OCC_ORIGIN_SECRET`.
Direct-TLS production remains runnable through Compose by hand, but the
automated pipeline deploys only the CloudFront mode. Each release publishes
all five images regardless of which changed — the release contract requires
a complete manifest, and unchanged components reuse their verified input
hashes so they are rebuilt only when their inputs actually changed. The
pipeline ignores any `OCC_*_IMAGE` entries in the imported `.env`; image
references always come from the verified release manifest.

## Budget and cost model

`scripts/cd/cost-estimate.py` computes the always-on monthly estimate from
dated us-east-1 price inputs and bounded planning allowances — the t3.small
instance hours (730 typical, 744 longest-month sensitivity), the public IPv4
address, both 20 GiB gp3 volumes, bounded ECR/S3/backup storage, two Secrets
Manager secrets, a 50 GiB CloudFront HTTPS transfer allowance, the WAF web ACL
with its managed rule group and the release-bucket KMS key. With the 2026-11-10
price inputs the total is **$49.42/month at 730h**, just inside the $50/month
planning target; the longest month remains below it. The estimate uses no
promotional credits; the longest month adds only the extra instance and IPv4
hours.

Excluded from the estimate: tax, paid providers (Supabase, Gemini, DataMall
overages), data-transfer overages beyond the CloudFront allowance, and
budget-alert notification costs. The $50/month target is not a hard cap; a
budget alert requires a notification destination and does not stop charges.
No NAT gateway, ALB or RDS is part of this architecture, and no silent
instance resize is permitted — capacity problems are reported as tradeoffs
before any scope or size change.

Capacity qualification on the real 2 GiB host (cold/warm start with the
retained tuning, full reference import/polling, setup with writers stopped,
image pull/extract, backup/autovacuum/restart, representative authenticated
and SSE load, MemAvailable, cgroup OOM counters, swap growth, standard-credit
depletion) remains an authorized-rollout measurement; the historical idle
sample in [PERFORMANCE.md](PERFORMANCE.md) is not capacity evidence.

## Cleanup and security refresh

Release cleanup retains the active, previous and any queued/paused release
references and the newest database backups; disk or cleanup failures are
reported and can never erase retained data. A security refresh (base image
or dependency CVE fix) runs as a new release with a fresh `releaseId` —
immutable SHA tags are never overwritten — and reuses unchanged components
only after their digests pass the current vulnerability scans.

## Skip-ci policy

Commit classification follows [commit convention](commit-convention.md):
deployment-affecting commits — workflows, Dockerfiles and build tooling,
runtime Compose/Nginx/CloudFront configuration, database setup and
migrations, and CD/lifecycle scripts — always run CI/CD even with no service
or frontend edits; only exclusively documentation, agent-prose or
local-launcher commits carry `[skip ci]`, with manual dispatch as the
recovery when a marker is used by mistake.

Deployment layers the Compose files as `compose.yaml` +
`infra/compose.images.yaml` (immutable digest pins, no source/dist mounts) +
`infra/compose.host-data.yaml` (PostgreSQL data on the retained EBS volume)
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
