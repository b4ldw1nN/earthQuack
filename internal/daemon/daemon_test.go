package daemon

import (
	"strings"
	"sync"
	"testing"
	"time"
)

// TestClipboardStateVersioning pins the idempotence rule that stops the
// desktop and the phone echoing a value back and forth forever: an update
// that changes neither text nor origin must not bump the version, because
// the version is what clients use to tell "new" from "already seen".
func TestClipboardStateVersioning(t *testing.T) {
	s := NewClipboardState()

	if got := s.Get(); got.Version != 0 || got.Clipboard != "" || got.Origin != "" {
		t.Fatalf("fresh state must be empty at version 0, got %+v", got)
	}

	changed, snap := s.Update("hello", "desktop")
	if !changed || snap.Version != 1 {
		t.Fatalf("first write must change and be version 1, got changed=%v %+v", changed, snap)
	}

	// Same text and origin: no change, no bump.
	changed, snap = s.Update("hello", "desktop")
	if changed || snap.Version != 1 {
		t.Fatalf("identical rewrite must be a no-op, got changed=%v %+v", changed, snap)
	}

	// Same text, different origin: that IS a change (matches Python, which
	// compared both fields).
	changed, snap = s.Update("hello", "phone")
	if !changed || snap.Version != 2 {
		t.Fatalf("origin change must bump the version, got changed=%v %+v", changed, snap)
	}

	// Different text, same origin: also a change.
	changed, snap = s.Update("goodbye", "phone")
	if !changed || snap.Version != 3 {
		t.Fatalf("text change must bump the version, got changed=%v %+v", changed, snap)
	}

	if got := s.Get(); got.Clipboard != "goodbye" || got.Origin != "phone" || got.Version != 3 {
		t.Fatalf("Get disagrees with last Update: %+v", got)
	}
}

// TestClipboardStateIsConcurrencySafe is the regression test for the class of
// bug the Python original's threading.Lock existed to prevent and that the Go
// wallpaper module forgot entirely (unsynchronised maps => fatal error).
func TestClipboardStateIsConcurrencySafe(t *testing.T) {
	s := NewClipboardState()
	var wg sync.WaitGroup
	const writers, reads = 8, 200

	for w := 0; w < writers; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			for i := 0; i < reads; i++ {
				s.Update(string(rune('a'+w))+string(rune('0'+i%10)), "desktop")
			}
		}(w)
	}
	for r := 0; r < writers; r++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < reads; i++ {
				_ = s.Get()
			}
		}()
	}
	wg.Wait()

	// The only invariant that must hold: the version never went backwards
	// and never exceeded the number of writes.
	if got := s.Get().Version; got < 0 || got > writers*reads {
		t.Fatalf("implausible version %d after %d writes", got, writers*reads)
	}
}

// TestCryptoRoundTrip covers the enabled path, the disabled passthrough, and
// the "never lose the clipboard" rule for payloads that cannot be decrypted.
func TestCryptoRoundTrip(t *testing.T) {
	key, err := GenerateAESKey()
	if err != nil {
		t.Fatal(err)
	}
	c, err := NewCrypto(key)
	if err != nil {
		t.Fatal(err)
	}
	if !c.Enabled() {
		t.Fatal("a valid key must enable encryption")
	}

	for _, plain := range []string{"", "hello", "héllo wörld ☃", "a much longer clipboard payload " + strings.Repeat("x", 5000)} {
		wrapped := c.Wrap(plain)
		if plain != "" && !strings.HasPrefix(wrapped, AESPrefix) {
			t.Errorf("Wrap must tag ciphertext with %q, got %q", AESPrefix, wrapped[:min(20, len(wrapped))])
		}
		if got := c.Unwrap(wrapped); got != plain {
			t.Errorf("round trip failed: want %q got %q", truncate(plain), truncate(got))
		}
	}

	// Ciphertext must differ each time (fresh IV per message).
	a, b := c.Wrap("same input"), c.Wrap("same input")
	if a == b {
		t.Error("two wraps of the same plaintext must not produce identical ciphertext (IV reuse)")
	}

	// A payload we cannot decrypt is returned as-is rather than dropped or
	// replaced with empty — both existing implementations do this so a
	// wrong key degrades to "shows ciphertext" instead of "clipboard lost".
	other, err := GenerateAESKey()
	if err != nil {
		t.Fatal(err)
	}
	wrongKey, err := NewCrypto(other)
	if err != nil {
		t.Fatal(err)
	}
	foreign := wrongKey.Wrap("secret from another key")
	if got := c.Unwrap(foreign); got != foreign {
		t.Error("a payload encrypted under a different key must be returned unchanged")
	}

	// Plaintext without the prefix passes through untouched.
	if got := c.Unwrap("not encrypted at all"); got != "not encrypted at all" {
		t.Errorf("plaintext must pass through Unwrap unchanged, got %q", got)
	}
}

// TestCryptoDisabledPassthrough pins the no-key behaviour: with encryption
// off the daemon must work exactly as before rather than failing.
func TestCryptoDisabledPassthrough(t *testing.T) {
	c, err := NewCrypto("")
	if err != nil {
		t.Fatal(err)
	}
	if c.Enabled() {
		t.Fatal("no key must mean encryption disabled")
	}
	if got := c.Wrap("plain"); got != "plain" {
		t.Errorf("Wrap must pass plain through when disabled, got %q", got)
	}
	if got := c.Unwrap("plain"); got != "plain" {
		t.Errorf("Unwrap must pass plain through when disabled, got %q", got)
	}
	// An encrypted value seen with no key configured is returned as-is
	// rather than dropped.
	if got := c.Unwrap(AESPrefix + "abc"); got != AESPrefix+"abc" {
		t.Errorf("Unwrap with no key must not alter the value, got %q", got)
	}
}

// TestCryptoRejectsBadKeys ensures a typo in the key is a loud failure at
// startup, not a silent downgrade to plaintext.
func TestCryptoRejectsBadKeys(t *testing.T) {
	for _, bad := range []string{"not base64!!", "c2hvcnQ=", "aGVsbG8gd29ybGQ="} {
		if _, err := NewCrypto(bad); err == nil {
			t.Errorf("NewCrypto(%q) must fail: a wrong-size key must not silently disable encryption", bad)
		}
		if ValidAESKey(bad) {
			t.Errorf("ValidAESKey(%q) must be false", bad)
		}
	}
}

// TestGenerateAndValidateKey covers the helper the setup instructions and the
// Android app both use.
func TestGenerateAndValidateKey(t *testing.T) {
	key, err := GenerateAESKey()
	if err != nil {
		t.Fatal(err)
	}
	if !ValidAESKey(key) {
		t.Fatal("a generated key must validate")
	}
	if len(key) != 44 { // 32 bytes base64-encoded with padding
		t.Fatalf("expected a 44-char base64 key, got %d chars", len(key))
	}
	other, err := GenerateAESKey()
	if err != nil {
		t.Fatal(err)
	}
	if other == key {
		t.Fatal("two generated keys must differ")
	}
}

// TestBrokerFanOutAndFraming checks delivery to every subscriber and the
// exact wire framing, which both clients parse literally.
func TestBrokerFanOutAndFraming(t *testing.T) {
	b := NewBroker()
	a, _, cancelA := b.Subscribe()
	defer cancelA()
	bCh, _, cancelB := b.Subscribe()
	defer cancelB()

	if got := b.Subscribers(); got != 2 {
		t.Fatalf("Subscriber count = %d, want 2", got)
	}

	payload := map[string]any{"clipboard": "v", "origin": "phone", "version": 3}
	if err := b.Publish("clipboard", payload); err != nil {
		t.Fatal(err)
	}

	for name, ch := range map[string]<-chan []byte{"first": a, "second": bCh} {
		frame := readFrame(t, ch)
		if !strings.HasPrefix(frame, "event: clipboard\ndata: {") || !strings.HasSuffix(frame, "\n\n") {
			t.Errorf("subscriber %s got malformed frame %q", name, frame)
		}
	}
}

// TestBrokerCancelUnsubscribes verifies a departed reader stops receiving,
// so a disconnected phone does not accumulate as a subscriber forever.
func TestBrokerCancelUnsubscribes(t *testing.T) {
	b := NewBroker()
	ch, dropped, cancel := b.Subscribe()
	if got := b.Subscribers(); got != 1 {
		t.Fatalf("Subscriber count = %d, want 1", got)
	}
	cancel()
	if got := b.Subscribers(); got != 0 {
		t.Fatalf("after cancel Subscriber count = %d, want 0", got)
	}
	// The drop channel is closed so a handler blocked on it wakes up.
	select {
	case <-dropped:
	case <-time.After(time.Second):
		t.Fatal("cancel must close the drop channel so blocked readers wake")
	}
	// Cancelling twice must not panic (the SSE handler's defer runs on
	// every exit path).
	cancel()

	// A publish with no subscribers is a no-op, not an error.
	if err := b.Publish("clipboard", map[string]any{"x": 1}); err != nil {
		t.Fatalf("publish with no subscribers must succeed, got %v", err)
	}
	_ = ch
}

// TestBrokerDropsSlowSubscriber is the anti-stall property: a reader that
// cannot keep up is signalled and disconnected rather than allowed to block
// every other client.
//
// This matters because the Python broker wrote to each socket
// synchronously inside publish — one slow phone could stall delivery to
// every other subscriber, including the desktop bridge.
func TestBrokerDropsSlowSubscriber(t *testing.T) {
	b := NewBroker()
	// Deliberately never drained, so its buffer fills and overflows.
	_, slowDropped, _ := b.Subscribe()
	fastEvents, _, _ := b.Subscribe()

	// Overflow the slow subscriber's buffer. If Publish blocked on it this
	// loop would hang, which is the regression being guarded.
	for i := 0; i < brokerQueue+10; i++ {
		if err := b.Publish("clipboard", map[string]any{"i": i}); err != nil {
			t.Fatal(err)
		}
	}

	select {
	case <-slowDropped:
	case <-time.After(2 * time.Second):
		t.Fatal("a subscriber that overflowed its buffer must be dropped, not left blocking")
	}

	// The fast subscriber must still be receiving: the stall is isolated.
	readFrame(t, fastEvents)
}

// TestBrokerConcurrentSubscribeAndPublish guards the broker's locking under
// the one pattern the daemon actually produces: many SSE clients
// connecting and disconnecting while the desktop bridge and file service
// publish continuously.
func TestBrokerConcurrentSubscribeAndPublish(t *testing.T) {
	b := NewBroker()
	var wg sync.WaitGroup
	stop := make(chan struct{})

	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; ; i++ {
			select {
			case <-stop:
				return
			default:
			}
			if err := b.Publish("clipboard", map[string]any{"i": i}); err != nil {
				t.Errorf("publish: %v", err)
				return
			}
		}
	}()

	for w := 0; w < 8; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 100; i++ {
				_, _, cancel := b.Subscribe()
				cancel()
			}
		}()
	}
	// Let the churn finish, then stop the publisher.
	time.Sleep(200 * time.Millisecond)
	close(stop)
	wg.Wait()
}

// TestClipboardAuth covers the security change: the Python services accepted
// anyone; these require the bearer token except on /health.
func TestClipboardAuth(t *testing.T) {
	const token = "secret-token"
	srv := httptestServer(t, newClipboardHandler(NewClipboardState(), NewBroker(), token))

	t.Run("health is public", func(t *testing.T) {
		if code := statusOnly(t, "GET", srv+"/health", "", nil); code != 200 {
			t.Errorf("/health must be reachable without a token, got %d", code)
		}
	})

	for _, path := range []string{"/clipboard", "/events", "/signal"} {
		t.Run("no token on "+path, func(t *testing.T) {
			if code := statusOnly(t, "GET", srv+path, "", nil); code != 401 {
				t.Errorf("%s without a token must be 401, got %d", path, code)
			}
		})
	}

	t.Run("valid token is accepted", func(t *testing.T) {
		if code := statusOnly(t, "GET", srv+"/clipboard", token, nil); code != 200 {
			t.Errorf("/clipboard with a valid token must be 200, got %d", code)
		}
	})

	t.Run("wrong token is rejected", func(t *testing.T) {
		if code := statusOnly(t, "GET", srv+"/clipboard", "wrong", nil); code != 401 {
			t.Errorf("/clipboard with a bad token must be 401, got %d", code)
		}
	})

	t.Run("scheme is case-insensitive", func(t *testing.T) {
		req := func(scheme string) int {
			r, _ := newRequest("GET", srv+"/clipboard", nil)
			r.Header.Set("Authorization", scheme+" "+token)
			return do(t, r)
		}
		if code := req("bearer"); code != 200 {
			t.Errorf("lowercase 'bearer' must be accepted, got %d", code)
		}
		if code := req("BEARER"); code != 200 {
			t.Errorf("uppercase 'BEARER' must be accepted, got %d", code)
		}
		if code := req("Basic"); code != 401 {
			t.Errorf("a non-bearer scheme must be rejected, got %d", code)
		}
	})
}

// TestClipboardFailsClosedWithoutToken: an operator who forgets to set a
// token must get 503, never an open service. This is the bug class that made
// the Python data plane dangerous.
func TestClipboardFailsClosedWithoutToken(t *testing.T) {
	srv := httptestServer(t, newClipboardHandler(NewClipboardState(), NewBroker(), ""))
	for _, path := range []string{"/clipboard", "/events", "/signal"} {
		if code := statusOnly(t, "GET", srv+path, "", nil); code != 503 {
			t.Errorf("%s with no configured token must fail closed with 503, got %d", path, code)
		}
	}
	// /health still answers so the node's service probe sees it as up.
	if code := statusOnly(t, "GET", srv+"/health", "", nil); code != 200 {
		t.Errorf("/health must stay public even with no token, got %d", code)
	}
}

// --- small helpers ---

func truncate(s string) string {
	if len(s) > 40 {
		return s[:40] + "..."
	}
	return s
}

func readFrame(t *testing.T, ch <-chan []byte) string {
	t.Helper()
	select {
	case frame, ok := <-ch:
		if !ok {
			t.Fatal("frame channel closed unexpectedly")
		}
		return string(frame)
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for an event frame")
		return ""
	}
}
