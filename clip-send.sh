#!/usr/bin/env bash
# clip-send — send a file from Desktop to Phone
# Usage: clip-send <file> [<file2> ...]
# Files land in Downloads/ClipboardSync/ on the phone.

# Resolve Tailscale IP dynamically
if [ -z "$CLIPBOARD_SERVER_HOST" ] || [ "$CLIPBOARD_SERVER_HOST" = "YOUR_TAILSCALE_IP" ]; then
  CLIPBOARD_SERVER_HOST=$(tailscale ip -4 2>/dev/null | head -n1)
fi

FILE_SERVER="${CLIPBOARD_FILE_SERVER:-http://${CLIPBOARD_SERVER_HOST:-127.0.0.1}:${CLIPBOARD_FILE_PORT:-8876}}"

# The in-process sync services require the shared bearer token. The Python
# daemon accepted any caller, so the header is only added when a token is
# actually present — that keeps this script working against both.
AUTH_ARGS=()
if [ -n "$EARTHQUACK_AUTH_TOKEN" ]; then
  AUTH_ARGS=(-H "Authorization: Bearer $EARTHQUACK_AUTH_TOKEN")
fi

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
    "${AUTH_ARGS[@]}" \
    -H "X-Filename: $NAME" \
    -H "X-Origin: desktop" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@$FILE")
  echo ""
  if [ "$CODE" = "200" ]; then
    echo " Sent  $NAME — phone will download automatically"
  else
    echo "✗ Failed (HTTP $CODE)"
    if [ "$CODE" = "401" ] || [ "$CODE" = "503" ]; then
      echo "  The sync services need EARTHQUACK_AUTH_TOKEN set and matching the node."
    fi
  fi
done

