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

DATA_DEVICE="${OCC_DATA_DEVICE:-/dev/nvme1n1}"
DATA_MOUNT_POINT=/var/lib/postgresql
DATA_UUID_FILE=/etc/occ/data-volume-uuid
ENV_DIR=/opt/occ

echo "Installing container runtime..."
dnf install -y docker iptables-nft
systemctl enable --now docker
mkdir -p /usr/libexec/docker/cli-plugins
curl --fail --silent --show-error --location \
    "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64" \
    --output /usr/libexec/docker/cli-plugins/docker-compose
chmod 0755 /usr/libexec/docker/cli-plugins/docker-compose

echo "Preparing the durable data volume..."
if [ ! -b "$DATA_DEVICE" ]; then
    echo "error: data device $DATA_DEVICE is not present" >&2
    exit 1
fi

# Refuse to touch anything that already carries a filesystem other than the
# recorded one; formatting is only ever allowed on a completely blank volume.
FILESYSTEM_KIND="$(blkid -s TYPE -o value "$DATA_DEVICE" || true)"
RECORDED_UUID="$(cat "$DATA_UUID_FILE" 2>/dev/null || true)"
ACTUAL_UUID="$(blkid -s UUID -o value "$DATA_DEVICE" || true)"
if [ -n "$FILESYSTEM_KIND" ]; then
    if [ -z "$RECORDED_UUID" ] || [ "$ACTUAL_UUID" != "$RECORDED_UUID" ]; then
        echo "error: $DATA_DEVICE already contains a filesystem ($FILESYSTEM_KIND, uuid $ACTUAL_UUID) " \
             "that does not match the recorded data volume; refusing to format or mount it" >&2
        exit 1
    fi
else
    if [ -n "$RECORDED_UUID" ]; then
        echo "error: the recorded data volume lost its filesystem; refusing to reformat automatically" >&2
        exit 1
    fi
    echo "First boot: creating the encrypted-volume filesystem on the blank data volume..."
    mkfs.ext4 -L occ-postgresql "$DATA_DEVICE"
    ACTUAL_UUID="$(blkid -s UUID -o value "$DATA_DEVICE")"
    mkdir -p /etc/occ
    printf '%s\n' "$ACTUAL_UUID" > "$DATA_UUID_FILE"
    chmod 0600 "$DATA_UUID_FILE"
fi

# Systemd mounts the validated UUID before Docker starts; a missing or
# failing mount blocks the host from ever running the application stack.
cat > /etc/systemd/system/opt-occ-data.mount <<UNIT
[Unit]
Description=OCC durable PostgreSQL data volume
Requires=local-fs.target
Before=docker.service
[Mount]
What=UUID=$ACTUAL_UUID
Where=$DATA_MOUNT_POINT
Type=ext4
Options=noatime
UNIT
systemctl daemon-reload
mkdir -p "$DATA_MOUNT_POINT"
if ! systemctl start "$(systemd-escape --path --suffix mount "$DATA_MOUNT_POINT")"; then
    echo "error: the durable data volume could not be mounted; startup blocked" >&2
    exit 1
fi
systemctl enable "$(systemd-escape --path --suffix mount "$DATA_MOUNT_POINT")"

echo "Writing host secrets (values are never logged)..."
SECRET_PAYLOAD="$ENV_DIR/env"
mkdir -p "$ENV_DIR" /run/occ
chmod 0700 "$ENV_DIR" /run/occ
aws secretsmanager get-secret-value \
    --secret-id "${OCC_DEPLOYMENT_SECRET_NAME:?set OCC_DEPLOYMENT_SECRET_NAME}" \
    --query SecretString --output text > "$ENV_DIR/env.rendered"
chmod 0600 "$ENV_DIR/env.rendered"
# The rendered runtime environment: only the deployer and root may read it.
chmod 0600 "$ENV_DIR/env"
aws secretsmanager get-secret-value \
    --secret-id "${OCC_ORIGIN_SECRET_NAME:?set OCC_ORIGIN_SECRET_NAME}" \
    --query SecretString --output text > /run/occ/origin-secret
chmod 0600 /run/occ/origin-secret

echo "Logging in to ECR..."
aws ecr get-login-password | docker login --username AWS --password-stdin "${OCC_ACCOUNT_ID:?set OCC_ACCOUNT_ID}.dkr.ecr.${OCC_REGION:?set OCC_REGION}.amazonaws.com"

echo "Host bootstrap complete."
