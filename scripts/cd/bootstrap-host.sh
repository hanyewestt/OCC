#!/bin/sh
# Prepare the OCC EC2 host for deployments. Run through SSM Run Command
# (scripts/cd/deploy.sh in a later task) or a one-off SSM session.
#
# Responsibilities:
#   - install Docker and the Compose plugin from the AL2023 repositories;
#   - prepare the durable data volume: mount the validated filesystem UUID
#     before Docker or PostgreSQL ever start, and block boot on failure;
#   - fetch the deployment and origin secrets into root-only files;
#   - log in to ECR for image pulls.
#
# The script never formats an existing retained volume and never falls back
# to a root-disk data directory.

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OCC_DATA_VOLUME_ID="${OCC_DATA_VOLUME_ID:?set OCC_DATA_VOLUME_ID from the stack output}"
DATA_DEVICE=$(python3 "$SCRIPT_DIR/volume.py" resolve "$OCC_DATA_VOLUME_ID")
DATA_MOUNT_POINT=/var/lib/postgresql
DATA_UUID_FILE=/etc/occ/data-volume-uuid
ENV_DIR=/opt/occ

[ -b "$DATA_DEVICE" ] || { echo "error: designated EBS device is absent" >&2; exit 1; }
# The authoritative filesystem UUID survives root-disk replacement on the EBS tag.
EXPECTED_UUID=$(aws ec2 describe-volumes --volume-ids "$OCC_DATA_VOLUME_ID" \
    --query 'Volumes[0].Tags[?Key==`occ:filesystem-uuid`].Value | [0]' --output text)
[ "$EXPECTED_UUID" != "None" ] || EXPECTED_UUID=""
FILESYSTEM_KIND=$(blkid -s TYPE -o value "$DATA_DEVICE" || true)
ACTUAL_UUID=$(blkid -s UUID -o value "$DATA_DEVICE" || true)
VOLUME_ACTION=$(python3 "$SCRIPT_DIR/volume.py" check "$FILESYSTEM_KIND" "$ACTUAL_UUID" \
    "$EXPECTED_UUID" "${OCC_INITIALIZE_DATA_VOLUME:-false}")
if [ "$VOLUME_ACTION" = initialize ]; then
    # Existing filesystems never reach mkfs, even with initialize=true.
    mkfs.ext4 -L occ-postgresql "$DATA_DEVICE"
    ACTUAL_UUID=$(blkid -s UUID -o value "$DATA_DEVICE")
    aws ec2 create-tags --resources "$OCC_DATA_VOLUME_ID" \
        --tags "Key=occ:filesystem-uuid,Value=$ACTUAL_UUID"
fi
mkdir -p /etc/occ
printf '%s\n' "$ACTUAL_UUID" > "$DATA_UUID_FILE"
printf '%s\n' "$OCC_DATA_VOLUME_ID" > /etc/occ/data-volume-id
chmod 0600 "$DATA_UUID_FILE" /etc/occ/data-volume-id

# Install boot dependencies before Docker can start containers.
mkdir -p /etc/systemd/system/docker.service.d
cat > /etc/systemd/system/var-lib-postgresql.mount <<UNIT
[Unit]
Description=OCC durable PostgreSQL data volume
Before=docker.service
[Mount]
What=UUID=$ACTUAL_UUID
Where=$DATA_MOUNT_POINT
Type=ext4
Options=noatime
[Install]
WantedBy=local-fs.target
UNIT
cat > /etc/systemd/system/docker.service.d/occ-data.conf <<UNIT
[Unit]
RequiresMountsFor=$DATA_MOUNT_POINT
Requires=occ-gate-close.service
After=occ-gate-close.service
UNIT
# A reboot never exposes the old stack before an explicit checked apply/resume.
cat > /etc/occ/close-gate.sh <<'SH'
#!/bin/sh
set -eu
file=/opt/occ/runtime/nginx-gate/traffic-gate.inc
[ ! -f "$file" ] || sed -i 's/"open"/"closed"/' "$file"
SH
chmod 0700 /etc/occ/close-gate.sh
cat > /etc/systemd/system/occ-gate-close.service <<'UNIT'
[Unit]
Description=Close OCC traffic before container startup
Before=docker.service
[Service]
Type=oneshot
ExecStart=/bin/sh /etc/occ/close-gate.sh
RemainAfterExit=yes
UNIT
systemctl daemon-reload
mkdir -p "$DATA_MOUNT_POINT"
systemctl enable --now var-lib-postgresql.mount
findmnt --mountpoint "$DATA_MOUNT_POINT" --source "UUID=$ACTUAL_UUID" >/dev/null || {
    echo "error: durable data mount identity could not be verified" >&2
    exit 1
}
echo "Installing container runtime..."
dnf install -y docker iptables-nft
mkdir -p /usr/libexec/docker/cli-plugins
curl --fail --silent --show-error --location \
    "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64" \
    --output /usr/libexec/docker/cli-plugins/docker-compose
chmod 0755 /usr/libexec/docker/cli-plugins/docker-compose
systemctl enable --now docker

# Proof that the retained volume is the one mounted where PostgreSQL writes.
# deploy.sh treats a missing marker as "not mounted" and blocks startup.
printf '{"volumeId":"%s","uuid":"%s"}\n' "$OCC_DATA_VOLUME_ID" "$ACTUAL_UUID" > "$DATA_MOUNT_POINT/.occ-data-marker"
chmod 0600 "$DATA_MOUNT_POINT/.occ-data-marker"

echo "Writing host secrets (values are never logged)..."
mkdir -p "$ENV_DIR" /run/occ
chmod 0700 "$ENV_DIR" /run/occ

# The deployment secret is a JSON object of KEY=VALUE pairs written by the
# bootstrap tool. Render it to a shell-sourceable env file; sourcing raw JSON
# would be a syntax error in deploy.sh.
aws secretsmanager get-secret-value \
    --secret-id "${OCC_DEPLOYMENT_SECRET_NAME:?set OCC_DEPLOYMENT_SECRET_NAME}" \
    --query SecretString --output text > "$ENV_DIR/env.json"
chmod 0600 "$ENV_DIR/env.json"
python3 - "$ENV_DIR/env.json" "$ENV_DIR/env.rendered" <<'PY'
import json
import sys

settings = json.load(open(sys.argv[1], encoding="utf-8"))
with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as handle:
    for key, value in sorted(settings.items()):
        if not key or "=" in key:
            continue
        value = str(value).replace("'", "'\\''")
        handle.write(f"export {key}='{value}'\n")
PY
rm -f "$ENV_DIR/env.json"
chmod 0600 "$ENV_DIR/env.rendered"

# The imported .env may carry empty placeholders for these two values; drop
# them so the generated secret is the only definition (shell last-wins, but
# Compose env files should not carry duplicate keys).
grep -vE '^export (OCC_ORIGIN_SECRET|OCC_GATE_TOKEN)=' "$ENV_DIR/env.rendered" \
    > "$ENV_DIR/env.rendered.tmp" || true
mv "$ENV_DIR/env.rendered.tmp" "$ENV_DIR/env.rendered"
chmod 0600 "$ENV_DIR/env.rendered"

# The origin secret goes into the deployment env (compose.cloudfront.yaml
# requires OCC_ORIGIN_SECRET) and into a root-only file for the gate.
aws secretsmanager get-secret-value \
    --secret-id "${OCC_ORIGIN_SECRET_NAME:?set OCC_ORIGIN_SECRET_NAME}" \
    --query SecretString --output text > /run/occ/origin-secret
chmod 0600 /run/occ/origin-secret
ORIGIN_SECRET_VALUE=$(cat /run/occ/origin-secret)
printf "export OCC_ORIGIN_SECRET='%s'\n" "$ORIGIN_SECRET_VALUE" >> "$ENV_DIR/env.rendered"
unset ORIGIN_SECRET_VALUE

# The gate token guards the closed-traffic readiness request; generate it
# once from the safe alphabet the nginx include can hold verbatim.
if [ ! -f /run/occ/gate-token ]; then
    GATE_TOKEN="$(tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 48)"
    printf '%s\n' "$GATE_TOKEN" > /run/occ/gate-token
    chmod 0600 /run/occ/gate-token
fi
GATE_TOKEN_VALUE=$(cat /run/occ/gate-token)
printf "export OCC_GATE_TOKEN='%s'\n" "$GATE_TOKEN_VALUE" >> "$ENV_DIR/env.rendered"
unset GATE_TOKEN_VALUE
# The gate include lives in the runtime dir deploy.sh hands to the gate.
mkdir -p "$ENV_DIR/runtime/nginx-gate"
cp /run/occ/gate-token "$ENV_DIR/runtime/nginx-gate/gate-token"
chmod 0600 "$ENV_DIR/runtime/nginx-gate/gate-token"

echo "Logging in to ECR..."
aws ecr get-login-password | docker login --username AWS --password-stdin "${OCC_ACCOUNT_ID:?set OCC_ACCOUNT_ID}.dkr.ecr.${OCC_REGION:?set OCC_REGION}.amazonaws.com"

echo "Host bootstrap complete."
