package node

import (
	"context"
	"errors"
	"os/exec"
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

func TestDaemonPauseResumeAndCrashRecovery(t *testing.T) {
	if _, err := exec.LookPath("sleep"); err != nil {
		t.Skip("sleep unavailable")
	}
	m := NewDaemonManager(PythonDaemonConfig{})
	var launches atomic.Int32
	m.commandFactory = func() *exec.Cmd { launches.Add(1); return exec.Command("sleep", "60") }
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	defer m.Shutdown()
	m.BeginRestartLoop(ctx)
	if err := m.Start(); err != nil {
		t.Fatal(err)
	}
	if err := m.Start(); err != nil {
		t.Fatal(err)
	}
	if launches.Load() != 1 {
		t.Fatal("duplicate child")
	}
	m.mu.Lock()
	child := m.cmd.Process
	m.mu.Unlock()
	if err := child.Kill(); err != nil {
		t.Fatal(err)
	}
	waitManaged(t, func() bool { return launches.Load() >= 2 && m.Snapshot().Running })
	m.Stop()
	count := launches.Load()
	time.Sleep(1200 * time.Millisecond)
	if m.Snapshot().Running || launches.Load() != count {
		t.Fatal("intentional stop was restarted")
	}
	if err := m.Start(); err != nil {
		t.Fatal(err)
	}
	if !m.Snapshot().Running {
		t.Fatal("did not resume")
	}
	m.Shutdown()
	if err := m.Start(); err == nil {
		t.Fatal("started after shutdown")
	}
}

func TestDaemonFailedLaunchAndStopWithoutSupervisor(t *testing.T) {
	m := NewDaemonManager(PythonDaemonConfig{})
	m.commandFactory = func() *exec.Cmd { return exec.Command("/nonexistent/earthquack-test") }
	if err := m.Start(); err == nil {
		t.Fatal("expected launch error")
	}
	m.Stop()
	m.Stop()
	if m.Snapshot().Running {
		t.Fatal("failed launch reported running")
	}
	if _, err := exec.LookPath("sleep"); err != nil {
		t.Skip("sleep unavailable")
	}
	m.commandFactory = func() *exec.Cmd { return exec.Command("sleep", "60") }
	if err := m.Start(); err != nil {
		t.Fatal(err)
	}
	m.Stop()
	if m.Snapshot().Running {
		t.Fatal("Stop without supervisor left child alive")
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
