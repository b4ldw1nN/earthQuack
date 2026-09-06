package node

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// fakeStorageProvider is a deterministic StorageProvider for tests.
type fakeStorageProvider struct{ info StorageInfo }

func (f fakeStorageProvider) Collect() StorageInfo { return f.info }

// testStorage returns a deterministic two-filesystem snapshot.
func testStorage() StorageInfo {
	return StorageInfo{Filesystems: []Filesystem{
		{Mount: "/", Filesystem: "ext4", Total: 250_000_000_000, Used: 82_500_000_000, Available: 167_500_000_000, UsagePercent: 33},
		{Mount: "/mnt/Storage", Filesystem: "ext4", Total: 1_000_000_000_000, Used: 732_000_000_000, Available: 268_000_000_000, UsagePercent: 73.2},
	}}
}

// --- pure parsing / math ---

func TestParseMounts(t *testing.T) {
	data := "/dev/nvme1n1p5 / ext4 rw,noatime 0 0\n" +
		"tmpfs /run tmpfs rw,nosuid,size=3152920k 0 0\n" +
		"badline\n" +
		"only two\n" +
		"/dev/sda1 /mnt/weird\\040name btrfs rw 0 0\n" +
		"/dev/sdb1 /mnt/back\\134slash xfs rw 0 0\n"
	got := parseMounts(data)
	if len(got) != 4 {
		t.Fatalf("want 4 valid entries, got %d: %+v", len(got), got)
	}
	if got[0].Device != "/dev/nvme1n1p5" || got[0].MountPoint != "/" || got[0].Type != "ext4" {
		t.Errorf("entry 0: %+v", got[0])
	}
	if got[2].MountPoint != "/mnt/weird name" {
		t.Errorf("\\040 not decoded: %+v", got[2])
	}
	if got[3].MountPoint != `/mnt/back\slash` {
		t.Errorf("\\134 not decoded: %+v", got[3])
	}
	if got := parseMounts(""); len(got) != 0 {
		t.Errorf("empty input: %+v", got)
	}
}

func TestUnescapeMountField(t *testing.T) {
	cases := map[string]string{
		"/mnt/plain":       "/mnt/plain",
		"/mnt/my\\040disk": "/mnt/my disk",
		"/mnt/tab\\011x":   "/mnt/tab\tx",
		"/mnt/nl\\012x":    "/mnt/nl\nx",
		"/mnt/b\\134s":     `/mnt/b\s`,
		"/mnt/trailing\\":  "/mnt/trailing\\", // dangling escape kept verbatim
		"/mnt/bad\\999x":   "/mnt/bad\\999x",  // non-octal escape kept verbatim
		"":                 "",
	}
	for in, want := range cases {
		if got := unescapeMountField(in); got != want {
			t.Errorf("unescape(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestIsPseudoFilesystem(t *testing.T) {
	for _, pseudo := range []string{"proc", "sysfs", "devtmpfs", "tmpfs", "cgroup", "cgroup2", "devpts", "efivarfs", "autofs", "overlay", "squashfs"} {
		if !isPseudoFilesystem(pseudo) {
			t.Errorf("%q should be filtered", pseudo)
		}
	}
	// Real storage types — including unusual ones — must never be
	// filtered, so a future homeserver's filesystems always show up.
	for _, real := range []string{"ext4", "xfs", "btrfs", "zfs", "vfat", "nfs4", "f2fs", "exfat", "ntfs3", "somethingexotic"} {
		if isPseudoFilesystem(real) {
			t.Errorf("%q must not be filtered", real)
		}
	}
}

func TestFileUsagePercent(t *testing.T) {
	if got := fileUsagePercent(250_000_000_000, 167_500_000_000); got != 33.0 {
		t.Errorf("percent = %v, want 33", got)
	}
	if got := fileUsagePercent(100, 0); got != 100 {
		t.Errorf("full = %v, want 100", got)
	}
	if got := fileUsagePercent(0, 0); got != 0 {
		t.Errorf("zero total = %v, want 0", got)
	}
	if got := fileUsagePercent(100, 100); got != 0 {
		t.Errorf("empty = %v, want 0", got)
	}
	if got := fileUsagePercent(100, 150); got != 0 { // statfs overrun: clamp
		t.Errorf("overrun = %v, want 0", got)
	}
}

func TestUsageHuman(t *testing.T) {
	// bytesPerGB (GiB) matches the existing SystemInfo.MemoryHuman
	// convention, so use byte counts that are exact in that unit.
	f := Filesystem{Total: 1000 * bytesPerGB, Used: 742 * bytesPerGB}
	if got := f.UsageHuman(); got != "742.0 / 1000.0 GB" {
		t.Errorf("UsageHuman = %q", got)
	}
	if got := (Filesystem{}).UsageHuman(); got != "" {
		t.Errorf("zero total UsageHuman = %q, want empty", got)
	}
}

func TestUsagePercentHuman(t *testing.T) {
	f := Filesystem{Total: 100, Used: 33, Available: 67}
	if got := f.UsagePercentHuman(); got != "33%" {
		t.Errorf("got %q", got)
	}
	if got := (Filesystem{}).UsagePercentHuman(); got != "" {
		t.Errorf("empty fs percent = %q, want empty", got)
	}
}

func TestUsageBar(t *testing.T) {
	cases := []struct {
		total, avail uint64
		want         string
	}{
		{100, 100, strings.Repeat("░", 20)},                         // 0%
		{100, 67, strings.Repeat("█", 6) + strings.Repeat("░", 14)}, // 33%
		{100, 0, strings.Repeat("█", 20)},                           // 100%
		{0, 0, ""},                                                  // no data
	}
	for i, tc := range cases {
		f := Filesystem{Total: tc.total, Available: tc.avail}
		if got := f.UsageBar(); got != tc.want {
			t.Errorf("case %d: bar = %q, want %q", i, got, tc.want)
		}
	}
}

func TestStorageHasInfo(t *testing.T) {
	if (StorageInfo{}).HasInfo() {
		t.Error("empty snapshot claims info")
	}
	if !(StorageInfo{Filesystems: []Filesystem{{Mount: "/"}}}).HasInfo() {
		t.Error("filesystem snapshot denies info")
	}
}

// --- collection (injected reader + statfs; no real disks) ---

// fakeStatfs maps mount points to fixed statfsData; unmapped points fail.
func fakeStatfs(m map[string]statfsData) func(string) (statfsData, error) {
	return func(p string) (statfsData, error) {
		if d, ok := m[p]; ok {
			return d, nil
		}
		return statfsData{}, errors.New("statfs: no such file or directory")
	}
}

const testMountsTable = `/dev/nvme1n1p5 / ext4 rw,noatime 0 0
devtmpfs /dev devtmpfs rw,nosuid,size=7746180k 0 0
tmpfs /run tmpfs rw,nosuid,size=3152920k 0 0
proc /proc proc rw,nosuid 0 0
sysfs /sys sysfs rw 0 0
cgroup2 /sys/fs/cgroup cgroup2 rw 0 0
/dev/nvme0n1p1 /mnt/Storage ext4 rw,relatime,stripe=32 0 0
/dev/nvme1n1p1 /boot/efi vfat rw,relatime 0 0
//freenas/media /mnt/media nfs4 rw 0 0
/dev/sda1 /mnt/weird\040name btrfs rw 0 0
/dev/does-not-exist /mnt/ghost ext4 rw 0 0
/dev/zeroed /mnt/zero ext4 rw 0 0
overlay /mnt/Storage overlay rw 0 0
tmpfs /run/user/1000 tmpfs rw,nosuid 0 0
`

func testStatfsTable() map[string]statfsData {
	return map[string]statfsData{
		"/":               {Total: 250_000_000_000, Free: 180_000_000_000, Available: 167_500_000_000},
		"/boot/efi":       {Total: 536_870_912, Free: 268_435_456, Available: 268_435_456},
		"/mnt/Storage":    {Total: 1_000_000_000_000, Free: 300_000_000_000, Available: 268_000_000_000},
		"/mnt/media":      {Total: 4_000_000_000_000, Free: 1_000_000_000_000, Available: 1_000_000_000_000},
		"/mnt/weird name": {Total: 2_000_000_000_000, Free: 1_500_000_000_000, Available: 1_500_000_000_000},
		"/mnt/zero":       {Total: 0, Free: 0, Available: 0},
	}
}

func TestProcStorageCollect(t *testing.T) {
	p := &procStorageInfo{
		read:  func(string) ([]byte, error) { return []byte(testMountsTable), nil },
		statf: fakeStatfs(testStatfsTable()),
	}
	got := p.Collect()
	wantMounts := []string{"/", "/boot/efi", "/mnt/Storage", "/mnt/media", "/mnt/weird name"}
	if len(got.Filesystems) != len(wantMounts) {
		t.Fatalf("want %d filesystems, got %d: %+v", len(wantMounts), len(got.Filesystems), got.Filesystems)
	}
	for i, want := range wantMounts {
		if got.Filesystems[i].Mount != want {
			t.Errorf("fs[%d].Mount = %q, want %q (must be sorted by mount point)", i, got.Filesystems[i].Mount, want)
		}
	}
	root := got.Filesystems[0]
	if root.Filesystem != "ext4" || root.Total != 250_000_000_000 {
		t.Errorf("root entry wrong: %+v", root)
	}
	if root.Used != 250_000_000_000-180_000_000_000 {
		t.Errorf("root used = %d, want total-free", root.Used)
	}
	if root.Available != 167_500_000_000 {
		t.Errorf("root available = %d", root.Available)
	}
	if root.UsagePercent != 33.0 {
		t.Errorf("root percent = %v, want 33", root.UsagePercent)
	}
	if got.Filesystems[3].Filesystem != "nfs4" {
		t.Errorf("nfs type lost: %+v", got.Filesystems[3])
	}
	if got.Filesystems[4].Mount != "/mnt/weird name" {
		t.Errorf("escaped mount not decoded: %+v", got.Filesystems[4])
	}
	// Deterministic: a second collect is identical.
	if got2 := p.Collect(); fmt.Sprint(got2) != fmt.Sprint(got) {
		t.Errorf("collect is not deterministic:\n%v\n%v", got, got2)
	}
}

func TestProcStorageCollectFiltersAndSkips(t *testing.T) {
	p := &procStorageInfo{
		read:  func(string) ([]byte, error) { return []byte(testMountsTable), nil },
		statf: fakeStatfs(testStatfsTable()),
	}
	got := p.Collect()
	for _, fs := range got.Filesystems {
		if isPseudoFilesystem(fs.Filesystem) {
			t.Errorf("pseudo filesystem leaked: %+v", fs)
		}
		if fs.Mount == "/mnt/ghost" {
			t.Errorf("statfs failure fabricated an entry: %+v", fs)
		}
		if fs.Mount == "/mnt/zero" {
			t.Errorf("zero-capacity entry leaked: %+v", fs)
		}
	}
	// The later overlay entry must not shadow the real ext4 /mnt/Storage.
	for _, fs := range got.Filesystems {
		if fs.Mount == "/mnt/Storage" && fs.Filesystem != "ext4" {
			t.Errorf("overmount shadowing a real fs: %+v", fs)
		}
	}
}

func TestProcStorageCollectUnreadableMountTable(t *testing.T) {
	p := &procStorageInfo{
		read:  func(string) ([]byte, error) { return nil, errors.New("ENOENT") },
		statf: fakeStatfs(testStatfsTable()),
	}
	got := p.Collect()
	if got.HasInfo() {
		t.Errorf("unreadable mount table must yield empty snapshot: %+v", got)
	}
}

// --- Node / registry / API integration ---

func TestLocalNodeStorage(t *testing.T) {
	reg, _ := newTestRegistry(t)
	reg.SetStorageInfoProvider(fakeStorageProvider{info: testStorage()})
	n := reg.Local()
	if n.Storage == nil || !n.Storage.HasInfo() {
		t.Fatal("local node missing storage snapshot")
	}
	if len(n.Storage.Filesystems) != 2 || n.Storage.Filesystems[0].Mount != "/" {
		t.Fatalf("storage not attached to local node: %+v", n.Storage)
	}
}

func TestAPINodeHasStorage(t *testing.T) {
	reg, _ := newTestRegistry(t)
	reg.SetStorageInfoProvider(fakeStorageProvider{info: testStorage()})
	h, err := NewAPI(reg, "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/node")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var n Node
	if err := json.NewDecoder(resp.Body).Decode(&n); err != nil {
		t.Fatal(err)
	}
	if n.Storage == nil || len(n.Storage.Filesystems) != 2 {
		t.Fatalf("storage missing from /api/node: %+v", n.Storage)
	}
	fs := n.Storage.Filesystems[1]
	if fs.Mount != "/mnt/Storage" || fs.Total != 1_000_000_000_000 || fs.Available != 268_000_000_000 {
		t.Errorf("storage values wrong through API: %+v", fs)
	}
	if fs.UsagePercent < 73.19 || fs.UsagePercent > 73.21 {
		t.Errorf("usage percent through API = %v, want ~73.2", fs.UsagePercent)
	}
}

func TestDiscoveredPeerHasNoStorage(t *testing.T) {
	reg, _ := newTestRegistry(t)
	reg.SetStorageInfoProvider(fakeStorageProvider{info: testStorage()})
	for _, n := range reg.Nodes() {
		if !n.Registered && n.Storage != nil {
			t.Errorf("discovered/unregistered peer fabricated storage info: %+v", n)
		}
	}
	// And /api/nodes must likewise carry storage only for the local node.
	h, err := NewAPI(reg, "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/api/nodes")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var payload struct {
		Nodes []Node
	}
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		t.Fatal(err)
	}
	for _, n := range payload.Nodes {
		if !n.Registered && n.Storage != nil {
			t.Errorf("unregistered node served storage: %+v", n)
		}
		if n.Registered && n.Storage == nil {
			t.Errorf("registered local node missing storage: %+v", n)
		}
	}
}

func TestPeerProbePreservesStorage(t *testing.T) {
	peerJSON := validNodeJSONWithStorage("machine:peer1", testStorage())
	srv, _ := fakeNodeServer(t, peerJSON, "application/json", http.StatusOK)
	reg, _ := newProbingRegistry(t, "127.0.0.1", portOf(t, srv.URL))
	n := findNode(reg.Nodes(), "machine:peer1")
	if n.Identity != "machine:peer1" {
		t.Fatalf("peer not registered: %+v", reg.Nodes())
	}
	if n.Storage == nil || len(n.Storage.Filesystems) != 2 {
		t.Fatalf("peer storage not preserved through probe: %+v", n.Storage)
	}
	// Values come from the peer's own response — including its custom
	// mount layout, proving nothing is inferred locally.
	if n.Storage.Filesystems[0].Mount != "/" || n.Storage.Filesystems[1].Mount != "/mnt/Storage" {
		t.Errorf("peer storage data wrong: %+v", n.Storage)
	}
}

// validNodeJSONWithStorage builds a valid /api/node response carrying a
// storage snapshot, as the opposite side of a peer probe would.
func validNodeJSONWithStorage(identity string, stor StorageInfo) string {
	b, _ := json.Marshal(Node{
		Identity:     Identity(identity),
		Hostname:     "homeserver",
		OS:           "linux",
		Online:       true,
		Registered:   true,
		Capabilities: []string{"clipboard"},
		Storage:      &stor,
	})
	return string(b)
}

func TestStorageJSONShape(t *testing.T) {
	// Additive and omitted-when-absent: a node without storage must not
	// gain a "storage" key at all.
	plain, err := json.Marshal(Node{Identity: "machine:x", Hostname: "h"})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(plain), "storage") {
		t.Errorf("nil storage must be omitted: %s", plain)
	}
	withFS, err := json.Marshal(testStorage())
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(withFS, &m); err != nil {
		t.Fatal(err)
	}
	fss, ok := m["filesystems"].([]any)
	if !ok || len(fss) != 2 {
		t.Fatalf("filesystems shape wrong: %s", withFS)
	}
	fs0, ok := fss[0].(map[string]any)
	if !ok {
		t.Fatalf("filesystem shape wrong: %s", withFS)
	}
	for _, key := range []string{"mount", "filesystem", "total", "used", "available", "usage_percent"} {
		if _, ok := fs0[key]; !ok {
			t.Errorf("filesystem JSON missing %q: %s", key, withFS)
		}
	}
}

func TestConfigCannotFabricateStorage(t *testing.T) {
	// Config declares capabilities/services only; a "storage" key in
	// the node JSON config must be rejected, never turned into telemetry.
	path := writeTemp(t, `{"storage": [{"mount": "/fabricated", "total": 999}]}`)
	if _, err := LoadConfig(path); err == nil {
		t.Fatal("config with storage field accepted — runtime state must not be configurable")
	}
}
