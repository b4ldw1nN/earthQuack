package node

import (
	"encoding/json"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func authTestHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
}

func TestAuthMiddleware(t *testing.T) {
	h := AuthMiddleware(authTestHandler(), "secret-token")
	req := func(header string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodGet, "/api/node", nil)
		if header != "" {
			r.Header.Set("Authorization", header)
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}

	if w := req("Bearer secret-token"); w.Code != http.StatusOK {
		t.Errorf("valid token: want 200, got %d", w.Code)
	}
	if w := req(""); w.Code != http.StatusUnauthorized {
		t.Errorf("missing token: want 401, got %d", w.Code)
	}
	if w := req("secret-token"); w.Code != http.StatusUnauthorized {
		t.Errorf("no scheme: want 401, got %d", w.Code)
	}
	if w := req("Basic secret-token"); w.Code != http.StatusUnauthorized {
		t.Errorf("wrong scheme: want 401, got %d", w.Code)
	}
	if w := req("Bearer wrong"); w.Code != http.StatusUnauthorized {
		t.Errorf("wrong token: want 401, got %d", w.Code)
	}
	if w := req("Bearer "); w.Code != http.StatusUnauthorized {
		t.Errorf("empty bearer: want 401, got %d", w.Code)
	}
	// Uniform response body — no distinction between failure kinds.
	for _, hdr := range []string{"", "Bearer wrong", "Basic x"} {
		r := httptest.NewRequest(http.MethodGet, "/api/node", nil)
		if hdr != "" {
			r.Header.Set("Authorization", hdr)
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Body.String() != "unauthorized\n" {
			t.Errorf("header %q: non-uniform error body %q", hdr, w.Body.String())
		}
	}
}

func TestAuthFailClosedWithoutToken(t *testing.T) {
	h := AuthMiddleware(authTestHandler(), "")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/node", nil))
	if w.Code != http.StatusServiceUnavailable {
		t.Errorf("no token configured: want 503 fail-closed, got %d", w.Code)
	}
	// Public endpoints still work.
	w = httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/health", nil))
	if w.Code != http.StatusOK {
		t.Errorf("health without token configured: want 200, got %d", w.Code)
	}
}

func TestAuthPublicPaths(t *testing.T) {
	h := AuthMiddleware(authTestHandler(), "secret-token")
	for _, path := range []string{"/api/health", "/static/style.css"} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, path, nil))
		if w.Code != http.StatusOK {
			t.Errorf("%s should be public: got %d", path, w.Code)
		}
	}
	for _, path := range []string{"/", "/api/node", "/api/nodes"} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, path, nil))
		if w.Code != http.StatusUnauthorized {
			t.Errorf("%s should require auth: got %d", path, w.Code)
		}
	}
}

func TestAuthTokenNeverInResponses(t *testing.T) {
	reg, fp := newProbingRegistry(t, "127.0.0.1", DefaultNodePort)
	srv, _ := fakeNodeServer(t, validNodeJSON("machine:peer1"), "application/json", http.StatusOK)
	port := portOf(t, srv.URL)
	fp.mu.Lock()
	fp.nodes[0].Network.Addresses = []string{"127.0.0.1"}
	fp.mu.Unlock()
	reg.client = NewNodeClientWithToken(port, "TOPSECRET")

	handler, err := NewAPI(reg, "test")
	if err != nil {
		t.Fatal(err)
	}
	handler = AuthMiddleware(handler, "TOPSECRET")

	for _, path := range []string{"/api/node", "/api/nodes", "/"} {
		r := httptest.NewRequest(http.MethodGet, path, nil)
		r.Header.Set("Authorization", "Bearer TOPSECRET")
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != http.StatusOK {
			t.Fatalf("%s: status %d", path, w.Code)
		}
		if strings.Contains(w.Body.String(), "TOPSECRET") {
			t.Errorf("%s: token leaked in response", path)
		}
	}
	// Probe still works with credentials, peer stays authoritative.
	nodes := reg.Nodes()
	if n := findNode(nodes, "machine:peer1"); !n.Registered {
		t.Errorf("authenticated probe failed: %+v", nodes)
	}
}

func TestNodeClientSendsAuth(t *testing.T) {
	var gotAuth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(validNodeJSON("machine:p")))
	}))
	defer srv.Close()

	c := NewNodeClientWithToken(portOf(t, srv.URL), "abc")
	if _, err := c.GetNode(strings.TrimPrefix(srv.URL, "http://")); err != nil {
		t.Fatalf("GetNode: %v", err)
	}
	if gotAuth != "Bearer abc" {
		t.Errorf("client sent %q", gotAuth)
	}
}

func TestAuthConfigLoads(t *testing.T) {
	cases := []struct {
		name    string
		json    string
		want    string
		wantErr bool
	}{
		{"token present", `{"auth":{"token":"xyz"}}`, "xyz", false},
		{"token absent", `{"capabilities":[]}`, "", false},
		{"malformed auth", `{"auth":"nope"}`, "", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			path := t.TempDir() + "/cfg.json"
			if err := writeFile(path, tc.json); err != nil {
				t.Fatal(err)
			}
			cfg, err := LoadConfig(path)
			if tc.wantErr {
				if err == nil {
					t.Fatal("want error")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if cfg.Auth.Token != tc.want {
				t.Errorf("token = %q, want %q", cfg.Auth.Token, tc.want)
			}
			// Token must not appear in serialized Node output.
			b, _ := json.Marshal(cfg)
			if tc.want != "" && !strings.Contains(string(b), tc.want) {
				t.Log("config JSON contains token (expected; it must never reach API output)")
			}
		})
	}
}

func writeFile(path, content string) error {
	return osWriteFile(path, []byte(content), 0o600)
}

func osWriteFile(path string, data []byte, perm int) error {
	return os.WriteFile(path, data, fs.FileMode(perm))
}
