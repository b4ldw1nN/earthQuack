package node

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// ptr50 returns a heap float pointer so tests can set a CPUPercent.
func ptr50() *float64 {
	v := 50.0
	return &v
}

func TestRegistryHistoryLocalOnly(t *testing.T) {
	reg, err := NewRegistry("machine:h", []NetworkProvider{&fakeProvider{}}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	reg.History().AddSample(MetricSample{Time: time.Now(), MemUsed: 1})
	if got := reg.History().Len(); got != 1 {
		t.Fatalf("local history len = %d", got)
	}
	samples, _ := reg.LocalHistory(0)
	if len(samples) != 1 {
		t.Fatalf("LocalHistory = %d", len(samples))
	}
}

func TestHistoryAPI(t *testing.T) {
	reg, err := NewRegistry("machine:h", nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	reg.SetStorageInfoProvider(fakeStorageProvider{})
	reg.SetNetworkStatsProvider(fakeNetworkStatsProvider{})
	handler, err := NewAPI(reg, "test")
	if err != nil {
		t.Fatal(err)
	}
	get := func(path string) *httptest.ResponseRecorder {
		t.Helper()
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, httptest.NewRequest(http.MethodGet, path, nil))
		return w
	}
	w := get("/api/history")
	if w.Code != http.StatusOK {
		t.Fatalf("empty history status %d", w.Code)
	}
	var empty historyResponse
	if err := json.Unmarshal([]byte(w.Body.String()), &empty); err != nil {
		t.Fatal(err)
	}
	if len(empty.Samples) != 0 {
		t.Fatalf("empty history: %+v", empty.Samples)
	}

	reg.History().AddSample(MetricSample{Time: time.Now(), MemUsed: 99, MemTotal: 200})
	reg.History().AddEvent(HistoryEvent{Kind: "health", From: "healthy", To: "degraded"})
	w = get("/api/history")
	if w.Code != http.StatusOK {
		t.Fatalf("populated history status %d", w.Code)
	}
	var pop historyResponse
	if err := json.Unmarshal([]byte(w.Body.String()), &pop); err != nil {
		t.Fatal(err)
	}
	if len(pop.Samples) != 1 || pop.Samples[0].MemUsed != 99 {
		t.Fatalf("populated samples wrong: %+v", pop.Samples)
	}
	if len(pop.Events) != 1 || pop.Events[0].To != "degraded" {
		t.Fatalf("events wrong: %+v", pop.Events)
	}

	reg.History().AddSample(MetricSample{Time: time.Now(), MemUsed: 1, MemTotal: 2, CPUPercent: ptr50()})
	w = get("/api/history")
	var pct historyResponse
	if err := json.Unmarshal([]byte(w.Body.String()), &pct); err != nil {
		t.Fatal(err)
	}
	foundPct := false
	for _, ss := range pct.Samples {
		if ss.CPUPercent != nil && *ss.CPUPercent == 50 {
			foundPct = true
		}
	}
	if !foundPct {
		t.Fatalf("cpu_percent not serialized: %+v", pct.Samples)
	}
}

func TestHistoryAPIAuth(t *testing.T) {
	reg, err := NewRegistry("machine:h", nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	h, err := NewServer(reg, "test", ServerAuthConfig{Token: "tok"})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	if r, err := http.Get(srv.URL + "/api/history"); err == nil {
		io.Copy(io.Discard, r.Body)
		r.Body.Close()
		if r.StatusCode != http.StatusUnauthorized {
			t.Fatalf("history without bearer: %d", r.StatusCode)
		}
	}
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/history", nil)
	req.Header.Set("Authorization", "Bearer tok")
	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("history with bearer: %d", resp.StatusCode)
	}
}

func TestEventsAPI(t *testing.T) {
	reg, err := NewRegistry("machine:h", nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	reg.SetStorageInfoProvider(fakeStorageProvider{})
	reg.SetNetworkStatsProvider(fakeNetworkStatsProvider{})
	handler, err := NewAPI(reg, "test")
	if err != nil {
		t.Fatal(err)
	}
	get := func(path string) *httptest.ResponseRecorder {
		t.Helper()
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, httptest.NewRequest(http.MethodGet, path, nil))
		return w
	}
	w := get("/api/events")
	if w.Code != http.StatusOK {
		t.Fatalf("empty events status %d", w.Code)
	}
	var empty eventsResponse
	if err := json.Unmarshal([]byte(w.Body.String()), &empty); err != nil {
		t.Fatal(err)
	}
	if len(empty.Events) != 0 {
		t.Fatalf("empty events: %+v", empty.Events)
	}

	reg.History().AddEvent(HistoryEvent{Kind: "service", Name: "clipboard", From: "running", To: "stopped"})
	reg.History().AddEvent(HistoryEvent{Kind: "health", From: "healthy", To: "degraded"})

	w = get("/api/events")
	if w.Code != http.StatusOK {
		t.Fatalf("populated events status %d", w.Code)
	}
	var pop eventsResponse
	if err := json.Unmarshal([]byte(w.Body.String()), &pop); err != nil {
		t.Fatal(err)
	}
	if len(pop.Events) != 2 {
		t.Fatalf("want 2 events, got %d: %+v", len(pop.Events), pop.Events)
	}
	// Newest first: health.degraded, then service.stopped
	if pop.Events[0].Type != EvHealthDegraded {
		t.Errorf("first event want %s, got %s", EvHealthDegraded, pop.Events[0].Type)
	}
	if pop.Events[1].Type != EvServiceStopped {
		t.Errorf("second event want %s, got %s", EvServiceStopped, pop.Events[1].Type)
	}
}

func TestEventsAPIAuth(t *testing.T) {
	reg, err := NewRegistry("machine:h", nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	h, err := NewServer(reg, "test", ServerAuthConfig{Token: "tok"})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()

	// 1. Missing bearer -> 401 Unauthorized
	if r, err := http.Get(srv.URL + "/api/events"); err == nil {
		io.Copy(io.Discard, r.Body)
		r.Body.Close()
		if r.StatusCode != http.StatusUnauthorized {
			t.Fatalf("events without bearer: %d", r.StatusCode)
		}
	}

	// 2. Health endpoint remains public
	if r, err := http.Get(srv.URL + "/api/health"); err == nil {
		io.Copy(io.Discard, r.Body)
		r.Body.Close()
		if r.StatusCode != http.StatusOK {
			t.Fatalf("health status: %d", r.StatusCode)
		}
	}

	// 3. Valid bearer token -> 200 OK
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/events", nil)
	req.Header.Set("Authorization", "Bearer tok")
	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("events with bearer: %d", resp.StatusCode)
	}
}

func TestEventsAPILimitsAndOrdering(t *testing.T) {
	reg, err := NewRegistry("machine:h", nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	handler, err := NewAPI(reg, "test")
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 10; i++ {
		reg.History().AddEvent(HistoryEvent{Kind: "health", From: "healthy", To: "degraded", Message: "msg"})
	}

	get := func(path string) eventsResponse {
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, httptest.NewRequest(http.MethodGet, path, nil))
		var res eventsResponse
		_ = json.Unmarshal([]byte(w.Body.String()), &res)
		return res
	}

	// Default limit 50 -> returns all 10
	res := get("/api/events")
	if len(res.Events) != 10 {
		t.Fatalf("want 10 events, got %d", len(res.Events))
	}

	// Limit 3 -> returns 3
	res3 := get("/api/events?limit=3")
	if len(res3.Events) != 3 {
		t.Fatalf("want 3 events, got %d", len(res3.Events))
	}

	// Invalid limit -> fallback to default 50
	resInv := get("/api/events?limit=abc")
	if len(resInv.Events) != 10 {
		t.Fatalf("want 10 events on invalid limit, got %d", len(resInv.Events))
	}
}
