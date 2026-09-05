#!/usr/bin/env python3
"""
Watch ~/send-to-phone/ and auto-send any file dropped there to the phone.
Files are deleted from the folder after a successful send.
"""
import os
import subprocess
import sys
import time
from pathlib import Path

WATCH_DIR   = Path.home() / "send-to-phone"
SEND_SCRIPT = Path(__file__).parent.parent / "clip-send.sh"
WATCH_DIR.mkdir(exist_ok=True)

def main():
    print(f"Watching {WATCH_DIR}  (drop files here to send to Phone)", flush=True)
    seen: set[Path] = set(WATCH_DIR.iterdir())

    while True:
        try:
            current = set(WATCH_DIR.iterdir())
            new_files = current - seen
            seen = current

            for f in sorted(new_files):
                if not f.is_file() or f.name.startswith("."):
                    continue
                print(f"New file: {f.name}", flush=True)
                success = False
                if sys.platform == "win32":
                    try:
                        import urllib.request
                        host = os.environ.get("CLIPBOARD_SERVER_HOST", "")
                        port = os.environ.get("CLIPBOARD_FILE_PORT", "8876")
                        if not host or host == "YOUR_TAILSCALE_IP":
                            from tailscale_discovery import discover_clipboard_server
                            discovered = discover_clipboard_server(port=8875)
                            host = discovered if discovered else "127.0.0.1"
                        url = f"http://{host}:{port}/upload"
                        with open(f, "rb") as fp:
                            data = fp.read()
                        req = urllib.request.Request(
                            url, data=data,
                            headers={"X-Filename": f.name, "X-Origin": "desktop", "Content-Type": "application/octet-stream"},
                            method="POST"
                        )
                        with urllib.request.urlopen(req, timeout=10) as resp:
                            success = (resp.status == 200)
                    except Exception as e:
                        print(f"Windows upload error: {e}", flush=True)
                else:
                    result = subprocess.run(["bash", str(SEND_SCRIPT), str(f)])
                    success = (result.returncode == 0)

                if success:
                    try:
                        f.unlink()
                        print(f"Removed from watch folder: {f.name}", flush=True)
                    except Exception as e:
                        print(f"Could not remove {f.name}: {e}", flush=True)
        except Exception as e:
            pass
        time.sleep(0.3)

if __name__ == "__main__":
    main()
