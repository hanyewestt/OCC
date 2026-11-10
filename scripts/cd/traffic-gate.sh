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
        printf 'set $occ_traffic_gate "closed";\nset $occ_gate_token "%s";\n' "$OCC_GATE_TOKEN" \
            > "$OCC_GATE_DIR/traffic-gate.inc.new"
        mv "$OCC_GATE_DIR/traffic-gate.inc.new" "$OCC_GATE_DIR/traffic-gate.inc"
        chown "$TOUCH_TERM" "$OCC_GATE_DIR/traffic-gate.inc" 2>/dev/null || true
        chmod 0640 "$OCC_GATE_DIR/traffic-gate.inc"
        ;;
    open)
        # The token stays defined even while open: the readiness location
        # references it at configuration parse time.
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
# configuration (nginx -T) and never echo the token.
if docker compose -p "$OCC_PROJECT" exec -T nginx nginx -t >/dev/null 2>&1; then
    docker compose -p "$OCC_PROJECT" exec -T nginx nginx -s reload >/dev/null 2>&1 || true
else
    # The stack may not be running yet (first install): the include takes
    # effect at the next start, which is still gated because it starts closed.
    echo "traffic-gate: nginx not running; gate takes effect on next start"
fi
printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "gate $1" >> "$OCC_GATE_LOG"
