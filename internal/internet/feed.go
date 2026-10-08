package internet

import (
	"bytes"
	"encoding/xml"
	"fmt"
	"sort"
	"strings"
)

// Feed parsing: RSS 2.0 and Atom 1.0, reduced to the part that carries
// meaning — the items. Feed-level volatile metadata (lastBuildDate,
// pubDate, generator, ttl, updated, docs) is deliberately NOT part of
// the canonical form: a feed that refreshes its build timestamp without
// publishing anything new is unchanged.
//
// Item order is also deliberately NOT part of the canonical form
// (items are sorted by their stable key), because feeds legitimately
// reorder entries without anything being new.
type feedDocument struct {
	// RSS 2.0 (and most RSS 1.0/RDF feeds in practice).
	Channel feedChannel `xml:"channel"`
	// Atom 1.0.
	AtomTitle string      `xml:"title"`
	Entries   []atomEntry `xml:"entry"`
}

type feedChannel struct {
	Title string     `xml:"title"`
	Items []feedItem `xml:"item"`
}

type feedItem struct {
	Title       string `xml:"title"`
	Link        string `xml:"link"`
	GUID        string `xml:"guid"`
	PubDate     string `xml:"pubDate"`
	Date        string `xml:"date"`
	Description string `xml:"description"`
	Summary     string `xml:"summary"`
	Content     string `xml:"encoded"` // content:encoded (namespace ignored)
	Enclosure   []encl `xml:"enclosure"`
}

type atomEntry struct {
	Title     string     `xml:"title"`
	ID        string     `xml:"id"`
	Links     []atomLink `xml:"link"`
	Published string     `xml:"published"`
	Updated   string     `xml:"updated"`
	Summary   string     `xml:"summary"`
	Content   string     `xml:"content"`
}

type atomLink struct {
	Rel  string `xml:"rel,attr"`
	Href string `xml:"href,attr"`
}

type encl struct {
	URL string `xml:"url,attr"`
}

// normalizeFeed parses a feed and renders it as a canonical, sorted
// list of items. Unknown or unsupported XML is an error — a source that
// cannot be understood must surface as ERROR, never as "unchanged".
func normalizeFeed(body []byte) ([]byte, error) {
	dec := xml.NewDecoder(bytes.NewReader(body))
	// Real-world feeds routinely contain undefined HTML entities
	// (&nbsp;, &hellip;) inside descriptions; tolerate them instead of
	// failing the whole source.
	dec.Strict = false

	var doc feedDocument
	if err := dec.Decode(&doc); err != nil {
		return nil, fmt.Errorf("internet: parse feed: %w", err)
	}

	lines := []string{"feed\t" + collapse(doc.AtomTitle+" "+doc.Channel.Title)}
	for _, it := range doc.Channel.Items {
		lines = append(lines, itemLine(
			firstNonEmpty(it.GUID, it.Link, it.Title),
			it.Title,
			firstNonEmpty(it.Link, it.EnclosureURL()),
			firstNonEmpty(it.PubDate, it.Date),
			firstNonEmpty(it.Description, it.Summary, it.Content),
		))
	}
	for _, e := range doc.Entries {
		lines = append(lines, itemLine(
			firstNonEmpty(e.ID, linkHref(e.Links), e.Title),
			e.Title,
			linkHref(e.Links),
			firstNonEmpty(e.Published, e.Updated),
			firstNonEmpty(e.Summary, e.Content),
		))
	}
	if len(lines) == 1 {
		// A document that is neither RSS nor Atom with items. It may
		// still be a valid single-item feed (e.g. a lone <entry>), so
		// only reject it when it carries no feed markers at all.
		if doc.AtomTitle == "" && doc.Channel.Title == "" {
			return nil, fmt.Errorf("internet: parse feed: not an RSS or Atom feed")
		}
	}
	items := lines[1:]
	sort.Strings(items)
	return []byte(strings.Join(append([]string{lines[0]}, items...), "\n")), nil
}

// EnclosureURL returns the first enclosure URL, if any.
func (i feedItem) EnclosureURL() string {
	if len(i.Enclosure) == 0 {
		return ""
	}
	return i.Enclosure[0].URL
}

// itemLine renders one canonical item line: key, title, link, date,
// body — tab-separated, with every field whitespace-collapsed so
// formatting differences never register as content changes.
func itemLine(key, title, link, date, body string) string {
	return strings.Join([]string{
		"item",
		collapse(key),
		collapse(title),
		collapse(link),
		collapse(date),
		collapse(body),
	}, "\t")
}

// linkHref picks the alternate link (or the only link) from a set of
// atom-style links, deterministically.
func linkHref(links []atomLink) string {
	for _, l := range links {
		if l.Rel == "alternate" {
			return l.Href
		}
	}
	for _, l := range links {
		if l.Rel == "" {
			return l.Href
		}
	}
	if len(links) > 0 {
		return links[0].Href
	}
	return ""
}

// collapse trims a field and collapses all runs of whitespace into a
// single space, so line wrapping and indentation are never meaningful.
func collapse(s string) string {
	return strings.Join(strings.Fields(s), " ")
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}
