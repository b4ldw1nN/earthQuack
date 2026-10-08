package daemon

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"strings"
)

// AESPrefix tags an encrypted clipboard value so a receiver can tell
// ciphertext from plaintext without trying to decrypt.
//
// This exact prefix is a three-way contract: daemon/crypto_util.py and
// CryptoUtil.kt both emit it, and CryptoUtil.unwrapIfNeeded dispatches on
// it. Changing it silently breaks decryption on the phone.
const AESPrefix = "AES:"

// aesIVBytes is the GCM nonce length used by both existing
// implementations. It is the value AES-GCM is designed around; it is not
// configurable and must not be changed independently on one side.
const aesIVBytes = 12

// ErrInvalidKey reports a base64 AES key that does not decode to 32 bytes.
var ErrInvalidKey = errors.New("daemon: AES key must be 32 bytes (256-bit) base64")

// Crypto wraps and unwraps clipboard text with AES-256-GCM.
//
// Wire format, byte-for-byte identical to the Python and Kotlin versions:
//
//	AES:<base64( 12-byte IV || ciphertext || 16-byte tag )>
//
// Go's cipher.AEAD Seal appends the tag to the ciphertext, so sealing into
// a buffer pre-loaded with the IV produces exactly that layout. That is
// what makes a value encrypted by any of the three implementations
// decryptable by the others.
type Crypto struct {
	key []byte
}

// NewCrypto builds a Crypto from a base64 32-byte key.
//
// An empty key yields a disabled Crypto that passes text through
// untouched, which is the documented behaviour when no key is configured
// (Python: _get_key() returning None makes encrypt/decrypt pass through;
// Kotlin: wrapIfNeeded returns plain when the key is blank).
func NewCrypto(keyBase64 string) (*Crypto, error) {
	keyBase64 = strings.TrimSpace(keyBase64)
	if keyBase64 == "" {
		return &Crypto{}, nil
	}
	raw, err := base64.StdEncoding.DecodeString(keyBase64)
	if err != nil {
		// Tolerate the unpadded / URL-safe variants a user may have copied
		// out of another tool rather than failing the whole daemon.
		if raw, err = base64.RawStdEncoding.DecodeString(keyBase64); err != nil {
			return nil, fmt.Errorf("%w: %v", ErrInvalidKey, err)
		}
	}
	if len(raw) != 32 {
		return nil, fmt.Errorf("%w: got %d bytes", ErrInvalidKey, len(raw))
	}
	return &Crypto{key: raw}, nil
}

// Enabled reports whether encryption is active.
func (c *Crypto) Enabled() bool { return c != nil && len(c.key) == 32 }

// aead lazily builds the AEAD. A validated 32-byte key always yields a
// GCM instance, so the error is only defensive.
func (c *Crypto) aead() (cipher.AEAD, error) {
	block, err := aes.NewCipher(c.key)
	if err != nil {
		return nil, fmt.Errorf("daemon: aes: %w", err)
	}
	return cipher.NewGCM(block)
}

// Wrap encrypts plain text, returning an AES:-prefixed payload.
// With encryption disabled it returns plain unchanged.
func (c *Crypto) Wrap(plain string) string {
	if !c.Enabled() {
		return plain
	}
	gcm, err := c.aead()
	if err != nil {
		return plain
	}
	iv := make([]byte, aesIVBytes)
	if _, err := io.ReadFull(rand.Reader, iv); err != nil {
		return plain
	}
	// Seal(dst=iv[:0]...) would overwrite the IV, so append to a copy:
	// result is IV || ciphertext || tag.
	sealed := gcm.Seal(append([]byte(nil), iv...), iv, []byte(plain), nil)
	return AESPrefix + base64.StdEncoding.EncodeToString(sealed)
}

// Unwrap decrypts an AES:-prefixed payload.
//
// Text without the prefix is returned unchanged, so a mixed plaintext and
// AES deployment interoperates. A payload that fails to decrypt (wrong
// key, truncated, corrupted) is also returned unchanged rather than
// dropped — matching both existing implementations, which prefer showing
// the raw value over losing the clipboard.
func (c *Crypto) Unwrap(maybe string) string {
	if !strings.HasPrefix(maybe, AESPrefix) {
		return maybe
	}
	if !c.Enabled() {
		return maybe
	}
	raw, err := base64.StdEncoding.DecodeString(maybe[len(AESPrefix):])
	if err != nil {
		return maybe
	}
	if len(raw) <= aesIVBytes {
		return maybe
	}
	gcm, err := c.aead()
	if err != nil {
		return maybe
	}
	iv, ct := raw[:aesIVBytes], raw[aesIVBytes:]
	plain, err := gcm.Open(nil, iv, ct, nil)
	if err != nil {
		return maybe
	}
	return string(plain)
}

// GenerateAESKey returns a fresh base64 32-byte key, matching
// crypto_util.generate_key_b64 and CryptoUtil.generateKeyBase64.
func GenerateAESKey() (string, error) {
	raw := make([]byte, 32)
	if _, err := io.ReadFull(rand.Reader, raw); err != nil {
		return "", fmt.Errorf("daemon: generate key: %w", err)
	}
	return base64.StdEncoding.EncodeToString(raw), nil
}

// ValidAESKey reports whether s is base64 for exactly 32 bytes.
func ValidAESKey(s string) bool {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(s))
	return err == nil && len(raw) == 32
}
