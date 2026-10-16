# Phase 1 deployment and recovery runbook

This runbook prepares the single-host MVP deployment. It is not evidence of an AWS deployment or a production restore. Do not expose the service until the deployment operator has filled the external inputs below and rehearsed the procedure in an isolated staging environment.

## Required operator inputs

Before staging, record the AWS account and region, a supported Linux AMI ID and instance size, encrypted EBS sizing, the deployment hostname and DNS owner, the TLS certificate/renewal method, immutable registry digests for the seven Java service/migration images, Supabase project/issuer/JWKS and application roles, DataMall/Gemini credentials, database/service peer credentials, backup destination/retention, rollback owner, and the agreed write-pause/restore objectives. Keep secrets in the host's protected environment file or approved secret store; never commit that file, print resolved Compose configuration, or upload it with CI evidence.

Use the values and variable names in the repository-root `.env.example` as the inventory. On the host, store production values in `/etc/occ/occ.env` with owner `root` and mode `0600`; keep the TLS files outside the checkout and readable by the Nginx container UID 101. The `OCC_*_IMAGE` and `OCC_*_MIGRATION_IMAGE` values must use immutable registry digests. CI builds and scans images but does not publish them or provision AWS resources.

## Preflight and deploy

Install and patch the supported Linux AMI, Docker Engine and Compose plugin. Restrict the security group to HTTPS 443; do not publish PostgreSQL, pgAdmin, Gateway, or backend ports. Require IMDSv2 and use SSM for administration. Apply host updates, registry access, log handling, EBS encryption, secret access, and off-host backup permissions according to the selected AWS account and region; those identifiers must come from the operator, not this template.

From the checkout at `/opt/occ`, validate without printing interpolated values and pull the pinned images:

```sh
cd /opt/occ
docker compose --project-name occ --env-file /etc/occ/occ.env \
  -f compose.yaml -f infra/compose.production.yaml config -q
docker compose --project-name occ --env-file /etc/occ/occ.env \
  -f compose.yaml -f infra/compose.production.yaml pull
```

For a first deployment, start the stack only after TLS and real Supabase settings have been verified:

```sh
docker compose --project-name occ --env-file /etc/occ/occ.env \
  -f compose.yaml -f infra/compose.production.yaml up -d
docker compose --project-name occ --env-file /etc/occ/occ.env \
  -f compose.yaml -f infra/compose.production.yaml ps
```

Compose runs PostgreSQL, the repeatable role/schema bootstrap, the three Flyway migration jobs, the Java services, and Nginx. Confirm the one-shot migration jobs completed and each application reports healthy before external smoke checks. Keep provider keys and peer credentials out of command arguments and logs.

An optional systemd unit can start the existing Compose stack without another launcher script. Replace paths only if the deployment checkout or Docker binary differs:

```ini
[Unit]
Description=OCC Compose stack
Requires=docker.service
After=docker.service network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/opt/occ
ExecStart=/usr/bin/docker compose --project-name occ --env-file /etc/occ/occ.env -f compose.yaml -f infra/compose.production.yaml up -d
ExecStop=/usr/bin/docker compose --project-name occ --env-file /etc/occ/occ.env -f compose.yaml -f infra/compose.production.yaml stop
TimeoutStartSec=10min

[Install]
WantedBy=multi-user.target
```

Verify the Docker binary path, install the unit as `/etc/systemd/system/occ-compose.service`, then run `systemctl daemon-reload`, `systemctl enable --now occ-compose`, and `systemctl status occ-compose`. `stop` preserves the database volume. Do not use `docker compose down -v` for routine shutdown.

## Planned-pause migration and cutover

For a schema cutover, schedule an announced write pause. Record the old and new image digests, current Git revision, database row/hash reconciliation output, application health, backup checksum, and an operator for each step. Stop the legacy pollers, detectors, and planners before starting target workers; never run both generations against the same work. Keep legacy schemas until the target workflow is verified.

1. Confirm staging rehearsal, current backup, restored backup, external identity/JWKS, secrets, TLS, and rollback owner.
2. Put the existing clients into maintenance mode and stop legacy writers/workers. Record outstanding leases and pending work; reconcile or let bounded leases expire according to the migration evidence.
3. Take a final database backup using the procedure below. Run the target Compose bootstrap and migration jobs against the backed-up working database; inspect exit status and Flyway history for all three schemas.
4. Compare source and target key sets, row counts, identity hashes, live/demo partitions, non-terminal jobs, event evidence, diagnosis/recommendation records, model input/output evidence, and grants using the saved migration queries and mapping.
5. Start only the new Ingestion, Disruption, Planning, Gateway, and Nginx stack. Run readiness, role allow/deny, route-boundary, bunching, traffic, and existing diagnosis/recommendation checks. Record the write-pause duration and backlog catch-up.
6. Resume writes only after the rollback owner accepts the recorded checks. Preserve the baseline dump through the agreed observation window.

This MVP uses a planned write pause. The migration does not claim zero downtime, HA, replication, or multi-host failover.

## Database backup

The PostGIS/PostgreSQL image includes `pg_dump`. Create a custom-format logical database archive from the running stack and write it directly to a restricted host file; do not let dump contents reach the terminal or CI log:

```sh
set -euo pipefail
backup_dir=/var/backups/occ
install -d -m 0700 "$backup_dir"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
docker compose --project-name occ --env-file /etc/occ/occ.env \
  -f compose.yaml -f infra/compose.production.yaml exec -T postgres \
  sh -ec 'pg_dump --format=custom --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
  > "$backup_dir/occ-$stamp.dump"
sha256sum "$backup_dir/occ-$stamp.dump" > "$backup_dir/occ-$stamp.dump.sha256"
```

Encrypt the archive before off-host transfer, restrict its access, and apply the operator-approved retention policy. Preserve the matching database/service role names and secret version in the approved secret store; the SQL archive contains database objects and data but does not replace deployment configuration. PostgreSQL documents that `pg_dump` handles one database, while cluster-wide roles and tablespaces are separate global objects; this OCC stack recreates its known service roles through `infra/postgres/bootstrap.sql`. Add any future global roles or tablespaces to the recovery procedure before using them. [PostgreSQL 18 backup and restore](https://www.postgresql.org/docs/18/backup.html), [pg_dumpall global objects](https://www.postgresql.org/docs/18/app-pg-dumpall.html).

## Isolated restore rehearsal

Restore into a new, isolated Compose project and volume. Use `.env.example` only for the repository's disposable test stack; use a separately managed recovery environment file for an actual backup. Do not overwrite the active database to test a backup.

```sh
set -euo pipefail
restore_project=occ-restore-rehearsal
backup_file=/secure/restore-input/occ-backup.dump
compose=(docker compose --project-name "$restore_project" --env-file .env.example \
  -f compose.yaml -f infra/compose.test.yaml)
"${compose[@]}" up -d postgres
"${compose[@]}" run --rm db-bootstrap
db_container=$("${compose[@]}" ps -q postgres)
docker cp "$backup_file" "$db_container:/tmp/occ-restore.dump"
docker exec "$db_container" sh -ec \
  'pg_restore --exit-on-error --clean --if-exists --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" /tmp/occ-restore.dump'
"${compose[@]}" up -d
"${compose[@]}" ps
```

The test override disables scheduled provider polling and workflow workers. After restore, verify PostGIS is installed, the three target schemas and Flyway histories match the archive, runtime roles cannot read peer schemas or run DDL, and the restored counts/hashes match the recorded backup for operational cursors, pending/outbox jobs, diagnoses, recommendations, and retained model evidence. Confirm all services become healthy and the system smoke passes; then stop this project with `down --remove-orphans` (without `-v`) and keep or retire its isolated volume according to the rehearsal record. PostgreSQL custom archives are restored with `pg_restore`; rehearse this exact procedure against the pinned database major version before relying on it. [pg_restore reference](https://www.postgresql.org/docs/18/app-pgrestore.html).

## Rollback and recovery boundaries

Before target writes resume, the simplest rollback is to keep the legacy database backup and restart the previously recorded application images/configuration after confirming the old schemas remain intact. Record those image digests and the exact prior Git revision before cutover. For failure after target writes resume, stop all target writers first. Reconcile target deltas back to the old generation or forward-fix; restoring the baseline dump discards all changes after its timestamp and requires an explicit accepted loss window. Switching containers or checking out an earlier commit does not reverse database writes or Flyway migrations.

For host loss, create a replacement host with the same protected configuration, restore the latest validated logical archive to a fresh database volume, verify schemas/grants/queues/evidence and service health, then enable HTTPS traffic. Recover TLS private keys and environment values from their separately protected locations. Record the restored archive hash, image digests, elapsed restore time, and data-loss window. Until this rehearsal succeeds on a representative isolated host, recovery is unverified and public production deployment remains pending.

## Deployment status record

Current repository evidence covers local Compose configuration, Testcontainers migration/import tests, local authentication smoke checks, a localhost TLS edge smoke test, and an isolated custom-format backup/restore rehearsal with synthetic pending and retained records. It does not record an AWS instance, production DNS/certificate, published image digests, an external deployment, or an EC2/staging cutover and host-loss recovery rehearsal. Fill those fields only with measured operator evidence; do not infer deployment success from `docker compose config` or a local test certificate.
