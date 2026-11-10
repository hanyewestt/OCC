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
POSTGRES_DB="${POSTGRES_DB:?set POSTGRES_DB}"
POSTGRES_USER="${POSTGRES_USER:-occ_admin}"
POSTGIS_IMAGE="${POSTGIS_IMAGE:-postgis/postgis:18-3.6@sha256:7e00e8c3539fdd43f513b98806c8204714dcd09dea683c259e333d7690317119}"

log() { printf '%s\n' "backup: $*"; }
die() { printf 'backup: error: %s\n' "$*" >&2; exit 1; }

backup() {
    mkdir -p "$OCC_BACKUP_DIR"
    dump_path="$OCC_BACKUP_DIR/occ-$OCC_BACKUP_STAMP.sql"
    log "dumping $POSTGRES_DB consistently"
    # --single-transaction: one snapshot; the dump is only uploaded when the
    # whole dump completed, so partial dumps never reach the bucket.
    pg_dump --dbname "$POSTGRES_DB" --username "$POSTGRES_USER" \
        --format plain --single-transaction --no-owner \
        --file "$dump_path" \
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
    log "restoring into the isolated container $OCC_RESTORE_CONTAINER"
    # The isolated container is disposable; production volumes and the
    # production Compose project are never involved.
    docker run --rm --name "$OCC_RESTORE_CONTAINER" \
        -e POSTGRES_DB="$POSTGRES_DB" -e POSTGRES_USER="$POSTGRES_USER" \
        "$POSTGIS_IMAGE" \
        sh -c 'exec pg_restore --dbname "$POSTGRES_DB" --username "$POSTGRES_USER" \
               --no-owner --if-exists --clean' \
        < "$local_path" \
        || die "restore failed in the isolated container; production is untouched"
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
