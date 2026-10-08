# EarthQuack rclone shim (Go → Android)

This directory is a **separate Go module**. It is deliberately not part of the
root `earthQuack` Go module, whose `go.mod` has zero third-party dependencies
and is treated as a security property of the project. Adding rclone's
dependency tree there would be a large, permanent change to that guarantee, so
this module is isolated and built separately.

```
rclone-android/
├── go.mod          module earthQuack.local/rclone-android
└── rclone/
    └── rclone.go   the shim: Initialize / Finalize / RPC
```

## Licensing

| Component | License | Used how |
|---|---|---|
| `github.com/rclone/rclone` | **MIT** | Linked. Provides `librclone/librclone` and all backends. |
| This shim | MIT (EarthQuack) | Original work written for this project. |
| `gulp79/rclone-extra` | **no LICENSE file** | **Not used, not copied.** All-rights-reserved by default. |
| `gulp79/Round-Sync` | **GPL-3.0** | **Not used, not copied.** Reference only. |

Everything required already exists upstream under MIT. The shim is ~30 lines of
adapter over `librclone.Initialize/Finalize/RPC` plus blank imports that
register backends — there was no need to copy anything from the GPL or
unlicensed projects, and nothing was.

## Build

```bash
cd rclone-android

# Resolve the (large) dependency tree once.
go mod tidy

# Build the Android AAR.
#   -javapkg places the generated Java classes under our own namespace.
export ANDROID_NDK_HOME=/opt/android-ndk
export ANDROID_HOME=~/Android/Sdk

gomobile bind \
  -target=android/arm64 \
  -androidapi 26 \
  -javapkg com.example.earthquack.rclone \
  -o ../app/libs/rclone.aar \
  ./rclone
```

`-androidapi 26` matches the app's `minSdk = 26`. `arm64` alone builds a small
AAR for fast iteration; see "Shipping more ABIs" below.

Output: `app/libs/rclone.aar`, which Gradle packages automatically because
`app/libs` is on the default AAR search path.

## Persistent configuration

`Initialize(configPath)` hands the path to rclone's own
`config.SetConfigPath`. That is the supported mechanism for an embedded
rclone — not an env-var trick and not a reimplementation.

Why not the alternatives:

- **`RCLONE_CONFIG`** — honoured by rclone (`fs/config/config.go` reads it),
  but it is process-global state that would have to be mutated from Go with no
  way to verify it took effect before the first RPC.
- **`--config`** — rclone scans `os.Args` for it. An Android process has no
  meaningful command line.
- **`XDG_CONFIG_HOME` / `$HOME`** — depend on the environment and would tie the
  config location to something we do not control.

The path is passed in explicitly from Kotlin:
`context.filesDir/rclone/rclone.conf` (see `RcloneConfigManager`). Nothing
depends on the working directory, which on Android is `/`.

Two upstream behaviours worth knowing, both verified against the source rather
than assumed:

- **A missing config file is not an error.** `fs/config`'s `LoadedData` treats
  `ErrorConfigFileNotFound` as "use defaults" and continues, so a fresh install
  initialises cleanly and `config/listremotes` returns `[]`.
- **Config writes are already crash-safe and concurrency-safe.**
  `fs/config/configfile` guards its storage with a `sync.Mutex` and writes via
  `os.CreateTemp` + `os.Rename`, keeping a `.old` backup. There is no need for
  extra locking or atomic-replace logic on our side.

## RPC methods that are not available

`librclone.RPC` rejects two documented methods because they need
request/response objects rather than JSON parameters:

- `operations/uploadfile`
- `core/command`

Use the equivalents instead — `operations/copyfile`, `operations/movefile`,
`operations/deletefile`, `operations/mkdir`, `sync/copy`, `sync/move`.

## Argument shapes that are easy to get wrong

Every `operations/*` call takes a `fs` root plus a path **within** that root.
The pairs differ per method, and rclone reports a mismatch as a confusing
404/400 rather than naming the real problem:

| Method | Parameters |
|---|---|
| `operations/list` | `fs`, `remote` (empty for the root) |
| `operations/mkdir` | `fs`, `remote` = directory **relative to fs** |
| `operations/copyfile` | `srcFs`, `srcRemote`, `dstFs`, `dstRemote` |
| `operations/movefile` | `srcFs`, `srcRemote`, `dstFs`, `dstRemote` |
| `operations/deletefile` | `fs`, `remote` = **full file path within fs** (no `dst_file`) |

`operations/deletefile` is the counter-intuitive one: `fs/operations/rc.go`
does `f.NewObject(ctx, remote)`, so `remote` is the whole path to the object
and there is no `dst_file` parameter at all.

Two remote-name forms exist:

- `name:/path` — a **named remote** from rclone.conf
- `:backend:/path` — an **on-the-fly remote**, which bypasses config lookup

The leading colon matters: `local:/tmp` looks up a config section named
`local` and fails with `500 didn't find section in config file` on a fresh
install, while `:local:/tmp` works immediately. The `local` backend has no
configurable root, so a named `local:` remote resolves against the process
working directory — which on Android is `/`.

## Linked backends and what they cost

`rclone.go` blank-imports the backends. There is no runtime plugin mechanism on
Android, so that import list **is** the set of providers the app can configure.
The Kotlin layer never hard-codes a provider name: the Add-remote picker renders
whatever `config/providers` reports, so widening the list is the only change
needed to make a backend appear in the app.

Currently linked: `local`, `sftp`, `drive`, `mega`, `onedrive`, `dropbox`,
`box`, `pcloud`, `webdav`, `ftp`, `s3`, plus the bridging and local-transform
backends `combine`, `crypt`, `chunker`, `alias`, `archive`, `compress`,
`union`.

### Measured size cost

Each row is a real `gomobile bind` of that exact backend set, measured the same
way, so the numbers are comparable:

| Backend set | `libgojni.so` |
|---|---|
| `local` only | 35.7 MB |
| + bridging (`alias`, `combine`, `crypt`, `chunker`) | 35.7 MB |
| + `compress`, `archive`, `union` | 39.5 MB |
| + `sftp`, `ftp`, `webdav` | 41.3 MB |
| + `drive`, `mega`, `onedrive`, `dropbox`, `box`, `pcloud` | 42.2 MB |
| + `s3` (**the full set**) | **55.3 MB** |

**`s3` alone costs 13 MB** — more than every other backend combined — because it
pulls in the whole `aws-sdk-go-v2`. The bridging backends are free. Drop `s3`
from the import list if 13 MB is not worth it.

### Stripping

`scripts/build-rclone-aar.sh` passes `-ldflags="-w -s"`. This is not cosmetic:
it removes the Go DWARF debug info and symbol table, taking `libgojni.so` from
**55.3 MB to 39.2 MB** and the APK from 79.2 MB to 63.1 MB.

It also fixes a packaging warning. AGP logs `Unable to strip
.../libgojni.so` during packaging; that was the external `strip` failing on Go
symbols that `-w -s` removes before it ever gets a chance.

Rebuild without the flag only when debugging a native crash on device.

## Adding ABIs

The default `gomobile bind -target=android` builds all four supported
architectures (arm, arm64, 386, amd64). For a real device build that is the
right target set, but it multiplies build time and AAR size by roughly four.
To match the app's real requirements, declare the ABIs in
`app/build.gradle.kts` and build only those:

```kotlin
defaultConfig {
    ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
}
```

## Shipping more backends

rclone discovers backends through blank imports at link time — there is no
runtime plugin loading on Android. The provider set is therefore a **build-time**
decision, made only in `rclone/rclone.go`, so no Kotlin code has to change to
add or remove one.

The PoC imports `backend/local` only, to keep the AAR small while the pipeline
is proven. To ship real remotes, add the blank imports you intend to support:

```go
_ "github.com/rclone/rclone/backend/local"
_ "github.com/rclone/rclone/backend/drive"   // Google Drive
_ "github.com/rclone/rclone/backend/mega"    // MEGA
_ "github.com/rclone/rclone/backend/sftp"    // SFTP
```

Each import adds code size. Prefer an explicit allowlist over
`backend/all` so the AAR does not carry backends the product will never offer.

## Known constraints carried over from upstream

- `operations/uploadfile` and `core/command` are **not supported** by
  `librclone.RPC`; they require request/response objects. Use `operations/copyfile`,
  `operations/movefile`, `operations/deletefile`, `sync/copy`, `sync/move`.
- `Finalize()` only runs a GC. It does **not** cancel in-flight jobs. Cancel
  them with `core/stop` first if a clean drain matters.
- `configfile.Install()` reads rclone's config from its default path. On Android
  that must be pointed at the app's private storage before the first RPC —
  see the Kotlin `RcloneEngine` for how that is handled.
