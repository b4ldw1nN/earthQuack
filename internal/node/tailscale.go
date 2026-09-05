package node

import (
	"encoding/json"
	"fmt"
	"os/exec"
	"strings"
	"time"
)

// NetworkProvider discovers nodes reachable via a networking transport.
// Tailscale is the first implementation; later providers (LAN,
// WireGuard, VPS networking) can be added without changing the Node
// model or the registry.
type NetworkProvider interface {
	Name() string
	// DiscoverPeers returns the currently known peer nodes. Online
	// state reflects what the transport reports right now; callers
	// must treat it as a snapshot, not persisted truth.
	DiscoverPeers() ([]Node, error)
}

// TailscaleProvider implements NetworkProvider using
// `tailscale status --json`. It is intentionally thin: parsing only
// the fields the Node model needs.
type TailscaleProvider struct {
	binary string // resolved once; empty means auto-detect per call
}

// NewTailscaleProvider returns a Tailscale-backed NetworkProvider.
func NewTailscaleProvider() *TailscaleProvider {
	return &TailscaleProvider{}
}

func (p *TailscaleProvider) Name() string { return "tailscale" }

// tsStatus is the subset of `tailscale status --json` we consume.
type tsStatus struct {
	BackendState string            `json:"BackendState"`
	TailscaleIPs []string          `json:"TailscaleIPs"`
	Self         tsPeer            `json:"Self"`
	Peer         map[string]tsPeer `json:"Peer"`
}

type tsPeer struct {
	ID           string   `json:"ID"`
	HostName     string   `json:"HostName"`
	DNSName      string   `json:"DNSName"`
	OS           string   `json:"OS"`
	Online       bool     `json:"Online"`
	Active       bool     `json:"Active"`
	TailscaleIPs []string `json:"TailscaleIPs"`
}

// DiscoverPeers returns online/offline peers known to Tailscale.
// Peer identity is scoped to this transport ("tailscale:<node id>")
// and is stable even when Tailscale IPs or hostnames change.
func (p *TailscaleProvider) DiscoverPeers() ([]Node, error) {
	out, err := p.statusJSON()
	if err != nil {
		return nil, err
	}
	var st tsStatus
	if err := json.Unmarshal([]byte(out), &st); err != nil {
		return nil, fmt.Errorf("node: parse tailscale status: %w", err)
	}

	var nodes []Node
	now := time.Now().UTC()
	for key, peer := range st.Peer {
		id := peer.ID
		if id == "" {
			id = key // fall back to the map key (node key), still stable
		}
		nodes = append(nodes, Node{
			Identity:     Identity("tailscale:" + id),
			Hostname:     peer.HostName,
			OS:           peer.OS,
			Online:       peer.Online,
			Capabilities: []string{},
			Services:     []Service{},
			Network: NetworkInfo{
				Transport: "tailscale",
				Addresses: peer.TailscaleIPs,
			},
			LastSeen: now,
		})
	}
	SortNodes(nodes)
	return nodes, nil
}

// SelfInfo returns the local machine's Tailscale view of itself.
// It is transport information only — not the node's identity.
func (p *TailscaleProvider) SelfInfo() (hostname string, addresses []string, err error) {
	out, err := p.statusJSON()
	if err != nil {
		return "", nil, err
	}
	var st tsStatus
	if err := json.Unmarshal([]byte(out), &st); err != nil {
		return "", nil, fmt.Errorf("node: parse tailscale status: %w", err)
	}
	return st.Self.HostName, st.TailscaleIPs, nil
}

func (p *TailscaleProvider) statusJSON() (string, error) {
	bin := p.binary
	if bin == "" {
		bin = "tailscale"
	}
	out, err := exec.Command(bin, "status", "--json").Output()
	if err != nil {
		return "", fmt.Errorf("node: tailscale status --json: %w", err)
	}
	return strings.TrimSpace(string(out)), nil
}
