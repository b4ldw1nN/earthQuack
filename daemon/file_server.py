#!/usr/bin/env python3
"""
File transfer server for Clipboard Sync — port 8766
Runs on the Desktop machine (Tailscale IP YOUR_TAILSCALE_IP).

Endpoints:
  POST /upload          Upload a file (raw body + X-Filename / X-Origin headers)
  GET  /download/<id>   Download a staged file by ID
  GET  /files           List staged (Desktop→Phone pending) files

Flow:
  Phone → Desktop:  phone POSTs file → saved to ~/Downloads/from-phone/ → notify-send
  Desktop → Phone:  clip-send uploads → staged in /tmp/cs-files/ → signals server.py SSE
                → Android downloads via GET /download/<id>
"""

import argparse
import json
import os
import threading
import time
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DEFAULT_HOST = "YOUR_TAILSCALE_IP"
HOST         = os.environ.get("CLIPBOARD_SERVER_HOST", DEFAULT_HOST)   # Desktop Tailscale IP
PORT         = int(os.environ.get("CLIPBOARD_FILE_PORT", "8766"))
SIGNAL_URL   = f"http://{HOST}:{os.environ.get('CLIPBOARD_SERVER_PORT', '8765')}/signal"
PHONE_SAVE_DIR  = Path.home() / "Downloads" / "from-phone"
STAGE_DIR      = Path("/tmp/cs-files")

PHONE_SAVE_DIR.mkdir(parents=True, exist_ok=True)
STAGE_DIR.mkdir(parents=True, exist_ok=True)

# id → {id, name, size, path, ts}  (only Desktop→Phone staged files)
staged: dict[str, dict] = {}
lock = threading.Lock()

CHUNK = 256 * 1024   # 256 KB read chunks


def signal_sse(payload: dict):
    """Tell server.py to push an SSE event to all Android clients."""
    try:
        data = json.dumps(payload).encode()
        req  = urllib.request.Request(
            SIGNAL_URL, data=data,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        urllib.request.urlopen(req, timeout=3)
    except Exception as e:
        print(f"[file-server] SSE signal failed: {e}", flush=True)


def desktop_notify(name: str, size: int):
    size_str = (f"{size / 1024:.1f} KB" if size < 1_048_576
                else f"{size / 1_048_576:.1f} MB")
    os.system(f'notify-send " File from Phone" "{name}  ({size_str})" -t 6000')


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_): pass

    # ───────────────────────────────────────────────── GET ──

    def do_GET(self):
        if self.path == "/files":
            with lock:
                body = json.dumps(list(staged.values()), default=str).encode()
            self._json(200, body)

        elif self.path.startswith("/download/"):
            fid = self.path[len("/download/"):]
            with lock:
                entry = staged.get(fid)
            if not entry:
                self.send_error(404)
                return

            path = Path(entry["path"])
            if not path.exists():
                self.send_error(410)   # Gone
                return

            total = path.stat().st_size

            # Range support (for resumable downloads)
            start = 0
            rng = self.headers.get("Range", "")
            if rng.startswith("bytes="):
                try:
                    start = int(rng[6:].split("-")[0])
                except ValueError:
                    pass

            code = 206 if start else 200
            self.send_response(code)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Disposition",
                             f'attachment; filename="{entry["name"]}"')
            self.send_header("Content-Length", str(total - start))
            self.send_header("Accept-Ranges", "bytes")
            if start:
                self.send_header("Content-Range", f"bytes {start}-{total-1}/{total}")
            self.end_headers()

            with open(path, "rb") as f:
                f.seek(start)
                while True:
                    chunk = f.read(CHUNK)
                    if not chunk:
                        break
                    try:
                        self.wfile.write(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        break

            print(f"[file-server] Sent {entry['name']} ({total} bytes) to Phone", flush=True)

        else:
            self.send_error(404)

    # ───────────────────────────────────────────────── POST ──

    def do_POST(self):
        if self.path != "/upload":
            self.send_error(404)
            return

        name   = self.headers.get("X-Filename", f"file_{uuid.uuid4().hex[:6]}")
        origin = self.headers.get("X-Origin", "unknown")
        length = int(self.headers.get("Content-Length", 0))

        # ── Phone → Desktop: save directly to ~/Downloads/from-phone/
        if origin == "phone":
            save_path = PHONE_SAVE_DIR / name
            # Avoid overwriting existing files
            if save_path.exists():
                stem, suffix = Path(name).stem, Path(name).suffix
                save_path = PHONE_SAVE_DIR / f"{stem}_{uuid.uuid4().hex[:6]}{suffix}"

            written = self._stream_to_file(save_path, length)
            print(f"[file-server] phone→desktop: {save_path} ({written} bytes)", flush=True)
            self._json(200, json.dumps({
                "id": "", "name": save_path.name, "size": written
            }).encode())


        # ── Desktop → Phone: stage in /tmp/cs-files/, signal Android via SSE
        else:
            fid       = uuid.uuid4().hex
            save_path = STAGE_DIR / fid
            written   = self._stream_to_file(save_path, length)

            entry = {
                "id":   fid,
                "name": name,
                "size": written,
                "path": str(save_path),
                "ts":   time.time(),
            }
            with lock:
                staged[fid] = entry

            print(f"[file-server] desktop→phone: staged {name} id={fid} ({written} bytes)", flush=True)

            # Notify Android via SSE → triggers download
            signal_sse({
                "type":   "file_ready",
                "id":     fid,
                "name":   name,
                "size":   written,
            })

            self._json(200, json.dumps({
                "id": fid, "name": name, "size": written
            }).encode())

    # ─────────────────────────────────────────────── helpers ──

    def _stream_to_file(self, path: Path, total: int) -> int:
        written = 0
        with open(path, "wb") as f:
            remaining = total
            while remaining > 0:
                chunk = self.rfile.read(min(CHUNK, remaining))
                if not chunk:
                    break
                f.write(chunk)
                written += len(chunk)
                remaining -= len(chunk)
        return written

    def _json(self, code: int, body: bytes):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    global HOST, PORT, SIGNAL_URL
    parser = argparse.ArgumentParser(description="Clipboard file server")
    parser.add_argument("--host", default=HOST, help="Bind host (default: env CLIPBOARD_SERVER_HOST or YOUR_TAILSCALE_IP)")
    parser.add_argument("--port", type=int, default=PORT, help="Bind port (default: 8766)")
    parser.add_argument("--signal-port", default=os.environ.get("CLIPBOARD_SERVER_PORT", "8765"), help="Clipboard server port for SSE signals")
    args = parser.parse_args()
    HOST = args.host
    PORT = args.port
    SIGNAL_URL = f"http://{HOST}:{args.signal_port}/signal"
    print(f"File server: http://{HOST}:{PORT}")
    print(f"  Phone→Desktop saves to: {PHONE_SAVE_DIR}")
    print(f"  Desktop→Phone staging:  {STAGE_DIR}")
    print(f"  Signal URL: {SIGNAL_URL}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()

if __name__ == "__main__":
    main()

