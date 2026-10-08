package node

import (
	"time"
)

// EventType is a machine-readable event type. Only transitions are
// represented; current state is not emitted as an event.
type EventType string

const (
	EvServiceStopped   EventType = "service.stopped"
	EvServiceRecovered EventType = "service.recovered"
	EvHealthDegraded   EventType = "health.degraded"
	EvHealthRecovered  EventType = "health.recovered"
	EvNodeOffline      EventType = "node.offline"
	EvNodeOnline       EventType = "node.online"

	// Wallpaper module lifecycle events. Emitted by the wallpaper
	// module through its event sink when an integration bridges them
	// into the node history ring.
	EvWallpaperSyncStarted   EventType = "wallpaper.sync.started"
	EvWallpaperSyncCompleted EventType = "wallpaper.sync.completed"
	EvWallpaperSyncFailed    EventType = "wallpaper.sync.failed"

	// Internet Microscope observation transitions. Emitted by the
	// internet module and bridged by the node (see internetNodeEvent),
	// so consumers get Internet changes through the same audit stream
	// as every other earthQuack transition.
	EvInternetSourceNew       EventType = "internet.source.new"
	EvInternetSourceChanged   EventType = "internet.source.changed"
	EvInternetSourceError     EventType = "internet.source.error"
	EvInternetSourceRecovered EventType = "internet.source.recovered"
)

// Event is the public, typed representation of a state transition.
type Event struct {
	Seq     uint64    `json:"seq"`
	Time    time.Time `json:"time"`
	Node    Identity  `json:"node"`
	Type    EventType `json:"type"`
	Name    string    `json:"name,omitempty"`
	Message string    `json:"message"`
	// Data carries optional structured, event-specific details (for
	// example the source id, url and fingerprints of an Internet
	// Microscope change). It is additive: events without it are
	// unchanged, and consumers that only read Type/Message keep
	// working.
	Data map[string]string `json:"data,omitempty"`
}

// HistoryEvent is one discrete transition kept in the underlying History ring.
type HistoryEvent struct {
	Time    time.Time
	Seq     uint64
	Node    Identity
	Kind    string
	Name    string
	From    string
	To      string
	Message string
	Data    map[string]string
}

// eventTypeName converts internal kind/from/to strings or existing EventType
// names to the typed EventType.
func eventTypeName(kind, from, to string) EventType {
	switch kind {
	case string(EvServiceStopped), string(EvServiceRecovered),
		string(EvHealthDegraded), string(EvHealthRecovered),
		string(EvNodeOffline), string(EvNodeOnline),
		string(EvWallpaperSyncStarted), string(EvWallpaperSyncCompleted),
		string(EvWallpaperSyncFailed),
		string(EvInternetSourceNew), string(EvInternetSourceChanged),
		string(EvInternetSourceError), string(EvInternetSourceRecovered):
		return EventType(kind)
	case "service":
		if from == "running" && to == "stopped" {
			return EvServiceStopped
		}
		if (from == "stopped" || from == "unknown") && to == "running" {
			return EvServiceRecovered
		}
		if to == "stopped" {
			return EvServiceStopped
		}
		if to == "running" {
			return EvServiceRecovered
		}
		return EvServiceStopped
	case "health":
		if from == "healthy" && to == "degraded" {
			return EvHealthDegraded
		}
		if from == "degraded" && to == "healthy" {
			return EvHealthRecovered
		}
		if to == "degraded" {
			return EvHealthDegraded
		}
		if to == "healthy" {
			return EvHealthRecovered
		}
		return EvHealthDegraded
	case "node":
		if from == "true" && to == "false" {
			return EvNodeOffline
		}
		if from == "false" && to == "true" {
			return EvNodeOnline
		}
		if to == "false" {
			return EvNodeOffline
		}
		if to == "true" {
			return EvNodeOnline
		}
		return EvNodeOffline
	default:
		return ""
	}
}
