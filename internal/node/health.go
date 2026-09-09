// Package node health: a minimal, derived runtime health summary.
//
// Health is NEVER configured and NEVER probed/collected. It is a pure,
// deterministic evaluation of the already-established Node state:
//
//	Providers (telemetry/services/discovery)
//	    ↓
//	Node state (Online, Registered, Services; System/Storage/NetworkStats ignored)
//	    ↓
//	EvaluateHealth(node) → HealthInfo
//	    ↓
//	API (/api/node, /api/nodes) + dashboard
//
// Conservative rules only:
//   - registered + offline  → offline (stale service state never overrides)
//   - registered + online + a declared service stopped/unknown → degraded
//   - registered + online + all declared services running (or zero) → healthy
//   - not registered → no health at all (discovered peers are never unhealthy)
//   - zero-identity node → unknown (genuinely insufficient information)
//
// Telemetry (CPU, memory, disk usage, network traffic, errors/drops,
// load averages) NEVER influences health: those are measurements, not
// failures. There is no alerting policy.
//
// The evaluator is generic over Service/ServiceStatus: future services
// (storage, docker, database, ...) participate automatically with no
// service-specific code.
package node

import (
	"fmt"
	"sort"
	"strings"
)

// HealthStatus is the coarse runtime state of a registered node.
type HealthStatus string

const (
	// HealthHealthy means the registered node is online and every
	// declared service is running (or it declares zero services).
	HealthHealthy HealthStatus = "healthy"
	// HealthDegraded means the registered node is online but one or
	// more declared services are stopped or have unknown status.
	HealthDegraded HealthStatus = "degraded"
	// HealthOffline means the registered node is currently unreachable.
	// It is distinct from degraded: stale service states never
	// override the offline state.
	HealthOffline HealthStatus = "offline"
	// HealthUnknown means there genuinely is not enough information to
	// determine a meaningful state (e.g. the node has no identity at
	// all). It is never manufactured from missing optional telemetry.
	HealthUnknown HealthStatus = "unknown"
)

// HealthIssue is one explainable reason behind a non-healthy state.
// Type is a stable machine-readable key ("service", "connectivity");
// Name pinpoints the subject (e.g. the service name); Message is the
// human-readable explanation shown on the dashboard.
type HealthIssue struct {
	Type    string `json:"type"`
	Name    string `json:"name,omitempty"`
	Message string `json:"message"`
}

// HealthInfo is the health summary attached to a Node. It is derived
// runtime state — never configurable (see config.go: Config has no
// health/status/online fields and rejects unknown fields).
type HealthInfo struct {
	Status  HealthStatus  `json:"status"`
	Summary string        `json:"summary,omitempty"`
	Issues  []HealthIssue `json:"issues,omitempty"`
}

// EvaluateHealth derives the health of one node from its current state.
// It performs no I/O: no Tailscale calls, no port probes, no /proc
// reads, no network requests. It consumes only the Node value.
//
//   - Registered == false → nil: discovered peers get no health.
//     An unregistered Tailscale peer is not unhealthy; earthQuack has
//     no authoritative knowledge about it.
//   - Identity == "" (and Hostname == "") → unknown: genuinely
//     insufficient information to say anything meaningful.
//   - Registered && !Online → offline with a connectivity issue.
//   - Registered && Online → degraded if any declared service is
//     stopped or unknown (each listed as an issue, sorted by name);
//     healthy otherwise. Zero declared services is healthy.
func EvaluateHealth(n Node) *HealthInfo {
	if !n.Registered {
		return nil
	}
	if n.Identity == "" && n.Hostname == "" {
		return &HealthInfo{Status: HealthUnknown, Summary: "insufficient information"}
	}
	if !n.Online {
		return &HealthInfo{
			Status:  HealthOffline,
			Summary: "earthQuack node unavailable",
			Issues: []HealthIssue{{
				Type:    "connectivity",
				Message: "earthQuack node unavailable",
			}},
		}
	}
	var stopped []Service
	var unknown []Service
	for _, s := range n.Services {
		switch s.Status {
		case ServiceRunning:
		case ServiceStopped:
			stopped = append(stopped, s)
		default:
			unknown = append(unknown, s)
		}
	}
	if len(stopped) == 0 && len(unknown) == 0 {
		return &HealthInfo{Status: HealthHealthy}
	}
	sort.Slice(stopped, func(i, j int) bool { return stopped[i].Name < stopped[j].Name })
	sort.Slice(unknown, func(i, j int) bool { return unknown[i].Name < unknown[j].Name })
	issues := make([]HealthIssue, 0, len(stopped)+len(unknown))
	for _, s := range stopped {
		issues = append(issues, HealthIssue{
			Type:    "service",
			Name:    s.Name,
			Message: fmt.Sprintf("service %q stopped", s.Name),
		})
	}
	for _, s := range unknown {
		issues = append(issues, HealthIssue{
			Type:    "service",
			Name:    s.Name,
			Message: fmt.Sprintf("service %q status unknown", s.Name),
		})
	}
	return &HealthInfo{
		Status:  HealthDegraded,
		Summary: degradedSummary(stopped, unknown),
		Issues:  issues,
	}
}

// degradedSummary renders the one-line dashboard explanation, e.g.
// "1 service stopped" or "2 services stopped · 1 status unknown".
func degradedSummary(stopped, unknown []Service) string {
	var parts []string
	if n := len(stopped); n > 0 {
		if n == 1 {
			parts = append(parts, "1 service stopped")
		} else {
			parts = append(parts, fmt.Sprintf("%d services stopped", n))
		}
	}
	if n := len(unknown); n > 0 {
		if n == 1 {
			parts = append(parts, "1 service status unknown")
		} else {
			parts = append(parts, fmt.Sprintf("%d services status unknown", n))
		}
	}
	return strings.Join(parts, " · ")
}
