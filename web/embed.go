// Package web renders the earthQuack dashboard.
//
// Assets are embedded into the binary with the standard embed
// package — no frontend build system. The dashboard consumes the
// Node model only; it knows nothing about Tailscale or any other
// transport.
package web

import (
	"embed"
	"html/template"
)

//go:embed templates/*.html static/*.css static/*.svg
var assets embed.FS

// pageTemplate parses the named page template followed by the shared layout
// as a single template set. The page file is listed first so the returned
// *Template is named after the page — calling Execute() runs the page's
// top-level {{template "layout" .}}, which renders the full page through
// the layout shell with the "content" and "title" blocks defined in the page.
func pageTemplate(name string) (*template.Template, error) {
	return template.ParseFS(assets, "templates/"+name+".html", "templates/layout.html")
}

// OverviewTemplate parses the layout + overview page template.
func OverviewTemplate() (*template.Template, error) { return pageTemplate("overview") }

// NodesTemplate parses the layout + nodes page template.
func NodesTemplate() (*template.Template, error) { return pageTemplate("nodes") }

// EventsTemplate parses the layout + events page template.
func EventsTemplate() (*template.Template, error) { return pageTemplate("events") }

// PeersTemplate parses the layout + peers page template.
func PeersTemplate() (*template.Template, error) { return pageTemplate("peers") }

// LoginTemplate parses the embedded browser login template.
func LoginTemplate() (*template.Template, error) {
	return template.ParseFS(assets, "templates/login.html")
}

// StyleSheet returns the raw embedded dashboard stylesheet.
func StyleSheet() ([]byte, error) {
	return assets.ReadFile("static/style.css")
}

// Background returns the raw embedded atmospheric night-scene artwork.
func Background() ([]byte, error) {
	return assets.ReadFile("static/background.svg")
}
