#!/bin/sh
# OCC database backup and isolated restore.
#
#   backup.sh backup            Dump the database consistently and upload it
#                               to the encrypted/versioned release bucket
#                               with bounded retention.
#   backup.sh restore <key>     Download a backup and restore it into an
#                               isolated disposable PostGIS container. Never
#                               touches the production stack or its volume.
#
# Retention keeps the newest $OCC_BACKUP_RETAIN objects; a cleanup failure is
# reported but never rolls back or removes the freshly uploaded backup.
set -eu

OCC_RELEASE_BUCKET="${OCC_RELEASE_BUCKET:?set OCC_RELEASE_BUCKET to the deployment bucket}"
OCC_BACKUP_RETAIN="${OCC_BACKUP_RETAIN:-7}"
OCC_BACKUP_STAMP="${OCC_BACKUP_STAMP:-$(date -u +%Y%m%dT%H%M%SZ)}"
OCC_BACKUP_DIR="${OCC_BACKUP_DIR:-/opt/occ/backups}"
OCC_RESTORE_CONTAINER="${OCC_RESTORE_CONTAINER:-occ-restore}"
OCC_PROJECT="${OCC_PROJECT:-occ-production}"
POSTGRES_DB="${POSTGRES_DB:?set POSTGRES_DB}"
POSTGRES_USER="${POSTGRES_USER:-occ_admin}"
POSTGIS_IMAGE="${POSTGIS_IMAGE:-postgis/postgis:18-3.6@sha256:7e00e8c3539fdd43f513b98806c8204714dcd09dea683c259e333d7690317119}"

log() { printf '%s\n' "backup: $*"; }
die() { printf 'backup: error: %s\n' "$*" >&2; exit 1; }

backup() {
    mkdir -p "$OCC_BACKUP_DIR"
    dump_path="$OCC_BACKUP_DIR/occ-$OCC_BACKUP_STAMP.sql"
    POSTGRES_CONTAINER=$(docker ps --quiet \
        --filter "label=com.docker.compose.project=$OCC_PROJECT" \
        --filter "label=com.docker.compose.service=postgres" | head -n 1)
    [ -n "$POSTGRES_CONTAINER" ] || \
        die "the postgres container for $OCC_PROJECT is not running"
    log "dumping $POSTGRES_DB consistently"
    # --single-transaction: one snapshot; the dump is only uploaded when the
    # whole dump completed, so partial dumps never reach the bucket. The
    # client lives in the running postgres image, not on the host.
    docker exec "$POSTGRES_CONTAINER" \
        pg_dump --dbname "$POSTGRES_DB" --username "$POSTGRES_USER" \
        --format plain --single-transaction --no-owner \
        > "$dump_path" \
        || die "pg_dump failed; nothing was uploaded"
    gzip -f "$dump_path"
    dump_gz="$dump_path.gz"
    [ -s "$dump_gz" ] || die "the dump archive is empty"
    log "uploading $(basename "$dump_gz")"
    aws s3 cp "$dump_gz" "s3://$OCC_RELEASE_BUCKET/backups/occ-$OCC_BACKUP_STAMP.sql.gz"
    log "uploaded; applying retention (keep $OCC_BACKUP_RETAIN newest)"
    # Bounded retention: list, keep the newest N, remove the rest. A cleanup
    # failure is reported but never rolls back the fresh upload.
    if ! aws s3 ls "s3://$OCC_RELEASE_BUCKET/backups/" > "$OCC_BACKUP_DIR/backup-list.txt" 2>/dev/null; then
        echo "backup: warning: the retention listing failed; the new backup is safe" >&2
        exit 0
    fi
    stale_keys=$(python3 - "$OCC_BACKUP_DIR/backup-list.txt" "$OCC_BACKUP_RETAIN" <<'PY'
import sys

lines = [line.split(None, 3)[-1].strip()
         for line in open(sys.argv[1], encoding="utf-8") if line.strip()]
keep = int(sys.argv[2])
for key in sorted(lines)[:-keep] if len(lines) > keep else []:
    print(key)
PY
) || die "retention listing could not be parsed"
for key in $stale_keys; do
    if ! aws s3 rm "s3://$OCC_RELEASE_BUCKET/$key"; then
        echo "backup: warning: could not remove $key; the new backup is safe" >&2
    fi
done
}

restore() {
    key="$1"
    case "$key" in
        backups/*) ;;
        *) die "unsafe backup key: $key (must start with backups/)" ;;
    esac
    case "$key" in
        *[!A-Za-z0-9._/-]*|*..*) die "unsafe backup key: $key" ;;
    esac
    mkdir -p "$OCC_BACKUP_DIR"
    local_path="$OCC_BACKUP_DIR/restore.sql.gz"
    log "downloading $key"
    aws s3 cp "s3://$OCC_RELEASE_BUCKET/$key" "$local_path"
    local_sql="${local_path%.gz}"
    gunzip -c "$local_path" > "$local_sql" \
        || die "the downloaded backup could not be decompressed"
    log "restoring into the isolated container $OCC_RESTORE_CONTAINER"
    # The isolated container is disposable; production volumes and the
    # production Compose project are never involved. Start a real PostGIS
    # server, wait for it, then pipe the plain-SQL dump to psql.
    cleanup_restore() {
        docker rm --force "$OCC_RESTORE_CONTAINER" >/dev/null 2>&1 || true
    }
    trap cleanup_restore EXIT INT TERM
    docker run --detach --name "$OCC_RESTORE_CONTAINER" \
        -e POSTGRES_DB="$POSTGRES_DB" -e POSTGRES_USER="$POSTGRES_USER" \
        -e POSTGRES_PASSWORD=occ-restore-disposable \
        "$POSTGIS_IMAGE" >/dev/null \
        || die "restore failed: the isolated PostGIS container could not be started"
    attempt=0
    ready=""
    while [ "$attempt" -lt 60 ]; do
        if docker exec "$OCC_RESTORE_CONTAINER" \
            pg_isready -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
            >/dev/null 2>&1; then
            ready=1
            break
        fi
        attempt=$((attempt + 1))
        sleep 1
    done
    [ -n "$ready" ] || die "the isolated PostGIS container did not become ready"
    docker exec --interactive "$OCC_RESTORE_CONTAINER" \
        psql --dbname "$POSTGRES_DB" --username "$POSTGRES_USER" \
        --set ON_ERROR_STOP=1 < "$local_sql" \
        || die "restore failed in the isolated container; production is untouched"
    cleanup_restore
    trap - EXIT INT TERM
    rm -f "$local_sql"
    log "restore completed inside $OCC_RESTORE_CONTAINER"
}

case "${1:-}" in
    backup) shift; backup "$@" ;;
    restore)
        [ "$#" -eq 2 ] || die "usage: backup.sh restore <backups/key>"
        restore "$2"
        ;;
    *) die "usage: backup.sh backup|restore <backups/key>" ;;
esac
