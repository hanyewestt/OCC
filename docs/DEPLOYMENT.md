# Deployment

OCC deploys as one Docker Compose stack on a single host: PostgreSQL/PostGIS,
the three JVM services (ingestion, disruption, planning), the combined
db-setup migration image and the Nginx edge. This document describes the
deployment model, infrastructure provisioning, first release, lifecycle
controls and recovery procedures.

## First deployment from PowerShell

Run these steps from the repository root. Prerequisites are PowerShell 7,
Python 3.11+, AWS CLI v2 with authenticated deployment access, and authenticated
`gh` access to the repository. Select the intended AWS CLI profile first if you
use more than one account.

### 1. Check the target account and stack

The failed `occ-ec2-cd` stack record has been removed after verifying all its
resources were deleted. Bootstrap can now prepare a new CREATE change set.
Preserve unrelated EC2 instances, volumes and the existing GitHub OIDC provider.

Outbound access uses HTTPS on port 443, plus VPC DNS. No customer-managed egress
prefix list, provider IP refresh or security-group quota increase is required.
The existing unused `occ-ec2-cd-egress` list is no longer referenced; it can be
removed separately after confirming it still has no associations. Do not reduce
the regional groups-per-interface quota for this deployment. The old 1,000-rule
quota request is unnecessary; withdraw it through AWS Support if still pending.

### 2. Prepare settings and review the infrastructure change set

Populate the ignored root `.env` with real database passwords, peer tokens,
DataMall/Gemini settings, Supabase project/JWKS settings and its public browser
key. Main's identity integration additionally needs a backend-only modern
`OCC_IDENTITY_SECRET_KEY=sb_secret_...` when `OCC_IDENTITY_ENABLED=true`.
Complete the [Supabase identity rollout](../infra/supabase/README.md) before
enabling that integration. Alternatively, explicitly set
`OCC_IDENTITY_ENABLED=false` to run without its account-provisioning/directory
features. These are production settings; dedicated Supabase test accounts are
not needed. Use the inline backend key for this CD configuration; a workstation
secret-file path is not automatically mounted onto EC2.

```powershell
python -m pip install -r scripts/cd/requirements.txt
gh auth status
aws sts get-caller-identity
$Repository = gh repo view --json nameWithOwner --jq .nameWithOwner
pwsh scripts/cd/bootstrap.ps1 -Repository $Repository -EnvFile .env
```

Review the printed CloudFormation change set, then apply the reviewed change set:

```powershell
pwsh scripts/cd/bootstrap.ps1 -Repository $Repository -EnvFile .env -ApplyChangeSet
```

Bootstrap creates the roles/resources, imports settings into Secrets Manager,
initializes a new deployment as **paused**, and configures GitHub repository
variables plus the main-only `production` environment and its role/repository
secrets. It does not prepare the operating system or run the application yet.

### 3. Prepare the new EC2 host through SSM

Wait until the new instance is Online in Systems Manager. The following sends
the two local bootstrap files to that instance without SSH or a public SSH port.
Use `OCC_INITIALIZE_DATA_VOLUME=true` only for the brand-new blank volume that
this stack created; omit that export when adopting a retained volume.

```powershell
$Region = 'us-east-1'
$StackName = 'occ-ec2-cd'
$outputs = @{}
$stack = aws cloudformation describe-stacks --stack-name $StackName --region $Region | ConvertFrom-Json
$stack.Stacks[0].Outputs | ForEach-Object { $outputs[$_.OutputKey] = $_.OutputValue }
$adoptedVolume = ($stack.Stacks[0].Parameters | Where-Object ParameterKey -eq ExistingDataVolumeId).ParameterValue
$account = aws sts get-caller-identity --query Account --output text
$commands = @('set -eu', 'umask 077', 'mkdir -p /opt/occ/bootstrap')
foreach ($name in @('bootstrap-host.sh', 'volume.py')) {
    $encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $PWD "scripts/cd/$name")))
    $commands += "printf '%s' '$encoded' | base64 --decode > /opt/occ/bootstrap/$name"
}
$commands += @(
    "export AWS_DEFAULT_REGION='$Region' OCC_REGION='$Region' OCC_ACCOUNT_ID='$account'",
    "export OCC_DATA_VOLUME_ID='$($outputs['DataVolumeId'])'",
    "export OCC_DEPLOYMENT_SECRET_NAME='$($outputs['DeploymentSecretName'])'",
    "export OCC_ORIGIN_SECRET_NAME='$($outputs['OriginSecretName'])'"
)
if ([string]::IsNullOrWhiteSpace($adoptedVolume)) {
    $commands += 'export OCC_INITIALIZE_DATA_VOLUME=true'
}
$commands += 'sh /opt/occ/bootstrap/bootstrap-host.sh'
$parameters = Join-Path ([IO.Path]::GetTempPath()) ('occ-host-' + [guid]::NewGuid() + '.json')
try {
    [IO.File]::WriteAllText($parameters, (@{commands=$commands; executionTimeout=@('1800')} | ConvertTo-Json -Depth 4))
    aws ssm send-command --region $Region --instance-ids $outputs['InstanceId'] `
        --document-name AWS-RunShellScript --timeout-seconds 600 `
        --parameters ("file://" + ($parameters -replace '\\','/')) `
        --query Command.CommandId --output text
} finally {
    Remove-Item -LiteralPath $parameters -Force
}
```

Wait for that command's **Success** status in Systems Manager Run Command and
inspect its output. Verify the UUID-mounted data volume and enabled
`occ-backup.timer` before proceeding. A failed command must be fixed before a
resume; do not format an existing filesystem to bypass an identity check.

### 4. Enable the workflows on main and start the application

Push this branch and merge its reviewed PR into the repository's `main` branch.
Merging `origin/main` into this feature branch alone does not activate CD.
The first Main verification run builds/tests/scans a candidate; Release and
deploy publishes it while the paused intent prevents application deployment.
For an explicit first rebuild or retry after setup, run:

```powershell
gh workflow run verify.yml --repo $Repository --ref main -f refresh=rebuild
gh run list --repo $Repository --workflow verify.yml --branch main --limit 3
```

Wait for **Main verification** and **Release and deploy** to succeed. Then resume:

```powershell
gh workflow run environment-control.yml --repo $Repository --ref main -f action=resume
```

After that run succeeds, open the stack's `CloudFrontUrl` output. It provides
HTTPS with the AWS certificate; no custom domain or certificate is required.
Subsequent eligible pushes to `main` deploy automatically while running.
`action=pause` gracefully stops the stack and EC2; `action=resume` starts the
newest eligible release without rebuilding; `action=status` reports state.
While paused, pushes can publish releases without waking the host. Recovery
uses `action=recover -f recovery-operation-id=<the active ID shown by status>`.

## Deployment triggers

Before workstation bootstrap, install `python -m pip install -r
scripts/cd/requirements.txt`. Dotenv quotes, escapes and comments are parsed;
dollar-containing credentials remain literal (variable interpolation is disabled).
Bootstrap transfers parsed settings through private temporary files and never
prints their values.

Manual `recover` requires the original active operation ID reported by `status`.
If a terminal command produced no receipt, recovery takes the host lock and
stops the edge and application writers before recording failure. A running
setup container or foreign host operation blocks recovery. An uncertain database
outcome leaves `state/setup-recovery-required.json`; inspect migration history
and the host journal and perform any required database repair before explicitly
clearing that marker. Recovery never assumes an uncertain migration can be rerun.
The original failed deployment remains failed.

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

Each release identifies exactly five OCC images: `ingestion`, `disruption`,
`planning`, `db-setup` and `web`. Only changed or unavailable owners rebuild;
the remaining components reuse their tested immutable digests. PostgreSQL/PostGIS is a
separately pinned upstream image and is never built by OCC.

Deployment-affecting commits — including workflow, build-tooling, runtime
configuration, migration and CD-script changes with no service edits — must
run CI/CD. Only exclusively documentation, agent-prose or local-launcher
commits carry `[skip ci]`; see [commit convention](commit-convention.md) for
the full rule and for recovery when a skip marker is used by mistake.

## Provisioning the EC2 CD deployment

The November 2026 remediation passed local CD, Java, frontend, packaged-image
system, backup/restore and vulnerability gates. Live Actions/OIDC, EC2 boot,
CloudFront and t3.small capacity acceptance still require the reviewed rollout.
The previous failed stack record has been removed. Bootstrap configures GitHub
settings after provisioning. Standard security-group quotas accommodate this
stack; the former provider IP allowlist and quota prerequisite have been removed.

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

### Retained data volume and replacement

New stacks create a separate encrypted `OccDataVolume`, retained on deletion
and replacement. `DataVolumeId` and `DataVolumeAvailabilityZone` identify it.
Copy `bootstrap-host.sh` and its sibling `volume.py` together before host setup.
Pass `OCC_DATA_VOLUME_ID` from the stack output. Only the first initialization
of the explicitly created blank volume uses `OCC_INITIALIZE_DATA_VOLUME=true`;
omit that flag for later setup/adoption. The host resolves the Nitro device by
EBS serial, records the filesystem UUID in the volume's `occ:filesystem-uuid`
tag, and requires that independent identity before mounting an existing disk.

To replace a host or adopt an older inline disk: pause, verify PostgreSQL is
stopped, create and verify a snapshot, record/verify the old filesystem UUID,
and detach the retained volume. Set its verified UUID tag if legacy setup did
not create one. Review the infrastructure replacement change set, then pass
`bootstrap.ps1 -ExistingDataVolumeId <verified-volume-id>`. Bootstrap requires
an encrypted detached volume and selects its actual AZ. Adoption is external
by ID; it does not format or automatically import the disk into CloudFormation.
Never delete the old disk/snapshot until row continuity has been demonstrated.
Docker requires the correct `/var/lib/postgresql` mount, and a boot hook closes
the traffic gate before old application containers can restart.

### Host outbound access

The host security group allows outbound TCP **443** to public IPv4 destinations
for AWS APIs, external providers and container/package registries, plus TCP/UDP
**53** within the VPC for DNS. It has no public HTTP or all-protocol outbound
rule. Provider IP changes therefore do not require allowlist maintenance or quota
changes. HTTPS destinations are not restricted by domain; application TLS
certificate validation and scoped AWS permissions remain in force.

Inbound access remains separate: only the AWS-managed CloudFront origin-facing
prefix list can reach port 8080, with the origin secret checked at Nginx. Database
and service ports are not publicly exposed. The template still needs the
CloudFront-managed **inbound** prefix list, which bootstrap discovers automatically.

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
depletion) remains an authorized-rollout measurement; local test results do
not establish capacity on the EC2 host.

## Cleanup and security refresh

Database backups use `scripts/cd/backup.sh backup|overdue|restore <key>`.
The daily `occ-backup.timer` runs only on a running host; it cannot wake EC2.
Explicit resume checks for a successful backup within the last 24 hours before
setup or application writers start. `OCC_BACKUP_RETAIN` defaults to seven.
Local `backups/status.json` records attempts, the latest successful key/checksum
and failures, including retention failures; inspect it and
`journalctl -u occ-backup.service` when a timer fails.

An archive contains a consistent custom-format PostgreSQL dump, role definitions
without passwords, and checksums. It preserves ownership, grants, default ACLs
and migration history. Uploads are create-only with S3 SHA-256 checksums and the
bucket's KMS encryption. Structured paginated listings retain the newest seven
current backups; local archives are bounded too. Retired versions expire after
seven days and expired delete markers are removed by the bucket lifecycle.
Failed uploads are removed locally; failures never delete the new successful
backup. Older `.sql.gz` archives are preserved for manual inspection and are
not accepted as this verified archive format.

Restore drills use a uniquely created PostGIS container on `--network none`,
with no host data mount or published ports. Roles load before the custom dump;
the target is created from `template0`, avoiding PostGIS schema collisions.
Temporary dump/role files and the owned container are removed on all normal
failure paths. Role passwords must be reinjected/rotated before any recovered
database serves applications. The command performs an isolated drill; it does
not replace production data.

Release/application rollback is separate from database restore. The remote
driver selects a pinned current/previous reference under durable operation
ownership. A changed setup fingerprint requires reviewed compatibility evidence
covering the actual baseline and exact setup-input range in the candidate's
original immutable manifest. A rollback never rewrites an old manifest to grant
itself permission or reruns setup against the newer database.

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

Actual provisioning requires verified AWS permissions and a
reviewed change set. Cost qualification is excluded from the current remediation
acceptance at the user's request.

GitHub OIDC trust uses the CloudFormation parameter `GitHubOidcSubjectPrefix`.
Its default matches the immutable subject prefix for `pslimmm/OCC`. When
deploying another repository, obtain its prefix from `gh api repos/OWNER/REPO/actions/oidc/customization/sub`
and set that parameter to match before applying the stack. Keep the exact
`main` and `production` suffix restrictions; do not use wildcard trust.
