package wallpaper

import (
	"os"
	"path/filepath"
	"testing"
)

// writeFile writes content to a temp path under t and returns it.
func writeFile(t *testing.T, dir, rel string, content []byte) string {
	t.Helper()
	p := filepath.Join(dir, rel)
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, content, 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestScanRecursiveDiscoversSupportedImages(t *testing.T) {
	root := t.TempDir()
	writeFile(t, root, "Tokyo Night/lowpoly_street.png", []byte("a"))
	writeFile(t, root, "Catppuccin Latte/cat.webp", []byte("b"))
	writeFile(t, root, "nested/deep/thing.avif", []byte("c"))
	writeFile(t, root, "top.gif", []byte("d"))

	files, err := Scan(root)
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 4 {
		t.Fatalf("want 4 discovered, got %d: %+v", len(files), files)
	}
}

func TestScanFiltersUnsupportedExtensions(t *testing.T) {
	root := t.TempDir()
	writeFile(t, root, "a.jpg", []byte("ok"))
	writeFile(t, root, "b.mp4", []byte("video"))
	writeFile(t, root, "c.txt", []byte("text"))
	writeFile(t, root, "d.JPEG", []byte("upper"))
	writeFile(t, root, "e.tiff", []byte("tiff"))
	writeFile(t, root, "f.xyz", []byte("unknown"))

	files, err := Scan(root)
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]bool{}
	for _, f := range files {
		got[f.Rel] = true
	}
	for _, want := range []string{"a.jpg", "d.JPEG", "e.tiff"} {
		if !got[want] {
			t.Errorf("expected %q to be discovered", want)
		}
	}
	for _, banned := range []string{"b.mp4", "c.txt", "f.xyz"} {
		if got[banned] {
			t.Errorf("expected %q to be filtered out", banned)
		}
	}
}

func TestScanSkipsUnreadableSubtrees(t *testing.T) {
	root := t.TempDir()
	writeFile(t, root, "ok/a.png", []byte("a"))
	// Create an unreadable directory to ensure walk survives it.
	locked := filepath.Join(root, "locked")
	if err := os.MkdirAll(locked, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(locked, 0); err == nil {
		defer os.Chmod(locked, 0o755)
	}
	writeFile(t, root, "ok/c.jpg", []byte("c"))

	files, err := Scan(root)
	if err != nil {
		t.Fatalf("scan must not fail on unreadable subtree: %v", err)
	}
	if len(files) != 2 {
		t.Fatalf("want 2 files, got %d", len(files))
	}
}

func TestCategoryMapping(t *testing.T) {
	cases := []struct{ rel, want string }{
		{"Tokyo Night/lowpoly.png", "Tokyo Night"},
		{"Catppuccin Latte/sub/deep.jpg", "Catppuccin Latte"},
		{"wallpaper.jpg", Unsorted},
		{"a/b/c.webp", "a"},
	}
	for _, c := range cases {
		if got := CategoryOf(c.rel); got != c.want {
			t.Errorf("CategoryOf(%q) = %q, want %q", c.rel, got, c.want)
		}
	}
}

func TestIsSupportedImage(t *testing.T) {
	for _, ok := range []string{"x.jpg", "x.jpeg", "x.png", "x.webp", "x.gif", "x.bmp", "x.tif", "x.tiff", "x.avif", "x.JPG", "x.PNG"} {
		if !IsSupportedImage(ok) {
			t.Errorf("expected %q supported", ok)
		}
	}
	for _, no := range []string{"x.mp4", "x.mov", "x.txt", "x", ".png", "x.WebP2"} {
		if IsSupportedImage(no) {
			t.Errorf("expected %q not supported", no)
		}
	}
}
