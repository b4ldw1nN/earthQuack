#!/bin/bash
# earthQuack daemon launcher — run MANUALLY whenever you want sync.
# Starts server.py (8875) + file_server.py (8876) + desktop bridge + folder watch.
# Uses the AES key that matches the earthQuack Android app, so clipboard
# decrypts to plaintext in the app.
#
# Usage:  ./run-earthquack.sh
# Stop  :  Ctrl+C  (or kill the process)
set -eou pipefail
cd "$(dirname "$0")"

export EARTHQUACK_HOST="${EARTHQUACK_HOST:-100.92.160.31}"
export EARTHQUACK_PORT="${EARTHQUACK_PORT:-8875}"
export EARTHQUACK_FILE_PORT="${EARTHQUACK_FILE_PORT:-8876}"
export CLIPBOARD_SERVER_HOST="$EARTHQUACK_HOST"
export CLIPBOARD_SERVER_PORT="$EARTHQUACK_PORT"
export CLIPBOARD_FILE_PORT="$EARTHQUACK_FILE_PORT"

# AES key — MUST match the earthQuack Android app, otherwise clipboard
# won't decrypt to plaintext. It is intentionally NOT stored in this
# file (never commit secrets): pass it in the environment.
#   CLIPBOARD_AES_KEY='<your-key>' ./run-earthquack.sh
# If unset, AES is disabled (clipboard travels unencrypted).
if [ -z "${CLIPBOARD_AES_KEY:-}" ]; then
  echo "[earthQuack] WARNING: CLIPBOARD_AES_KEY not set — AES disabled." >&2
fi
export CLIPBOARD_AES_KEY="${CLIPBOARD_AES_KEY:-}"

echo "[earthQuack] starting with AES enabled and key set"
exec python3 app.py