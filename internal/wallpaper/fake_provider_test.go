package wallpaper

import (
	"context"
	"sync"
)

// fakeProvider is an in-memory ArchiveProvider for tests. It records
// every upload and can be configured to fail specific source paths so
// tests do not need a real Telegram bot.
type fakeProvider struct {
	mu          sync.Mutex
	name        string
	uploads     []ArchiveFile
	maxSize     int64
	failOn      map[string]error // keyed by SourcePath
	createTopic bool             // when true, pretend topics are made
}

func newFakeProvider() *fakeProvider {
	return &fakeProvider{name: "fake", failOn: map[string]error{}}
}

func (f *fakeProvider) Name() string { return f.name }

func (f *fakeProvider) MaxUploadSize() int64 { return f.maxSize }

func (f *fakeProvider) Upload(ctx context.Context, af ArchiveFile) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if err, ok := f.failOn[af.SourcePath]; ok {
		return err
	}
	f.uploads = append(f.uploads, af)
	return nil
}

func (f *fakeProvider) uploadedPaths() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([]string, 0, len(f.uploads))
	for _, u := range f.uploads {
		out = append(out, u.SourcePath)
	}
	return out
}

func (f *fakeProvider) uploadedCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.uploads)
}
