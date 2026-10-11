package rclone

// Verifies the new RC method end to end through librclone itself, against a
// real backend (local), rather than by calling the handler directly. This is
// the only test in the repo that covers the native surface; the Kotlin side
// covers the same method against a fake bridge.
import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/rclone/rclone/fs/config"
	"github.com/rclone/rclone/librclone/librclone"
)

// The same payload pattern the Kotlin range server produces: a known prefix,
// a known suffix, and a size that is awkward on purpose.
func readTestContents(t *testing.T) []byte {
	t.Helper()
	// 64 KiB of distinguishable bytes, so a wrong offset is visible.
	buf := make([]byte, 64*1024)
	for i := range buf {
		buf[i] = byte(i % 251)
	}
	return buf
}

// Calls the method the way the app does: JSON in, JSON out, through
// librclone.RPC.
func callRead(t *testing.T, params string) (string, int) {
	t.Helper()
	out, status := librclone.RPC(readMethod, params)
	return out, status
}

func TestReadRangeAgainstLocalBackend(t *testing.T) {
	dir := t.TempDir()
	remote := filepath.Join(dir, "films", "clip.mp4")
	if err := os.MkdirAll(filepath.Dir(remote), 0o755); err != nil {
		t.Fatal(err)
	}
	contents := readTestContents(t)
	if err := os.WriteFile(remote, contents, 0o644); err != nil {
		t.Fatal(err)
	}

	// Point rclone at a config in the temp dir so a developer's real
	// rclone.conf is neither read nor written.
	if err := config.SetConfigPath(filepath.Join(dir, "rclone.conf")); err != nil {
		t.Fatal(err)
	}
	librclone.Initialize()

	type want struct {
		offset int
		length int
	}
	cases := []struct {
		name   string
		params string
		want   want
	}{
		{
			name:   "the first 4096 bytes",
			params: `{"srcFs":":local:` + dir + `","srcRemote":"films/clip.mp4","offset":0,"length":4096}`,
			want:   want{0, 4096},
		},
		{
			name:   "a middle window",
			params: `{"srcFs":":local:` + dir + `","srcRemote":"films/clip.mp4","offset":1024,"length":2048}`,
			want:   want{1024, 2048},
		},
		{
			// Past the end: zero bytes, eof, and no backend error.
			name:   "offset beyond the end",
			params: `{"srcFs":":local:` + dir + `","srcRemote":"films/clip.mp4","offset":65536,"length":64}`,
			want:   want{len(contents), 0},
		},
		{
			// Runs off the end: a short read is not an error.
			name:   "a range that runs past the end",
			params: `{"srcFs":":local:` + dir + `","srcRemote":"films/clip.mp4","offset":65530,"length":64}`,
			want:   want{65530, 6},
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			out, status := callRead(t, c.params)
			if status != 200 {
				t.Fatalf("status %d for %s: %s", status, c.name, out)
			}
			reply, err := parseReply(out)
			if err != nil {
				t.Fatal(err)
			}
			data := base64.StdEncoding.EncodeToString(contents[c.want.offset : c.want.offset+c.want.length])
			if reply["data"] != data {
				t.Fatalf("read the wrong bytes at offset %d", c.want.offset)
			}
			if length, _ := reply["length"].(float64); int(length) != c.want.length {
				t.Fatalf("length = %v, want %d", reply["length"], c.want.length)
			}
			if size, _ := reply["size"].(float64); int(size) != len(contents) {
				t.Fatalf("size = %v, want %d", reply["size"], len(contents))
			}
		})
	}
}

func TestReadRangeRejectsBadInput(t *testing.T) {
	if err := config.SetConfigPath(filepath.Join(t.TempDir(), "rclone.conf")); err != nil {
		t.Fatal(err)
	}
	librclone.Initialize()

	cases := []struct {
		name   string
		params string
	}{
		{"negative offset", `{"srcFs":":local:/tmp","srcRemote":"x","offset":-1,"length":16}`},
		{"zero length", `{"srcFs":":local:/tmp","srcRemote":"x","offset":0,"length":0}`},
		{"length over the cap", `{"srcFs":":local:/tmp","srcRemote":"x","offset":0,"length":9000000}`},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, status := callRead(t, c.params)
			if status == 200 {
				t.Fatalf("%s was accepted", c.name)
			}
		})
	}
}

func TestReadRangeReportsMissingObject(t *testing.T) {
	dir := t.TempDir()
	if err := config.SetConfigPath(filepath.Join(dir, "rclone.conf")); err != nil {
		t.Fatal(err)
	}
	librclone.Initialize()

	out, status := callRead(t, `{"srcFs":":local:`+dir+`","srcRemote":"not/here.mp4","offset":0,"length":16}`)
	if status == 200 {
		t.Fatalf("a missing object reported success: %s", out)
	}
}

// parseReply decodes an RC reply with encoding/json rather than a hand written
// scanner, so a malformed reply fails the test instead of being tolerated.
func parseReply(out string) (map[string]any, error) {
	var reply map[string]any
	dec := json.NewDecoder(strings.NewReader(out))
	if err := dec.Decode(&reply); err != nil {
		return nil, err
	}
	return reply, nil
}
