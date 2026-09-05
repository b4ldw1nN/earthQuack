package node

import (
	"fmt"
	"net"
	"os"
	"strconv"
	"sync"
	"time"
)

// Registry holds the known nodes of this earthQuack instance:
// the local node plus peers discovered via NetworkProviders and —
// where a peer answers /api/node — authoritative earthQuack nodes.
//
// State is in-memory only — no database. The local node is always
// present; peers are refreshed from their provider on each listing.
// A peer is considered online only if its provider currently reports
// it online and it was seen within PeerTTL, so `online=true` is never
// persisted forever.
//
// Semantics:
//
//	discovered peer = known to a NetworkProvider (e.g. Tailscale)
//	registered node = probed successfully via NodeClient; its
//	                  self-reported Identity is authoritative
type Registry struct {
	mu        sync.RWMutex
	local     Node
	peers     map[Identity]peerEntry
	alias     map[Identity]Identity // discovery identity -> authoritative identity
	lastProbe map[Identity]time.Time
	providers []NetworkProvider
	client    *NodeClient // nil disables peer probing
	now       func() time.Time
}

type peerEntry struct {
	node     Node
	lastSeen time.Time
}

// PeerTTL bounds how long a peer is trusted as online without a
// refresh. Generous by design: no aggressive polling.
const PeerTTL = 5 * time.Minute

// ProbeBackoff bounds how often an individual peer is re-probed
// during refresh cycles, so listing nodes never hammers the network.
const ProbeBackoff = 30 * time.Second

// Local capabilities and services are NOT hardcoded here. They are
// registered explicitly at startup (see service.go), so adding a new
// capability/service never requires modifying the Registry, API,
// dashboard, or peer probing.

// NewRegistry creates a registry with the local node resolved from
// the machine identity and the given network providers. client may be
// nil, which disables authoritative peer probing (discovery only).
// The local node starts with no capabilities/services; register them
// via RegisterCapability/RegisterService.
func NewRegistry(identity Identity, providers []NetworkProvider, now func() time.Time, client *NodeClient) (*Registry, error) {
	if now == nil {
		now = time.Now
	}
	local := Node{
		Identity:     identity,
		Hostname:     localHostname(),
		OS:           normalizeOS(),
		Online:       true, // this instance is, by definition, up
		Registered:   true, // authoritative by definition
		Capabilities: []string{},
		Services:     []Service{},
		LastSeen:     now(),
	}
	return &Registry{
		local:     local,
		peers:     make(map[Identity]peerEntry),
		alias:     make(map[Identity]Identity),
		lastProbe: make(map[Identity]time.Time),
		providers: providers,
		client:    client,
		now:       now,
	}, nil
}

// RegisterCapability declares that this node provides a capability.
// Capabilities are explicit declarations — never inferred from the
// OS, hostname, network presence, or open ports.
func (r *Registry) RegisterCapability(cap string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, c := range r.local.Capabilities {
		if c == cap {
			return
		}
	}
	r.local.Capabilities = append(r.local.Capabilities, cap)
}

// RegisterService declares a concrete service implemented by this
// node. Status starts as unknown; ProbeLocalServices sets the runtime
// status from reachability.
func (r *Registry) RegisterService(s Service) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if s.Status == "" {
		s.Status = ServiceUnknown
	}
	for i := range r.local.Services {
		if r.local.Services[i].Name == s.Name {
			r.local.Services[i] = s
			return
		}
	}
	r.local.Services = append(r.local.Services, s)
}

// Local returns a copy of the local node definition.
func (r *Registry) Local() Node {
	r.mu.RLock()
	defer r.mu.RUnlock()
	n := r.local
	return n
}

// SetLocalNetwork updates the local node's transport information
// (e.g. its current Tailscale addresses). Never touches identity.
func (r *Registry) SetLocalNetwork(info NetworkInfo) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.local.Network = info
}

// SetLocalServiceStatus updates the runtime status of a previously
// registered local service. It never creates a service: a port being
// open determines status only, never capabilities or service existence.
func (r *Registry) SetLocalServiceStatus(name string, status ServiceStatus, endpoint string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for i := range r.local.Services {
		if r.local.Services[i].Name == name {
			r.local.Services[i].Status = status
			if endpoint != "" {
				r.local.Services[i].Endpoint = endpoint
			}
			return
		}
	}
}

// Refresh asks each provider for current peers and merges them into
// the registry. Provider errors are non-fatal: the registry keeps the
// last known state of peers that could not be refreshed.
//
// For peers the provider reports online, and when a NodeClient is
// configured, Refresh attempts an authoritative probe
// (GET /api/node) subject to ProbeBackoff. A successful probe
// registers the peer under its self-reported Identity with its
// capabilities/services; a failed probe leaves it as a plain
// discovered peer. Discovery information is never discarded.
func (r *Registry) Refresh() {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := r.now()
	for _, p := range r.providers {
		peers, err := p.DiscoverPeers()
		if err != nil {
			continue
		}
		for _, peer := range peers {
			if peer.Identity == r.local.Identity {
				continue // never probe/track ourselves
			}

			// Authoritative probe: only for online peers, only when a
			// client is configured, and never more often than
			// ProbeBackoff. The address is merely the current route.
			probeDue := r.client != nil && peer.Online &&
				now.Sub(r.lastProbe[peer.Identity]) >= ProbeBackoff

			if probeDue {
				r.lastProbe[peer.Identity] = now
				if remote, err := r.probeLocked(peer, now); err == nil {
					if remote.Identity == r.local.Identity {
						continue // a peer reporting our identity is us; skip
					}
					r.peers[remote.Identity] = peerEntry{node: *remote, lastSeen: now}
					r.alias[peer.Identity] = remote.Identity
					// Drop any stale discovered entry under the transport-
					// scoped identity; the authoritative entry supersedes it.
					if peer.Identity != remote.Identity {
						delete(r.peers, peer.Identity)
					}
					continue
				}
				delete(r.alias, peer.Identity)
			}

			// Probe not due: keep the authoritative entry fresh (route
			// from discovery, trust while the provider reports online)
			// instead of duplicating it as a discovered peer.
			if authID, ok := r.alias[peer.Identity]; ok {
				if e, exists := r.peers[authID]; exists {
					e.node.Network = peer.Network
					if peer.Online {
						e.lastSeen = now
					}
					r.peers[authID] = e
					continue
				}
			}

			// Discovered peer (or probe failed): keep transport
			// knowledge, claim nothing about earthQuack.
			peer.Registered = false
			r.peers[peer.Identity] = peerEntry{node: peer, lastSeen: now}
		}
	}
}

// probeLocked fetches the authoritative Node from a discovered peer.
// The peer's network address supplies only the route; its response
// supplies the authoritative content. Caller holds r.mu.
func (r *Registry) probeLocked(peer Node, now time.Time) (*Node, error) {
	if len(peer.Network.Addresses) == 0 {
		return nil, fmt.Errorf("node: peer %s has no address to probe", peer.Identity)
	}
	remote, err := r.client.GetNode(net.JoinHostPort(peer.Network.Addresses[0], strconv.Itoa(r.client.Port)))
	if err != nil {
		return nil, err
	}
	// The route is informational; discovery tells us how we reached it.
	remote.Network = peer.Network
	remote.Registered = true
	remote.LastSeen = now
	return remote, nil
}

// Nodes returns the local node plus all known peers, with online
// state recomputed from lastSeen + PeerTTL and provider snapshot.
func (r *Registry) Nodes() []Node {
	r.Refresh()
	r.mu.RLock()
	defer r.mu.RUnlock()

	nodes := []Node{r.local}
	now := r.now()
	for _, e := range r.peers {
		n := e.node
		n.Online = n.Online && now.Sub(e.lastSeen) < PeerTTL
		nodes = append(nodes, n)
	}
	SortNodes(nodes)
	return nodes
}

// ProbeService performs a lightweight TCP reachability check against
// a host:port to determine a service's runtime status. Used for the
// local node's own services only.
func ProbeService(host string, port int, timeout time.Duration) bool {
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(port)), timeout)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}

func localHostname() string {
	h, err := os.Hostname()
	if err != nil || h == "" {
		return "unknown"
	}
	return h
}

func normalizeOS() string {
	return "linux" // earthQuack daemon currently targets Linux; extend per-OS later
}
