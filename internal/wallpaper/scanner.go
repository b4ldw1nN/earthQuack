package wallpaper

import (
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// ImageExtensions is the exact set of supported image formats. It
// preserves the behaviour of the original wallpaper-backup
// implementation — no video formats are added.
var ImageExtensions = map[string]struct{}{
	".jpg":  {},
	".jpeg": {},
	".png":  {},
	".webp": {},
	".gif":  {},
	".bmp":  {},
	".tif":  {},
	".tiff": {},
	".avif": {},
}

// IsSupportedImage reports whether the given file name has a known
// image extension (case-insensitive). It matches the original
// wallpaper-backup behaviour, in which a lone extension with no
// filename (e.g. a file literally named ".png") is not treated as a
// supported image.
func IsSupportedImage(name string) bool {
	base := filepath.Base(name)
	dot := strings.LastIndexByte(base, '.')
	if dot <= 0 { // no dot, or dot at the very start (no stem)
		return false
	}
	ext := strings.ToLower(base[dot:])
	_, ok := ImageExtensions[ext]
	return ok
}

// Discovered is one supported image file found under the source tree.
type Discovered struct {
	Path     string // absolute path on disk
	Rel      string // path relative to the source root
	Category string // folder name, or Unsorted for root-level files
}

// CategoryOf maps a file's relative path to its destination category:
// the first path component, or Unsorted when the file sits directly at
// the source root.
func CategoryOf(rel string) string {
	// Example: "Tokyo Night/lowpoly_street.png" → "Tokyo Night"
	parts := strings.Split(strings.TrimPrefix(filepath.ToSlash(rel), "/"), "/")
	if len(parts) > 1 {
		return parts[0]
	}
	// Example: "wallhaven-2863em.jpg" → Unsorted
	return Unsorted
}

// Scan recursively walks the source directory and returns every
// supported image file, ordered deterministically by relative path.
// It never descends into a directory the process cannot read; such
// subtrees are skipped. The returned slice is stable (sorted) so sync
// and dry-run output is reproducible.
func Scan(source string) ([]Discovered, error) {
	info, err := os.Stat(source)
	if err != nil {
		return nil, fmt.Errorf("wallpaper: source directory: %w", err)
	}
	if !info.IsDir() {
		return nil, fmt.Errorf("wallpaper: source is not a directory: %s", source)
	}

	var out []Discovered
	walkErr := filepath.WalkDir(source, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			// Skip unreadable subtrees without failing the whole scan.
			if d != nil && d.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		if d.IsDir() {
			return nil
		}
		if !IsSupportedImage(d.Name()) {
			return nil
		}
		rel, rerr := filepath.Rel(source, path)
		if rerr != nil {
			return nil
		}
		out = append(out, Discovered{
			Path:     path,
			Rel:      rel,
			Category: CategoryOf(rel),
		})
		return nil
	})
	if walkErr != nil {
		return nil, fmt.Errorf("wallpaper: scan %s: %w", source, walkErr)
	}

	sort.Slice(out, func(i, j int) bool { return out[i].Rel < out[j].Rel })
	return out, nil
}
