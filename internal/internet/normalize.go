package internet

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
)

// fingerprintVersion is mixed into every hash so a future change to the
// canonical form cannot silently look like a content change (or, worse,
// like no change at all).
const fingerprintVersion = "earthquack-microscope-v1"

// Normalize returns the deterministic canonical representation of a
// response body for the given source type. The fingerprint is computed
// over this representation, so anything Normalize discards can never
// cause a "changed" event.
//
// The contract is:
//
//	same meaningful content  → byte-identical canonical form
//	different meaningful     → different canonical form
//	volatile metadata        → dropped (never compared)
//
// Only the source's own content is canonicalized; the request URL,
// request time, headers and transfer encoding are never included.
func Normalize(t SourceType, body []byte) ([]byte, error) {
	switch t {
	case "", TypeHTTP:
		return normalizeText(body), nil
	case TypeRSS:
		return normalizeFeed(body)
	default:
		return nil, fmt.Errorf("internet: no normalizer for source type %q", t)
	}
}

// Fingerprint returns the SHA-256 hex digest of the normalized
// representation of body, tagged with the fingerprint version and the
// source type so two source types can never collide.
func Fingerprint(t SourceType, body []byte) (string, error) {
	canonical, err := Normalize(t, body)
	if err != nil {
		return "", err
	}
	h := sha256.New()
	h.Write([]byte(fingerprintVersion))
	h.Write([]byte{0})
	h.Write([]byte(t))
	h.Write([]byte{0})
	h.Write(canonical)
	return hex.EncodeToString(h.Sum(nil)), nil
}

// normalizeText canonicalizes an arbitrary text document: line endings
// are unified and trailing whitespace and trailing blank lines are
// dropped. The body is preserved otherwise — the microscope reports
// what the origin served, it does not rewrite it.
//
// Note the deliberate limit: a page that embeds a wall-clock timestamp
// in its own content will still look "changed" when that content
// changes. That is a property of the source, not of the fingerprint.
func normalizeText(body []byte) []byte {
	body = bytes.ReplaceAll(body, []byte("\r\n"), []byte("\n"))
	body = bytes.ReplaceAll(body, []byte("\r"), []byte("\n"))

	out := make([]byte, 0, len(body))
	for _, line := range bytes.Split(body, []byte("\n")) {
		out = append(out, bytes.TrimRight(line, " \t")...)
		out = append(out, '\n')
	}
	return bytes.TrimRight(out, "\n")
}
