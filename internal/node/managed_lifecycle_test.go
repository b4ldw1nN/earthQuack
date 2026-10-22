package node

import (
	"context"
	"errors"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func waitManaged(t *testing.T, check func() bool) {
	t.Helper()
	deadline := time.Now().Add(4 * time.Second)
	for !check() {
		if time.Now().After(deadline) {
			t.Fatal("timed out waiting for lifecycle transition")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func freeLoopbackPort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	return ln.Addr().(*net.TCPAddr).Port
}

func TestGoDaemonLifecycle(t *testing.T) {
	clipPort := freeLoopbackPort(t)
	filePort := freeLoopbackPort(t)
	for filePort == clipPort {
		filePort = freeLoopbackPort(t)
	}

	// Invalid port fails validation
	if _, err := NewGoDaemon(GoDaemonConfig{ClipboardPort: "invalid", FilePort: "8876"}); err == nil {
		t.Fatal("expected error on invalid clipboard port")
	}

	tempDir := t.TempDir()
	g, err := NewGoDaemon(GoDaemonConfig{
		Host:          "127.0.0.1",
		ClipboardPort: strconv.Itoa(clipPort),
		FilePort:      strconv.Itoa(filePort),
		AuthToken:     "test-token",
		StageDir:      tempDir,
		PhoneSaveDir:  tempDir,
		DesktopBridge: false,
	})
	if err != nil {
		t.Fatalf("failed to create GoDaemon: %v", err)
	}

	if g.Snapshot().Running {
		t.Fatal("expected daemon to not be running before Start()")
	}

	if err := g.Start(); err != nil {
		t.Fatalf("Start() failed: %v", err)
	}

	if !g.Snapshot().Running {
		t.Fatal("expected daemon to be running after Start()")
	}

	// Idempotent start
	if err := g.Start(); err != nil {
		t.Fatalf("double Start() failed: %v", err)
	}

	g.Stop()
	if g.Snapshot().Running {
		t.Fatal("expected daemon to not be running after Stop()")
	}

	// Double stop is safe
	g.Stop()

	// Resume
	if err := g.Start(); err != nil {
		t.Fatalf("resuming Start() failed: %v", err)
	}
	if !g.Snapshot().Running {
		t.Fatal("expected daemon to be running after resume")
	}

	g.Shutdown()
	if g.Snapshot().Running {
		t.Fatal("expected daemon to not be running after Shutdown()")
	}
}

func TestManagedJobCancellationAndRestart(t *testing.T) {
	var calls atomic.Int32
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	entered := make(chan struct{}, 2)
	j := NewManagedJob(ctx, func(ctx context.Context) (string, error) {
		calls.Add(1)
		entered <- struct{}{}
		<-ctx.Done()
		return "", ctx.Err()
	})
	defer func() { j.Stop(); j.Wait() }()
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); _ = j.Start(); _ = j.Snapshot() }()
	}
	wg.Wait()
	<-entered
	if calls.Load() != 1 {
		t.Fatal("duplicate job")
	}
	j.Stop()
	j.Wait()
	if j.Snapshot().Running || !strings.Contains(j.Snapshot().Message, "stopped") {
		t.Fatal(j.Snapshot())
	}
	if err := j.Start(); err != nil {
		t.Fatal(err)
	}
	<-entered
	cancel()
	j.Wait()
	if err := j.Start(); err == nil {
		t.Fatal("started after parent cancellation")
	}
}

func TestManagedJobCompletionAndSafeErrors(t *testing.T) {
	for _, fail := range []bool{false, true} {
		j := NewManagedJob(context.Background(), func(context.Context) (string, error) {
			if fail {
				return "", errors.New("private-provider-token")
			}
			return "Uploaded: 2", nil
		})
		if err := j.Start(); err != nil {
			t.Fatal(err)
		}
		j.Wait()
		s := j.Snapshot()
		if s.Running || strings.Contains(s.Message, "private-provider-token") {
			t.Fatal(s)
		}
		if !fail && !strings.Contains(s.Message, "Uploaded: 2") {
			t.Fatal(s)
		}
	}
}
