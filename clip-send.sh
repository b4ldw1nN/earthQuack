#!/usr/bin/env bash
# clip-send — send a file from Desktop to Phone
# Usage: clip-send <file> [<file2> ...]
# Files land in Downloads/ClipboardSync/ on the phone.

# Resolve Tailscale IP dynamically
if [ -z "$CLIPBOARD_SERVER_HOST" ] || [ "$CLIPBOARD_SERVER_HOST" = "YOUR_TAILSCALE_IP" ]; then
  # Try python discover module first if python3 is available
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  if command -v python3 >/dev/null 2>&1 && [ -f "$SCRIPT_DIR/daemon/tailscale_discovery.py" ]; then
    DISCOVERED=$(python3 -c "import sys; sys.path.insert(0, '$SCRIPT_DIR/daemon'); from tailscale_discovery import discover_clipboard_server; print(discover_clipboard_server() or '')" 2>/dev/null)
    if [ -n "$DISCOVERED" ]; then
      CLIPBOARD_SERVER_HOST="$DISCOVERED"
    fi
  fi
  # Fallback to local machine's Tailscale IPv4 if no peer server was found
  if [ -z "$CLIPBOARD_SERVER_HOST" ] || [ "$CLIPBOARD_SERVER_HOST" = "YOUR_TAILSCALE_IP" ]; then
    CLIPBOARD_SERVER_HOST=$(tailscale ip -4 2>/dev/null | head -n1)
  fi
fi

FILE_SERVER="${CLIPBOARD_FILE_SERVER:-http://${CLIPBOARD_SERVER_HOST:-127.0.0.1}:${CLIPBOARD_FILE_PORT:-8876}}"

if [ $# -eq 0 ]; then
  echo "Usage: clip-send <file> [<file2> ...]"
  exit 1
fi

for FILE in "$@"; do
  if [ ! -f "$FILE" ]; then
    echo "Not a file: $FILE"
    continue
  fi
  NAME=$(basename "$FILE")
  SIZE=$(stat -c%s "$FILE")
  if command -v numfmt >/dev/null 2>&1; then
    HSIZE=$(numfmt --to=iec "$SIZE")
  else
    HSIZE="${SIZE}B"
  fi
  echo "→ Sending  $NAME  ($HSIZE)"
  CODE=$(curl -s --connect-timeout 5 -m 120 -o /dev/null -w "%{http_code}" -X POST "$FILE_SERVER/upload" \
    -H "X-Filename: $NAME" \
    -H "X-Origin: desktop" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@$FILE")
  echo ""
  if [ "$CODE" = "200" ]; then
    echo " Sent  $NAME — phone will download automatically"
  else
    echo "✗ Failed (HTTP $CODE)"
  fi
done

