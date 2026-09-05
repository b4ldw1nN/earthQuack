package node

import (
	"os"
	"path/filepath"
	"testing"
)

func writeTemp(t *testing.T, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "earthquack-node.json")
	if content != "" {
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return path
}

func TestLoadConfigMissingFileIsNotError(t *testing.T) {
	cfg, err := LoadConfig(writeTemp(t, ""))
	if err != nil || cfg != nil {
		t.Fatalf("missing config must yield (nil, nil), got (%v, %v)", cfg, err)
	}
}

func TestLoadConfigValid(t *testing.T) {
	cfg, err := LoadConfig(writeTemp(t, `{
		"capabilities": ["media"],
		"services": [
			{"name": "clipboard", "port": 8875},
			{"capability": "file-transfer", "name": "file-transfer", "port": 8876, "version": "1.0"}
		]
	}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Capabilities) != 1 || cfg.Capabilities[0] != "media" {
		t.Errorf("capabilities: %+v", cfg.Capabilities)
	}
	if len(cfg.Services) != 2 {
		t.Fatalf("services: %+v", cfg.Services)
	}
	if cfg.Services[0].Capability != "clipboard" { // defaults to Name
		t.Errorf("capability default: %+v", cfg.Services[0])
	}
	if cfg.Services[1].Port != 8876 || cfg.Services[1].Version != "1.0" {
		t.Errorf("service fields: %+v", cfg.Services[1])
	}
}

func TestLoadConfigMalformedFailsCleanly(t *testing.T) {
	cases := []struct {
		name string
		body string
	}{
		{"bad json", "{not json"},
		{"unknown field (typo protection)", `{"hostnames": ["x"]}`},
		{"service without name", `{"services": [{"port": 1234}]}`},
		{"negative port", `{"services": [{"name": "x", "port": -1}]}`},
		{"port out of range", `{"services": [{"name": "x", "port": 70000}]}`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := LoadConfig(writeTemp(t, tc.body)); err == nil {
				t.Fatal("malformed config accepted")
			}
		})
	}
}

func TestConfigCannotFabricateRuntimeFields(t *testing.T) {
	// The Config type has no identity/hostname/os/online/network fields,
	// so an attacker-controlled config file cannot inject runtime state.
	cfg, err := LoadConfig(writeTemp(t, `{
		"identity": "machine:fake", "hostname": "evil", "os": "linux",
		"online": true, "network": {"transport": "tailscale", "addresses": ["1.2.3.4"]},
		"services": []
	}`))
	if err == nil {
		t.Fatalf("runtime fields in config accepted: %+v", cfg)
	}
}

func TestConfiguredServiceRetainedWhenProbeFails(t *testing.T) {
	reg, err := NewRegistry("machine:test", nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	// Probe a port with nothing listening: service must remain, marked
	// stopped, and the capability must survive.
	specs := []LocalServiceSpec{{Capability: "clipboard", Name: "clipboard", Port: 1}}
	RegisterLocalServices(reg, specs, "127.0.0.1", 50_000_000) // 50ms

	local := reg.Local()
	if !local.HasCapability("clipboard") {
		t.Error("capability lost when probe failed")
	}
	var svc *Service
	for i := range local.Services {
		if local.Services[i].Name == "clipboard" {
			svc = &local.Services[i]
		}
	}
	if svc == nil {
		t.Fatal("configured service dropped after failed probe")
	}
	if svc.Status != ServiceStopped {
		t.Errorf("status: want stopped, got %s", svc.Status)
	}
}

func TestConfigServicePortZeroIsNotProbed(t *testing.T) {
	reg, err := NewRegistry("machine:test", nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	RegisterLocalServices(reg, []LocalServiceSpec{{Name: "virtual", Port: 0}}, "127.0.0.1", 50_000_000)
	svc := reg.Local().Services[0]
	if svc.Status != ServiceUnknown {
		t.Errorf("unprobed service status: want unknown, got %s", svc.Status)
	}
}
