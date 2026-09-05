import threading
from typing import BinaryIO


class EventBroker:
    """Thread-safe Server-Sent Events broker."""

    def __init__(self):
        self._clients: list[BinaryIO] = []
        self._lock = threading.Lock()

    def subscribe(self, client: BinaryIO):
        with self._lock:
            self._clients.append(client)

    def unsubscribe(self, client: BinaryIO):
        with self._lock:
            if client in self._clients:
                self._clients.remove(client)

    def publish(self, event_type: str, data: dict):
        event = (
            f"event: {event_type}\n"
            f"data: {self._encode(data)}\n\n"
        ).encode()

        with self._lock:
            clients = self._clients.copy()

        dead = []

        for client in clients:
            try:
                client.write(event)
                client.flush()
            except (BrokenPipeError, ConnectionResetError):
                dead.append(client)

        if dead:
            with self._lock:
                for client in dead:
                    if client in self._clients:
                        self._clients.remove(client)

    @staticmethod
    def _encode(data: dict) -> str:
        import json
        return json.dumps(data)

    def count(self) -> int:
        with self._lock:
            return len(self._clients)
