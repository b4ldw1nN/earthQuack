#!/usr/bin/env python3
"""
crypto_util.py — AES-256-GCM for Clipboard Sync (no SHA, pure AES)
Wire format: AES:<Base64(12-byte IV || ciphertext+tag)>
Key: Base64 32 bytes (256-bit) via CLIPBOARD_AES_KEY env
If not set or AES disabled, data passes plaintext.
"""
import base64
import os

PREFIX = "AES:"
AES_KEY_B64 = os.environ.get("CLIPBOARD_AES_KEY", "")
AES_ENABLED = os.environ.get("CLIPBOARD_AES_ENABLED", "0") == "1" or bool(AES_KEY_B64 and len(AES_KEY_B64.strip()) > 0)

def _get_key() -> bytes | None:
    b64 = os.environ.get("CLIPBOARD_AES_KEY", "").strip()
    if not b64:
        return None
    try:
        k = base64.b64decode(b64)
        if len(k) != 32:
            print(f"[crypto] AES key must be 32 bytes, got {len(k)}", flush=True)
            return None
        return k
    except Exception as e:
        print(f"[crypto] bad AES key: {e}", flush=True)
        return None

def is_valid_key(b64: str) -> bool:
    try:
        return len(base64.b64decode(b64.strip())) == 32
    except:
        return False

def generate_key_b64() -> str:
    return base64.b64encode(os.urandom(32)).decode()

def encrypt(plain: str) -> str:
    key = _get_key()
    if key is None:
        return plain
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        iv = os.urandom(12)
        aesgcm = AESGCM(key)
        ct = aesgcm.encrypt(iv, plain.encode("utf-8"), None)
        combined = iv + ct
        return PREFIX + base64.b64encode(combined).decode()
    except ImportError:
        try:
            from Crypto.Cipher import AES
            from Crypto.Random import get_random_bytes
            iv = get_random_bytes(12)
            cipher = AES.new(key, AES.MODE_GCM, nonce=iv)
            ct, tag = cipher.encrypt_and_digest(plain.encode("utf-8"))
            combined = iv + ct + tag
            return PREFIX + base64.b64encode(combined).decode()
        except Exception as e:
            print(f"[crypto] encrypt fallback failed: {e}", flush=True)
            return plain
    except Exception as e:
        print(f"[crypto] encrypt failed: {e}", flush=True)
        return plain

def decrypt(maybe_encrypted: str) -> str:
    if not maybe_encrypted.startswith(PREFIX):
        return maybe_encrypted
    key = _get_key()
    if key is None:
        return maybe_encrypted
    b64 = maybe_encrypted[len(PREFIX):]
    try:
        data = base64.b64decode(b64)
        iv, ct = data[:12], data[12:]
        try:
            from cryptography.hazmat.primitives.ciphers.aead import AESGCM
            aesgcm = AESGCM(key)
            pt = aesgcm.decrypt(iv, ct, None)
            return pt.decode("utf-8")
        except ImportError:
            from Crypto.Cipher import AES
            # pycryptodome: last 16 bytes are tag
            tag = ct[-16:]
            ciphertext = ct[:-16]
            cipher = AES.new(key, AES.MODE_GCM, nonce=iv)
            pt = cipher.decrypt_and_verify(ciphertext, tag)
            return pt.decode("utf-8")
    except Exception as e:
        print(f"[crypto] decrypt failed: {e}", flush=True)
        return maybe_encrypted

def wrap(plain: str) -> str:
    # Re-read env each time to pick up changes without restart (for CLI)
    enabled = os.environ.get("CLIPBOARD_AES_ENABLED", "0") == "1" or bool(os.environ.get("CLIPBOARD_AES_KEY","").strip())
    # Also check if file has override? we check env directly
    # Use global flag but allow per-call env check
    if not enabled:
        # check if key present still means enabled
        if not os.environ.get("CLIPBOARD_AES_KEY","").strip():
            return plain
    if _get_key() is None:
        return plain
    return encrypt(plain)

def unwrap(maybe_encrypted: str) -> str:
    if not maybe_encrypted.startswith(PREFIX):
        return maybe_encrypted
    return decrypt(maybe_encrypted)
