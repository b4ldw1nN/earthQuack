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

//go:embed templates/*.html static/*.css
var assets embed.FS

// DashboardTemplate parses the embedded dashboard template.
func DashboardTemplate() (*template.Template, error) {
	return template.ParseFS(assets, "templates/dashboard.html")
}

// LoginTemplate parses the embedded browser login template.
func LoginTemplate() (*template.Template, error) {
	return template.ParseFS(assets, "templates/login.html")
}

// StyleSheet returns the raw embedded dashboard stylesheet.
func StyleSheet() ([]byte, error) {
	return assets.ReadFile("static/style.css")
}
