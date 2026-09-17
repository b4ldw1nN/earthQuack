package node

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"log"
	"net/http"
)

// ManagedState describes lifecycle state independently of port health.
type ManagedState struct {
	Running bool
	Message string
}

// ManagedService is a startup-only allowlist entry, never a client command.
// Multiple declared services can share a single lifecycle controller.
type ManagedService struct {
	ID          string
	Name        string
	Description string
	Start       func() error
	Stop        func()
	Snapshot    func() ManagedState
}

type managedView struct {
	ID          string
	Name        string
	Description string
	ManagedState
}

type controlsView struct {
	Services []managedView
	CSRF     string
	Notice   string
}

type controlsKey struct{}

type serviceControls struct {
	services []ManagedService
	sessions *SessionStore
	secret   [32]byte
}

func newServiceControls(services []ManagedService, sessions *SessionStore) (*serviceControls, error) {
	c := &serviceControls{services: append([]ManagedService(nil), services...), sessions: sessions}
	_, err := rand.Read(c.secret[:])
	return c, err
}

func (c *serviceControls) token(session string) string {
	mac := hmac.New(sha256.New, c.secret[:])
	mac.Write([]byte(session))
	return hex.EncodeToString(mac.Sum(nil))
}

func (c *serviceControls) session(r *http.Request) string {
	cookie, err := r.Cookie(sessionCookieName)
	if err != nil || !c.sessions.Valid(cookie.Value) {
		return ""
	}
	return cookie.Value
}

func (c *serviceControls) page(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session := c.session(r)
		if session == "" {
			next(w, r)
			return
		}
		view := controlsView{CSRF: c.token(session)}
		for _, svc := range c.services {
			view.Services = append(view.Services, managedView{ID: svc.ID, Name: svc.Name, Description: svc.Description, ManagedState: svc.Snapshot()})
		}
		switch r.URL.Query().Get("control") {
		case "ok":
			view.Notice = "Service action completed. Refresh to see the latest runtime status."
		case "failed":
			view.Notice = "Service action failed. Check the node logs and configuration."
		}
		w.Header().Set("Cache-Control", "no-store")
		next(w, r.WithContext(context.WithValue(r.Context(), controlsKey{}, view)))
	}
}

func (c *serviceControls) action(w http.ResponseWriter, r *http.Request) {
	// A browser session AND a session-bound CSRF token are required. Bearer
	// credentials remain read-only on this browser endpoint.
	session := c.session(r)
	if session == "" {
		http.Error(w, "browser session required", http.StatusForbidden)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	if err := r.ParseForm(); err != nil {
		http.Error(w, "invalid form", http.StatusBadRequest)
		return
	}
	if !hmac.Equal([]byte(r.PostForm.Get("csrf")), []byte(c.token(session))) {
		http.Error(w, "invalid CSRF token", http.StatusForbidden)
		return
	}
	action := r.PostForm.Get("action")
	if action != "start" && action != "stop" {
		http.Error(w, "invalid action", http.StatusBadRequest)
		return
	}
	for _, svc := range c.services {
		if svc.ID != r.PostForm.Get("service") {
			continue
		}
		result := "ok"
		if action == "start" {
			if err := svc.Start(); err != nil {
				// Do not expose provider errors/credentials in HTML or redirects.
				log.Printf("service control %s: start failed", svc.ID)
				result = "failed"
			}
		} else {
			svc.Stop()
		}
		log.Printf("service control %s: %s (%s)", svc.ID, action, result)
		http.Redirect(w, r, "/nodes?control="+result+"#managed-services", http.StatusSeeOther)
		return
	}
	http.Error(w, "service is not managed by this node", http.StatusNotFound)
}
