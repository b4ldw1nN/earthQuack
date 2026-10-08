package internet

import (
	"testing"
)

func mustFingerprint(t *testing.T, typ SourceType, body string) string {
	t.Helper()
	fp, err := Fingerprint(typ, []byte(body))
	if err != nil {
		t.Fatalf("fingerprint: %v", err)
	}
	if len(fp) != 64 {
		t.Fatalf("fingerprint length = %d, want 64", len(fp))
	}
	return fp
}

func TestFingerprintDeterministic(t *testing.T) {
	body := []byte("hello\nworld")
	first := mustFingerprint(t, TypeHTTP, string(body))
	second := mustFingerprint(t, TypeHTTP, string(body))
	if first != second {
		t.Fatalf("same input produced different fingerprints: %s vs %s", first, second)
	}
}

func TestFingerprintTypeTagged(t *testing.T) {
	body := "the same bytes"
	httpFP := mustFingerprint(t, TypeHTTP, body)
	rssType := mustFingerprint(t, TypeRSS, "<rss version=\"2.0\"><channel><title>t</title></channel></rss>")
	if httpFP == rssType {
		t.Fatal("http and rss fingerprints must not be comparable")
	}
}

func TestFingerprintDetectsChanges(t *testing.T) {
	a := mustFingerprint(t, TypeHTTP, "version one\n")
	b := mustFingerprint(t, TypeHTTP, "version two\n")
	if a == b {
		t.Fatal("different content produced identical fingerprints")
	}
}

func TestHTTPWhitespaceOnlyIsNotAChange(t *testing.T) {
	a := mustFingerprint(t, TypeHTTP, "title\nbody line   \n")
	b := mustFingerprint(t, TypeHTTP, "title\r\nbody line\t\n\n\n")
	if a != b {
		t.Fatalf("whitespace-only differences must not change the fingerprint:\n%s\n%s", a, b)
	}
}

func TestHTTPVolatileLineStillChanges(t *testing.T) {
	// A page embedding its own request timestamp changes truthfully.
	a := mustFingerprint(t, TypeHTTP, "served at 10:00\n")
	b := mustFingerprint(t, TypeHTTP, "served at 10:15\n")
	if a == b {
		t.Fatal("different content must produce different fingerprints")
	}
}

func TestRSSIgnoresVolatileMetadata(t *testing.T) {
	feed := func(buildDate, ttl string) string {
		return `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0">
<channel>
<title>Example feed</title>
<lastBuildDate>` + buildDate + `</lastBuildDate>
<ttl>` + ttl + `</ttl>
<generator>v2</generator>
<item><guid>1</guid><title>Post one</title><link>https://example.com/1</link><pubDate>Mon, 01 Jan 2024 00:00:00 GMT</pubDate></item>
</channel>
</rss>`
	}
	a := mustFingerprint(t, TypeRSS, feed("Mon, 01 Jan 2024 00:00:00 GMT", "60"))
	b := mustFingerprint(t, TypeRSS, feed("Mon, 01 Jan 2024 00:05:00 GMT", "120"))
	if a != b {
		t.Fatal("volatile feed metadata must not change the fingerprint")
	}
}

func TestRSSContentChangeDetected(t *testing.T) {
	before := `<?xml version="1.0"?><rss version="2.0"><channel><title>f</title>` +
		`<item><guid>1</guid><title>One</title></item></channel></rss>`
	after := `<?xml version="1.0"?><rss version="2.0"><channel><title>f</title>` +
		`<item><guid>1</guid><title>One, updated</title></item>` +
		`<item><guid>2</guid><title>Two</title></item></channel></rss>`
	if mustFingerprint(t, TypeRSS, before) == mustFingerprint(t, TypeRSS, after) {
		t.Fatal("a new/updated item must change the fingerprint")
	}
}

func TestRSSItemReorderIsNotAChange(t *testing.T) {
	a := `<?xml version="1.0"?><rss version="2.0"><channel><title>f</title>` +
		`<item><guid>1</guid><title>One</title></item>` +
		`<item><guid>2</guid><title>Two</title></item></channel></rss>`
	b := `<?xml version="1.0"?><rss version="2.0"><channel><title>f</title>` +
		`<item><guid>2</guid><title>Two</title></item>` +
		`<item><guid>1</guid><title>One</title></item></channel></rss>`
	if mustFingerprint(t, TypeRSS, a) != mustFingerprint(t, TypeRSS, b) {
		t.Fatal("item reordering must not change the fingerprint")
	}
}

func TestAtomFeedParses(t *testing.T) {
	atom := `<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
<title>Atom example</title>
<updated>2024-01-01T00:00:00Z</updated>
<entry><id>1</id><title>Entry</title><link href="https://example.com/1"/><updated>2024-01-01T00:00:00Z</updated><summary>text</summary></entry>
</feed>`
	fp := mustFingerprint(t, TypeRSS, atom)

	other := `<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
<title>Atom example</title>
<updated>2024-01-02T00:00:00Z</updated>
<entry><id>1</id><title>Entry</title><link href="https://example.com/1"/><updated>2024-01-01T00:00:00Z</updated><summary>text</summary></entry>
</feed>`
	if mustFingerprint(t, TypeRSS, other) != fp {
		t.Fatal("feed-level updated timestamp must not change the fingerprint")
	}
}

func TestRSSRejectsNonFeed(t *testing.T) {
	if _, err := Fingerprint(TypeRSS, []byte("<html><body>not a feed</body></html>")); err == nil {
		t.Fatal("non-feed XML must fail normalization")
	}
	if _, err := Fingerprint(TypeRSS, []byte("this is not xml at all <")); err == nil {
		t.Fatal("malformed XML must fail normalization")
	}
	if _, err := Fingerprint("magic", []byte("x")); err == nil {
		t.Fatal("unknown source type must fail normalization")
	}
}

func TestRSSToleratesHTMLEntities(t *testing.T) {
	feed := `<?xml version="1.0"?><rss version="2.0"><channel><title>f</title>` +
		`<item><guid>1</guid><title>Fish &amp; Chips</title>` +
		`<description>1&nbsp;2</description></item></channel></rss>`
	if _, err := Fingerprint(TypeRSS, []byte(feed)); err != nil {
		t.Fatalf("common feed entity quirks must parse: %v", err)
	}
}
