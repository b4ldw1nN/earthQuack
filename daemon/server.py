#!/usr/bin/env python3

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import argparse
import json
import time

from config import (
    AES_ENABLED,
    APP_NAME,
    APP_VERSION,
    FILE_SERVER_PORT,
    SERVER_HOST,
    SERVER_PORT,
    aes_key_fingerprint,
    is_authorized,
)
from core.events import EventBroker
from core.state import ClipboardState


clipboard_state = ClipboardState()
events = EventBroker()


class Handler(BaseHTTPRequestHandler):

    def do_GET(self):
        if not self._authorized():
            return

        if self.path == "/health":
            data = json.dumps({
                "status": "ok",
                "service": APP_NAME,
            }).encode()

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        if self.path == "/capabilities":
            # What this desktop supports, so the phone can check that both
            # sides agree (encryption especially) instead of discovering a
            # mismatch as unreadable clipboard content.
            data = json.dumps({
                "service": APP_NAME,
                "version": APP_VERSION,
                "aes_enabled": AES_ENABLED,
                "aes_key_fingerprint": aes_key_fingerprint(),
                "clipboard_port": SERVER_PORT,
                "file_port": FILE_SERVER_PORT,
                "endpoints": [
                    "clipboard",
                    "events",
                    "health",
                    "capabilities",
                ],
            }).encode()

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        if self.path == "/clipboard":
            data = json.dumps(
                clipboard_state.get()
            ).encode()

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        if self.path == "/events":
            self.send_response(200)
            self.send_header(
                "Content-Type",
                "text/event-stream",
            )
            self.send_header(
                "Cache-Control",
                "no-cache",
            )
            self.send_header(
                "Connection",
                "keep-alive",
            )
            self.end_headers()

            events.subscribe(self.wfile)

            try:
                while True:
                    self.wfile.write(b": keepalive\n\n")
                    self.wfile.flush()
                    time.sleep(15)

            except (
                BrokenPipeError,
                ConnectionResetError,
            ):
                pass

            finally:
                events.unsubscribe(self.wfile)

            return

        self.send_error(404)

    def do_POST(self):
        if not self._authorized():
            return

        if self.path == "/signal":
            self.handle_signal()
            return

        if self.path == "/clipboard":
            self.handle_clipboard()
            return

        self.send_error(404)

    def _authorized(self) -> bool:
        """Whether this request may use the server.

        Enforced only when a token is configured, so a fresh install keeps
        working; once one is set, every endpoint needs it.
        """
        if is_authorized(self.headers.get("Authorization")):
            return True

        self.send_response(401)
        self.send_header("Content-Type", "application/json")
        self.send_header("WWW-Authenticate", "Bearer")
        self.end_headers()
        self.wfile.write(b'{"error":"unauthorized"}')

        return False

    def handle_signal(self):
        try:
            length = int(
                self.headers.get("Content-Length", 0)
            )

            body = json.loads(
                self.rfile.read(length)
            )

            event_type = body.get(
                "type",
                "signal",
            )

            events.publish(
                event_type,
                body,
            )

            response = b'{"ok":true}'

            self.send_response(200)
            self.send_header(
                "Content-Type",
                "application/json",
            )
            self.send_header(
                "Content-Length",
                str(len(response)),
            )
            self.end_headers()
            self.wfile.write(response)

        except (
            ValueError,
            json.JSONDecodeError,
        ):
            self.send_error(400)

    def handle_clipboard(self):
        try:
            length = int(
                self.headers.get("Content-Length", 0)
            )

            body = self.rfile.read(length)
            data = json.loads(body)

            new_clipboard = data["clipboard"]
            new_origin = data["origin"]

            changed, state = clipboard_state.update(
                new_clipboard,
                new_origin,
            )

            if changed:
                print(
                    f"Clipboard updated "
                    f"(origin={state['origin']}, "
                    f"version={state['version']}): "
                    f"{state['clipboard']!r}",
                    flush=True,
                )

                events.publish(
                    "clipboard",
                    state,
                )

            response = json.dumps({
                "version": state["version"],
            }).encode()

            self.send_response(200)
            self.send_header(
                "Content-Type",
                "application/json",
            )
            self.send_header(
                "Content-Length",
                str(len(response)),
            )
            self.end_headers()
            self.wfile.write(response)

        except (
            ValueError,
            KeyError,
            json.JSONDecodeError,
            TypeError,
        ):
            self.send_error(400)

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser(
        description=f"{APP_NAME} HTTP server"
    )

    parser.add_argument(
        "--host",
        default=SERVER_HOST,
        help="Bind host",
    )

    parser.add_argument(
        "--port",
        type=int,
        default=SERVER_PORT,
        help="Bind port",
    )

    args = parser.parse_args()

    print(
        f"{APP_NAME} server: "
        f"http://{args.host}:{args.port}"
    )

    ThreadingHTTPServer(
        (args.host, args.port),
        Handler,
    ).serve_forever()


if __name__ == "__main__":
    main()

