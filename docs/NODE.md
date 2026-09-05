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
other endpoints return `503 authentication not configured`.

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

Open `http://100.x.x.x:8890/` with the token from another tailnet
machine (API clients and `curl` can send the header directly; a plain
browser gets `401` — see the auth note in the project docs).

## 7. How a new machine joins the dashboard

1. Build and copy the binary.
2. Write that machine's declarations file (copy an example, edit).
3. `export EARTHQUACK_AUTH_TOKEN=<shared token>` and run.
4. Once Tailscale connects it, the existing nodes discover the peer and
   probe `GET /api/node` — the node appears in the dashboard with its
   self-declared capabilities. **No earthQuack source changes needed.**
