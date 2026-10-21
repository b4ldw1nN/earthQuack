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

# Encryption state of the clipboard API. The phone encrypts what it sends
# when it has a key configured; if the two sides disagree, the phone stores
# ciphertext the desktop cannot read (and the other way round). The app
# queries /capabilities to detect exactly that, so it can say so instead of
# silently exchanging garbage.
AES_KEY = os.environ.get(
    "EARTHQUACK_AES_KEY",
    "",
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

