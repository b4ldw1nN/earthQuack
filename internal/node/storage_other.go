//go:build !linux

package node

// procStorageInfo is a stub for platforms where /proc and statfs are
// unavailable (Windows/Android node support is deferred). Collect
// intentionally returns an empty snapshot; the Node layer degrades
// cleanly, exactly as for the system-info stub.
type procStorageInfo struct{}

// newStorageProvider returns a provider that reports no storage info.
func newStorageProvider() StorageProvider { return procStorageInfo{} }

func (p procStorageInfo) Collect() StorageInfo { return StorageInfo{} }
