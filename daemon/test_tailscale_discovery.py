#!/usr/bin/env python3
"""
test_tailscale_discovery.py — Unit tests for Tailscale discovery module.
"""

import unittest
from tailscale_discovery import parse_tailscale_status, discover_clipboard_server

SAMPLE_TAILSCALE_JSON = """{
  "Version": "1.102.3",
  "BackendState": "Running",
  "TailscaleIPs": [
    "100.92.160.31",
    "fd7a:115c:a1e0::ae32:a01f"
  ],
  "Self": {
    "HostName": "archii",
    "DNSName": "archii.snares-hexatonic.ts.net.",
    "OS": "linux",
    "TailscaleIPs": [
      "100.92.160.31",
      "fd7a:115c:a1e0::ae32:a01f"
    ],
    "Online": true,
    "Active": false
  },
  "Peer": {
    "nodekey:1": {
      "HostName": "DESKTOP-9S55DRM",
      "DNSName": "desktop-9s55drm.snares-hexatonic.ts.net.",
      "OS": "windows",
      "TailscaleIPs": [
        "100.105.106.87",
        "fd7a:115c:a1e0::573a:6a58"
      ],
      "Online": false,
      "Active": false
    },
    "nodekey:2": {
      "HostName": "V2253",
      "DNSName": "v2253.snares-hexatonic.ts.net.",
      "OS": "android",
      "TailscaleIPs": [
        "100.87.152.1",
        "fd7a:115c:a1e0::23a:9802"
      ],
      "Online": true,
      "Active": true
    }
  }
}"""

MULTIPLE_SERVERS_JSON = """{
  "Self": {
    "HostName": "V2253",
    "OS": "android",
    "TailscaleIPs": ["100.87.152.1"],
    "Online": true
  },
  "Peer": {
    "nodekey:1": {
      "HostName": "archii",
      "OS": "linux",
      "TailscaleIPs": ["100.92.160.31"],
      "Online": true
    },
    "nodekey:2": {
      "HostName": "DESKTOP-9S55DRM",
      "OS": "windows",
      "TailscaleIPs": ["100.105.106.87"],
      "Online": true
    }
  }
}"""


class TestTailscaleDiscovery(unittest.TestCase):

    def test_parse_tailscale_status_success(self):
        self_info, peers = parse_tailscale_status(SAMPLE_TAILSCALE_JSON)
        self.assertIsNotNone(self_info)
        self.assertEqual(self_info["hostname"], "archii")
        self.assertEqual(self_info["tailscale_ipv4"], ["100.92.160.31"])

        self.assertEqual(len(peers), 2)
        offline_peer = next(p for p in peers if p["hostname"] == "DESKTOP-9S55DRM")
        self.assertFalse(offline_peer["online"])
        self.assertEqual(offline_peer["tailscale_ipv4"], ["100.105.106.87"])

        online_peer = next(p for p in peers if p["hostname"] == "V2253")
        self.assertTrue(online_peer["online"])
        self.assertEqual(online_peer["tailscale_ipv4"], ["100.87.152.1"])

    def test_parse_tailscale_status_malformed(self):
        self_info, peers = parse_tailscale_status("{ invalid json ")
        self.assertIsNone(self_info)
        self.assertEqual(peers, [])

    def test_discover_clipboard_server_single_online(self):
        def mock_check_service(ip, port):
            return ip == "100.87.152.1"

        discovered = discover_clipboard_server(
            json_str_override=SAMPLE_TAILSCALE_JSON,
            check_service_fn=mock_check_service
        )
        self.assertEqual(discovered, "100.87.152.1")

    def test_discover_clipboard_server_no_online_peers(self):
        json_no_online = """{
          "Self": {"HostName": "self", "Online": true, "TailscaleIPs": ["100.1.1.1"]},
          "Peer": {"key1": {"HostName": "peer1", "Online": false, "TailscaleIPs": ["100.2.2.2"]}}
        }"""
        discovered = discover_clipboard_server(
            json_str_override=json_no_online,
            check_service_fn=lambda ip, port: True
        )
        self.assertIsNone(discovered)

    def test_discover_clipboard_server_multiple_servers_deterministic(self):
        # Both peers respond to port 8875
        def mock_check_service(ip, port):
            return True

        discovered = discover_clipboard_server(
            json_str_override=MULTIPLE_SERVERS_JSON,
            check_service_fn=mock_check_service
        )
        # Should deterministically pick "100.92.160.31" (since 100.105.106.87 > 100.92.160.31 or by host ordering)
        self.assertEqual(discovered, "100.92.160.31")


if __name__ == "__main__":
    unittest.main()
