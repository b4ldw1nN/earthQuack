// Command earthquakes-node exposes the earthQuack Node API.
//
// It is the Go foundation for the future earthQuack core. It serves
// read-only endpoints describing this machine as a Node and the peers
// discovered via Tailscale. It runs alongside the existing Python
// clipboard/file-transfer daemon (ports 8875/8876) and defaults to
// port 8890 on the same host.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/b4ldw1nN/earthquack/internal/node"
)

const version = "0.1.0"

func main() {
	host := flag.String("host", envOr("EARTHQUACK_NODE_HOST", "0.0.0.0"), "bind host")
	port := flag.Int("port", envIntOr("EARTHQUACK_NODE_PORT", 8890), "bind port")
	configPath := flag.String("config", envOr("EARTHQUACK_NODE_CONFIG", ""),
		"optional JSON file declaring this node's capabilities/services")
	flag.Parse()

	// Node declarations come from config when provided, otherwise the
	// built-in defaults below. Configuration is declarations only:
	// identity, hostname, OS, network, and service status are runtime
	// state and are never configurable.
	specs := []node.LocalServiceSpec{
		{Capability: "clipboard", Name: "clipboard", Port: 8875, Version: "0.1.0"},
		{Capability: "file-transfer", Name: "file-transfer", Port: 8876, Version: "0.1.0"},
	}
	var cfg *node.Config
	if *configPath != "" {
		var err error
		cfg, err = node.LoadConfig(*configPath)
		if err != nil {
			log.Fatalf("config: %v", err)
		}
		if cfg != nil {
			specs = cfg.Services
			log.Printf("config: loaded %d capabilities, %d services from %s",
				len(cfg.Capabilities), len(cfg.Services), *configPath)
		}
	}

	// Token precedence: EARTHQUACK_AUTH_TOKEN env var wins over the
	// config file, so the secret never has to be stored in the repo.
	// An empty token fails closed: protected endpoints return 503
	// instead of serving unauthenticated.
	authToken := os.Getenv("EARTHQUACK_AUTH_TOKEN")
	if authToken == "" && cfg != nil {
		authToken = cfg.Auth.Token
	}
	if authToken != "" {
		log.Printf("auth: bearer token configured (env=%v, file=%v)",
			os.Getenv("EARTHQUACK_AUTH_TOKEN") != "",
			cfg != nil && cfg.Auth.Token != "")
	} else {
		log.Printf("auth: NO token configured - protected endpoints will fail closed (503)")
	}

	identity, err := node.ResolveLocalIdentity()
	if err != nil {
		log.Fatalf("node identity: %v", err)
	}

	// ── Python daemon supervisor ───────────────────────────────────────
	// The Go node is the single entry point: it owns the earthQuack
	// daemon (app.py) that serves clipboard (8875) and file-transfer
	// (8876). Start it before the refresher so those ports are up when
	// registration happens. AES key MUST match the Android app or
	// clipboard won't decrypt to plaintext there.
	repoRoot := envOr("EARTHQUACK_REPO", ".")
	daemonMgr := node.NewDaemonManager(node.PythonDaemonConfig{
		RepoDir:       repoRoot + "/daemon",
		Host:          envOr("EARTHQUACK_HOST", "127.0.0.1"),
		ClipboardPort: fmt.Sprintf("%d", envIntOr("EARTHQUACK_PORT", 8875)),
		FilePort:      fmt.Sprintf("%d", envIntOr("EARTHQUACK_FILE_PORT", 8876)),
		AESKey:        os.Getenv("CLIPBOARD_AES_KEY"),
	})
	if err := daemonMgr.Start(); err != nil {
		log.Printf("earthquack: failed to start python daemon: %v (continuing)", err)
	}

	ts := node.NewTailscaleProvider()
	client := node.NewNodeClientWithToken(*port, authToken)
	reg, err := node.NewRegistry(identity, []node.NetworkProvider{ts}, nil, client)
	if err != nil {
		log.Fatalf("registry: %v", err)
	}

	// Best-effort: describe local Tailscale presence (transport info
	// only — never identity).
	if _, addrs, err := ts.SelfInfo(); err == nil {
		reg.SetLocalNetwork(node.NetworkInfo{Transport: "tailscale", Addresses: addrs})
	}

	// Register declared capabilities/services. A new service is added
	// by declaring a spec (config or defaults) — Registry, API,
	// dashboard and peer probing need no changes.
	// Services are probed on the local node's own address: the Python
	// daemons bind to the Tailscale IP rather than loopback.
	// Register declared capabilities/services. A new service is added
	// by declaring a spec (config or defaults) — Registry, API,
	// dashboard and peer probing need no changes. Registration is
	// declaration-only; the refresher determines runtime status.
	for _, spec := range specs {
		capName := spec.Capability
		if capName == "" {
			capName = spec.Name
		}
		reg.RegisterCapability(capName)
		reg.RegisterService(node.Service{Name: spec.Name, Status: node.ServiceUnknown, Version: spec.Version})
	}
	// Standalone capabilities declared in config but not backed by a
	// local service (e.g. a node that provides something another
	// earthQuack component implements).
	if cfg != nil {
		for _, capName := range cfg.Capabilities {
			reg.RegisterCapability(capName)
		}
	}
	probeHost := "127.0.0.1"
	if local := reg.Local(); len(local.Network.Addresses) > 0 {
		probeHost = local.Network.Addresses[0]
	}

	// Lifecycle: SIGINT/SIGTERM cancels the context, which stops the
	// local service refresh loop and shuts the HTTP server down.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Local service status: the refresher probes immediately on Run
	// and then gently on a ticker (DefaultRefreshInterval). It is
	// update-only — it can never create services or capabilities.
	// API/dashboard read registry state; they never probe.
	refresher := node.NewServiceRefresher(reg, specs, probeHost, 500*time.Millisecond, 0)
	go refresher.Run(ctx)

	// Supervise the python daemon for its whole lifetime.
	go daemonMgr.BeginRestartLoop(ctx)

	addr := net.JoinHostPort(*host, fmt.Sprint(*port))

	// Secure cookie is opt-in: the dashboard is served over http on the
	// Tailscale address by default, where a Secure flag would block the
	// cookie. Enable via EARTHQUACK_SECURE_COOKIE=1 once TLS is present.
	secureCookie := os.Getenv("EARTHQUACK_SECURE_COOKIE") == "1"
	handler, err := node.NewServer(reg, version, node.ServerAuthConfig{
		Token:        authToken,
		SecureCookie: secureCookie,
	})
	if err != nil {
		log.Fatalf("api: %v", err)
	}
	// Authentication boundaries are applied inside NewServer:
	//   /api/*        — bearer token (fail-closed)
	//   /static/*     — public
	//   / (browser)   — session cookie + login page
	srv := &http.Server{Addr: addr, Handler: handler}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdownCtx)
		daemonMgr.Stop()
	}()
	log.Printf("earthQuack node %s listening on http://%s", version, addr)
	log.Printf("identity: %s", identity)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("server: %v", err)
	}
	daemonMgr.Stop()
	log.Printf("earthQuack node stopped cleanly")
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envIntOr(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		var n int
		if _, err := fmt.Sscanf(v, "%d", &n); err == nil {
			return n
		}
	}
	return def
}
