#!/usr/bin/env python3
"""
win-shot.py — Capture full screen on Windows and auto-send to Phone gallery.
Can be triggered silently via hotkey or shortcut.
"""
import os
import sys
import time
import tempfile
import urllib.request
from pathlib import Path

def capture_and_send():
    try:
        from PIL import ImageGrab
    except ImportError:
        print("[win-shot] Error: Pillow required for screenshot capture. Install with: pip install Pillow", flush=True)
        return

    img = ImageGrab.grab(all_screens=True)
    filename = f"shot_{time.strftime('%Y%m%d_%H%M%S')}.png"
    filepath = Path(tempfile.gettempdir()) / filename
    img.save(filepath, "PNG")

    host = os.environ.get("CLIPBOARD_SERVER_HOST", "")
    port = os.environ.get("CLIPBOARD_FILE_PORT", "8876")
    if not host or host == "YOUR_TAILSCALE_IP":
        try:
            sys.path.insert(0, str(Path(__file__).parent / "daemon"))
            from tailscale_discovery import discover_clipboard_server
            discovered = discover_clipboard_server(port=8875)
            host = discovered if discovered else "127.0.0.1"
        except Exception:
            host = "127.0.0.1"

    url = f"http://{host}:{port}/upload"

    try:
        with open(filepath, "rb") as f:
            data = f.read()
        req = urllib.request.Request(
            url, data=data,
            headers={"X-Filename": filename, "X-Origin": "desktop", "Content-Type": "application/octet-stream"},
            method="POST"
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            if resp.status == 200:
                print(f"[win-shot] Sent {filename} to Phone gallery", flush=True)
    except Exception as e:
        print(f"[win-shot] Upload error: {e}", flush=True)
    finally:
        if filepath.exists():
            try:
                filepath.unlink()
            except Exception:
                pass

if __name__ == "__main__":
    capture_and_send()
