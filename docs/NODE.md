# Running an earthQuack Node

earthQuack nodes are ordinary Go binaries. The **same binary** runs on
every machine (Arch desktop, homeserver, VPS); each installation differs
only in its optional configuration file and its auth token. No systemd,
no root, no Docker, no CI required.

```text
install binary  →  provide config  →  provide token  →  run  →  Tailscale connects nodes
```

## 1. Build

On any machine with Go (or cross-compile from another):

```sh
cd earthQuack
go build -o earthquakes-node ./cmd/earthquack-node      # or: go build ./cmd/earthquack-node
# cross-compile example (build on Arch, run on another Linux box):
# GOOS=linux GOARCH=amd64 go build -o earthquakes-node ./cmd/earthquack-node
```

Single static binary, Go standard library only, no external dependencies.

## 2. Configure (optional)

A node with **no configuration file** is valid: it reports itself with
`capabilities: []`, `services: []` — a perfectly fine starting point.

For a node that provides services, give it a declarations file
(see `examples/`):

| Example            | Intended machine | Declares                        |
|--------------------|------------------|---------------------------------|
| `examples/arch.json`        | Arch desktop     | clipboard, file-transfer        |
| `examples/homeserver.json`  | homeserver       | storage, docker *(illustrative)*|
| `examples/vps.json`         | VPS              | reverse-proxy, docker *(illustrative)* |

The homeserver/VPS examples are **configuration examples only** — they
demonstrate that different machines declare different capabilities.
Nothing is fabricated at runtime: a service is only reported `running`
if its port is actually reachable, and capabilities exist only because
they are explicitly declared.

The config boundary is strict:

```text
CONFIG (declarations only)     RUNTIME (never configurable)
  capabilities                   identity (machine-id)
  services (name/port/version)   hostname, OS
  auth token                     online/offline, service status
                                 network addresses, peers
```

A config file cannot set identity, hostname, OS, or network state —
those fields are rejected at load time.

## 3. Authentication

Generate a token once per machine (or reuse one shared token across all
of your nodes — that is the current model):

```sh
export EARTHQUACK_AUTH_TOKEN="$(openssl rand -hex 32)"
```

**Precedence: `EARTHQUACK_AUTH_TOKEN` environment variable > config file `auth.token`.**
The env var wins so secrets never have to be committed. Never put a real
token in `examples/` or in version control. The token is never logged,
never returned by any endpoint, and never sent anywhere except in
`Authorization: Bearer` headers to peer nodes.

Without any token the node **fails closed**: `/api/health` works, all
other endpoints return `503 authentication not configured` — the API
*and* the browser dashboard alike (no login page is shown when login
could never succeed).

### Browser sessions

A plain browser cannot send `Authorization` headers, so the dashboard
has its own browser layer derived from the same single token (no second
secret):

* `GET /login` — minimal sign-in page; `POST /login` validates the
  token and issues a session cookie, then redirects to `/`.
* `POST /logout` — invalidates the session, clears the cookie.
* The cookie (`eq_session`) holds only a random 256-bit session id —
  never the token. It is `HttpOnly`, `SameSite=Strict`, `Path=/`, and
  `Secure` when `EARTHQUACK_SECURE_COOKIE=1` (opt-in until the node is
  served over TLS; the default Tailscale-IP deployment is plain HTTP).
* Sessions are in-memory only (12h TTL): a node restart signs every
  browser out. No database, no persistent state.
* API endpoints never accept the session cookie: `/api/node` and
  `/api/nodes` require the Bearer token, exactly as before. The cookie
  authorizes the dashboard only.
* Conversely, `curl`-style clients may present the Bearer token to `/`
  directly to read the dashboard without a session.
* Wrong/missing login tokens render the same generic "Invalid token"
  message; tokens are never echoed, logged, or put in URLs.
* CSRF posture: `SameSite=Strict` + POST-only login/logout, and the
  dashboard is read-only with no state-changing endpoints. Management
  endpoints, when they ever exist, will need a real CSRF/auth design
  first.

## 4. Bind address

By default the node binds `0.0.0.0:8890`. On a Tailscale machine, bind
to the tailnet address so the API is not exposed on your LAN:

```sh
./earthquack-node --host "$(tailscale ip -4)" --port 8890 \
    --config ./my-node.json
```

Use `127.0.0.1` for a local-only node. Binding is transport practice,
not security — the bearer token protects the API regardless.

## 5. Run

```sh
./earthquack-node --host 100.x.x.x --port 8890 --config ./my-node.json
```

Runs as an ordinary unprivileged user. Required access:

* **read:** `/etc/machine-id` (node identity), Tailscale CLI
  (`tailscale status --json`) for peer discovery
* **network:** bind the configured port, outbound HTTP to peers
* **write:** nothing, unless `/etc/machine-id` is missing — then one
  small file is created under `~/.config/earthquack/node-id`

Stop with `Ctrl-C` (graceful shutdown: refresh loop stops, HTTP server drains).

## 6. Verify

```sh
curl http://127.0.0.1:8890/api/health                      # public: liveness only
curl -H "Authorization: Bearer $EARTHQUACK_AUTH_TOKEN" \
     http://127.0.0.1:8890/api/node                        # this node
curl -H "Authorization: Bearer $EARTHQUACK_AUTH_TOKEN" \
     http://127.0.0.1:8890/api/nodes                       # this node + peers
```

Open `http://100.x.x.x:8890/` in a browser and sign in once with the
token (see *Browser sessions* in section 3). API clients and `curl`
can skip login by sending the Bearer header directly to `/`.

## 7. How a new machine joins the dashboard

1. Build and copy the binary.
2. Write that machine's declarations file (copy an example, edit).
3. `export EARTHQUACK_AUTH_TOKEN=<shared token>` and run.
4. Once Tailscale connects it, the existing nodes discover the peer and
   probe `GET /api/node` — the node appears in the dashboard with its
   self-declared capabilities. **No earthQuack source changes needed.**
