#!/usr/bin/env python3
"""
app.py — Stealth Clipboard Sync (Single-Process Threaded Daemon)

Runs all 4 services inside a single Python process using threads:
  - server.py (port 8765)
  - file-server.py (port 8766)
  - desktop.py (clipboard watcher & SSE client)
  - watch-send-folder.py (watch ~/send-to-phone/)

Masks process title using setproctitle / prctl so process tree / exam scanners
see a standard system process name (e.g. 'systemd-user-service').
"""

import ctypes
import os
import sys
import threading
import time
from pathlib import Path

# ── Mask Process Name via libc / prctl ───────────────────────────────────────

def mask_process_name(name: str = "systemd-user-service"):
    """Mask process name in /proc/self/comm and process listing."""
    try:
        # Set thread name via prctl PR_SET_NAME
        libc = ctypes.CDLL("libc.so.6")
        PR_SET_NAME = 15
        libc.prctl(PR_SET_NAME, name.encode('utf-8'), 0, 0, 0)
    except Exception:
        pass

    try:
        # Try setproctitle if installed
        import setproctitle
        setproctitle.setproctitle(name)
    except ImportError:
        pass


mask_process_name("systemd-user-service")


# ── Import & Launch All Services as Threads ─────────────────────────────────

BASE = Path(__file__).parent
sys.path.insert(0, str(BASE))

def _run_guarded(name, fn):
    while True:
        try:
            fn()
        except Exception as e:
            print(f"[{name}] crashed: {e} — restarting in 5s", flush=True)
            import traceback; traceback.print_exc()
            time.sleep(5)
        else:
            print(f"[{name}] exited — restarting in 5s", flush=True)
            time.sleep(5)

def run_server():
    mask_process_name("systemd-user-service")
    def _fn():
        import server
        server.main()
    _run_guarded("server", _fn)

def run_file_server():
    mask_process_name("systemd-user-service")
    def _fn():
        import file_server
        file_server.main()
    _run_guarded("file-server", _fn)


def run_desktop():
    mask_process_name("systemd-user-service")
    def _fn():
        import desktop
        desktop.main()
    _run_guarded("desktop", _fn)

def run_watch_folder():
    mask_process_name("systemd-user-service")
    def _fn():
        import watch_send_folder
        watch_send_folder.main()
    _run_guarded("watch", _fn)



def trigger_screenshot():
    """Cross-platform screenshot request handler triggered by hotkey."""
    print("[screenshot] capture requested", flush=True)
    if sys.platform == "win32":
        try:
            import win_shot
            win_shot.capture_and_send()
        except ImportError:
            # Fallback path if win_shot imported from root directory
            import win_shot
            win_shot.capture_and_send()
        except Exception as e:
            print(f"[screenshot] Windows capture error: {e}", flush=True)
    else:
        try:
            script_path = Path(__file__).parent.parent / "clip-shot.sh"
            subprocess.Popen(["bash", str(script_path)])
        except Exception as e:
            print(f"[screenshot] Linux capture error: {e}", flush=True)


def main():
    print("[stealth-daemon] Starting single-process Clipboard Sync...", flush=True)

    try:
        from hotkey_manager import HotkeyManager
        hk_manager = HotkeyManager()
        hk_manager.register_screenshot_callback(trigger_screenshot)
    except Exception as e:
        print(f"[hotkey] HotkeyManager init error: {e}", flush=True)
        hk_manager = None

    threads = [
        threading.Thread(target=run_server, daemon=True, name="systemd-user-service"),
        threading.Thread(target=run_file_server, daemon=True, name="systemd-user-service"),
        threading.Thread(target=run_desktop, daemon=True, name="systemd-user-service"),
        threading.Thread(target=run_watch_folder, daemon=True, name="systemd-user-service"),
    ]

    for t in threads:
        t.start()

    print("[stealth-daemon] All services active inside single hidden process.", flush=True)

    try:
        while True:
            time.sleep(10)
    except KeyboardInterrupt:
        print("\nStopping stealth daemon...", flush=True)
        if hk_manager:
            hk_manager.stop()
        sys.exit(0)


if __name__ == "__main__":
    main()
