// Package node defines the earthQuack Node model: identity, runtime
// state, capabilities, services, and network information.
//
// The Node abstraction is transport-agnostic. Tailscale is only the
// first implementation of node network information (see tailscale.go).
package node

import (
	"sort"
	"time"
)

// Identity is the stable, transport-independent identity of a node.
// It must not change when the IP address, hostname, or online state
// changes. For the local node it is derived from the machine ID; for
// peers it is scoped to the network transport that discovered them
// (e.g. "tailscale:<stable node id>").
type Identity string

// ServiceStatus describes the runtime state of a service on a node.
type ServiceStatus string

const (
	ServiceRunning ServiceStatus = "running"
	ServiceStopped ServiceStatus = "stopped"
	ServiceUnknown ServiceStatus = "unknown"
)

// Service is a discoverable capability of a node that is currently
// implemented by a running (or known) component. Keep this minimal:
// more metadata can be added later without changing the wire shape.
type Service struct {
	Name     string        `json:"name"`
	Status   ServiceStatus `json:"status"`
	Version  string        `json:"version,omitempty"`
	Endpoint string        `json:"endpoint,omitempty"`
}

// NetworkInfo describes how a node is reachable on a transport. A node
// may be reachable via multiple transports over time (Tailscale, LAN,
// WireGuard, VPS networking), so transport is explicit and addresses
// are informational, never used as identity.
type NetworkInfo struct {
	Transport string   `json:"transport"`           // "tailscale", "lan", ...
	Addresses []string `json:"addresses,omitempty"` // current addresses, may change
}

// Node is a machine participating in the earthQuack ecosystem.
// Identity (Identity) is stable; everything else is runtime state.
//
// Registered distinguishes earthQuack knowledge from mere network
// discovery: true means this instance has verified the node via its
// authoritative /api/node response; false means it is only a
// discovered transport peer (no capabilities/services are claimed).
type Node struct {
	Identity     Identity    `json:"identity"`
	Hostname     string      `json:"hostname"`
	OS           string      `json:"os"`
	Online       bool        `json:"online"`
	Registered   bool        `json:"registered"`
	Capabilities []string    `json:"capabilities"`
	Services     []Service   `json:"services"`
	Network      NetworkInfo `json:"network"`
	System       *SystemInfo `json:"system,omitempty"`
	LastSeen     time.Time   `json:"last_seen,omitempty"`
}

// LocalNodeName is the name reported for this instance in /api/node.
const LocalNodeName = "earthQuack"

// HasCapability reports whether the node declares the given capability.
func (n *Node) HasCapability(cap string) bool {
	for _, c := range n.Capabilities {
		if c == cap {
			return true
		}
	}
	return false
}

// SortNodes orders nodes deterministically by hostname then identity.
func SortNodes(nodes []Node) {
	sort.Slice(nodes, func(i, j int) bool {
		if nodes[i].Hostname != nodes[j].Hostname {
			return nodes[i].Hostname < nodes[j].Hostname
		}
		return nodes[i].Identity < nodes[j].Identity
	})
}
