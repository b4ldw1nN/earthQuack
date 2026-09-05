##!/usr/bin/env python3
#!/usr/bin/env python3
"""
earthQuack daemon supervisor.

Starts and supervises the currently available earthQuack components
inside a single process.

The supervisor is intentionally small:
    - component lifecycle
    - restart-on-failure
    - hotkey initialization
    - graceful shutdown

Components themselves own their functionality.
"""

import os
import subprocess
import sys
import threading
import time
from pathlib import Path


BASE = Path(__file__).resolve().parent


def run_guarded(name, fn):
    """Run a component continuously and restart it if it exits or crashes."""
    while True:
        try:
            fn()
        except Exception as exc:
            print(
                f"[{name}] crashed: {exc} — restarting in 5s",
                flush=True,
            )
            import traceback
            traceback.print_exc()
            time.sleep(5)
        else:
            print(
                f"[{name}] exited — restarting in 5s",
                flush=True,
            )
            time.sleep(5)


def run_server():
    def start():
        import server
        server.main()

    run_guarded("server", start)


def run_file_server():
    def start():
        import file_server
        file_server.main()

    run_guarded("file-server", start)


def run_desktop():
    def start():
        import desktop
        desktop.main()

    run_guarded("desktop", start)


def run_watch_folder():
    def start():
        import watch_send_folder
        watch_send_folder.main()

    run_guarded("watch-folder", start)


def trigger_screenshot():
    """Capture a screenshot and send it through the existing pipeline."""
    print("[screenshot] capture requested", flush=True)

    try:
        if sys.platform == "win32":
            import win_shot
            win_shot.capture_and_send()
        else:
            script_path = BASE.parent / "clip-shot.sh"
            subprocess.Popen(["bash", str(script_path)])

    except Exception as exc:
        print(
            f"[screenshot] capture error: {exc}",
            flush=True,
        )


def setup_hotkeys():
    """Initialize the optional hotkey manager."""
    try:
        from hotkey_manager import HotkeyManager

        manager = HotkeyManager()
        manager.register_screenshot_callback(trigger_screenshot)

        print("[hotkey] manager active", flush=True)
        return manager

    except Exception as exc:
        print(
            f"[hotkey] unavailable: {exc}",
            flush=True,
        )
        return None


def start_components():
    """Start all currently enabled earthQuack components."""
    components = [
        ("server", run_server),
        ("file-server", run_file_server),
        ("desktop", run_desktop),
        ("watch-folder", run_watch_folder),
    ]

    threads = []

    for name, target in components:
        thread = threading.Thread(
            target=target,
            daemon=True,
            name=f"earthQuack-{name}",
        )
        thread.start()
        threads.append(thread)

        print(
            f"[earthQuack] started {name}",
            flush=True,
        )

    return threads


def main():
    print("[earthQuack] starting", flush=True)

    hotkey_manager = setup_hotkeys()
    start_components()

    print("[earthQuack] all components active", flush=True)

    try:
        while True:
            time.sleep(10)

    except KeyboardInterrupt:
        print("\n[earthQuack] stopping", flush=True)

        if hotkey_manager is not None:
            try:
                hotkey_manager.stop()
            except Exception as exc:
                print(
                    f"[hotkey] shutdown error: {exc}",
                    flush=True,
                )


if __name__ == "__main__":
    main()
