package wallpaper

import (
	"crypto/sha256"
	"encoding/hex"
	"path/filepath"
	"testing"
)

func TestSHA256(t *testing.T) {
	const content = "hello wallpaper"
	root := t.TempDir()
	p := writeFile(t, root, "x.png", []byte(content))

	sum := sha256.Sum256([]byte(content))
	want := hex.EncodeToString(sum[:])

	got, err := SHA256(p)
	if err != nil {
		t.Fatal(err)
	}
	if got != want {
		t.Errorf("SHA256 mismatch:\n got %s\nwant %s", got, want)
	}
}

func TestSHA256LargeStreamsInChunks(t *testing.T) {
	root := t.TempDir()
	big := make([]byte, hashChunkSize*3+123)
	for i := range big {
		big[i] = byte(i % 251)
	}
	p := writeFile(t, root, "big.bmp", big)
	got, err := SHA256(p)
	if err != nil {
		t.Fatal(err)
	}
	want := sha256.Sum256(big[:])
	if got != hex.EncodeToString(want[:]) {
		t.Error("large file hash mismatch (chunked hashing broken)")
	}
}

func TestSHA256MissingFile(t *testing.T) {
	if _, err := SHA256(filepath.Join(t.TempDir(), "nope.png")); err == nil {
		t.Error("expected error for missing file")
	}
}
