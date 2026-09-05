package node

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// DefaultNodePort is the port the earthQuack node server listens on.
// Shared here so peer probing never hardcodes the port elsewhere.
const DefaultNodePort = 8890

// NodeClient fetches authoritative Node information from a remote
// earthQuack instance's /api/node endpoint. It is transport-agnostic:
// callers supply the address (host:port) of the peer on whatever
// network reaches it (Tailscale today, WireGuard/LAN later).
// It performs GET requests only — read-only by construction.
type NodeClient struct {
	// HTTP is the underlying client; a short, bounded timeout is set
	// by NewNodeClient and must not be removed (peers may be dead).
	HTTP *http.Client
	// Port is the earthQuack node server port used to reach peers.
	Port int
	// Token is the shared bearer token sent as
	// "Authorization: Bearer <token>" on peer requests. Empty means
	// peers are contacted without credentials (they will fail closed).
	Token string
}

// NewNodeClient returns a NodeClient with a short bounded timeout so
// an unreachable peer never blocks discovery indefinitely.
func NewNodeClient(port int) *NodeClient {
	return &NodeClient{
		HTTP: &http.Client{Timeout: 2 * time.Second},
		Port: port,
	}
}

// NewNodeClientWithToken is NewNodeClient with shared-token
// authentication for peer requests. Token lives only in the client —
// the Registry and Tailscale provider stay auth-agnostic.
func NewNodeClientWithToken(port int, token string) *NodeClient {
	c := NewNodeClient(port)
	c.Token = token
	return c
}

// GetNode fetches and validates the Node description at
// http://<addr>/api/node, where addr is "host:port".
//
// Validation is deliberately strict: a peer only becomes an
// authoritative earthQuack node if the response is HTTP 200, JSON,
// parses into a Node, and carries a non-empty stable identity and
// hostname. Anything else is an error — never silently trusted.
func (c *NodeClient) GetNode(addr string) (*Node, error) {
	url := "http://" + addr + "/api/node"
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("node client: %s: %w", addr, err)
	}
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("node client: %s: %w", addr, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusServiceUnavailable {
		return nil, fmt.Errorf("node client: %s: peer rejected credentials (status %d)", addr, resp.StatusCode)
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("node client: %s: unexpected status %d", addr, resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		return nil, fmt.Errorf("node client: %s: unexpected content-type %q", addr, ct)
	}

	// Bound the read so a malicious/broken peer cannot stream forever.
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, fmt.Errorf("node client: %s: read body: %w", addr, err)
	}

	var n Node
	if err := json.Unmarshal(body, &n); err != nil {
		return nil, fmt.Errorf("node client: %s: decode: %w", addr, err)
	}
	if n.Identity == "" {
		return nil, fmt.Errorf("node client: %s: response missing identity", addr)
	}
	if n.Hostname == "" {
		return nil, fmt.Errorf("node client: %s: response missing hostname", addr)
	}
	return &n, nil
}
