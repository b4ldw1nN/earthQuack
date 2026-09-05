#!/usr/bin/env python3
"""
desktop.py — bidirectional clipboard sync for Desktop Linux & Windows

  Desktop → Phone:  polls wl-paste every 0.5s, POSTs changes to server.py
  Phone → Desktop:  listens to SSE stream, writes received text to wl-copy
"""

import argparse
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
try:
    import crypto_util
except ImportError:
    crypto_util = None

DEFAULT_HOST = "YOUR_TAILSCALE_IP"
SERVER_HOST = os.environ.get("CLIPBOARD_SERVER_HOST", DEFAULT_HOST)
SERVER_PORT = os.environ.get("CLIPBOARD_SERVER_PORT", "8875")
SERVER_URL  = f"http://{SERVER_HOST}:{SERVER_PORT}"
POST_URL    = f"{SERVER_URL}/clipboard"
EVENTS_URL  = f"{SERVER_URL}/events"

last_sent     = None   # text we last pushed (don't re-push what we just received)
last_received = None   # text we last received from SSE (don't echo it back)
lock = threading.Lock()


# ──────────────────────────────────────────────── Desktop → Server (push thread) ──

def get_clipboard():
    if sys.platform == "win32":
        # 1. Try tkinter (fastest, built-in with Python on Windows)
        try:
            import tkinter
            root = tkinter.Tk()
            root.withdraw()
            val = root.clipboard_get()
            root.destroy()
            if val:
                return val
        except Exception:
            pass
        # 2. Try PowerShell Get-Clipboard
        try:
            r = subprocess.run(
                ["powershell", "-NoProfile", "-Command", "Get-Clipboard"],
                capture_output=True, text=True, timeout=2,
                creationflags=subprocess.CREATE_NO_WINDOW if hasattr(subprocess, "CREATE_NO_WINDOW") else 0
            )
            if r.returncode == 0 and r.stdout.strip():
                return r.stdout.rstrip("\r\n")
        except Exception:
            pass
        return None

    for cmd in [
        ["wl-paste", "--no-newline"],
        ["wl-paste", "--primary", "--no-newline"],
        ["xclip", "-selection", "clipboard", "-o"],
        ["xsel", "-b", "-o"],
    ]:
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=1)
            if r.returncode == 0 and r.stdout:
                return r.stdout
        except (FileNotFoundError, subprocess.TimeoutExpired):
            continue
    return None


def push_loop():
    global last_sent
    print(f"[push] Watching Desktop clipboard → {POST_URL}", flush=True)
    while True:
        try:
            text = get_clipboard()
            if text:
                with lock:
                    skip = (text == last_sent or text == last_received)
                if not skip:
                    send_text = crypto_util.wrap(text) if crypto_util else text
                    data = json.dumps({"clipboard": send_text, "origin": "desktop"}).encode()
                    req  = urllib.request.Request(
                        POST_URL, data=data,
                        headers={"Content-Type": "application/json"},
                        method="POST",
                    )
                    urllib.request.urlopen(req, timeout=5)
                    with lock:
                        last_sent = text
                    print(f"[push] → server: {text[:60]!r}", flush=True)
        except Exception as e:
            print(f"[push] Error: {e}", flush=True)
        time.sleep(0.5)


# ────────────────────────────────────────────── Server → Desktop (SSE recv thread) ──

def set_clipboard(text: str):
    """Write text to Desktop clipboard (Windows, Wayland, X11)."""
    if sys.platform == "win32":
        # 1. Try clip.exe
        try:
            creation_flags = subprocess.CREATE_NO_WINDOW if hasattr(subprocess, "CREATE_NO_WINDOW") else 0
            p = subprocess.Popen(["clip"], stdin=subprocess.PIPE, text=True, creationflags=creation_flags)
            p.communicate(input=text)
            if p.returncode == 0:
                return True
        except Exception:
            pass
        # 2. Try powershell Set-Clipboard
        try:
            r = subprocess.run(
                ["powershell", "-NoProfile", "-Command", "Set-Clipboard", "-Value", text],
                timeout=2,
                creationflags=subprocess.CREATE_NO_WINDOW if hasattr(subprocess, "CREATE_NO_WINDOW") else 0
            )
            if r.returncode == 0:
                return True
        except Exception:
            pass
        return False

    for cmd in [["wl-copy"], ["xclip", "-selection", "clipboard"], ["xsel", "-b", "-i"]]:
        try:
            r = subprocess.run(cmd, input=text, text=True, timeout=2)
            if r.returncode == 0:
                return True
        except (FileNotFoundError, subprocess.TimeoutExpired):
            continue
    return False


def sse_loop():
    global last_received
    backoff = 2
    print(f"[sse]  Listening for Phone clipboard ← {EVENTS_URL}", flush=True)
    while True:
        try:
            req = urllib.request.Request(EVENTS_URL)
            with urllib.request.urlopen(req, timeout=None) as resp:
                backoff = 2   # reset on successful connection
                event_type  = ""
                data_buffer = ""
                for raw in resp:
                    line = raw.decode("utf-8").rstrip("\n\r")
                    if line.startswith(":"):
                        continue                           # keepalive
                    elif line.startswith("event:"):
                        event_type = line[len("event:"):].strip()
                    elif line.startswith("data:"):
                        data_buffer += line[len("data:"):].strip()
                    elif line == "":
                        if event_type == "clipboard" and data_buffer:
                            try:
                                obj    = json.loads(data_buffer)
                                origin = obj.get("origin", "")
                                text   = obj.get("clipboard", "")
                                if origin == "phone" and text:
                                    plain = crypto_util.unwrap(text) if crypto_util else text
                                    # keep raw encrypted for dedup? use plain for dedup & clipboard
                                    text = plain
                                    # If text is a URL shared from phone, auto-open in Desktop browser
                                    if text.startswith("http://") or text.startswith("https://"):
                                        subprocess.Popen(["xdg-open", text])
                                        print(f"[sse]  Auto-opened URL from Phone: {text[:60]!r}", flush=True)
                                    with lock:
                                        last_received = text
                                    if set_clipboard(text):
                                        print(f"[sse]  ← Phone: {text[:60]!r}", flush=True)
                                    else:
                                        print(f"[sse]  wl-copy failed for: {text[:40]!r}", flush=True)
                            except json.JSONDecodeError as e:
                                print(f"[sse]  JSON error: {e}", flush=True)
                        elif event_type == "open_url" and data_buffer:
                            try:
                                obj = json.loads(data_buffer)
                                url = obj.get("url", "")
                                if url:
                                    subprocess.Popen(["xdg-open", url])
                                    print(f"[sse]  Opened URL from signal: {url}", flush=True)
                            except Exception as e:
                                print(f"[sse]  URL open error: {e}", flush=True)

                        event_type  = ""
                        data_buffer = ""
        except Exception as e:
            print(f"[sse]  Disconnected: {e} — reconnecting in {backoff}s", flush=True)
            time.sleep(backoff)
            backoff = min(backoff * 2, 60)


# ────────────────────────────────────────────────────────────────────────────────

def main():
    global SERVER_URL, POST_URL, EVENTS_URL
    parser = argparse.ArgumentParser(description="Desktop clipboard sync")
    parser.add_argument("--host", default=SERVER_HOST, help="Server host (default: auto discover via Tailscale or env CLIPBOARD_SERVER_HOST)")
    parser.add_argument("--port", default=SERVER_PORT, help="Server port (default: 8875)")
    args = parser.parse_args()

    host = args.host
    if not host or host == "YOUR_TAILSCALE_IP" or host == "auto":
        from tailscale_discovery import discover_clipboard_server
        discovered = discover_clipboard_server(port=int(args.port), prompt_interactive=True)
        if discovered:
            host = discovered
            print(f"[desktop] Connected to Tailscale clipboard server at: {host}")
        else:
            print("[desktop] Tailscale auto-discovery did not find an active remote server. Defaulting to localhost/127.0.0.1 for local daemon mode.")
            host = "127.0.0.1"

    SERVER_URL = f"http://{host}:{args.port}"
    POST_URL   = f"{SERVER_URL}/clipboard"
    EVENTS_URL = f"{SERVER_URL}/events"
    print(f"[desktop] Using server: {SERVER_URL}")

    t = threading.Thread(target=sse_loop, daemon=True)
    t.start()
    push_loop()

if __name__ == "__main__":
    main()

