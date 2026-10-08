package internet

import (
	"fmt"
	"net/url"
	"regexp"
	"strings"
)

// SourceType is the kind of Internet source. The type selects the
// normalizer used to build the fingerprint, nothing else: fetching is
// always the same bounded HTTP request. New types are added by
// extending Normalize, not by adding a second fetch path.
type SourceType string

const (
	// TypeHTTP is a generic HTTP/HTTPS endpoint (a web page, a JSON or
	// text document, an API response). The fingerprint covers the
	// response body itself.
	TypeHTTP SourceType = "http"
	// TypeRSS is an RSS 2.0 or Atom feed. The fingerprint covers the
	// normalized feed items, so feed-level volatile metadata
	// (lastBuildDate, generator, ttl) and item reordering do not
	// register as changes.
	TypeRSS SourceType = "rss"
)

// SourceTypes lists the supported source types in display order.
var SourceTypes = []SourceType{TypeHTTP, TypeRSS}

// Source is one observed Internet endpoint. It is a declaration: what
// to look at, how often, and whether it is active. Nothing observed is
// stored here — that lives in Observation, so configuration and
// measured state never mix.
type Source struct {
	// ID is the stable, unique, human-chosen identifier used by the
	// CLI, the API and events.
	ID string `json:"id"`
	// Name is the display name. Empty means "use ID".
	Name string `json:"name,omitempty"`
	// Type selects the normalizer (http, rss).
	Type SourceType `json:"type"`
	// URL is the endpoint to fetch. Only explicitly configured URLs
	// are ever fetched; links inside a response are never followed.
	URL string `json:"url"`
	// Interval overrides the module's default poll interval. Zero
	// means "use the module default".
	Interval Duration `json:"interval,omitempty"`
	// Enabled reports whether the source is polled. A disabled source
	// keeps its history but is skipped.
	Enabled bool `json:"enabled"`
	// Metadata is optional operator-supplied context (category,
	// labels, owner, ...). It is never part of the fingerprint.
	Metadata map[string]string `json:"metadata,omitempty"`
}

// EffectiveInterval returns the interval to poll this source at, given
// the module default. It never returns less than MinInterval.
func (s Source) EffectiveInterval(def Duration) Duration {
	iv := s.Interval
	if iv <= 0 {
		iv = def
	}
	if iv < Duration(MinInterval) {
		return Duration(MinInterval)
	}
	return iv
}

// DisplayName returns the name to show for the source.
func (s Source) DisplayName() string {
	if s.Name != "" {
		return s.Name
	}
	return s.ID
}

var sourceIDPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]*$`)

// Normalize fills in defaults and validates a source definition. It is
// the only place source validity is decided, so the CLI, the state file
// and the config path all agree.
func (s Source) Normalize() (Source, error) {
	s.ID = strings.TrimSpace(s.ID)
	s.Name = strings.TrimSpace(s.Name)
	s.URL = strings.TrimSpace(s.URL)
	if s.Type == "" {
		s.Type = TypeHTTP
	}
	if !sourceIDPattern.MatchString(s.ID) {
		return Source{}, fmt.Errorf("internet: invalid source id %q (use letters, digits, dot, dash, underscore)", s.ID)
	}
	if !s.Type.Valid() {
		return Source{}, fmt.Errorf("internet: source %q: unsupported type %q (supported: %s)", s.ID, s.Type, supportedTypes())
	}
	u, err := url.Parse(s.URL)
	if err != nil {
		return Source{}, fmt.Errorf("internet: source %q: invalid url: %w", s.ID, err)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return Source{}, fmt.Errorf("internet: source %q: url scheme must be http or https", s.ID)
	}
	if u.Host == "" {
		return Source{}, fmt.Errorf("internet: source %q: url has no host", s.ID)
	}
	if s.Interval < 0 {
		return Source{}, fmt.Errorf("internet: source %q: interval cannot be negative", s.ID)
	}
	if s.Interval > 0 && s.Interval < Duration(MinInterval) {
		return Source{}, fmt.Errorf("internet: source %q: interval %s is below the %s minimum",
			s.ID, s.Interval, Duration(MinInterval))
	}
	return s, nil
}

// Valid reports whether the type is supported.
func (t SourceType) Valid() bool {
	for _, known := range SourceTypes {
		if t == known {
			return true
		}
	}
	return false
}

func supportedTypes() string {
	names := make([]string, 0, len(SourceTypes))
	for _, t := range SourceTypes {
		names = append(names, string(t))
	}
	return strings.Join(names, ", ")
}
