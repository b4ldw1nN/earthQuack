package wallpaper

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
)

// hashChunkSize bounds memory usage when hashing large files: the file
// is streamed in fixed-size chunks, never loaded into memory whole.
const hashChunkSize = 1 << 20 // 1 MiB

// SHA256 computes the hex SHA-256 digest of the file at path, reading
// it in bounded chunks so arbitrarily large files never fill memory.
func SHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()

	h := sha256.New()
	buf := make([]byte, hashChunkSize)
	if _, err := io.CopyBuffer(h, f, buf); err != nil {
		return "", fmt.Errorf("hash %s: %w", path, err)
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
