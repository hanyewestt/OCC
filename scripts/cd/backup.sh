#!/bin/sh
# Keep the documented host entrypoint; policy and structured AWS data live in Python.
set -eu
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$SCRIPT_DIR/backup.py" "$@"
