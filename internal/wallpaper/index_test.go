package wallpaper

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestFileIndexMissingFileIsEmpty(t *testing.T) {
	dir := t.TempDir()
	ix, err := loadFileIndex(dir)
	if err != nil {
		t.Fatal(err)
	}
	if ix.size() != 0 {
		t.Fatalf("missing files.json must yield an empty index, got %d", ix.size())
	}
	if _, ok := ix.lookup("/a.jpg", 1, 2); ok {
		t.Fatal("empty index must not produce cache hits")
	}
}

// TestFileIndexReadsPythonShape pins the on-disk format shared with the
// wallpaper-backup script (one entry per resolved path).
func TestFileIndexReadsPythonShape(t *testing.T) {
	dir := t.TempDir()
	raw := `{
  "/home/zoro/Pictures/wallpapers/superhero/DeathStar.png": {"size": 754073, "mtime_ns": 1768554325206268164, "sha256": "8c43e9048a713a9c4fc982c77b18023c99d76d524fc3780aa7dd477d031e05b3"}
}`
	if err := os.WriteFile(filepath.Join(dir, filesFileName), []byte(raw), 0o600); err != nil {
		t.Fatal(err)
	}
	ix, err := loadFileIndex(dir)
	if err != nil {
		t.Fatal(err)
	}
	const path = "/home/zoro/Pictures/wallpapers/superhero/DeathStar.png"
	digest, ok := ix.lookup(path, 754073, 1768554325206268164)
	if !ok {
		t.Fatal("index entry written by the Python script must be honoured")
	}
	if digest != "8c43e9048a713a9c4fc982c77b18023c99d76d524fc3780aa7dd477d031e05b3" {
		t.Fatalf("unexpected digest: %s", digest)
	}
}

func TestFileIndexLookupRequiresMatchingFingerprint(t *testing.T) {
	ix, err := loadFileIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	ix.set("/a.jpg", 100, 5000, "digest-a")

	if _, ok := ix.lookup("/a.jpg", 100, 5000); !ok {
		t.Error("unchanged file must be a cache hit")
	}
	if _, ok := ix.lookup("/a.jpg", 100, 5001); ok {
		t.Error("changed mtime must invalidate the cache")
	}
	if _, ok := ix.lookup("/a.jpg", 101, 5000); ok {
		t.Error("changed size must invalidate the cache")
	}
	if _, ok := ix.lookup("/other.jpg", 100, 5000); ok {
		t.Error("unknown path must not be a cache hit")
	}
}

func TestFileIndexRoundTripPersistsAtomically(t *testing.T) {
	dir := t.TempDir()
	ix, err := loadFileIndex(dir)
	if err != nil {
		t.Fatal(err)
	}
	ix.set("/a.jpg", 10, 20, "aaaa")
	ix.set("/b.png", 30, 40, "bbbb")
	if err := ix.save(); err != nil {
		t.Fatal(err)
	}

	data, err := os.ReadFile(filepath.Join(dir, filesFileName))
	if err != nil {
		t.Fatal(err)
	}
	var raw map[string]FileIndexRecord
	if err := json.Unmarshal(data, &raw); err != nil {
		t.Fatalf("files.json not valid JSON: %v", err)
	}
	if len(raw) != 2 || raw["/a.jpg"].SHA256 != "aaaa" || raw["/b.png"].MtimeNS != 40 {
		t.Fatalf("unexpected persisted index: %v", raw)
	}

	reloaded, err := loadFileIndex(dir)
	if err != nil {
		t.Fatal(err)
	}
	if d, ok := reloaded.lookup("/b.png", 30, 40); !ok || d != "bbbb" {
		t.Fatalf("index did not round-trip: %q %v", d, ok)
	}
	// Atomic writes must leave no temp files behind.
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if filepath.Ext(e.Name()) == ".tmp" {
			t.Errorf("leftover temp file: %s", e.Name())
		}
	}
}

func TestFileIndexPrunesVanishedFiles(t *testing.T) {
	ix, err := loadFileIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	ix.set("/src/keep.jpg", 1, 1, "k")
	ix.set("/src/gone.jpg", 2, 2, "g")
	removed := ix.prune("/src", map[string]struct{}{"/src/keep.jpg": {}})
	if removed != 1 {
		t.Fatalf("want 1 pruned entry, got %d", removed)
	}
	if ix.size() != 1 {
		t.Fatalf("want 1 remaining entry, got %d", ix.size())
	}
	if _, ok := ix.lookup("/src/gone.jpg", 2, 2); ok {
		t.Fatal("pruned entry must not be readable")
	}
}

// TestFileIndexPruneLeavesOtherRootsAlone protects a shared state
// directory: this archive caches two source trees, and scanning one must
// not discard the other's cached digests.
func TestFileIndexPruneLeavesOtherRootsAlone(t *testing.T) {
	ix, err := loadFileIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	ix.set("/src/a.jpg", 1, 1, "a")
	ix.set("/other/b.jpg", 2, 2, "b")
	ix.set("/srcsibling/c.jpg", 3, 3, "c") // shares a prefix but not a root
	// /src/a.jpg vanished; everything else is outside this scan's root.
	removed := ix.prune("/src", map[string]struct{}{})
	if removed != 1 {
		t.Fatalf("want only the vanished in-root entry pruned, got %d", removed)
	}
	if ix.size() != 2 {
		t.Fatalf("entries outside the root must survive, got %d", ix.size())
	}
	for p, want := range map[string]string{"/other/b.jpg": "b", "/srcsibling/c.jpg": "c"} {
		sizes := map[string]int64{"/other/b.jpg": 2, "/srcsibling/c.jpg": 3}
		if d, ok := ix.lookup(p, sizes[p], sizes[p]); !ok || d != want {
			t.Errorf("entry %s lost: %q %v", p, d, ok)
		}
	}
}

func TestFileIndexRejectsInvalidJSON(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, filesFileName), []byte("{oops"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := loadFileIndex(dir); err == nil {
		t.Fatal("invalid files.json must be an error, never silently ignored")
	}
}
