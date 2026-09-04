#!/bin/bash
# Wrapper to ensure WAYLAND_DISPLAY/XDG_RUNTIME_DIR are correct even after reboot
# Hyprland imports them via import-environment, but service may start before that.
if [ -z "$WAYLAND_DISPLAY" ]; then
  for var in WAYLAND_DISPLAY DISPLAY XDG_RUNTIME_DIR; do
    val=$(systemctl --user show-environment 2>/dev/null | grep "^$var=" | cut -d= -f2-)
    if [ -n "$val" ]; then
      export $var="$val"
    fi
  done
fi
if [ -z "$WAYLAND_DISPLAY" ] || [ ! -e "/run/user/$(id -u)/${WAYLAND_DISPLAY}" ]; then
  for sock in /run/user/$(id -u)/wayland-*; do
    [ -S "$sock" ] || continue
    case "$sock" in
      *.lock) continue;;
      *-awww*) continue;;
      *) export WAYLAND_DISPLAY=$(basename "$sock"); break;;
    esac
  done
fi
export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}
export DISPLAY=${DISPLAY:-:0}
# Wait for Wayland socket (Hyprland may not have started yet at boot)
for i in $(seq 1 30); do
  if [ -n "$WAYLAND_DISPLAY" ] && [ -S "/run/user/$(id -u)/${WAYLAND_DISPLAY}" ]; then
    break
  fi
  for sock in /run/user/$(id -u)/wayland-*; do
    [ -S "$sock" ] || continue
    case "$sock" in
      *.lock) continue;;
      *-awww*) continue;;
      *) export WAYLAND_DISPLAY=$(basename "$sock"); break 2;;
    esac
  done
  sleep 1
done
# Wait for local Tailscale IP if needed
if [ -z "$CLIPBOARD_SERVER_HOST" ] || [ "$CLIPBOARD_SERVER_HOST" = "YOUR_TAILSCALE_IP" ]; then
  for i in $(seq 1 15); do
    LOCAL_TS=$(tailscale ip -4 2>/dev/null | head -n1)
    if [ -n "$LOCAL_TS" ]; then
      export CLIPBOARD_SERVER_HOST="$LOCAL_TS"
      break
    fi
    sleep 1
  done
fi
exec /home/zoro/clipboard-sync/clipsyncd
