#!/usr/bin/env python3
"""
test_hotkey_manager.py — Unit tests for HotkeyManager parsing and representation.
"""

import sys
import unittest
from pathlib import Path

BASE = Path(__file__).parent
sys.path.insert(0, str(BASE))

from hotkey_manager import parse_hotkey_string, HotkeyManager


class TestHotkeyManager(unittest.TestCase):

    def test_parse_hotkey_string_default(self):
        mods, vk, canonical = parse_hotkey_string("Ctrl+Alt+Shift+S")
        self.assertEqual(canonical, "Ctrl+Alt+Shift+S")
        # MOD_NOREPEAT (0x4000) | MOD_CONTROL (0x0002) | MOD_ALT (0x0001) | MOD_SHIFT (0x0004) = 0x4007
        self.assertEqual(mods, 0x4007)
        self.assertEqual(vk, ord("S"))

    def test_parse_hotkey_string_custom(self):
        mods, vk, canonical = parse_hotkey_string("Ctrl+Alt+P")
        self.assertEqual(canonical, "Ctrl+Alt+P")
        self.assertEqual(mods, 0x4003)
        self.assertEqual(vk, ord("P"))

    def test_parse_hotkey_f_key(self):
        mods, vk, canonical = parse_hotkey_string("Ctrl+F12")
        self.assertEqual(canonical, "Ctrl+F12")
        self.assertEqual(mods, 0x4002)
        self.assertEqual(vk, 0x7B)  # VK_F12 is 0x70 + 11 = 0x7B (123)

    def test_duplicate_registration_handling(self):
        hm = HotkeyManager("Ctrl+Alt+Shift+S")
        called = []
        hm.callback = lambda: called.append(True)

        # First start
        res1 = hm.start()
        self.assertTrue(res1)
        self.assertTrue(hm.registered)

        # Second start (duplicate)
        res2 = hm.start()
        self.assertTrue(res2)

        hm.stop()
        self.assertFalse(hm.registered)


if __name__ == "__main__":
    unittest.main()
