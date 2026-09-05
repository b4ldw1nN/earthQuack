package node

import (
	"fmt"
	"time"
)

// LocalServiceSpec declares a local service implemented by this node:
// the capability it belongs to, the concrete service name, and the
// TCP port its implementation listens on (used only for status
// probing — never for capability creation). It doubles as the JSON
// shape for node configuration declarations.
type LocalServiceSpec struct {
	Capability string `json:"capability,omitempty"` // defaults to Name
	Name       string `json:"name"`
	Port       int    `json:"port,omitempty"` // 0 = no probe
	Version    string `json:"version,omitempty"`
}

// RegisterLocalServices registers capabilities and services from the
// given specs and determines each service's runtime status with a
// bounded TCP probe against host.
//
// The registration flow is:
//
//	spec (explicit declaration)
//	    → RegisterCapability  (what the node provides)
//	    → RegisterService     (what it implements)
//	    → TCP probe           (running/stopped status only)
//
// A closed port yields a "stopped" service but never removes the
// capability, and an open port can never create a capability.
func RegisterLocalServices(reg *Registry, specs []LocalServiceSpec, host string, timeout time.Duration) {
	for _, spec := range specs {
		reg.RegisterCapability(spec.Capability)
		reg.RegisterService(Service{Name: spec.Name, Status: ServiceUnknown, Version: spec.Version})

		if spec.Port == 0 {
			continue // nothing to probe
		}
		status := ServiceStopped
		if ProbeService(host, spec.Port, timeout) {
			status = ServiceRunning
		}
		reg.SetLocalServiceStatus(spec.Name, status, fmt.Sprintf("%s:%d", host, spec.Port))
	}
}
