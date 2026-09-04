#!/usr/bin/env python3

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import argparse
import json
import os
import time

DEFAULT_HOST = "YOUR_TAILSCALE_IP"
HOST = os.environ.get("CLIPBOARD_SERVER_HOST", DEFAULT_HOST)
PORT = int(os.environ.get("CLIPBOARD_SERVER_PORT", "8765"))

clipboard = ""
origin = ""
version = 0

# Connected SSE clients — guarded by clients_lock for ThreadingHTTPServer concurrency
import threading
clients = []
clients_lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):

    def do_GET(self):
        if self.path == "/health":
            data = b'{"status":"ok"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        if self.path == "/clipboard":
            data = json.dumps({
                "clipboard": clipboard,
                "origin": origin,
                "version": version,
            }).encode()

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        if self.path == "/events":
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.end_headers()

            with clients_lock:
                clients.append(self.wfile)

            try:
                while True:
                    # Keep the SSE connection alive.
                    self.wfile.write(b": keepalive\n\n")
                    self.wfile.flush()
                    time.sleep(15)

            except (BrokenPipeError, ConnectionResetError):
                pass

            finally:
                with clients_lock:
                    if self.wfile in clients:
                        clients.remove(self.wfile)

            return

        self.send_error(404)

    def do_POST(self):
        global clipboard, origin, version

        if self.path == "/signal":
            # Internal endpoint: file-server.py calls this to broadcast
            # arbitrary SSE events (e.g. file_ready) to all Android clients.
            try:
                length = int(self.headers.get("Content-Length", 0))
                body   = json.loads(self.rfile.read(length))
                event_type = body.get("type", "signal")
                event = (
                    f"event: {event_type}\n"
                    f"data: {json.dumps(body)}\n\n"
                ).encode()
                with clients_lock:
                    snapshot = clients.copy()
                for client in snapshot:
                    try:
                        client.write(event)
                        client.flush()
                    except (BrokenPipeError, ConnectionResetError):
                        with clients_lock:
                            if client in clients:
                                clients.remove(client)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"ok":true}')
            except Exception as e:
                self.send_error(400)
            return

        if self.path != "/clipboard":
            self.send_error(404)
            return

        try:
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length)
            data = json.loads(body)

            new_clipboard = data["clipboard"]
            new_origin = data["origin"]

            # Only create a new version when the content/origin changes.
            if new_clipboard != clipboard or new_origin != origin:
                clipboard = new_clipboard
                origin = new_origin
                version += 1

                print(
                    f"Clipboard updated "
                    f"(origin={origin}, version={version}): "
                    f"{clipboard!r}",
                    flush=True,
                )

                # Push the update to every connected SSE client.
                event = (
                    "event: clipboard\n"
                    f"data: {json.dumps({
                        'clipboard': clipboard,
                        'origin': origin,
                        'version': version,
                    })}\n\n"
                ).encode()

                with clients_lock:
                    snapshot = clients.copy()
                for client in snapshot:
                    try:
                        client.write(event)
                        client.flush()
                    except (BrokenPipeError, ConnectionResetError):
                        with clients_lock:
                            if client in clients:
                                clients.remove(client)

            response = json.dumps({
                "version": version,
            }).encode()

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)

        except (ValueError, KeyError, json.JSONDecodeError):
            self.send_error(400)

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser(description="Clipboard SSE server")
    parser.add_argument("--host", default=HOST, help="Bind host (default: env CLIPBOARD_SERVER_HOST or YOUR_TAILSCALE_IP)")
    parser.add_argument("--port", type=int, default=PORT, help="Bind port (default: 8765)")
    args = parser.parse_args()
    print(f"Clipboard server: http://{args.host}:{args.port}")
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()

if __name__ == "__main__":
    main()