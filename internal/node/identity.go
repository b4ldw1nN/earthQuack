package node

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"strings"
)

// Identity for the local node is derived from the machine ID
// (/etc/machine-id on Linux), which is stable across reboots and
// independent of IP address or hostname. If no machine ID exists,
// a random ID is generated once and persisted under the user
// configuration directory.

const (
	machineIDPath    = "/etc/machine-id"
	altMachineIDPath = "/var/lib/dbus/machine-id"
	identityFileName = "node-id"
	identityDirName  = "earthquack"
)

// ErrNoIdentity is returned when no stable identity can be established.
var ErrNoIdentity = errors.New("node: unable to determine machine identity")

// ResolveLocalIdentity returns the stable identity for this machine.
// It is called once at startup; the result should be reused.
func ResolveLocalIdentity() (Identity, error) {
	for _, path := range []string{machineIDPath, altMachineIDPath} {
		data, err := os.ReadFile(path)
		if err == nil {
			if id := strings.TrimSpace(string(data)); id != "" {
				return Identity("machine:" + id), nil
			}
		}
	}
	// Fallback: persistent, randomly generated node ID.
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", ErrNoIdentity
	}
	path := filepath.Join(dir, identityDirName, identityFileName)
	if data, err := os.ReadFile(path); err == nil {
		if id := strings.TrimSpace(string(data)); id != "" {
			return Identity("node:" + id), nil
		}
	}
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		return "", ErrNoIdentity
	}
	id := hex.EncodeToString(buf)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return "", ErrNoIdentity
	}
	if err := os.WriteFile(path, []byte(id+"\n"), 0o600); err != nil {
		return "", ErrNoIdentity
	}
	return Identity("node:" + id), nil
}
