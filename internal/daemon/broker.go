package daemon

import (
	"encoding/json"
	"fmt"
	"sync"
)

// brokerQueue is how many events may be buffered per subscriber before it
// is considered stalled and disconnected.
//
// The Python broker wrote straight to each socket while holding no lock,
// so one slow reader blocked the publisher for every other client. Here
// publishing never blocks: a subscriber that cannot keep up is dropped and
// its client reconnects, which is what the SSE contract expects anyway
// (the phone's loop reconnects with backoff).
const brokerQueue = 64

// subscriber is one connected SSE reader.
type subscriber struct {
	events chan []byte
	closed chan struct{}
	once   sync.Once
}

func (s *subscriber) close() {
	s.once.Do(func() { close(s.closed) })
}

// Broker fans events out to connected Server-Sent Events readers.
//
// It replaces daemon/core/events.py. Event framing is identical:
// "event: <type>\ndata: <json>\n\n", which is what both the Python
// desktop bridge and the Android app parse.
type Broker struct {
	mu     sync.Mutex
	subs   map[int]*subscriber
	nextID int
}

// NewBroker returns an empty broker.
func NewBroker() *Broker {
	return &Broker{subs: make(map[int]*subscriber)}
}

// Subscribe registers a reader.
//
// It returns three things: a channel of pre-framed event blocks, a channel
// closed when the broker drops this subscriber (because it fell too far
// behind), and a cancel function the reader must call when it goes away.
// Cancelling twice is safe.
//
// The drop signal is a separate channel rather than a closed event channel
// on purpose. Publishing copies its subscriber list under the lock and then
// writes outside it, so a write can be in flight while another goroutine
// drops the same subscriber; closing the event channel underneath an
// in-flight send would panic. A dedicated channel removes that race.
func (b *Broker) Subscribe() (<-chan []byte, <-chan struct{}, func()) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.nextID++
	id := b.nextID
	sub := &subscriber{
		events: make(chan []byte, brokerQueue),
		closed: make(chan struct{}),
	}
	b.subs[id] = sub

	var once sync.Once
	cancel := func() {
		once.Do(func() {
			b.mu.Lock()
			if cur, ok := b.subs[id]; ok && cur == sub {
				delete(b.subs, id)
			}
			b.mu.Unlock()
			sub.close()
		})
	}
	return sub.events, sub.closed, cancel
}

// Subscribers reports the current reader count, mirroring EventBroker.count
// and serving the /health payload.
func (b *Broker) Subscribers() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.subs)
}

// Publish delivers an event to every current subscriber.
//
// Subscribers whose buffer is full are disconnected rather than blocking
// the publisher, so a stalled reader cannot stall the others. The event is
// still delivered to everyone who is keeping up.
func (b *Broker) Publish(eventType string, data any) error {
	payload, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("broker: marshal %s event: %w", eventType, err)
	}
	frame := make([]byte, 0, len(eventType)+len(payload)+16)
	frame = append(frame, "event: "...)
	frame = append(frame, eventType...)
	frame = append(frame, "\ndata: "...)
	frame = append(frame, payload...)
	frame = append(frame, '\n', '\n')

	b.mu.Lock()
	targets := make([]*subscriber, 0, len(b.subs))
	for _, sub := range b.subs {
		targets = append(targets, sub)
	}
	b.mu.Unlock()

	var stalled []*subscriber
	for _, sub := range targets {
		select {
		case sub.events <- frame:
		default:
			stalled = append(stalled, sub)
		}
	}
	for _, sub := range stalled {
		sub.close()
	}
	return nil
}

// keepalive is the SSE comment the Python server sent every 15 seconds to
// keep proxies and the phone's read timeout from closing an idle stream.
// It is a comment line, so conformant SSE clients ignore it — the Android
// client explicitly skips lines starting with ':'.
const keepalive = ": keepalive\n\n"
