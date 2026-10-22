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
  CLIPBOARD_SERVER_HOST=$(tailscale ip -4 2>/dev/null | head -n1)
fi
HOST="${CLIPBOARD_SERVER_HOST:-127.0.0.1}"
PORT="${CLIPBOARD_SERVER_PORT:-8875}"

# The in-process sync services require the shared bearer token; the Python
# daemon accepted any caller. Send it when one is available.
AUTH_ARGS=()
if [ -n "$EARTHQUACK_AUTH_TOKEN" ]; then
  AUTH_ARGS=(-H "Authorization: Bearer $EARTHQUACK_AUTH_TOKEN")
fi

CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://$HOST:$PORT/signal" \
  "${AUTH_ARGS[@]}" \
  -H "Content-Type: application/json" \
  -d "{\"type\":\"open_url\",\"url\":\"$URL\"}")

if [ "$CODE" = "200" ]; then
  echo "Opened $URL on Phone"
else
  echo "✗ Failed to signal the phone (HTTP $CODE)"
  if [ "$CODE" = "401" ] || [ "$CODE" = "503" ]; then
    echo "  The sync services need EARTHQUACK_AUTH_TOKEN set and matching the node."
  fi
  exit 1
fi
