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
# Compose interpolates the retained-volume bind mount from the environment.
export OCC_DATA_MOUNT
OCC_MIN_FREE_MB="${OCC_MIN_FREE_MB:-2048}"
OCC_PROJECT="${OCC_PROJECT:-occ-production}"
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
OPERATION_ID="${OCC_OPERATION_ID:-$ACTION-$RELEASE_ID-$(date +%Y%m%d%H%M%S)-$$}"
REFERENCE_FILE="${OCC_RELEASE_REFERENCE_FILE:-$RELEASE_DIR/reference.json}"

# A kernel lock is released even if the process or host dies.
exec 9>"$STATE/deploy.lock"
flock -n 9 || die "another deploy operation holds the deploy lock"

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

# A marker alone is not a mount. Require its verified UUID on an actual mountpoint.
[ -f "$OCC_DATA_MOUNT/.occ-data-marker" ] || die "durable data volume is not mounted"
MOUNT_UUID=$(findmnt --mountpoint "$OCC_DATA_MOUNT" --noheadings --output UUID) || die "durable data mount is absent"
python3 - "$OCC_DATA_MOUNT/.occ-data-marker" "$MOUNT_UUID" <<'PY'
import json,sys
marker=json.load(open(sys.argv[1]))
if marker.get('uuid')!=sys.argv[2].strip() or not marker.get('volumeId'): sys.exit('durable mount identity mismatch')
PY
for disk in "$OCC_DATA_MOUNT" "$OCC_HOME"; do
    FREE_MB=$(df -Pm "$disk" | awk 'NR==2 {print $4}')
    [ "${FREE_MB:-0}" -ge "$OCC_MIN_FREE_MB" ] || die "insufficient disk space"
done
python3 - "$REFERENCE_FILE" "$BUNDLE/manifest.json" "$OPERATION_ID" <<'PY'
import hashlib,json,re,sys
reference=json.load(open(sys.argv[1])); raw=open(sys.argv[2],'rb').read(); manifest=json.loads(raw)
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,99}',sys.argv[3]): sys.exit('unsafe operation ID')
if reference.get('releaseId')!=manifest['releaseId'] or reference.get('revision')!=manifest['revision']: sys.exit('release reference mismatch')
if reference.get('version') in (None,'','-','null'): sys.exit('immutable version required')
if reference.get('checksum')!='sha256:'+hashlib.sha256(raw).hexdigest(): sys.exit('manifest reference checksum mismatch')
PY

set -a
# shellcheck disable=SC1090
. "${OCC_DEPLOYMENT_ENV:?set OCC_DEPLOYMENT_ENV to the deployment environment file}"
set +a
# Image references are release-managed; drop any imported placeholder so the
# manifest pins below are the only source the Compose stack sees.
unset OCC_INGESTION_IMAGE OCC_DISRUPTION_IMAGE OCC_PLANNING_IMAGE \
    OCC_DB_SETUP_IMAGE OCC_WEB_IMAGE
# Prefer the CloudFront hostname recorded by the bootstrap for the public
# checks; fall back to the local origin listener.
OCC_PUBLIC_URL="${OCC_PUBLIC_URL:-${OCC_ORIGIN_PUBLIC_URL:-http://127.0.0.1:8080}}"

compose() {
    # Production enables the edge profile explicitly: local development resets
    # the nginx profile, but the immutable CloudFront stack keeps it gated.
    docker compose -p "$OCC_PROJECT" --env-file "$COMPOSE_ENV" --profile edge \
        -f "$BUNDLE/compose.yaml" -f "$BUNDLE/compose.images.yaml" \
        -f "$BUNDLE/compose.cloudfront.yaml" -f "$BUNDLE/compose.host-data.yaml" "$@"
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

# Secrets already exist as literal exported shell values. Only image pins need
# a Compose dotenv file; copying shell quoting into dotenv corrupts valid values.
COMPOSE_ENV="$RUNTIME/env"
python3 - "$BUNDLE/manifest.json" > "$COMPOSE_ENV" <<'PY'
import json,sys
for component, reference in json.load(open(sys.argv[1]))['images'].items():
    print('OCC_'+component.upper().replace('-','_')+'_IMAGE='+reference)
PY
chmod 0600 "$COMPOSE_ENV"

APPLIED_SETUP="$STATE/applied-setup.json"
ACTIVE_RELEASE="$STATE/active-release.json"

record_phase() {
    printf '{"operationId":"%s","phase":"%s","at":"%s"}\n' \
        "$OPERATION_ID" "$1" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$JOURNAL/$OPERATION_ID.json.log"
}

OCC_GATE_DIR="${OCC_GATE_DIR:?set OCC_GATE_DIR to the rendered traffic-gate include directory}"
export OCC_GATE_DIR

# An immutable prior manifest is never rewritten to mint rollback permission.
PREVIOUS_SETUP_FINGERPRINT=""
[ ! -f "$APPLIED_SETUP" ] || PREVIOUS_SETUP_FINGERPRINT=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["fingerprint"])' "$APPLIED_SETUP")
if [ "$ACTION" = rollback ]; then
    [ -f "$ACTIVE_RELEASE" ] || die "no previous release on this host"
    python3 - "$ACTIVE_RELEASE" "$APPLIED_SETUP" "$BUNDLE/manifest.json" "$RELEASES" "$REFERENCE_FILE" "$STATE/failed-release.json" <<'PY'
import hashlib,json,pathlib,sys
active=json.load(open(sys.argv[1])); applied=json.load(open(sys.argv[2])); target=json.load(open(sys.argv[3])); ref=json.load(open(sys.argv[5]))
if target['releaseId']==active['releaseId']:
    if not pathlib.Path(sys.argv[6]).exists(): sys.exit('rollback compatibility has no failed candidate to recover')
    source_ref=json.load(open(sys.argv[6])); baseline=active.get('setupFingerprint'); expected_ref=active.get('reference')
elif active.get('previous')==target['releaseId']:
    source_ref=active['reference']; baseline=active.get('previousSetupFingerprint'); expected_ref=active.get('previousReference')
else: sys.exit('rollback compatibility target is not the previous deployed release')
if ref!=expected_ref: sys.exit('rollback reference differs from pinned deployed history')
if applied['fingerprint']==baseline: sys.exit(0)
source_path=pathlib.Path(sys.argv[4])/source_ref['releaseId']/'bundle/manifest.json'
raw=source_path.read_bytes()
if 'sha256:'+hashlib.sha256(raw).hexdigest()!=source_ref['checksum']: sys.exit('rollback source manifest checksum mismatch')
current=json.loads(raw); evidence=current.get('compatibilityEvidence',{})
old=target.get('setupInputFiles'); new=current.get('setupInputFiles')
if old is None or new is None: sys.exit('rollback compatibility has no verified migration range')
changed=sorted(k for k in set(old)|set(new) if old.get(k)!=new.get(k))
if current.get('migrationCompatibility')!='backward-compatible' or evidence.get('rollbackReleaseId')!=target['releaseId'] or evidence.get('setupFingerprint')!=baseline or sorted(evidence.get('migrations',[]))!=changed:
    sys.exit('rollback compatibility does not cover actual deployed baseline and migration range')
PY
    # Rollback changes applications, not the successfully applied database identity.
    SETUP_FINGERPRINT="$PREVIOUS_SETUP_FINGERPRINT"
fi
OCC_GATE_DIR="${OCC_GATE_DIR:?set OCC_GATE_DIR}"
OCC_GATE_TOKEN=$(cat "$OCC_GATE_DIR/gate-token")
export OCC_GATE_DIR OCC_GATE_TOKEN
mkdir -p "$STATE/receipts"
RECEIPT="$STATE/receipts/$OPERATION_ID.json"
OUTSTANDING="$STATE/outstanding-operation.json"
HOST_VERIFIED=0

publish_receipt() {
    [ -n "${OCC_RELEASE_BUCKET:-}" ] || return 0
    if aws s3api put-object --bucket "$OCC_RELEASE_BUCKET" --key "state/receipts/$(basename "$RECEIPT")" \
        --body "$RECEIPT" --if-none-match '*' >/dev/null; then
        return 0
    fi
    # A retry may encounter the identical already-published immutable receipt.
    aws s3api get-object --bucket "$OCC_RELEASE_BUCKET" --key "state/receipts/$(basename "$RECEIPT")" \
        "$RECEIPT.remote" >/dev/null && cmp -s "$RECEIPT" "$RECEIPT.remote"
}
write_receipt() {
    python3 - "$RECEIPT" "$REFERENCE_FILE" "$ACTION" "$OPERATION_ID" "$1" "$2" "$SETUP_FINGERPRINT" <<'PY'
import json,os,sys
path,ref,action,operation,outcome,closed,fingerprint=sys.argv[1:]
reference=json.load(open(ref))
value=dict(operationId=operation,action=action,releaseId=reference['releaseId'],reference=reference,
           outcome=outcome,phase='verified' if outcome=='success' else 'failed',gateClosed=closed=='true',setupFingerprint=fingerprint)
with open(path+'.new','w') as handle:
    json.dump(value,handle,sort_keys=True); handle.flush(); os.fsync(handle.fileno())
os.replace(path+'.new',path)
PY
}
on_exit() {
    result=$?
    trap - EXIT INT TERM HUP
    if [ "$result" -ne 0 ] && [ "$HOST_VERIFIED" -eq 0 ]; then
        set +e
        closed=false
        if sh "$GATE_SCRIPT" close; then closed=true
        elif compose stop nginx; then closed=true
        fi
        cp "$REFERENCE_FILE" "$STATE/failed-release.json"
        write_receipt failed "$closed"
        publish_receipt
        rm -f "$OUTSTANDING"
        exit "$result"
    fi
    exit "$result"
}
trap on_exit EXIT
trap 'exit 143' INT TERM HUP
# A receipt-pending retry only republishes the exact completed receipt.
if [ -f "$STATE/pointer-pending.json" ]; then
    PENDING_RELEASE=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' "$STATE/pointer-pending.json")
    [ "$PENDING_RELEASE" = "$RELEASE_ID" ] || die "another release has pending receipt recovery"
    PENDING_ID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["operationId"])' "$STATE/pointer-pending.json")
    RECEIPT="$STATE/receipts/$PENDING_ID.json"
    HOST_VERIFIED=1
    publish_receipt || die "receipt publication still pending; do not repeat setup"
    rm -f "$STATE/pointer-pending.json"
    log "completed host receipt reconciled; remote driver must CAS the deployed pointer"
    exit 0
fi
[ ! -f "$STATE/setup-recovery-required.json" ] || die "partial setup requires explicit recovery before retry"
# ECR credentials expire; the instance role refreshes immediately before pulls.
REGISTRY=$(python3 -c 'import json,sys; print(next(iter(json.load(open(sys.argv[1]))["images"].values())).split("/")[0])' "$BUNDLE/manifest.json")
ECR_PASSWORD=$(aws ecr get-login-password)
printf '%s' "$ECR_PASSWORD" | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
unset ECR_PASSWORD

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

if [ "$ACTION" = apply ] && [ "$NEED_SETUP" -eq 1 ]; then
    log "setup or role-config identity changed: closing the gate and running the aggregate setup"
    OCC_GATE_TOKEN=$(cat "$OCC_GATE_DIR/gate-token")
    export OCC_GATE_TOKEN
    sh "$GATE_SCRIPT" close
    GATE_WAS_CLOSED=1
    record_phase "gate-closed"
    compose stop nginx ingestion disruption planning >/dev/null 2>&1
    record_phase "writers-stopped"
    # PostgreSQL stays up (and is started on first install); the aggregate
    # setup requires it healthy before any schema work.
    compose up -d --no-build --wait --wait-timeout "$OCC_READINESS_TIMEOUT" postgres
    compose exec -T postgres sh -c 'pg_isready -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
        >/dev/null || die "PostgreSQL is not healthy; setup refused"
    log "running the combined db-setup once (ingestion -> disruption -> planning)"
    if ! compose --profile setup run --rm --no-deps db-setup; then
        record_phase "setup-failed"
        printf '{"operationId":"%s","releaseId":"%s"}\n' "$OPERATION_ID" "$RELEASE_ID" > "$STATE/setup-recovery-required.json"
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
compose up -d --no-build --wait --wait-timeout "$OCC_READINESS_TIMEOUT"
record_phase "stack-up"

# Backend readiness first: nginx health alone proves nothing. Each service's
# internal port variable name matches its Compose environment entry, with the
# documented per-service defaults as fallback.
READY_DEADLINE=$(( $(date +%s) + OCC_READINESS_TIMEOUT ))
for service in ingestion disruption planning; do
    case "$service" in
        ingestion) port="${OCC_INGESTION_INTERNAL_PORT:-8001}" ;;
        disruption) port="${OCC_DISRUPTION_INTERNAL_PORT:-8002}" ;;
        planning) port="${OCC_PLANNING_INTERNAL_PORT:-8003}" ;;
    esac
    log "waiting for $service readiness on port $port"
    while :; do
        if compose exec -T "$service" sh -c \
            "curl --fail --silent --max-time 5 http://127.0.0.1:$port/actuator/health/readiness" \
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
# The deployment env is already sourced above; the shell strips the quoting
# that the Compose env file keeps verbatim.
ORIGIN_SECRET_HEADER="${OCC_ORIGIN_SECRET:-}"
# Expect the closed-gate response only when the gate is actually closed; a
# routine release with unchanged setup leaves it open.
GATE_STATE=$(sed -n 's/^set [$]occ_traffic_gate "\(.*\)";/\1/p' \
    "$OCC_GATE_DIR/traffic-gate.inc" 2>/dev/null || true)
case "$GATE_STATE" in
    open) edge_expect=204 ;;
    *) edge_expect=503 ;;
esac
for check in "_occ_gate_readiness:204" "_occ_edge_health:$edge_expect"; do
    path=${check%%:*}
    expected=${check##*:}
    STATUS=$(curl --connect-timeout 5 --max-time 20 --silent --output /dev/null --write-out '%{http_code}' \
        --header "X-OCC-Origin-Secret: $ORIGIN_SECRET_HEADER" \
        --header "X-OCC-Gate-Token: $GATE_TOKEN" "$OCC_PUBLIC_URL/$path" || printf '000')
    [ "$STATUS" = "$expected" ] || {
        record_phase "origin-check-failed"
        rm -f "$OUTSTANDING"
        die "origin check failed: /$path returned $STATUS, expected $expected"
    }
done
record_phase "origin-checked"

# Open the gate only when it is closed: this completes a setup-gated deploy
# and recovers a gate left closed by an earlier failure, without reloading
# nginx on every routine release.
if [ "$GATE_STATE" != "open" ]; then
    log "opening the traffic gate and validating the public URL"
    sh "$GATE_SCRIPT" open
fi
record_phase "gate-open"
for path in "" "frontend-config" "login"; do
    STATUS=$(OCC_CURL_PHASE=public curl --connect-timeout 5 --max-time 20 --silent --output /dev/null --write-out '%{http_code}' \
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

# Commit local host state only after all readiness/public checks have passed.
python3 - "$ACTIVE_RELEASE" "$RELEASE_ID" "$REVISION" "$SETUP_FINGERPRINT" "$PREVIOUS_SETUP_FINGERPRINT" "$REFERENCE_FILE" <<'PY'
import json,os,sys
path,release_id,revision,fingerprint,prior_fingerprint,ref=sys.argv[1:]
try: old=json.load(open(path))
except FileNotFoundError: old={}
value=dict(releaseId=release_id,revision=revision,setupFingerprint=fingerprint,reference=json.load(open(ref)))
if old.get('releaseId')==release_id:
    value.update(previous=old.get('previous'),previousReference=old.get('previousReference'),previousSetupFingerprint=old.get('previousSetupFingerprint'))
else:
    value.update(previous=old.get('releaseId'),previousReference=old.get('reference'),previousSetupFingerprint=prior_fingerprint)
with open(path+'.new','w') as handle:
    json.dump(value,handle); handle.flush(); os.fsync(handle.fileno())
os.replace(path+'.new',path)
PY
cp "$REFERENCE_FILE" "$STATE/last-deployed.json"
write_receipt success false
HOST_VERIFIED=1
if ! publish_receipt; then
    printf '{"operationId":"%s","releaseId":"%s"}\n' "$OPERATION_ID" "$RELEASE_ID" > "$STATE/pointer-pending.json"
    rm -f "$OUTSTANDING"
    die "host verified but receipt publication failed; reconcile without repeating setup"
fi
rm -f "$OUTSTANDING"
record_phase "success"
log "release $RELEASE_ID verified; remote driver owns conditional deployed-pointer advancement"
