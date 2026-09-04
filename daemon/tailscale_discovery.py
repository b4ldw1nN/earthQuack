#!/usr/bin/env python3
"""
tailscale_discovery.py — Automatic Tailscale peer discovery for Clipboard Sync.

Discovers online Tailscale peers running the clipboard server (:8765).
"""

import json
import logging
import os
import shutil
import subprocess
import sys
import urllib.request
import urllib.error
import time
from typing import Dict, List, Optional, Tuple

logger = logging.getLogger("tailscale_discovery")

# Cache to avoid repeatedly executing `tailscale status --json` unnecessarily
_DISCOVERY_CACHE: Optional[Tuple[float, Optional[str]]] = None
CACHE_TTL_SECONDS = 10.0


def find_tailscale_binary() -> Optional[str]:
    """Locate tailscale executable cleanly on Linux and Windows."""
    binary = shutil.which("tailscale")
    if binary:
        return binary

    if sys.platform == "win32":
        possible_paths = [
            r"C:\Program Files\Tailscale\tailscale.exe",
            r"C:\Program Files (x86)\Tailscale\tailscale.exe",
            os.path.expandvars(r"%LOCALAPPDATA%\Tailscale\tailscale.exe"),
            os.path.expandvars(r"%ProgramFiles%\Tailscale\tailscale.exe"),
        ]
        for path in possible_paths:
            if path and os.path.isfile(path):
                return path
    else:
        possible_paths = [
            "/usr/bin/tailscale",
            "/usr/local/bin/tailscale",
            "/usr/sbin/tailscale",
            "/sbin/tailscale",
        ]
        for path in possible_paths:
            if os.path.isfile(path) and os.access(path, os.X_OK):
                return path

    return None


def get_tailscale_status_json() -> Optional[str]:
    """Execute `tailscale status --json` and return the stdout string."""
    binary = find_tailscale_binary()
    if not binary:
        logger.warning("[tailscale] tailscale executable is missing or not found in PATH")
        return None

    try:
        creation_flags = 0
        if sys.platform == "win32" and hasattr(subprocess, "CREATE_NO_WINDOW"):
            creation_flags = subprocess.CREATE_NO_WINDOW

        res = subprocess.run(
            [binary, "status", "--json"],
            capture_output=True,
            text=True,
            timeout=5,
            creationflags=creation_flags
        )
        if res.returncode != 0:
            logger.warning(f"[tailscale] tailscale status --json failed with code {res.returncode}: {res.stderr.strip()}")
            return None
        return res.stdout
    except subprocess.TimeoutExpired:
        logger.warning("[tailscale] tailscale status --json timed out after 5s")
        return None
    except Exception as e:
        logger.warning(f"[tailscale] Failed to execute tailscale: {e}")
        return None


def parse_tailscale_status(json_str: str) -> Tuple[Optional[Dict], List[Dict]]:
    """
    Parse tailscale status JSON string safely.
    Returns (self_dict, peers_list).
    Extracts:
      - hostname
      - DNS name
      - OS
      - Tailscale IPv4 addresses
      - online status
      - active status
    """
    if not json_str or not json_str.strip():
        return None, []

    try:
        data = json.loads(json_str)
    except json.JSONDecodeError as e:
        logger.warning(f"[tailscale] Malformed JSON from tailscale status: {e}")
        return None, []

    if not isinstance(data, dict):
        logger.warning("[tailscale] Unexpected JSON structure: top level is not an object")
        return None, []

    def extract_node_info(node: dict) -> Optional[Dict]:
        if not isinstance(node, dict):
            return None
        
        hostname = node.get("HostName", "") or ""
        dns_name = node.get("DNSName", "") or ""
        os_name = node.get("OS", "") or ""
        online = bool(node.get("Online", False))
        active = bool(node.get("Active", False))

        raw_ips = node.get("TailscaleIPs", []) or []
        ipv4_ips = [ip for ip in raw_ips if isinstance(ip, str) and "." in ip]

        return {
            "hostname": hostname,
            "dns_name": dns_name,
            "os": os_name,
            "tailscale_ipv4": ipv4_ips,
            "online": online,
            "active": active,
            "raw_node": node
        }

    self_info = None
    raw_self = data.get("Self")
    if isinstance(raw_self, dict):
        self_info = extract_node_info(raw_self)

    peers = []
    raw_peers = data.get("Peer")
    if isinstance(raw_peers, dict):
        for peer_key, peer_node in raw_peers.items():
            info = extract_node_info(peer_node)
            if info:
                peers.append(info)

    return self_info, peers


def check_app_service(ip: str, port: int = 8765, timeout: float = 1.5) -> bool:
    """
    Perform application-level discovery:
    Try /health endpoint first, fallback to /clipboard endpoint if /health returns 404.
    """
    # 1. Try /health
    health_url = f"http://{ip}:{port}/health"
    try:
        req = urllib.request.Request(health_url, method="GET")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status == 200:
                return True
    except urllib.error.HTTPError as e:
        if e.code == 404:
            # Fallback to /clipboard
            pass
        else:
            return False
    except Exception:
        return False

    # 2. Fallback /clipboard
    clip_url = f"http://{ip}:{port}/clipboard"
    try:
        req = urllib.request.Request(clip_url, method="GET")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status == 200:
                return True
    except Exception:
        return False

    return False


def discover_clipboard_server(
    port: int = 8765,
    json_str_override: Optional[str] = None,
    check_service_fn=check_app_service,
    force_refresh: bool = False
) -> Optional[str]:
    """
    Automatically discover the target Tailscale IP running the clipboard service.
    
    Rules:
    - Determine current machine from Self object.
    - Only consider peers that are currently Online.
    - Filter peers with valid Tailscale IPv4 addresses.
    - Test application reachability on port 8765.
    - Handle multiple clipboard servers deterministically by sorting IPs (or hostname).
    """
    global _DISCOVERY_CACHE

    now = time.time()
    if not force_refresh and json_str_override is None and _DISCOVERY_CACHE is not None:
        cache_time, cached_ip = _DISCOVERY_CACHE
        if now - cache_time < CACHE_TTL_SECONDS:
            return cached_ip

    json_str = json_str_override if json_str_override is not None else get_tailscale_status_json()
    if not json_str:
        if json_str_override is None and _DISCOVERY_CACHE is not None:
            _DISCOVERY_CACHE = (now, None)
        return None

    self_info, peers = parse_tailscale_status(json_str)

    # Filter online peers with IPv4 address
    online_peers = [p for p in peers if p["online"] and p["tailscale_ipv4"]]
    logger.info(f"[tailscale] discovered {len(online_peers)} online peers")

    if not online_peers:
        logger.info("[tailscale] no online peers found")
        if json_str_override is None:
            _DISCOVERY_CACHE = (now, None)
        return None

    # Sort peers deterministically by hostname then primary IP for reproducible candidate order
    online_peers.sort(key=lambda p: (p["hostname"], p["tailscale_ipv4"][0]))

    working_ips = []
    for peer in online_peers:
        primary_ip = peer["tailscale_ipv4"][0]
        peer_name = peer["hostname"] or primary_ip
        logger.info(f"[tailscale] checking {peer_name} ({primary_ip})")
        if check_service_fn(primary_ip, port):
            logger.info(f"[clipboard] service found at {primary_ip}:{port}")
            working_ips.append(primary_ip)

    if not working_ips:
        logger.warning(f"[clipboard] port {port} unreachable on all online Tailscale peers")
        return None, []
    
    return working_ips[0], working_ips


def prompt_select_device(working_servers: List[Dict], interactive: bool = True) -> List[str]:
    """
    If multiple clipboard servers are online:
    Prompt the user on CLI to pick a specific device or ALL devices.
    If non-interactive (e.g. background service), returns all working server IPs or the primary one.
    Returns list of chosen server IP addresses.
    """
    if not working_servers:
        return []
    if len(working_servers) == 1:
        return [working_servers[0]["ip"]]

    print("\n[tailscale] Multiple online clipboard services discovered:")
    print("  0) [ALL] Sync with all active devices")
    for idx, srv in enumerate(working_servers, 1):
        name = srv.get("hostname") or srv["ip"]
        os_info = srv.get("os", "")
        os_str = f" ({os_info})" if os_info else ""
        print(f"  {idx}) {name}{os_str} — {srv['ip']}")

    if not interactive or not sys.stdin.isatty():
        print("[tailscale] Non-interactive environment detected. Defaulting to: ALL active devices.")
        return [srv["ip"] for srv in working_servers]

    try:
        choice = input(f"\nSelect target device [0-{len(working_servers)}] (default: 0 [ALL]): ").strip()
        if not choice or choice == "0":
            return [srv["ip"] for srv in working_servers]
        
        idx = int(choice)
        if 1 <= idx <= len(working_servers):
            chosen_ip = working_servers[idx - 1]["ip"]
            print(f"[tailscale] Selected target: {working_servers[idx - 1].get('hostname') or chosen_ip} ({chosen_ip})")
            return [chosen_ip]
        else:
            print("[tailscale] Invalid selection. Defaulting to: ALL active devices.")
            return [srv["ip"] for srv in working_servers]
    except Exception as e:
        print(f"[tailscale] Selection error: {e}. Defaulting to: ALL active devices.")
        return [srv["ip"] for srv in working_servers]


def discover_clipboard_server(
    port: int = 8765,
    json_str_override: Optional[str] = None,
    check_service_fn=check_app_service,
    force_refresh: bool = False,
    prompt_interactive: bool = False
) -> Optional[str]:
    """
    Automatically discover target Tailscale IP running the clipboard service.
    If multiple servers are found and prompt_interactive is True, prompts user for choice.
    """
    global _DISCOVERY_CACHE

    now = time.time()
    if not force_refresh and json_str_override is None and _DISCOVERY_CACHE is not None:
        cache_time, cached_ip = _DISCOVERY_CACHE
        if now - cache_time < CACHE_TTL_SECONDS:
            return cached_ip

    json_str = json_str_override if json_str_override is not None else get_tailscale_status_json()
    if not json_str:
        if json_str_override is None and _DISCOVERY_CACHE is not None:
            _DISCOVERY_CACHE = (now, None)
        return None

    self_info, peers = parse_tailscale_status(json_str)

    # Filter online peers with IPv4 address
    online_peers = [p for p in peers if p["online"] and p["tailscale_ipv4"]]
    logger.info(f"[tailscale] discovered {len(online_peers)} online peers")

    if not online_peers:
        logger.info("[tailscale] no online peers found")
        if json_str_override is None:
            _DISCOVERY_CACHE = (now, None)
        return None

    # Sort peers deterministically by hostname then primary IP
    online_peers.sort(key=lambda p: (p["hostname"], p["tailscale_ipv4"][0]))

    working_servers = []
    for peer in online_peers:
        primary_ip = peer["tailscale_ipv4"][0]
        peer_name = peer["hostname"] or primary_ip
        logger.info(f"[tailscale] checking {peer_name} ({primary_ip})")
        if check_service_fn(primary_ip, port):
            logger.info(f"[clipboard] service found at {primary_ip}:{port}")
            working_servers.append({
                "ip": primary_ip,
                "hostname": peer["hostname"],
                "os": peer["os"]
            })

    if not working_servers:
        logger.warning(f"[clipboard] port {port} unreachable on all online Tailscale peers")
        res_ip = None
    elif len(working_servers) == 1:
        res_ip = working_servers[0]["ip"]
    else:
        # Multiple online servers found!
        if prompt_interactive:
            selected_ips = prompt_select_device(working_servers, interactive=True)
            res_ip = selected_ips[0] if selected_ips else working_servers[0]["ip"]
        else:
            # Sort deterministically by IPv4 numerical octets
            def ip_key(srv: dict):
                try:
                    return tuple(int(x) for x in srv["ip"].split("."))
                except Exception:
                    return (999, 999, 999, 999)

            working_servers.sort(key=ip_key)
            res_ip = working_servers[0]["ip"]
            logger.warning(f"[clipboard] multiple clipboard servers found: {[s['ip'] for s in working_servers]}. Selected deterministic peer: {res_ip}")

    if json_str_override is None:
        _DISCOVERY_CACHE = (now, res_ip)

    return res_ip

