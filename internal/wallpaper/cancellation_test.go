package wallpaper

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

type cancellingProvider struct {
	*fakeProvider
	cancel context.CancelFunc
	calls  int
}

func (p *cancellingProvider) Upload(ctx context.Context, file ArchiveFile) error {
	p.calls++
	p.cancel()
	return ctx.Err()
}

func TestSyncCancellationDoesNotFailRemainingFiles(t *testing.T) {
	source, state := t.TempDir(), t.TempDir()
	for _, name := range []string{"a.jpg", "b.jpg"} {
		if err := os.WriteFile(filepath.Join(source, name), []byte(name), 0600); err != nil {
			t.Fatal(err)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	provider := &cancellingProvider{fakeProvider: newFakeProvider(), cancel: cancel}
	m, err := NewModule(Config{Source: source, StateDir: state}, provider)
	if err != nil {
		t.Fatal(err)
	}
	report, err := m.Sync(ctx, false)
	if !errors.Is(err, context.Canceled) || provider.calls != 1 || report.Failed != 0 {
		t.Fatalf("calls=%d report=%+v err=%v", provider.calls, report, err)
	}
	for _, name := range []string{"failed.json", "sync.json"} {
		if _, err := os.Stat(filepath.Join(state, name)); !os.IsNotExist(err) {
			t.Fatalf("cancelled work wrote %s: %v", name, err)
		}
	}
}
