package node

import (
	"context"
	"fmt"
	"time"
)

// DefaultRefreshInterval is the gentle default period between local
// service status refreshes. Deliberately slow: this is a health
// signal, not a monitoring system.
const DefaultRefreshInterval = 30 * time.Second

// ServiceRefresher keeps the runtime status of explicitly registered
// local services up to date. It operates purely on already-declared
// LocalServiceSpecs: it can only update the status of services that
// were registered beforehand — never create services, never create
// capabilities, never infer services from open ports.
//
// Lifecycle: call Run in a goroutine; cancellation of the context
// exits the loop, so the process can shut down cleanly.
type ServiceRefresher struct {
	reg      *Registry
	specs    []LocalServiceSpec
	host     string
	timeout  time.Duration
	interval time.Duration
}

// NewServiceRefresher returns a refresher for the given declared
// specs, probing against host. interval <= 0 selects
// DefaultRefreshInterval.
func NewServiceRefresher(reg *Registry, specs []LocalServiceSpec, host string, timeout time.Duration, interval time.Duration) *ServiceRefresher {
	if interval <= 0 {
		interval = DefaultRefreshInterval
	}
	return &ServiceRefresher{reg: reg, specs: specs, host: host, timeout: timeout, interval: interval}
}

// Refresh performs one probe pass: for each declared spec with a port
// it updates the registered service's status via the existing
// update-only SetLocalServiceStatus path. Specs with port 0 are not
// probed. Errors are impossible by construction; an unreachable port
// simply yields "stopped".
func (s *ServiceRefresher) Refresh() {
	for _, spec := range s.specs {
		if spec.Port == 0 {
			continue
		}
		status := ServiceStopped
		if ProbeService(s.host, spec.Port, s.timeout) {
			status = ServiceRunning
		}
		s.reg.SetLocalServiceStatus(spec.Name, status, fmt.Sprintf("%s:%d", s.host, spec.Port))
	}
}

// Run refreshes immediately, then on every tick until ctx is
// cancelled. It returns when the context is done — the goroutine does
// not outlive the process's intended lifetime.
func (s *ServiceRefresher) Run(ctx context.Context) {
	s.Refresh()
	ticker := time.NewTicker(s.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.Refresh()
		}
	}
}
