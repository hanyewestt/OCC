#!/bin/sh
# OCC host deployment: apply or roll back one verified release directory.
#
#   deploy.sh apply <release-directory>
#   deploy.sh rollback <release-directory>
#
# The release bundle carries manifest.json, the exact Compose overlays and
# checksums.txt. The host never builds images, never deletes volumes and
# never reruns the aggregate setup unless the setup or role-config identity
# actually changed. Durable PostgreSQL data lives on the mounted data volume;
# state, releases and runtime live outside it.
#
# Safety model: one flock-serialized operation at a time, an operation intent
# journaled before the first mutation, ambiguous state blocks further work,
# the traffic gate stays closed through any failure, and the active/previous
# state only advances after readiness and the public smoke pass.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OCC_HOME="${OCC_HOME:-/opt/occ}"
OCC_DATA_MOUNT="${OCC_DATA_MOUNT:-/var/lib/postgresql}"
OCC_MIN_FREE_MB="${OCC_MIN_FREE_MB:-2048}"
OCC_PROJECT="${OCC_PROJECT:-occ-production}"
OCC_PUBLIC_URL="${OCC_PUBLIC_URL:-http://127.0.0.1:8080}"
OCC_READINESS_TIMEOUT="${OCC_READINESS_TIMEOUT:-300}"
STATE="$OCC_HOME/state"
JOURNAL="$STATE/journal"
RUNTIME="$OCC_HOME/runtime"
RELEASES="$OCC_HOME/releases"
GATE_SCRIPT="$SCRIPT_DIR/traffic-gate.sh"

log() { printf '%s\n' "deploy: $*"; }
die() { printf 'deploy: error: %s\n' "$*" >&2; exit 1; }

command -v python3 >/dev/null || die "python3 is required on the host"

[ "$#" -eq 2 ] || die "usage: deploy.sh apply|rollback <release-directory>"
ACTION="$1"
RELEASE_ARG="$2"
case "$ACTION" in apply|rollback) ;; *) die "unknown action: $ACTION" ;; esac

# Normalize through realpath so the containment check compares identical
# spellings regardless of the invoking shell's path forms.
RELEASE_DIR=$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]).replace(chr(92), "/"))' "$RELEASE_ARG")
RELEASES_ROOT=$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]).replace(chr(92), "/"))' "$RELEASES")
RELEASE_ID=$(basename "$RELEASE_DIR")
case "$RELEASE_ID" in
    ""|"."|".."|*/*|*\\*) die "unsafe release id: $RELEASE_ID" ;;
esac
case "$RELEASE_DIR" in
    "$RELEASES_ROOT") ;;
    "$RELEASES_ROOT"/*) ;;
    *) die "release directory must live under $RELEASES; refusing to deploy from $RELEASE_DIR" ;;
esac
BUNDLE="$RELEASE_DIR/bundle"
[ -d "$BUNDLE" ] || die "release bundle not found: $BUNDLE"

mkdir -p "$STATE" "$JOURNAL" "$RUNTIME"
OPERATION_ID="$ACTION-$RELEASE_ID-$(date +%Y%m%d%H%M%S)-$$"

# One deploy at a time: an atomic mkdir lock with a live-owner check. A hard
# killed run leaves the lock with a dead owner, which the next run steals;
# a live owner blocks the new operation.
LOCK_DIR="$STATE/deploy.lock"
if mkdir "$LOCK_DIR" 2>/dev/null; then
    printf '%s\n' "$$" > "$LOCK_DIR/owner"
    trap 'rm -rf "$LOCK_DIR"' EXIT INT TERM HUP
else
    OWNER_PID=$(cat "$LOCK_DIR/owner" 2>/dev/null || true)
    if [ -n "$OWNER_PID" ] && ! kill -0 "$OWNER_PID" 2>/dev/null; then
        log "stealing the deploy lock from the dead owner $OWNER_PID"
        rm -rf "$LOCK_DIR"
        if mkdir "$LOCK_DIR" 2>/dev/null; then
            printf '%s\n' "$$" > "$LOCK_DIR/owner"
            trap 'rm -rf "$LOCK_DIR"' EXIT INT TERM HUP
        else
            die "another deploy operation holds the deploy lock"
        fi
    else
        die "another deploy operation holds the deploy lock; refusing to run concurrently"
    fi
fi

[ -f "$STATE/outstanding-operation.json" ] && die \
    "an outstanding operation $(cat "$STATE/outstanding-operation.json") has ambiguous state; \
reconcile it before any new deploy"

validate_manifest() {
    python3 - "$BUNDLE/manifest.json" <<'PY'
import hashlib
import json
import re
import sys

digest_pattern = re.compile(
    r"^[a-z0-9][a-z0-9-]*\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/"
    r"[A-Za-z0-9][A-Za-z0-9._/-]*@sha256:[0-9a-f]{64}$")
components = ("ingestion", "disruption", "planning", "db-setup", "web")
manifest = json.load(open(sys.argv[1], encoding="utf-8"))
if manifest.get("schemaVersion") != 2:
    sys.exit("unsupported manifest schemaVersion")
if set(manifest.get("images", {})) != set(components):
    sys.exit("manifest must carry exactly the five component images")
for component, reference in manifest["images"].items():
    if not digest_pattern.match(reference):
        sys.exit(f"image for {component} is not a digest-pinned ECR reference")
if not isinstance(manifest.get("releaseId"), str) or not manifest["releaseId"]:
    sys.exit("manifest has no releaseId")
if not isinstance(manifest.get("revision"), str) or len(manifest["revision"]) != 40:
    sys.exit("manifest has no full revision")
if not isinstance(manifest.get("dbSetupInputHash"), str):
    sys.exit("manifest has no dbSetupInputHash")
for value in json.dumps(manifest).split('"'):
    if re.search(r"AKIA[0-9A-Z]{16}", value):
        sys.exit("manifest contains a credential-shaped value")
PY
}

# The bundle must be byte-identical to the published, checksummed release.
(cd "$BUNDLE" && sha256sum --quiet --check checksums.txt) || \
    die "bundle checksums do not match the published release; refusing to deploy"
validate_manifest || die "manifest validation failed: $?"

# The durable data volume must be the mounted retained volume; a missing
# mount is a hard stop, never a root-disk fallback.
[ -f "$OCC_DATA_MOUNT/.occ-data-marker" ] || \
    die "the durable data volume is not mounted at $OCC_DATA_MOUNT; blocking startup"

FREE_MB=$(df -Pm "$OCC_DATA_MOUNT" | awk 'NR==2 {print $4}')
[ "${FREE_MB:-0}" -ge "$OCC_MIN_FREE_MB" ] || \
    die "insufficient disk space on the data volume: ${FREE_MB}MB free, ${OCC_MIN_FREE_MB}MB required"

set -a
# shellcheck disable=SC1090
. "${OCC_DEPLOYMENT_ENV:?set OCC_DEPLOYMENT_ENV to the deployment environment file}"
set +a

compose() {
    docker compose -p "$OCC_PROJECT" --env-file "$COMPOSE_ENV" \
        -f "$BUNDLE/compose.yaml" -f "$BUNDLE/compose.images.yaml" \
        -f "$BUNDLE/compose.cloudfront.yaml" "$@"
}

manifest_value() {
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' \
        "$BUNDLE/manifest.json" "$1"
}

REVISION=$(manifest_value revision)
DB_SETUP_INPUT_HASH=$(manifest_value dbSetupInputHash)

# Role-config identity: the structural database role settings. Passwords are
# deliberately excluded; rotation is a separate tested path, never an
# env-var-driven setup rerun.
ROLE_IDENTITY=$(printf '%s\n' \
    "db=${POSTGRES_DB:-}" "admin_user=${POSTGRES_USER:-}" \
    "ingestion=${OCC_INGESTION_DB_USERNAME:-}" "ingestion_migrator=${OCC_INGESTION_DB_MIGRATOR_USERNAME:-}" \
    "disruption=${OCC_DISRUPTION_DB_USERNAME:-}" "disruption_migrator=${OCC_DISRUPTION_DB_MIGRATOR_USERNAME:-}" \
    "planning=${OCC_PLANNING_DB_USERNAME:-}" "planning_migrator=${OCC_PLANNING_DB_MIGRATOR_USERNAME:-}" \
    | python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())')
SETUP_FINGERPRINT=$(printf '%s\n%s\n' "$DB_SETUP_INPUT_HASH" "$ROLE_IDENTITY" \
    | python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())')

# Runtime environment: deployment settings plus this release's exact images.
COMPOSE_ENV="$RUNTIME/env"
{
    grep -E '^(POSTGRES_|SUPABASE_|OCC_[A-Z_]+|DATAMALL_API_KEY)' \
        "${OCC_DEPLOYMENT_ENV:?}" 2>/dev/null || true
    python3 - "$BUNDLE/manifest.json" <<'PY'
import json
import sys

manifest = json.load(open(sys.argv[1], encoding="utf-8"))
for component, reference in manifest["images"].items():
    key = "OCC_" + component.upper().replace("-", "_") + "_IMAGE"
    print(f"{key}={reference}")
PY
} > "$COMPOSE_ENV"
chmod 0600 "$COMPOSE_ENV"

APPLIED_SETUP="$STATE/applied-setup.json"
ACTIVE_RELEASE="$STATE/active-release.json"

record_phase() {
    printf '{"operationId":"%s","phase":"%s","at":"%s"}\n' \
        "$OPERATION_ID" "$1" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$JOURNAL/$OPERATION_ID.json.log"
}

OCC_GATE_DIR="${OCC_GATE_DIR:?set OCC_GATE_DIR to the rendered traffic-gate include directory}"
export OCC_GATE_DIR

if [ "$ACTION" = rollback ]; then
    # Application rollback only: never the old db-setup, never SQL downgrades.
    [ -f "$ACTIVE_RELEASE" ] || die "no previous release on this host; nothing to roll back to"
    TARGET_COMPAT=$(python3 - "$BUNDLE/manifest.json" "$ACTIVE_RELEASE" "$APPLIED_SETUP" 2>&1 <<'PY'
import json
import sys

manifest = json.load(open(sys.argv[1], encoding="utf-8"))
evidence = manifest.get("compatibilityEvidence")
active = json.load(open(sys.argv[2], encoding="utf-8"))
applied = json.load(open(sys.argv[3], encoding="utf-8"))
if manifest.get("migrationCompatibility") != "backward-compatible" or not evidence:
    sys.exit("rollback compatibility is unknown; it cannot authorize automatic rollback")
if evidence.get("rollbackReleaseId") != active.get("releaseId"):
    sys.exit("rollback evidence does not cover the actually deployed release")
if evidence.get("setupFingerprint") != applied.get("fingerprint"):
    sys.exit("rollback evidence does not cover the applied setup fingerprint")
print("ok")
PY
    ) || die "$TARGET_COMPAT"
    record_phase "rollback-authorized"
    log "rollback authorized: compatible app/web images only, database untouched"
    compose stop nginx ingestion disruption planning >/dev/null 2>&1 || true
    compose up -d --no-build --wait
    record_phase "rollback-applied"
    python3 - "$ACTIVE_RELEASE" "$RELEASE_ID" "$REVISION" <<'PY'
import json
import sys

path, release_id, revision = sys.argv[1], sys.argv[2], sys.argv[3]
active = json.load(open(path, encoding="utf-8"))
previous = {
    "releaseId": release_id,
    "revision": revision,
    "previous": active.get("releaseId"),
    "rollbackOf": active.get("releaseId"),
}
with open(path + ".new", "w", encoding="utf-8") as handle:
    json.dump(previous, handle)
PY
    mv "$ACTIVE_RELEASE.new" "$ACTIVE_RELEASE"
    record_phase "rollback-state-advanced"
    log "rollback complete; the failed deployment remains recorded as failed"
    exit 0
fi

# --- apply -----------------------------------------------------------------

if [ -f "$STATE/pointer-pending.json" ] && [ -f "$ACTIVE_RELEASE" ]; then
    PENDING_RELEASE=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' \
        "$STATE/pointer-pending.json")
    ACTIVE_ID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' \
        "$ACTIVE_RELEASE")
    if [ "$PENDING_RELEASE" = "$RELEASE_ID" ] && [ "$ACTIVE_ID" = "$RELEASE_ID" ]; then
        log "reconciling the remote state pointer only; the host already runs this release"
        if [ -n "${OCC_RELEASE_BUCKET:-}" ]; then
            aws s3 cp "$STATE/last-deployed.json" "s3://$OCC_RELEASE_BUCKET/state/last-deployed.json" || \
                die "remote pointer write failed again; pointer-pending state retained"
        fi
        rm -f "$STATE/pointer-pending.json"
        log "pointer reconciled"
        exit 0
    fi
fi

if [ -n "${OCC_RELEASE_BUCKET:-}" ] && [ ! -f "$STATE/last-deployed.json" ]; then
    # First pointer write happens after success; nothing to reconcile.
    :
fi

# Operation intent is journaled before the first mutation.
OUTSTANDING="$STATE/outstanding-operation.json"
printf '{"operationId":"%s","action":"apply","releaseId":"%s","phase":"started"}\n' \
    "$OPERATION_ID" "$RELEASE_ID" > "$OUTSTANDING"
record_phase "started"

log "pulling the release images before any mutation"
for image in $(python3 - "$BUNDLE/manifest.json" <<'PY'
import json
import sys

manifest = json.load(open(sys.argv[1], encoding="utf-8"))
for reference in sorted(manifest["images"].values()):
    print(reference)
PY
); do
    docker pull "$image" >/dev/null
done
record_phase "pulled"

NEED_SETUP=1
if [ -f "$APPLIED_SETUP" ]; then
    APPLIED_FINGERPRINT=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["fingerprint"])' \
        "$APPLIED_SETUP")
    [ "$APPLIED_FINGERPRINT" = "$SETUP_FINGERPRINT" ] && NEED_SETUP=0
fi

if [ "$NEED_SETUP" -eq 1 ]; then
    log "setup or role-config identity changed: closing the gate and running the aggregate setup"
    OCC_GATE_TOKEN=$(cat "$OCC_GATE_DIR/gate-token")
    export OCC_GATE_TOKEN
    sh "$GATE_SCRIPT" close
    GATE_WAS_CLOSED=1
    record_phase "gate-closed"
    compose stop nginx ingestion disruption planning >/dev/null 2>&1 || true
    record_phase "writers-stopped"
    # PostgreSQL stays up; the setup requires it healthy.
    compose exec -T postgres sh -c 'pg_isready -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
        >/dev/null || die "PostgreSQL is not healthy; setup refused"
    log "running the combined db-setup once (ingestion -> disruption -> planning)"
    if ! compose run --rm --no-deps db-setup; then
        record_phase "setup-failed"
        rm -f "$OUTSTANDING"
        die "aggregate setup failed; the gate stays closed and the schema state needs recovery"
    fi
    record_phase "setup-completed"
    printf '{"fingerprint":"%s","dbSetupInputHash":"%s","roleIdentity":"%s","releaseId":"%s","operationId":"%s"}\n' \
        "$SETUP_FINGERPRINT" "$DB_SETUP_INPUT_HASH" "$ROLE_IDENTITY" "$RELEASE_ID" "$OPERATION_ID" \
        > "$APPLIED_SETUP"
else
    log "setup fingerprint unchanged: skipping the aggregate setup and writer shutdown"
fi

log "applying the exact base/images/cloudfront files with --no-build"
compose up -d --no-build --wait
record_phase "stack-up"

# Backend readiness first: nginx health alone proves nothing.
READY_DEADLINE=$(( $(date +%s) + OCC_READINESS_TIMEOUT ))
for service in ingestion disruption planning; do
    log "waiting for $service readiness"
    while :; do
        if compose exec -T "$service" sh -c \
            'curl --fail --silent http://127.0.0.1:${OCC_SERVICE_INTERNAL_PORT:-8000}/actuator/health/readiness' \
            >/dev/null 2>&1; then
            break
        fi
        [ "$(date +%s)" -lt "$READY_DEADLINE" ] || {
            record_phase "readiness-failed"
            rm -f "$OUTSTANDING"
            die "$service did not become ready; the gate stays closed"
        }
        sleep 5
    done
done
record_phase "backends-ready"

log "checking the restricted origin while the gate is closed"
GATE_TOKEN=$(cat "$OCC_GATE_DIR/gate-token" 2>/dev/null || printf 'missing')
ORIGIN_SECRET_HEADER=$(grep -E '^OCC_ORIGIN_SECRET=' "$COMPOSE_ENV" | head -1 | cut -d= -f2-)
for check in "_occ_gate_readiness:204" "_occ_edge_health:503"; do
    path=${check%%:*}
    expected=${check##*:}
    STATUS=$(curl --silent --output /dev/null --write-out '%{http_code}' \
        --header "X-OCC-Origin-Secret: $ORIGIN_SECRET_HEADER" \
        --header "X-OCC-Gate-Token: $GATE_TOKEN" "$OCC_PUBLIC_URL/$path" || printf '000')
    [ "$STATUS" = "$expected" ] || {
        record_phase "origin-check-failed"
        rm -f "$OUTSTANDING"
        die "origin check failed: /$path returned $STATUS, expected $expected"
    }
done
record_phase "origin-checked"

if [ "${GATE_WAS_CLOSED:-0}" = "1" ]; then
    log "opening the traffic gate and validating the public URL"
    sh "$GATE_SCRIPT" open
fi
record_phase "gate-open"
for path in "" "frontend-config" "login"; do
    STATUS=$(OCC_CURL_PHASE=public curl --silent --output /dev/null --write-out '%{http_code}' \
        "$OCC_PUBLIC_URL/$path" || printf '000')
    [ "$STATUS" = "200" ] || {
        record_phase "public-smoke-failed"
        rm -f "$OUTSTANDING"
        die "public smoke failed: /$path returned $STATUS; the deployment is not accepted"
    }
done
record_phase "public-smoke-passed"

# Full routing/auth/hidden-path and SSE verification through the edge is the
# system-verification suite's job; this host check never substitutes for it.

# Advance active/previous state atomically, then the remote pointer.
python3 - "$ACTIVE_RELEASE" "$RELEASE_ID" "$REVISION" <<'PY'
import json
import sys

path, release_id, revision = sys.argv[1], sys.argv[2], sys.argv[3]
previous = None
try:
    previous = json.load(open(path, encoding="utf-8")).get("releaseId")
except FileNotFoundError:
    pass
with open(path + ".new", "w", encoding="utf-8") as handle:
    json.dump({"releaseId": release_id, "revision": revision,
               "previous": previous}, handle)
PY
mv "$ACTIVE_RELEASE.new" "$ACTIVE_RELEASE"

DEPLOYED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
printf '{"releaseId":"%s","revision":"%s","deployedAt":"%s","checksums":"%s"}\n' \
    "$RELEASE_ID" "$REVISION" "$DEPLOYED_AT" \
    "$(sha256sum "$BUNDLE/manifest.json" | awk '{print $1}')" > "$STATE/last-deployed.json"

POINTER_FAILURE=""
if [ -n "${OCC_RELEASE_BUCKET:-}" ]; then
    if ! aws s3 cp "$STATE/last-deployed.json" "s3://$OCC_RELEASE_BUCKET/state/last-deployed.json"; then
        POINTER_FAILURE=1
    fi
fi
if [ -n "$POINTER_FAILURE" ]; then
    printf '{"releaseId":"%s","reason":"s3-write-failed"}\n' "$RELEASE_ID" \
        > "$STATE/pointer-pending.json"
    rm -f "$OUTSTANDING"
    record_phase "pointer-pending"
    die "the host succeeded but the remote state pointer write failed; rerun apply to reconcile"
fi

rm -f "$OUTSTANDING"
record_phase "success"
log "release $RELEASE_ID deployed and verified"
