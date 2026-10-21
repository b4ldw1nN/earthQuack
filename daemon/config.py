import os


SERVER_HOST = os.environ.get(
    "EARTHQUACK_HOST",
    "100.92.160.31",
)

SERVER_PORT = int(
    os.environ.get(
        "EARTHQUACK_PORT",
        "8875",
    )
)

FILE_SERVER_PORT = int(
    os.environ.get(
        "EARTHQUACK_FILE_PORT",
        "8876",
    )
)


APP_NAME = "earthQuack"
APP_VERSION = "0.1.0"


def _key_from_config_file() -> str:
    """The AES key from ~/.config/earthquack/config.json, if it is there.

    That file is where the key is actually kept; the environment variable is
    only a fallback for one-off runs.
    """
    import json

    path = os.path.expanduser(
        "~/.config/earthquack/config.json"
    )

    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError):
        return ""

    key = data.get(
        "clipboard_aes_key",
        "",
    )

    return key if isinstance(key, str) else ""


# Encryption state of the clipboard API. The phone encrypts what it sends
# when it has a key configured; if the two sides disagree, the phone stores
# ciphertext the desktop cannot read (and the other way round). The app
# queries /capabilities to see what this side actually does.
AES_KEY = (
    _key_from_config_file()
    or os.environ.get(
        "EARTHQUACK_AES_KEY",
        "",
    )
)

AES_ENABLED = bool(AES_KEY)


def aes_key_fingerprint() -> str:
    """Short, non-reversible fingerprint of the AES key.

    Enough for two sides to tell "same key" from "different key" without
    publishing the key itself.
    """
    if not AES_KEY:
        return ""

    import hashlib

    return hashlib.sha256(
        AES_KEY.encode("utf-8")
    ).hexdigest()[:16]


def _auth_token_from_config_file() -> str:
    """The access token from ~/.config/earthquack/config.json, if present."""
    import json

    path = os.path.expanduser(
        "~/.config/earthquack/config.json"
    )

    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError):
        return ""

    auth = data.get(
        "auth",
        {}
    )

    if not isinstance(auth, dict):
        return ""

    token = auth.get(
        "token",
        ""
    )

    return token if isinstance(token, str) else ""


# Access token for this server. When it is set, callers must send it as
# "Authorization: Bearer <token>"; the phone sends the same value. When it is
# not set, the server stays open, as it always was.
AUTH_TOKEN = (
    _auth_token_from_config_file()
    or os.environ.get(
        "EARTHQUACK_AUTH_TOKEN",
        "",
    )
)


def is_authorized(header_value: str | None) -> bool:
    """Whether a request's Authorization header may use this server.

    No configured token means no enforcement: the server behaves as it did
    before, which is the right default for a tailnet-only service someone
    may not have secured yet.
    """
    if not AUTH_TOKEN:
        return True

    if not header_value:
        return False

    return header_value.strip() == f"Bearer {AUTH_TOKEN}"

