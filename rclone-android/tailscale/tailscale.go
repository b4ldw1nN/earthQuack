// Package tailscale is the EarthQuack-owned gomobile shim around tailscale.com/tsnet.
//
// It exposes an in-process Tailscale node (userspace WireGuard, no tun) to the
// Android app via gomobile bind, mirroring the rclone shim in ../rclone. All
// complex values cross the JNI boundary as JSON strings so gomobile's type
// restrictions do not limit the API surface.
//
// See docs/tailscale-embedded-roadmap.md for the full contract.
package tailscale

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"

	"tailscale.com/client/local"
	"tailscale.com/client/tailscale/apitype"
	"tailscale.com/drive"
	_ "tailscale.com/feature/androidbin"
	"tailscale.com/ipn"
	"tailscale.com/net/netmon"
	"tailscale.com/net/socks5"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
)

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------

var (
	stateMu   sync.Mutex
	srv       *tsnet.Server
	lc        *local.Client
	socksLn   net.Listener
	socksPort int

	serveMu  sync.Mutex
	serveLns = map[int]net.Listener{}
)

// ---------------------------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------------------------

func mustJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return "{}"
	}
	return string(b)
}

// ---------------------------------------------------------------------------
// Android interface fallback
// ---------------------------------------------------------------------------

func init() {
	// Android denies app UIDs netlink interface enumeration. Register a
	// synthetic interface getter so netmon (and tsnet) can start.
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		addrs, err := outboundAddrs()
		if err != nil {
			return nil, err
		}
		if len(addrs) == 0 {
			return nil, fmt.Errorf("no outbound routes found")
		}
		return []netmon.Interface{{
			Interface: &net.Interface{
				MTU:   1500,
				Name:  "android",
				Flags: net.FlagUp | net.FlagRunning,
			},
			AltAddrs: addrs,
		}}, nil
	})
}

func outboundAddrs() ([]net.Addr, error) {
	var addrs []net.Addr
	for _, target := range []struct {
		network string
		addr    string
	}{
		{"udp4", "8.8.8.8:53"},
		{"udp6", "[2001:4860:4860::8888]:53"},
	} {
		d := net.Dialer{Timeout: 2 * time.Second}
		c, err := d.Dial(target.network, target.addr)
		if err != nil {
			continue
		}
		ua, ok := c.LocalAddr().(*net.UDPAddr)
		c.Close()
		if !ok {
			continue
		}
		ip, err := netip.ParseAddr(ua.IP.String())
		if err != nil {
			continue
		}
		ip = ip.Unmap()
		if ip.IsLoopback() || ip.IsUnspecified() {
			continue
		}
		mask := 32
		if ip.Is6() {
			mask = 128
		}
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(mask, mask)})
	}
	if len(addrs) == 0 {
		return nil, fmt.Errorf("no outbound routes found")
	}
	return addrs, nil
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

// Start initialises the tsnet node.
// stateDir: app-private path (context.filesDir + "/tailscale")
// hostname: how this device appears in the tailnet admin console
// Returns a JSON object: {"state":..., "socks_port":N, "login_url":"...", "node_ip":"..."}
func Start(stateDir, hostname string) string {
	stateMu.Lock()
	defer stateMu.Unlock()

	if srv != nil {
		// Already started; return current status.
		return statusLocked()
	}

	if err := os.MkdirAll(stateDir, 0700); err != nil {
		return mustJSON(map[string]any{
			"state":      "Error",
			"error":      err.Error(),
			"socks_port": 0,
			"login_url":  "",
			"node_ip":    "",
		})
	}
	os.Setenv("HOME", stateDir)
	os.Setenv("XDG_CACHE_HOME", stateDir)
	os.Setenv("XDG_CONFIG_HOME", stateDir)

	s := &tsnet.Server{
		Dir:      stateDir,
		Hostname: hostname,
	}
	if err := s.Start(); err != nil {
		return mustJSON(map[string]any{
			"state":      "Error",
			"error":      err.Error(),
			"socks_port": 0,
			"login_url":  "",
			"node_ip":    "",
		})
	}

	client, err := s.LocalClient()
	if err != nil {
		s.Close()
		return mustJSON(map[string]any{
			"state":      "Error",
			"error":      err.Error(),
			"socks_port": 0,
			"login_url":  "",
			"node_ip":    "",
		})
	}

	srv = s
	lc = client

	// Start a no-auth SOCKS5 proxy on loopback that dials over the tailnet.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return mustJSON(map[string]any{
			"state":      "Error",
			"error":      err.Error(),
			"socks_port": 0,
			"login_url":  "",
			"node_ip":    "",
		})
	}
	socksLn = ln
	socksPort = ln.Addr().(*net.TCPAddr).Port

	s5 := &socks5.Server{
		Logf: func(format string, args ...any) {},
		Dialer: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return srv.Dial(ctx, network, addr)
		},
	}
	go func() {
		_ = s5.Serve(ln)
	}()

	return statusLocked()
}

// Stop shuts the node down cleanly.
func Stop() {
	stateMu.Lock()
	defer stateMu.Unlock()

	if socksLn != nil {
		socksLn.Close()
		socksLn = nil
	}
	socksPort = 0

	serveMu.Lock()
	for p, ln := range serveLns {
		ln.Close()
		delete(serveLns, p)
	}
	serveMu.Unlock()

	if srv != nil {
		srv.Close()
		srv = nil
	}
	lc = nil
}

// Status returns a JSON snapshot:
// {"state":"Running|NeedsLogin|Stopped", "node_ip":"100.x", "login_url":"...", "socks_port":N}
func Status() string {
	stateMu.Lock()
	defer stateMu.Unlock()
	return statusLocked()
}

func statusLocked() string {
	if srv == nil || lc == nil {
		return mustJSON(map[string]any{
			"state":      "Stopped",
			"node_ip":    "",
			"login_url":  "",
			"socks_port": socksPort,
		})
	}
	st, err := lc.Status(context.Background())
	if err != nil {
		return mustJSON(map[string]any{
			"state":      "Error",
			"error":      err.Error(),
			"node_ip":    "",
			"login_url":  "",
			"socks_port": socksPort,
		})
	}
	nodeIP := ""
	if len(st.TailscaleIPs) > 0 {
		nodeIP = st.TailscaleIPs[0].String()
	}
	// The MagicDNS name outlives an IP change, so it is the better address
	// to hand to a peer.
	dnsName := ""
	if st.Self != nil {
		dnsName = st.Self.DNSName
	}
	return mustJSON(map[string]any{
		"state":      st.BackendState,
		"node_ip":    nodeIP,
		"dns_name":   dnsName,
		"login_url":  st.AuthURL,
		"socks_port": socksPort,
	})
}

// SocksPort returns the loopback SOCKS5 port, or 0 if not running.
func SocksPort() int {
	stateMu.Lock()
	defer stateMu.Unlock()
	return socksPort
}

// ---------------------------------------------------------------------------
// Peers & network
// ---------------------------------------------------------------------------

type tailnetPeer struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	IP   string `json:"ip"`
	OS   string `json:"os"`
	// DNSName is the peer's MagicDNS name, which survives an IP change.
	DNSName string `json:"dns_name"`
	Online  bool   `json:"online"`
	// ExitNodeOption is true when this peer offers exit-node service
	// (advertised AND approved in the tailnet policy). Drives the
	// "Route internet through" picker.
	ExitNodeOption bool `json:"exit_node_option"`
	// ExitNode is true when this peer is currently OUR exit node.
	ExitNode bool `json:"exit_node"`
}

// Peers returns a JSON array of tailnet peers.
func Peers() string {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil {
		return "[]"
	}
	st, err := lc.Status(context.Background())
	if err != nil {
		return "[]"
	}
	var out []tailnetPeer
	for _, p := range st.Peer {
		ip := ""
		if len(p.TailscaleIPs) > 0 {
			ip = p.TailscaleIPs[0].String()
		}
		out = append(out, tailnetPeer{
			// ipnstate.PeerStatus.ID is tailcfg.StableNodeID already —
			// the same ID space SetExitNode() feeds to EditPrefs and
			// prefs.ExitNodeID stores. (The transient NodeID is the
			// separate PeerStatus.NodeID field; do not use it here.)
			ID:             string(p.ID),
			Name:           p.HostName,
			IP:             ip,
			OS:             p.OS,
			DNSName:        p.DNSName,
			Online:         p.Online,
			ExitNodeOption: p.ExitNodeOption,
			ExitNode:       p.ExitNode,
		})
	}
	if out == nil {
		out = []tailnetPeer{}
	}
	return mustJSON(out)
}

// ThisDevice returns a JSON object for this node.
func ThisDevice() string {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil || srv == nil {
		return "{}"
	}
	st, err := lc.Status(context.Background())
	if err != nil {
		return "{}"
	}
	nodeIP := ""
	if len(st.TailscaleIPs) > 0 {
		nodeIP = st.TailscaleIPs[0].String()
	}
	name := ""
	dnsName := ""
	if st.Self != nil {
		name = st.Self.HostName
		dnsName = st.Self.DNSName
	}
	return mustJSON(map[string]any{
		"name":     name,
		"ip":       nodeIP,
		"dns_name": dnsName,
		"os":       "android",
	})
}

// Resolve resolves a MagicDNS hostname to an IP.
func Resolve(hostname string) string {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil {
		return ""
	}
	body, _, err := lc.QueryDNS(context.Background(), hostname, "A")
	if err != nil {
		return ""
	}
	// Parse the DNS response.
	msg := dnsmessage.Message{}
	if err := msg.Unpack(body); err != nil {
		return ""
	}
	for _, ans := range msg.Answers {
		if a, ok := ans.Body.(*dnsmessage.AResource); ok {
			return net.IP(a.A[:]).String()
		}
	}
	return ""
}

// WhoIs identifies a tailnet IP.
func WhoIs(ip string) string {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil {
		return "{}"
	}
	resp, err := lc.WhoIs(context.Background(), ip+":0")
	if err != nil {
		// Try without port.
		resp, err = lc.WhoIs(context.Background(), ip)
		if err != nil {
			return "{}"
		}
	}
	login := ""
	display := ""
	device := ""
	if resp.UserProfile != nil {
		login = resp.UserProfile.LoginName
		display = resp.UserProfile.DisplayName
	}
	if resp.Node != nil {
		device = resp.Node.Name
	}
	return mustJSON(map[string]any{
		"login":        login,
		"display_name": display,
		"device":       device,
	})
}

// ---------------------------------------------------------------------------
// Exit node
// ---------------------------------------------------------------------------

// SetExitNode sets the exit node by peer ID. Pass "" to clear.
func SetExitNode(peerID string) error {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil {
		return fmt.Errorf("not running")
	}
	if peerID == "" {
		mp := &ipn.MaskedPrefs{
			ExitNodeIDSet: true,
			Prefs:         ipn.Prefs{ExitNodeID: ""},
		}
		_, err := lc.EditPrefs(context.Background(), mp)
		return err
	}
	mp := &ipn.MaskedPrefs{
		ExitNodeIDSet: true,
		Prefs:         ipn.Prefs{ExitNodeID: tailcfg.StableNodeID(peerID)},
	}
	_, err := lc.EditPrefs(context.Background(), mp)
	return err
}

// ExitNode returns the current exit node peer ID, or "".
func ExitNode() string {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil {
		return ""
	}
	prefs, err := lc.GetPrefs(context.Background())
	if err != nil {
		return ""
	}
	return string(prefs.ExitNodeID)
}

// ---------------------------------------------------------------------------
// Taildrop
// ---------------------------------------------------------------------------

// TaildropSend sends a file to a peer.
func TaildropSend(peerID, filePath string) error {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil {
		return fmt.Errorf("not running")
	}
	f, err := os.Open(filePath)
	if err != nil {
		return err
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return err
	}
	name := filepath.Base(filePath)
	return lc.PushFile(context.Background(), tailcfg.StableNodeID(peerID), fi.Size(), name, f)
}

// TaildropPending returns a JSON array of incoming file offers.
func TaildropPending() string {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil {
		return "[]"
	}
	files, err := lc.WaitingFiles(context.Background())
	if err != nil {
		return "[]"
	}
	type offer struct {
		ID   string `json:"id"`
		Name string `json:"name"`
		Size int64  `json:"size"`
		Peer string `json:"peer"`
	}
	var out []offer
	for _, wf := range files {
		out = append(out, offer{
			ID:   wf.Name,
			Name: wf.Name,
			Size: wf.Size,
			Peer: "",
		})
	}
	if out == nil {
		out = []offer{}
	}
	return mustJSON(out)
}

// TaildropAccept accepts a pending file offer and saves it to destDir.
func TaildropAccept(offerID, destDir string) error {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil {
		return fmt.Errorf("not running")
	}
	if err := os.MkdirAll(destDir, 0755); err != nil {
		return err
	}
	rc, size, err := lc.GetWaitingFile(context.Background(), offerID)
	if err != nil {
		return err
	}
	defer rc.Close()
	dst := filepath.Join(destDir, offerID)
	f, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer f.Close()
	if _, err := io.Copy(f, rc); err != nil {
		return err
	}
	_ = size
	return lc.DeleteWaitingFile(context.Background(), offerID)
}

// TaildropDecline declines a pending file offer.
func TaildropDecline(offerID string) error {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil {
		return fmt.Errorf("not running")
	}
	return lc.DeleteWaitingFile(context.Background(), offerID)
}

// ---------------------------------------------------------------------------
// Taildrive
// ---------------------------------------------------------------------------

// TaildriveShares returns a JSON array of shares visible on the tailnet.
func TaildriveShares() string {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil {
		return "[]"
	}
	shares, err := lc.DriveShareList(context.Background())
	if err != nil {
		return "[]"
	}
	type share struct {
		Peer string `json:"peer"`
		Name string `json:"name"`
		Path string `json:"path"`
	}
	var out []share
	for _, sh := range shares {
		out = append(out, share{
			Peer: "",
			Name: sh.Name,
			Path: sh.Path,
		})
	}
	if out == nil {
		out = []share{}
	}
	return mustJSON(out)
}

// TaildriveMount exposes a local directory to the tailnet via Taildrive.
func TaildriveMount(name, localPath string) error {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil {
		return fmt.Errorf("not running")
	}
	sh := &drive.Share{Name: name, Path: localPath}
	return lc.DriveShareSet(context.Background(), sh)
}

// TaildriveUnmount removes a previously mounted share by name.
func TaildriveUnmount(name string) error {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil {
		return fmt.Errorf("not running")
	}
	return lc.DriveShareRemove(context.Background(), name)
}

// ---------------------------------------------------------------------------
// Serve
// ---------------------------------------------------------------------------

func proxyTCP(dst net.Conn, localPort int) {
	defer dst.Close()
	src, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", localPort))
	if err != nil {
		return
	}
	defer src.Close()
	go func() { _, _ = io.Copy(src, dst) }()
	_, _ = io.Copy(dst, src)
}

// ServeHTTP serves localPort on the tailnet at the given tailnet port.
func ServeHTTP(localPort, tailnetPort int) error {
	stateMu.Lock()
	defer stateMu.Unlock()
	if srv == nil {
		return fmt.Errorf("not running")
	}
	serveMu.Lock()
	defer serveMu.Unlock()
	if _, ok := serveLns[tailnetPort]; ok {
		return fmt.Errorf("port %d already served", tailnetPort)
	}
	ln, err := srv.Listen("tcp", fmt.Sprintf(":%d", tailnetPort))
	if err != nil {
		return err
	}
	serveLns[tailnetPort] = ln
	go func(port int, l net.Listener, lp int) {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go proxyTCP(c, lp)
		}
	}(tailnetPort, ln, localPort)
	return nil
}

// ServeTLS serves localPort on the tailnet with automatic TLS.
func ServeTLS(localPort, tailnetPort int) error {
	stateMu.Lock()
	defer stateMu.Unlock()
	if srv == nil {
		return fmt.Errorf("not running")
	}
	serveMu.Lock()
	defer serveMu.Unlock()
	if _, ok := serveLns[tailnetPort]; ok {
		return fmt.Errorf("port %d already served", tailnetPort)
	}
	ln, err := srv.ListenTLS("tcp", fmt.Sprintf(":%d", tailnetPort))
	if err != nil {
		return err
	}
	serveLns[tailnetPort] = ln
	go func(port int, l net.Listener, lp int) {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go proxyTCP(c, lp)
		}
	}(tailnetPort, ln, localPort)
	return nil
}

// ServeStop stops the serve on tailnetPort.
func ServeStop(tailnetPort int) error {
	serveMu.Lock()
	defer serveMu.Unlock()
	ln, ok := serveLns[tailnetPort]
	if !ok {
		return fmt.Errorf("port %d not served", tailnetPort)
	}
	ln.Close()
	delete(serveLns, tailnetPort)
	return nil
}

// ---------------------------------------------------------------------------
// Funnel
// ---------------------------------------------------------------------------

// FunnelEnable exposes tailnetPort publicly to the internet via Funnel.
func FunnelEnable(tailnetPort int) error {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil {
		return fmt.Errorf("not running")
	}
	cfg, err := lc.GetServeConfig(context.Background())
	if err != nil {
		cfg = new(ipn.ServeConfig)
	}
	if cfg.AllowFunnel == nil {
		cfg.AllowFunnel = map[ipn.HostPort]bool{}
	}
	hp := ipn.HostPort(fmt.Sprintf(":%d", tailnetPort))
	cfg.AllowFunnel[hp] = true
	return lc.SetServeConfig(context.Background(), cfg)
}

// FunnelDisable removes Funnel for tailnetPort.
func FunnelDisable(tailnetPort int) error {
	stateMu.Lock()
	defer stateMu.Unlock()
	if lc == nil {
		return fmt.Errorf("not running")
	}
	cfg, err := lc.GetServeConfig(context.Background())
	if err != nil {
		return err
	}
	if cfg.AllowFunnel != nil {
		delete(cfg.AllowFunnel, ipn.HostPort(fmt.Sprintf(":%d", tailnetPort)))
	}
	return lc.SetServeConfig(context.Background(), cfg)
}

// ---------------------------------------------------------------------------
// Admin API
// ---------------------------------------------------------------------------

func adminGet(apiKey, path string) (string, error) {
	req, err := http.NewRequest("GET", "https://api.tailscale.com"+path, nil)
	if err != nil {
		return "", err
	}
	req.SetBasicAuth("", apiKey)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	if resp.StatusCode >= 400 {
		return string(b), fmt.Errorf("http %d", resp.StatusCode)
	}
	return string(b), nil
}

// AdminDevices returns all devices in the tailnet as JSON.
func AdminDevices(apiKey string) string {
	out, err := adminGet(apiKey, "/api/v2/tailnet/-/devices")
	if err != nil {
		return mustJSON(map[string]any{"error": err.Error()})
	}
	return out
}

// AdminExpireKey expires a device's node key by device ID.
func AdminExpireKey(apiKey, deviceID string) error {
	req, err := http.NewRequest("POST", "https://api.tailscale.com/api/v2/device/"+deviceID+"/expire", nil)
	if err != nil {
		return err
	}
	req.SetBasicAuth("", apiKey)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("http %d: %s", resp.StatusCode, string(b))
	}
	return nil
}

// AdminDNS returns the tailnet DNS configuration as JSON.
func AdminDNS(apiKey string) string {
	out, err := adminGet(apiKey, "/api/v2/tailnet/-/dns/nameservers")
	if err != nil {
		return mustJSON(map[string]any{"error": err.Error()})
	}
	return out
}

// AdminACL returns the tailnet ACL policy as JSON.
func AdminACL(apiKey string) string {
	out, err := adminGet(apiKey, "/api/v2/tailnet/-/acl")
	if err != nil {
		return mustJSON(map[string]any{"error": err.Error()})
	}
	return out
}

// keep imports used
var _ = bufio.NewScanner
var _ = strconv.Atoi
var _ = strings.TrimSpace
var _ = time.Second
var _ = net.JoinHostPort
var _ = apitype.WaitingFile{}
