#!/usr/bin/env bash
# clip-open.sh — open a URL on Phone from Desktop Linux terminal or current clipboard content

URL="$1"

# If no argument passed, read URL from Wayland/X11 clipboard
if [ -z "$URL" ]; then
    URL=$(wl-paste --no-newline 2>/dev/null || xclip -selection clipboard -o 2>/dev/null || xsel -b -o 2>/dev/null)
fi

if [ -z "$URL" ]; then
    echo "Usage: clip-open <url> or copy a URL to clipboard first"
    exit 1
fi

# Ensure URL has protocol
if [[ "$URL" != http://* ]] && [[ "$URL" != https://* ]]; then
    URL="https://$URL"
fi

if [ -z "$CLIPBOARD_SERVER_HOST" ] || [ "$CLIPBOARD_SERVER_HOST" = "YOUR_TAILSCALE_IP" ]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  if command -v python3 >/dev/null 2>&1 && [ -f "$SCRIPT_DIR/daemon/tailscale_discovery.py" ]; then
    DISCOVERED=$(python3 -c "import sys; sys.path.insert(0, '$SCRIPT_DIR/daemon'); from tailscale_discovery import discover_clipboard_server; print(discover_clipboard_server() or '')" 2>/dev/null)
    if [ -n "$DISCOVERED" ]; then
      CLIPBOARD_SERVER_HOST="$DISCOVERED"
    fi
  fi
fi
HOST="${CLIPBOARD_SERVER_HOST:-127.0.0.1}"
PORT="${CLIPBOARD_SERVER_PORT:-8875}"
curl -s -X POST "http://$HOST:$PORT/signal" \
  -H "Content-Type: application/json" \
  -d "{\"type\":\"open_url\",\"url\":\"$URL\"}"

echo "Opened $URL on Phone"
