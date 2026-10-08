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
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/b4ldw1nN/earthquack/internal/internet"
	"github.com/b4ldw1nN/earthquack/internal/node"
	"github.com/b4ldw1nN/earthquack/internal/wallpaper"
)

const version = "0.1.0"

// wallpaperVersion mirrors the module's declared service version in the
// node registry.
const wallpaperVersion = "0.1.0"

// internetVersion mirrors the internet module's declared service version
// in the node registry.
const internetVersion = "0.1.0"

func main() {
	flag.Usage = func() {
		fmt.Fprintln(flag.CommandLine.Output(), "Usage: earthquack-node [node flags] [wallpaper|internet <command> [flags]]\n\nWithout a subcommand, starts the node and dashboard.\nNode flags:")
		flag.PrintDefaults()
		fmt.Fprintln(flag.CommandLine.Output(), "\nWallpaper commands: status, scan, sync, retry-failed, help\n  wallpaper status             Show local archive status (no uploads)\n  wallpaper sync --dry-run     Preview without changing state\n  wallpaper sync               Archive pending files\n  wallpaper retry-failed       Retry failed uploads\n  wallpaper help               Show wallpaper flags\n\nInternet Microscope commands: status, sources, add, remove, enable, disable, check, help\n  internet status              Show state dir, interval and counts\n  internet sources             List sources and their last observation\n  internet add -id X -url URL  Define a source to observe\n  internet check <source>      Observe now: new / changed / unchanged / error\n  internet help                Show internet flags\n\nBoth node and CLI load config.json by default, searching: --config, $EARTHQUACK_NODE_CONFIG,\n./config.json, config.json beside the binary, then ~/.config/earthquack/config.json — so the node\nbehaves identically from any working directory (systemd, timers, scripts, ~/.local/bin).\nNothing is created for you: put config.json in one of those places.\nUse --config /absolute/node.json before wallpaper/internet to override.\nWallpaper flags: --source <directory or colon-separated roots>, --state <directory>, --provider telegram.\nInternet flags: --state <directory> (sources.json), -type http|rss, -interval <duration>.\nEnable wallpaper or internet in config to show their frontend controls; no sync is needed.")
	}

	host := flag.String("host", envOr("EARTHQUACK_NODE_HOST", "0.0.0.0"), "bind host")
	port := flag.Int("port", envIntOr("EARTHQUACK_NODE_PORT", 8890), "bind port")
	configFlag := flag.String("config", "",
		"optional JSON file declaring this node's capabilities/services (default: $EARTHQUACK_NODE_CONFIG, ./config.json, config.json beside the binary, then ~/.config/earthquack/config.json)")
	flag.Parse()

	// The config path is resolved after parsing so the default can also
	// look beside the binary — see resolveConfigPath. This keeps every
	// declaration (including wallpaper.env_file) available no matter
	// which directory the node or CLI was started from.
	configPath := resolveConfigPath(*configFlag, flagProvided(flag.CommandLine, "config"), os.Getenv, executablePath(), userConfigDir())

	// Node declarations come from config when provided, otherwise the
	// built-in defaults below. Configuration is declarations only:
	// identity, hostname, OS, network, and service status are runtime
	// state and are never configurable.
	specs := []node.LocalServiceSpec{
		{Capability: "clipboard", Name: "clipboard", Port: 8875, Version: "0.1.0"},
		{Capability: "file-transfer", Name: "file-transfer", Port: 8876, Version: "0.1.0"},
	}
	var cfg *node.Config
	if configPath != "" {
		var err error
		cfg, err = node.LoadConfig(configPath)
		if err != nil {
			log.Fatalf("config: %v", err)
		}
		if cfg != nil {
			specs = cfg.Services
			log.Printf("config: loaded %d capabilities, %d services from %s",
				len(cfg.Capabilities), len(cfg.Services), configPath)
		}
	}

	var wallpaperConfig *node.WallpaperConfig
	if cfg != nil {
		wallpaperConfig = cfg.Wallpaper
	}
	wallpaperEnv, err := node.WallpaperEnvironment(wallpaperConfig, os.Getenv)
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	if args := flag.Args(); len(args) > 0 {
		switch args[0] {
		case "wallpaper":
			os.Exit(wallpaper.RunCLIWithIO(args[1:], os.Stdin, os.Stdout, os.Stderr, wallpaperEnv))
		case "internet":
			os.Exit(internet.RunCLIWithIO(args[1:], os.Stdout, os.Stderr, os.Getenv))
		default:
			fmt.Fprintln(os.Stderr, "unknown command:", args[0])
			os.Exit(2)
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

	// An explicit config key wins over a stale shell environment key.
	// Environment-only deployments remain supported as a fallback.
	aesKey := resolveAESKey(cfg)
	if aesKey != "" {
		source := "environment"
		if cfg != nil && cfg.ClipboardAESKey != "" {
			source = "config"
		}
		log.Printf("clipboard: AES key configured (source=%s)", source)
	} else {
		log.Printf("clipboard: NO AES key configured - clipboard travels unencrypted")
	}

	identity, err := node.ResolveLocalIdentity()
	if err != nil {
		log.Fatalf("node identity: %v", err)
	}

	// ── Sync services (clipboard 8875 + file transfer 8876) ─────────────
	// Lifecycle first: SIGINT/SIGTERM cancels ctx, which stops every
	// background loop below and shuts the HTTP server down. Establishing it
	// before anything is launched means a Ctrl-C during startup cancels
	// cleanly instead of being ignored until the listener is up.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// The node is the single entry point for the sync services. Two
	// implementations exist and are selected by EARTHQUACK_DAEMON_IMPL:
	//
	//	python (default) — supervises daemon/app.py as a child process
	//	go                — runs them in-process (internal/daemon)
	//
	// The Go path is a verified rewrite of the Python one: the wire protocol
	// is unchanged (internal/daemon/parity_test.go runs both side by side),
	// so switching is reversible by changing one variable and restarting.
	// It is also strictly safer — the services require the auth token the
	// node already holds, whereas the Python daemon accepted any caller.
	//
	// AES key MUST match the Android app or the clipboard will not decrypt
	// to plaintext there. Start before the refresher so those ports are up
	// when registration happens.
	repoRoot := envOr("EARTHQUACK_REPO", ".")
	syncHost := envOr("EARTHQUACK_HOST", "0.0.0.0")
	clipPort := fmt.Sprintf("%d", envIntOr("EARTHQUACK_PORT", 8875))
	filePort := fmt.Sprintf("%d", envIntOr("EARTHQUACK_FILE_PORT", 8876))

	useGoDaemon := strings.EqualFold(envOr("EARTHQUACK_DAEMON_IMPL", "python"), "go")

	// startSync, stopSync and shutdownSync hide the implementation choice
	// from the rest of main.
	var startSync func() error
	var stopSync func()
	var shutdownSync func()
	var snapshotSync func() node.ManagedState

	if useGoDaemon {
		goDaemon, err := node.NewGoDaemon(node.GoDaemonConfig{
			Host:          syncHost,
			ClipboardPort: clipPort,
			FilePort:      filePort,
			AESKey:        aesKey,
			AuthToken:     authToken,
			// The desktop bridge is part of the sync service, not an
			// extra: without it the desktop clipboard never reaches the
			// node. It is on unless explicitly disabled, matching the
			// Python daemon, which always started it. Set
			// EARTHQUACK_DESKTOP_BRIDGE=0 on a headless homeserver that
			// only serves the phone.
			DesktopBridge: envOr("EARTHQUACK_DESKTOP_BRIDGE", "1") != "0",
		})
		if err != nil {
			log.Fatalf("sync services: %v", err)
		}
		if err := goDaemon.Start(); err != nil {
			log.Printf("earthquack: failed to start in-process sync services: %v (continuing)", err)
		}
		log.Printf("earthquack: sync services running in-process (EARTHQUACK_DAEMON_IMPL=go)")
		startSync, stopSync, shutdownSync = goDaemon.Start, goDaemon.Stop, goDaemon.Shutdown
		snapshotSync = goDaemon.Snapshot
	} else {
		daemonMgr := node.NewDaemonManager(node.PythonDaemonConfig{
			RepoDir:       repoRoot + "/daemon",
			Host:          syncHost,
			ClipboardPort: clipPort,
			FilePort:      filePort,
			AESKey:        aesKey,
		})
		if err := daemonMgr.Start(); err != nil {
			log.Printf("earthquack: failed to start python daemon: %v (continuing)", err)
		}
		// The Python child needs a watchdog: it can die and take the
		// clipboard service with it. The Go path has no child to watch.
		daemonMgr.BeginRestartLoop(ctx)
		defer daemonMgr.Shutdown()
		startSync, stopSync, shutdownSync = daemonMgr.Start, daemonMgr.Stop, daemonMgr.Shutdown
		snapshotSync = daemonMgr.Snapshot
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

	// Register the wallpaper module with the node's service/capability
	// system when it is configured (declared enabled, or a source/state
	// override or provider credential is present in the environment).
	// Wallpaper is an in-process module, so once declared it is a
	// "running" service: it does not depend on any TCP port and, by
	// design, having zero pending wallpapers never degrades node health.
	// Individual archive failures are recorded in failed state and
	// surfaced via events/status, not through global node health.
	wallpaperDeclared := cfg != nil && cfg.Wallpaper != nil && cfg.Wallpaper.Enabled
	if wallpaperDeclared || wallpaper.IsConfigured(wallpaperEnv) {
		reg.RegisterCapability("wallpaper")
		reg.RegisterService(node.Service{Name: "wallpaper", Status: node.ServiceRunning, Version: wallpaperVersion})
		log.Printf("wallpaper: registered as a node service (config enabled=%v)", wallpaperDeclared)
	}

	// ── Internet Microscope ────────────────────────────────────────────
	// The microscope is a declared node capability, not a separate
	// application: it is an in-process module (no TCP port) that owns
	// its sources, fetching, change detection, persistence and events.
	// The node learns about it through this registration and through the
	// read-only snapshot below; the event sink bridges its transitions
	// into the node's own event ring, so Internet changes appear in the
	// same audit stream as service/health/node transitions.
	//
	// Configuration is declarations only: whether the module is part of
	// this node and where its state lives. The sources it observes are
	// module state, managed with `earthquack-node internet ...`.
	var internetConfig *node.InternetConfig
	if cfg != nil {
		internetConfig = cfg.Internet
	}
	internetDeclared := internetConfig != nil && internetConfig.Enabled
	var internetModule *internet.Module
	if internetDeclared || internet.IsConfigured(os.Getenv) {
		moduleConfig, err := internet.ConfigFromEnv(os.Getenv)
		if err != nil {
			log.Fatalf("internet: %v", err)
		}
		if internetConfig != nil {
			if internetConfig.StateDir != "" {
				moduleConfig.StateDir = internetConfig.StateDir
			}
			if internetConfig.Interval != "" {
				interval, err := internet.ParseDuration(internetConfig.Interval)
				if err != nil {
					log.Fatalf("internet: config: %v", err)
				}
				moduleConfig.Interval = interval
			}
		}
		module, err := internet.NewModule(moduleConfig, internet.WithEventSink(func(ev internet.Event) {
			he := node.InternetEvent(ev)
			he.Node = reg.Local().Identity
			reg.History().AddEvent(he)
		}))
		if err != nil {
			// A malformed sources.json is reported and the node keeps
			// serving: the microscope is one capability, not the node.
			log.Printf("internet: module unavailable: %v", err)
		} else {
			internetModule = module
			reg.RegisterCapability("internet")
			reg.RegisterService(node.Service{Name: "internet", Status: node.ServiceRunning, Version: internetVersion})
			log.Printf("internet: registered as a node service (state dir=%s, interval=%s)",
				module.StateDir(), module.Config().Interval)
		}
	}

	probeHost := "127.0.0.1"
	if local := reg.Local(); len(local.Network.Addresses) > 0 {
		probeHost = local.Network.Addresses[0]
	}

	// Local service status: the refresher probes immediately on Run
	// and then gently on a ticker (DefaultRefreshInterval). It is
	// update-only — it can never create services or capabilities.
	// API/dashboard read registry state; they never probe.
	refresher := node.NewServiceRefresher(reg, specs, probeHost, 500*time.Millisecond, 0)
	go refresher.Run(ctx)

	// Telemetry history: independent bounded sampler on its own ticker.
	// It shares the registry (and the interval) with the refresher but
	// is deliberately NOT coupled to service probing — this goroutine
	// owns snapshots, the refresher owns port probes.
	telemetry := node.NewTelemetrySampler(reg, 0)
	go telemetry.Run(ctx)

	// The sync services, however they are implemented. Browser Stop
	// pauses them; the dashboard itself stays online.
	defer shutdownSync()
	syncDescription := "Clipboard (:8875) and file transfer (:8876) run as one supervised Python daemon. Stopping interrupts active transfers; the dashboard stays online."
	syncName := "Clipboard + file transfer"
	if useGoDaemon {
		syncDescription = "Clipboard (:8875) and file transfer (:8876) run in this process. Stopping interrupts active transfers; the dashboard stays online."
	}
	managed := []node.ManagedService{{
		ID: "sync-services", Name: syncName,
		Description: syncDescription,
		Start:       startSync, Stop: stopSync, Snapshot: snapshotSync,
	}}
	if wallpaperDeclared || wallpaper.IsConfigured(wallpaperEnv) {
		job := node.NewManagedJob(ctx, func(jobCtx context.Context) (string, error) {
			module, err := wallpaper.NewConfiguredModule("", "", "", wallpaperEnv)
			if err != nil {
				return "", err
			}
			report, err := module.Sync(jobCtx, false)
			if err != nil {
				return "", err
			}
			return fmt.Sprintf("Uploaded: %d; failed: %d; skipped: %d.", report.Uploaded, report.Failed, report.Skipped), nil
		})
		defer func() { job.Stop(); job.Wait() }()
		managed = append(managed, node.ManagedService{
			ID: "wallpaper", Name: "Wallpaper sync",
			Description: "Start runs one archive sync using this node's configured source, state and provider. Stop cancels only the dashboard job, not standalone CLI jobs. Do not run both against the same state directory.",
			Start:       job.Start, Stop: job.Stop, Snapshot: job.Snapshot,
		})
	}
	if internetModule != nil {
		// Polling starts with the node and can be paused/resumed from the
		// dashboard. Starting also re-reads sources.json, so sources added
		// or enabled by the CLI are picked up on a (re)start.
		poller := internet.NewPoller(ctx, internetModule)
		reg.SetInternetProvider(poller)
		if err := poller.Start(); err != nil {
			log.Printf("internet: polling not started: %v", err)
		}
		defer func() { poller.Stop(); poller.Wait() }()
		managed = append(managed, node.ManagedService{
			ID: "internet", Name: "Internet Microscope",
			Description: "Start resumes periodic observation of this node's configured Internet sources; Stop pauses it. Checks are read-only requests to explicitly configured URLs; changes are recorded as events.",
			Start:       poller.Start, Stop: poller.Stop,
			Snapshot: func() node.ManagedState {
				state := poller.Snapshot()
				return node.ManagedState{Running: state.Running, Message: poller.Message()}
			},
		})
	}

	addr := net.JoinHostPort(*host, fmt.Sprint(*port))

	// Secure cookie is opt-in: the dashboard is served over http on the
	// Tailscale address by default, where a Secure flag would block the
	// cookie. Enable via EARTHQUACK_SECURE_COOKIE=1 once TLS is present.
	secureCookie := os.Getenv("EARTHQUACK_SECURE_COOKIE") == "1"
	handler, err := node.NewServer(reg, version, node.ServerAuthConfig{
		Token:        authToken,
		SecureCookie: secureCookie,
	}, managed...)
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
		shutdownSync()
	}()
	log.Printf("earthQuack node %s listening on http://%s", version, addr)
	log.Printf("identity: %s", identity)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("server: %v", err)
	}
	shutdownSync()
	log.Printf("earthQuack node stopped cleanly")
}

func resolveAESKey(cfg *node.Config) string {
	if cfg != nil && cfg.ClipboardAESKey != "" {
		return cfg.ClipboardAESKey
	}
	return os.Getenv("CLIPBOARD_AES_KEY")
}

// resolveConfigPath decides which node configuration file to load.
//
// Precedence, most explicit first:
//
//  1. --config <path>         used verbatim; --config "" means "no config"
//  2. $EARTHQUACK_NODE_CONFIG used verbatim
//  3. ./config.json           relative to the working directory
//  4. config.json beside the binary   (portable/self-contained installs)
//  5. $XDG_CONFIG_HOME/earthquack/config.json, else ~/.config/earthquack/config.json
//
// Steps 3-5 are why the default is not simply "config.json". The binary
// is routinely started from a directory that is not the repo (systemd
// units, timers, cron, an absolute path typed in any shell), and a
// purely working-directory-relative default made every declaration
// vanish — most visibly the wallpaper credential file, which surfaced
// as "telegram: TELEGRAM_BOT_TOKEN is required". Each later step is a
// different way of saying "the operator's config", checked in
// decreasing order of locality so running from inside the repository
// behaves exactly as before.
//
// Step 5 covers the installed case: the binary in ~/.local/bin has no
// config.json beside it, so the per-user XDG location is the one that
// remains. Nothing is ever created here — the search only reports what
// already exists.
//
// When nothing is found the bare default name is returned: LoadConfig
// treats a missing file as "no config file" and the built-in defaults
// apply, rather than failing.
//
// An explicit flag or env value is never relocated, so a bad one is
// never silently swapped for a fallback. A malformed (or misspelled
// key) explicit config fails loudly; a path that does not exist is
// reported by LoadConfig as "no config file", which is the
// pre-existing contract for an absent configuration.
func resolveConfigPath(flagValue string, flagSet bool, getenv func(string) string, exePath, userConfigDir string) string {
	const name = "config.json"
	if flagSet {
		return flagValue
	}
	if v := getenv("EARTHQUACK_NODE_CONFIG"); v != "" {
		return v
	}
	if fileExists(name) {
		return name
	}
	if exePath != "" {
		if beside := filepath.Join(filepath.Dir(exePath), name); fileExists(beside) {
			return beside
		}
	}
	if userConfigDir != "" {
		if user := filepath.Join(userConfigDir, "earthquack", name); fileExists(user) {
			return user
		}
	}
	return name
}

// userConfigDir returns the per-user configuration directory following
// the XDG base directory spec: $XDG_CONFIG_HOME when set, otherwise
// ~/.config. It returns "" when no home directory can be determined, in
// which case the user-config step of the search is simply skipped.
func userConfigDir() string {
	if dir := os.Getenv("XDG_CONFIG_HOME"); dir != "" {
		return dir
	}
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return ""
	}
	return filepath.Join(home, ".config")
}

// flagProvided reports whether the named flag appeared on the command
// line, which is what separates an explicit --config (honoured as
// given) from the default (eligible for the binary-relative fallback).
func flagProvided(fs *flag.FlagSet, name string) bool {
	found := false
	fs.Visit(func(f *flag.Flag) {
		if f.Name == name {
			found = true
		}
	})
	return found
}

// executablePath returns the running binary's real path, or "" when it
// cannot be determined. Symlinks are resolved so a link in a bin
// directory still finds the config.json that ships beside the real
// binary.
func executablePath() string {
	exe, err := os.Executable()
	if err != nil {
		return ""
	}
	if resolved, err := filepath.EvalSymlinks(exe); err == nil {
		exe = resolved
	}
	return exe
}

// fileExists reports whether path is an existing regular file.
func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
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
