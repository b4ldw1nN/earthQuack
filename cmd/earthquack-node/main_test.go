package main

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/b4ldw1nN/earthquack/internal/node"
)

func TestResolveAESKey(t *testing.T) {
	fileKey := base64.StdEncoding.EncodeToString(make([]byte, 32))
	path := filepath.Join(t.TempDir(), "config.json")
	data, err := json.Marshal(map[string]string{"clipboard_aes_key": fileKey})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := node.LoadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, tt := range []struct {
		name string
		env  string
		cfg  *node.Config
		want string
	}{
		{"file key", "", cfg, fileKey},
		{"config wins over stale environment", "env-key", cfg, fileKey},
		{"environment fallback with empty config key", "env-key", &node.Config{}, "env-key"},
		{"environment without config", "env-key", nil, "env-key"},
		{"empty config", "", &node.Config{}, ""},
		{"no config", "", nil, ""},
	} {
		t.Run(tt.name, func(t *testing.T) {
			t.Setenv("CLIPBOARD_AES_KEY", tt.env)
			if got := resolveAESKey(tt.cfg); got != tt.want {
				t.Fatal("AES key resolution did not match expected source")
			}
		})
	}
}
