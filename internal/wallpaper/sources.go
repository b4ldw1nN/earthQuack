package wallpaper

import (
	"fmt"
	"path/filepath"
	"sort"
)

// ScanSources accepts a platform path list (colon-separated on Linux).
// Categories remain relative to each root, matching the original archive.
func ScanSources(source string) ([]Discovered, error) {
	roots := filepath.SplitList(source)
	if len(roots) == 0 {
		return nil, fmt.Errorf("wallpaper: source directory is required")
	}
	seen := map[string]bool{}
	var all []Discovered
	for _, root := range roots {
		absolute, err := filepath.Abs(root)
		if err != nil {
			return nil, err
		}
		files, err := Scan(absolute)
		if err != nil {
			return nil, err
		}
		for _, file := range files {
			if !seen[file.Path] {
				seen[file.Path] = true
				all = append(all, file)
			}
		}
	}
	sort.Slice(all, func(i, j int) bool { return all[i].Path < all[j].Path })
	return all, nil
}
