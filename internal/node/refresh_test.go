package node

import (
	"context"
	"net"
	"testing"
	"time"
)

// startListener opens a loopback listener whose Port() can be used as
// a service probe target; closing it simulates the service stopping.
func startListener(t *testing.T) (net.Listener, int) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	return ln, ln.Addr().(*net.TCPAddr).Port
}

func serviceStatus(t *testing.T, reg *Registry, name string) ServiceStatus {
	t.Helper()
	for _, s := range reg.Local().Services {
		if s.Name == name {
			return s.Status
		}
	}
	t.Fatalf("service %q missing", name)
	return ""
}

func TestRefreshTransitionsRunningStoppedRunning(t *testing.T) {
	reg, _ := newTestRegistry(t)
	ln, port := startListener(t)
	specs := []LocalServiceSpec{{Capability: "clipboard", Name: "clipboard", Port: port}}
	RegisterLocalServices(reg, specs, "127.0.0.1", 300*time.Millisecond)
	if got := serviceStatus(t, reg, "clipboard"); got != ServiceRunning {
		t.Fatalf("initial: want running, got %s", got)
	}

	r := NewServiceRefresher(reg, specs, "127.0.0.1", 300*time.Millisecond, time.Minute)

	// Service stops → refresh → stopped; capability retained.
	ln.Close()
	r.Refresh()
	if got := serviceStatus(t, reg, "clipboard"); got != ServiceStopped {
		t.Fatalf("after stop: want stopped, got %s", got)
	}
	local := reg.Local()
	if !local.HasCapability("clipboard") {
		t.Fatal("capability lost when service stopped")
	}

	// Service starts again → refresh → running.
	ln2, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", itoa(port)))
	if err != nil {
		t.Fatalf("rebind same port: %v", err)
	}
	defer ln2.Close()
	r.Refresh()
	if got := serviceStatus(t, reg, "clipboard"); got != ServiceRunning {
		t.Fatalf("after restart: want running, got %s", got)
	}
}

func TestRefreshServicesAreIndependent(t *testing.T) {
	reg, _ := newTestRegistry(t)
	ln, portA := startListener(t) // will stay open, then close
	ln2, portB := startListener(t)
	ln2.Close() // portB reserved but currently closed
	specs := []LocalServiceSpec{
		{Capability: "clipboard", Name: "clipboard", Port: portA},
		{Capability: "file-transfer", Name: "file-transfer", Port: portB},
	}
	RegisterLocalServices(reg, specs, "127.0.0.1", 300*time.Millisecond)
	r := NewServiceRefresher(reg, specs, "127.0.0.1", 300*time.Millisecond, time.Minute)

	if got := serviceStatus(t, reg, "clipboard"); got != ServiceRunning {
		t.Fatalf("clipboard: want running, got %s", got)
	}
	if got := serviceStatus(t, reg, "file-transfer"); got != ServiceStopped {
		t.Fatalf("file-transfer: want stopped, got %s", got)
	}

	// Flip the world: clipboard's port closes, file-transfer comes up
	// on its own (previously closed) port.
	ln.Close()
	ln3, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", itoa(portB)))
	if err != nil {
		t.Fatalf("rebind: %v", err)
	}
	defer ln3.Close()
	r.Refresh()
	if got := serviceStatus(t, reg, "clipboard"); got != ServiceStopped {
		t.Errorf("clipboard: want stopped, got %s", got)
	}
	if got := serviceStatus(t, reg, "file-transfer"); got != ServiceRunning {
		t.Errorf("file-transfer: want running, got %s", got)
	}
}

func TestRefreshNeverCreatesServicesOrCapabilities(t *testing.T) {
	// Bare registry: no declared capabilities or services.
	reg, err := NewRegistry("machine:test", []NetworkProvider{&fakeProvider{}}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	specs := []LocalServiceSpec{
		{Name: "ghost-a", Port: 0},     // never probed
		{Name: "ghost-b", Port: 65534}, // closed
	}
	r := NewServiceRefresher(reg, specs, "127.0.0.1", 300*time.Millisecond, time.Minute)
	r.Refresh()
	// Refresh works purely through SetLocalServiceStatus, which is
	// update-only: since nothing was registered, nothing appears.
	local := reg.Local()
	if len(local.Services) != 0 || len(local.Capabilities) != 0 {
		t.Fatalf("refresh fabricated entries: %+v", local)
	}
}

func TestRefreshZeroServiceNodeIsNoOp(t *testing.T) {
	reg, err := NewRegistry("machine:test", []NetworkProvider{&fakeProvider{}}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	r := NewServiceRefresher(reg, nil, "127.0.0.1", 300*time.Millisecond, time.Minute)
	r.Refresh() // must not panic, error, or change anything
	local := reg.Local()
	if len(local.Services) != 0 || len(local.Capabilities) != 0 {
		t.Fatalf("zero-service node changed: %+v", local)
	}
}

func TestRefresherRunRespectsContextCancellation(t *testing.T) {
	reg, _ := newTestRegistry(t)
	specs := []LocalServiceSpec{{Name: "clipboard", Port: 0}}
	r := NewServiceRefresher(reg, specs, "127.0.0.1", 300*time.Millisecond, time.Hour)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { r.Run(ctx); close(done) }()
	cancel()
	select {
	case <-done:
		// loop exited promptly: no goroutine leak on shutdown
	case <-time.After(2 * time.Second):
		t.Fatal("Run did not exit after context cancellation")
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [8]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}
