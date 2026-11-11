#!/bin/sh
# OCC traffic gate: close or open public access at the Nginx edge.
#
#   traffic-gate.sh close|open
#
# The gate include is mounted read-only into the Nginx containers at
# /etc/nginx/gate/traffic-gate.inc. Closing writes the closed state and the
# gate token into that include and reloads Nginx; while closed only the
# secret-bearing readiness request is served and every other request gets
# 503. The rendered configuration and the token are never logged.
set -eu

OCC_GATE_DIR="${OCC_GATE_DIR:?set OCC_GATE_DIR to the rendered traffic-gate include directory}"
OCC_GATE_TOKEN="${OCC_GATE_TOKEN:?set OCC_GATE_TOKEN to the deployment gate token}"
OCC_PROJECT="${OCC_PROJECT:-occ-production}"
OCC_GATE_LOG="${OCC_GATE_LOG:-$OCC_GATE_DIR/gate.log}"

[ "$#" -eq 1 ] || { echo "usage: traffic-gate.sh close|open" >&2; exit 2; }

mkdir -p "$OCC_GATE_DIR"
TOUCH_TERM="101:101"

case "$1" in
    close)
        # Nginx variables must remain literal.
        # shellcheck disable=SC2016
        printf 'set $occ_traffic_gate "closed";\nset $occ_gate_token "%s";\n' "$OCC_GATE_TOKEN" \
            > "$OCC_GATE_DIR/traffic-gate.inc.new"
        mv "$OCC_GATE_DIR/traffic-gate.inc.new" "$OCC_GATE_DIR/traffic-gate.inc"
        chown "$TOUCH_TERM" "$OCC_GATE_DIR/traffic-gate.inc" 2>/dev/null || true
        chmod 0640 "$OCC_GATE_DIR/traffic-gate.inc"
        ;;
    open)
        # The token stays defined even while open: the readiness location
        # references it at configuration parse time.
        # shellcheck disable=SC2016
        printf 'set $occ_traffic_gate "open";\nset $occ_gate_token "%s";\n' "$OCC_GATE_TOKEN" \
            > "$OCC_GATE_DIR/traffic-gate.inc.new"
        mv "$OCC_GATE_DIR/traffic-gate.inc.new" "$OCC_GATE_DIR/traffic-gate.inc"
        chown "$TOUCH_TERM" "$OCC_GATE_DIR/traffic-gate.inc" 2>/dev/null || true
        chmod 0640 "$OCC_GATE_DIR/traffic-gate.inc"
        ;;
    *)
        echo "usage: traffic-gate.sh close|open" >&2
        exit 2
        ;;
esac

# Validate and reload through the running container; never print rendered
# configuration (nginx -T) and never echo the token. Locating the container
# by Compose labels avoids needing a Compose file in the caller's directory.
NGINX_CONTAINERS=$(docker ps --quiet \
    --filter "label=com.docker.compose.project=$OCC_PROJECT" \
    --filter "label=com.docker.compose.service=nginx") || exit 1
NGINX_CONTAINER=$(printf '%s\n' "$NGINX_CONTAINERS" | head -n 1)
if [ -z "$NGINX_CONTAINER" ]; then
    # The stack may not be running yet (first install): the include takes
    # effect at the next start, which is still gated because it starts closed.
    echo "traffic-gate: nginx not running; gate takes effect on next start"
elif docker exec "$NGINX_CONTAINER" nginx -t >/dev/null 2>&1; then
    docker exec "$NGINX_CONTAINER" nginx -s reload >/dev/null 2>&1
else
    echo "traffic-gate: nginx rejected the rendered gate configuration" >&2
    exit 1
fi
printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "gate $1" >> "$OCC_GATE_LOG"
