import threading


class ClipboardState:
    """Thread-safe clipboard state shared by the application."""

    def __init__(self):
        self._clipboard = ""
        self._origin = ""
        self._version = 0
        self._lock = threading.Lock()

    def get(self) -> dict:
        with self._lock:
            return {
                "clipboard": self._clipboard,
                "origin": self._origin,
                "version": self._version,
            }

    def update(self, clipboard: str, origin: str) -> tuple[bool, dict]:
        """
        Update clipboard state.

        Returns:
            (changed, state)
        """
        with self._lock:
            if (
                clipboard == self._clipboard
                and origin == self._origin
            ):
                return False, {
                    "clipboard": self._clipboard,
                    "origin": self._origin,
                    "version": self._version,
                }

            self._clipboard = clipboard
            self._origin = origin
            self._version += 1

            return True, {
                "clipboard": self._clipboard,
                "origin": self._origin,
                "version": self._version,
            }
