#!/usr/bin/env python3

import argparse
import json
import os
import subprocess
import urllib.request

DEFAULT_HOST = "YOUR_TAILSCALE_IP"
SERVER_HOST = os.environ.get("CLIPBOARD_SERVER_HOST", DEFAULT_HOST)
SERVER_PORT = os.environ.get("CLIPBOARD_SERVER_PORT", "8765")
SERVER = f"http://{SERVER_HOST}:{SERVER_PORT}/clipboard"

text = subprocess.run(
    ["wl-paste", "--no-newline"],
    capture_output=True,
    text=True,
).stdout

data = json.dumps({
    "clipboard": text,
    "origin": "desktop",
}).encode()

request = urllib.request.Request(
    SERVER,
    data=data,
    headers={"Content-Type": "application/json"},
    method="POST",
)

try:
    urllib.request.urlopen(request, timeout=5).close()
    print(f"Desktop → server: {text!r}", flush=True)
except Exception as e:
    print(f"Sync failed: {e}", flush=True)
