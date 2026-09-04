#!/usr/bin/env bash
# clip-shot.sh — capture full screen on Desktop Wayland and auto-send to Phone gallery

FILE="/tmp/shot_$(date +%Y%m%d_%H%M%S).png"

# Capture full screen directly using grim (no area selection prompt)
if command -v grim >/dev/null 2>&1; then
    grim "$FILE"
elif command -v maim >/dev/null 2>&1; then
    maim "$FILE"
elif command -v import >/dev/null 2>&1; then
    import -window root "$FILE"
else
    echo "No screenshot tool found (install grim or maim)"
    exit 1
fi

if [ -f "$FILE" ]; then
    echo "Captured full screen: $FILE — sending to Phone..."
    bash "$(dirname "$0")/clip-send.sh" "$FILE"
    rm -f "$FILE"
fi
