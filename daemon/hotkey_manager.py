#!/usr/bin/env python3
"""
hotkey_manager.py — Cross-Platform Global Hotkey Abstraction for Clipboard Sync.

Provides a unified HotkeyManager interface:
  - On Windows: Uses native Win32 RegisterHotKey() / UnregisterHotKey() with WM_HOTKEY message loop.
  - On Linux: Operates as a stub/noop (since DE keybindings like Hyprland/i3 call clip-shot.sh directly).

Default shortcut: Ctrl+Alt+Shift+S (Configurable via env var SCREENSHOT_HOTKEY).
Note: Win+Shift+S is explicitly avoided as Windows system-reserved for Snipping Tool.
"""

import logging
import os
import sys
import threading
import time
from typing import Callable, Dict, Optional, Tuple

logger = logging.getLogger("hotkey_manager")
if not logger.handlers:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter('%(asctime)s [%(name)s] %(levelname)s: %(message)s'))
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)

DEFAULT_HOTKEY_STR = "Ctrl+Alt+Shift+S"


def parse_hotkey_string(hotkey_str: str) -> Tuple[int, int, str]:
    """
    Parses a hotkey string like 'Ctrl+Alt+Shift+S' into:
      (modifiers_bitmask, vk_code, canonical_description)

    Windows RegisterHotKey modifiers:
      MOD_ALT     = 0x0001
      MOD_CONTROL = 0x0002
      MOD_SHIFT   = 0x0004
      MOD_WIN     = 0x0008
      MOD_NOREPEAT = 0x4000
    """
    parts = [p.strip() for p in hotkey_str.split("+") if p.strip()]
    mods = 0x4000  # MOD_NOREPEAT by default to prevent holding trigger spam
    key_char = ""

    MOD_MAP = {
        "CTRL": 0x0002,
        "CONTROL": 0x0002,
        "ALT": 0x0001,
        "SHIFT": 0x0004,
        "WIN": 0x0008,
        "SUPER": 0x0008,
        "META": 0x0008,
    }

    for part in parts:
        upper = part.upper()
        if upper in MOD_MAP:
            mods |= MOD_MAP[upper]
        else:
            key_char = upper

    if not key_char:
        key_char = "S"

    vk_code = 0
    if len(key_char) == 1 and "A" <= key_char <= "Z":
        vk_code = ord(key_char)
    elif len(key_char) == 1 and "0" <= key_char <= "9":
        vk_code = ord(key_char)
    elif key_char.startswith("F") and key_char[1:].isdigit():
        f_num = int(key_char[1:])
        if 1 <= f_num <= 24:
            vk_code = 0x70 + (f_num - 1)  # VK_F1 is 0x70
    elif key_char == "PRINTSCREEN" or key_char == "SNAPSHOT":
        vk_code = 0x2C  # VK_SNAPSHOT
    else:
        vk_code = ord(key_char[0])

    # Reconstruct clean name
    clean_mods = []
    if mods & 0x0002:
        clean_mods.append("Ctrl")
    if mods & 0x0001:
        clean_mods.append("Alt")
    if mods & 0x0004:
        clean_mods.append("Shift")
    if mods & 0x0008:
        clean_mods.append("Win")

    canonical_str = "+".join(clean_mods + [key_char])
    return mods, vk_code, canonical_str


class HotkeyManager:
    """
    Platform-independent HotkeyManager.
    Instantiates platform-specific backend dynamically.
    """

    def __init__(self, hotkey_str: Optional[str] = None):
        self.hotkey_str = hotkey_str or os.environ.get("SCREENSHOT_HOTKEY", DEFAULT_HOTKEY_STR)
        self.mods, self.vk_code, self.canonical_str = parse_hotkey_string(self.hotkey_str)
        self.registered = False
        self.callback: Optional[Callable[[], None]] = None
        self._thread: Optional[threading.Thread] = None
        self._running = False
        self._hotkey_id = 1001

    def register_screenshot_callback(self, callback: Callable[[], None]) -> bool:
        """Register the SCREENSHOT_REQUESTED callback handler."""
        self.callback = callback
        return self.start()

    def start(self) -> bool:
        """Start global hotkey listener backend."""
        if self.registered:
            logger.warning(f"[hotkey] Hotkey {self.canonical_str} already registered")
            return True

        logger.info(f"[hotkey] Registering global screenshot hotkey: {self.canonical_str}")

        if sys.platform == "win32":
            return self._start_windows()
        else:
            return self._start_linux()

    def _start_windows(self) -> bool:
        try:
            import ctypes
            from ctypes import wintypes

            user32 = ctypes.windll.user32

            # Struct for MSG
            class MSG(ctypes.Structure):
                _fields_ = [
                    ("hwnd", wintypes.HWND),
                    ("message", wintypes.UINT),
                    ("wParam", wintypes.WPARAM),
                    ("lParam", wintypes.LPARAM),
                    ("time", wintypes.DWORD),
                    ("pt", wintypes.POINT),
                ]

            self._running = True

            def _win_msg_loop():
                # Register hotkey on thread message queue
                res = user32.RegisterHotKey(
                    None,  # NULL hwnd -> thread message queue
                    self._hotkey_id,
                    self.mods,
                    self.vk_code
                )

                if not res:
                    err_code = user32.GetLastError()
                    logger.error(f"[hotkey] Failed to register {self.canonical_str} (Win32 error code: {err_code}). Hotkey may be owned by another application.")
                    self.registered = False
                    self._running = False
                    return

                self.registered = True
                logger.info(f"[hotkey] Windows global hotkey registered successfully: {self.canonical_str}")

                msg = MSG()
                WM_HOTKEY = 0x0312
                WM_QUIT = 0x0012

                try:
                    while self._running:
                        # GetMessageW blocks cleanly without CPU polling
                        b_ret = user32.GetMessageW(ctypes.byref(msg), None, 0, 0)
                        if b_ret == 0 or b_ret == -1:
                            break

                        if msg.message == WM_HOTKEY and msg.wParam == self._hotkey_id:
                            logger.info("[hotkey] screenshot hotkey pressed")
                            if self.callback:
                                try:
                                    self.callback()
                                except Exception as e:
                                    logger.error(f"[screenshot] Exception in screenshot callback: {e}")

                        user32.TranslateMessage(ctypes.byref(msg))
                        user32.DispatchMessageW(ctypes.byref(msg))
                finally:
                    # Clean unregistration
                    user32.UnregisterHotKey(None, self._hotkey_id)
                    self.registered = False
                    logger.info(f"[hotkey] Unregistered Windows global hotkey: {self.canonical_str}")

            self._thread = threading.Thread(target=_win_msg_loop, daemon=True, name="win32-hotkey-loop")
            self._thread.start()

            # Wait briefly for registration status
            for _ in range(20):
                if self.registered or not self._running:
                    break
                time.sleep(0.05)

            return self.registered

        except Exception as e:
            logger.error(f"[hotkey] Windows hotkey registration exception: {e}")
            return False

    def _start_linux(self) -> bool:
        # On Linux, DE handles global keybinding (grim/clip-shot.sh).
        self.registered = True
        logger.info(f"[hotkey] Linux environment active — DE hotkeys execute clip-shot.sh directly ({self.canonical_str})")
        return True

    def stop(self):
        """Unregister hotkey and stop background message loop."""
        self._running = False
        if sys.platform == "win32" and self._thread and self._thread.is_alive():
            try:
                import ctypes
                # Post WM_QUIT to wake up GetMessageW
                WM_QUIT = 0x0012
                ctypes.windll.user32.PostThreadMessageW(self._thread.ident, WM_QUIT, 0, 0)
            except Exception:
                pass
        self.registered = False
