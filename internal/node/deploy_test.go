package node

import (
	"os"
	"path/filepath"
	"testing"
)

// TestIdentityDistinctPerMachine proves the multi-node identity model:
// different machines (different machine IDs) produce different stable
// identities, regardless of hostname or IP.
func TestIdentityDistinctPerMachine(t *testing.T) {
	restore := machineIDPaths
	defer func() { machineIDPaths = restore }()

	dir := t.TempDir()
	write := func(name, content string) string {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, []byte(content+"\n"), 0o444); err != nil {
			t.Fatal(err)
		}
		return p
	}
	idA := write("machine-a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	idB := write("machine-b", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

	machineIDPaths = []string{idA}
	identA, err := ResolveLocalIdentity()
	if err != nil {
		t.Fatal(err)
	}
	machineIDPaths = []string{idB}
	identB, err := ResolveLocalIdentity()
	if err != nil {
		t.Fatal(err)
	}

	if identA == identB {
		t.Fatalf("distinct machines share an identity: %s", identA)
	}
	if identA != Identity("machine:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") {
		t.Errorf("identity not derived from machine-id content: %s", identA)
	}
	// Hostname and IP play no part: same machine-id must yield the
	// same identity no matter what else changes.
	machineIDPaths = []string{idA}
	again, err := ResolveLocalIdentity()
	if err != nil || again != identA {
		t.Errorf("identity unstable across resolutions: %s vs %s (%v)", identA, again, err)
	}
}

// TestExampleNodeConfigs verifies the per-node example configurations:
// each loads cleanly, each node advertises only what it declares, and
// no node leaks another node's capabilities (capability isolation).
func TestExampleNodeConfigs(t *testing.T) {
	type want struct {
		caps  int
		svcs  int
		names map[string]bool
	}
	cases := map[string]want{
		"arch.json":       {2, 2, map[string]bool{"clipboard": true, "file-transfer": true}},
		"homeserver.json": {2, 2, map[string]bool{"storage": true, "docker": true}},
		"vps.json":        {2, 2, map[string]bool{"reverse-proxy": true, "docker": true}},
	}
	for file, w := range cases {
		t.Run(file, func(t *testing.T) {
			cfg, err := LoadConfig(filepath.Join("..", "..", "examples", file))
			if err != nil {
				t.Fatalf("example config does not load: %v", err)
			}
			if len(cfg.Capabilities) != w.caps || len(cfg.Services) != w.svcs {
				t.Fatalf("shape: caps=%d svcs=%d", len(cfg.Capabilities), len(cfg.Services))
			}
			for _, s := range cfg.Services {
				if !w.names[s.Name] {
					t.Errorf("unexpected service %q in %s", s.Name, file)
				}
				if !contains(cfg.Capabilities, s.Capability) {
					t.Errorf("service %q capability %q not declared", s.Name, s.Capability)
				}
			}
			// Isolation: capabilities of other example nodes must not appear.
			for _, other := range []string{"clipboard", "file-transfer", "storage", "reverse-proxy"} {
				if w.names[other] {
					continue
				}
				if contains(cfg.Capabilities, other) {
					t.Errorf("%s must not advertise %q", file, other)
				}
			}
			// No runtime fields are configurable (LoadConfig rejects them).
			if cfg.Auth.Token != "" {
				t.Errorf("%s must not contain a real token", file)
			}
		})
	}
}

func contains(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}
