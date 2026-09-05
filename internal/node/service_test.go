package node

import (
	"net"
	"testing"
	"time"
)

func TestCapabilityAndServiceRegistration(t *testing.T) {
	reg, err := NewRegistry("machine:test", []NetworkProvider{&fakeProvider{}}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}

	// Fresh registry: zero capabilities, zero services.
	local := reg.Local()
	if len(local.Capabilities) != 0 || len(local.Services) != 0 {
		t.Fatalf("fresh registry must not invent capabilities/services: %+v", local)
	}

	// Explicit registration: capability + service, port probed.
	reg.RegisterCapability("docker")
	reg.RegisterCapability("docker") // idempotent
	reg.RegisterService(Service{Name: "docker", Version: "24.0"})
	local = reg.Local()
	if len(local.Capabilities) != 1 || local.Capabilities[0] != "docker" {
		t.Errorf("capability registration broken: %+v", local.Capabilities)
	}
	if len(local.Services) != 1 || local.Services[0].Status != ServiceUnknown {
		t.Errorf("service registration broken: %+v", local.Services)
	}

	// Multiple capabilities/services survive together.
	reg.RegisterCapability("storage")
	reg.RegisterService(Service{Name: "storage"})
	local = reg.Local()
	if len(local.Capabilities) != 2 || len(local.Services) != 2 {
		t.Errorf("multi registration broken: %+v", local)
	}

	// Re-registering a service replaces it, not duplicates.
	reg.RegisterService(Service{Name: "docker", Version: "25.0"})
	if local = reg.Local(); len(local.Services) != 2 || local.Services[0].Version != "25.0" {
		t.Errorf("service re-registration broken: %+v", local.Services)
	}
}

func TestPortProbeOnlySetsStatus(t *testing.T) {
	reg, _ := newTestRegistry(t)

	// A listening port.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	openPort := ln.Addr().(*net.TCPAddr).Port

	RegisterLocalServices(reg, []LocalServiceSpec{
		{Capability: "clipboard", Name: "clipboard", Port: openPort},
		{Capability: "file-transfer", Name: "file-transfer", Port: 1}, // closed
	}, "127.0.0.1", 300*time.Millisecond)

	local := reg.Local()

	// Capabilities exist regardless of port state, exactly once each.
	if len(local.Capabilities) != 2 {
		t.Fatalf("capabilities must not depend on ports: %+v", local.Capabilities)
	}
	for _, s := range local.Services {
		switch s.Name {
		case "clipboard":
			if s.Status != ServiceRunning {
				t.Errorf("open port should be running: %+v", s)
			}
		case "file-transfer":
			if s.Status != ServiceStopped {
				t.Errorf("closed port should be stopped, capability kept: %+v", s)
			}
		}
	}

	// Status update on a registered service only; unknown names are ignored.
	reg.SetLocalServiceStatus("file-transfer", ServiceRunning, "127.0.0.1:8876")
	reg.SetLocalServiceStatus("ghost", ServiceRunning, "") // must not create
	local = reg.Local()
	if len(local.Services) != 2 {
		t.Fatalf("status update must not create services: %+v", local.Services)
	}
	for _, s := range local.Services {
		if s.Name == "file-transfer" && s.Status != ServiceRunning {
			t.Errorf("status change not applied: %+v", s)
		}
	}
}

func TestZeroServiceNode(t *testing.T) {
	reg, err := NewRegistry("machine:test", []NetworkProvider{&fakeProvider{}}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	// Never register anything: a valid node with nothing to offer.
	local := reg.Local()
	if len(local.Capabilities) != 0 || len(local.Services) != 0 || !local.Registered {
		t.Errorf("zero-service node invalid: %+v", local)
	}
}
