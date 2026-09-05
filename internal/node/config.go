package node

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
)

// Config holds node-specific declarations loaded from an optional
// JSON configuration file (e.g. earthquakes-node.json):
//
//	{
//	  "capabilities": ["storage"],
//	  "services": [
//	    {"capability": "storage", "name": "storage", "port": 9000}
//	  ]
//	  }
//
// The boundary is strict: configuration contains only explicit
// DECLARATIONS (what the node claims to provide/implement). Runtime
// state — identity, hostname, OS, online/offline, network addresses,
// service status, peer registration — is never configurable and is
// never accepted from this file. Each node uses the same binary and
// differs only in this file (or in having none at all).
type Config struct {
	Auth         AuthConfig         `json:"auth,omitempty"`
	Capabilities []string           `json:"capabilities"`
	Services     []LocalServiceSpec `json:"services"`
}

// AuthConfig is the application-authentication declaration for this
// node. Token is the shared bearer token trusted across the earthQuack
// ecosystem. It must never appear in API responses, logs, or peer data.
// Precedence: EARTHQUACK_AUTH_TOKEN env var overrides this file token,
// so secrets never have to be committed to disk in the repo.
type AuthConfig struct {
	Token string `json:"token,omitempty"`
}

// LoadConfig reads a node configuration file. A missing file is not
// an error: it returns (nil, nil) and the caller uses built-in
// defaults. A malformed file, unknown fields (typo protection), a
// service without a name, or a service with a negative port fails
// cleanly with an error. Runtime network fields in the file are
// rejected — configuration cannot fabricate them.
func LoadConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("node config: %s: %w", path, err)
	}

	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	var cfg Config
	if err := dec.Decode(&cfg); err != nil {
		return nil, fmt.Errorf("node config: %s: %w", path, err)
	}

	for i, s := range cfg.Services {
		if s.Name == "" {
			return nil, fmt.Errorf("node config: %s: service without name", path)
		}
		if s.Port < 0 || s.Port > 65535 {
			return nil, fmt.Errorf("node config: %s: service %q has invalid port %d", path, s.Name, s.Port)
		}
		if s.Capability == "" {
			s.Capability = s.Name
		}
		cfg.Services[i] = s
	}
	return &cfg, nil
}
