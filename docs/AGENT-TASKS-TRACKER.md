# EarthQuack — Work Tracker

> Multi-agent coordination file. **Edit only your own section.** Each section
> names its owner. Push when your part is done.

Last updated: 2026-10-11 22:45 IST · HEAD `21c5df0` + uncommitted Sec C (done) · Sec D (transfer-profile, in progress) · Sec E claimed (file-management agent, investigating)

---

## Section A — Terminal & app UX (owner: session that did terminal work)

### DONE — all pushed

| Commit | What |
|---|---|
| `02915b1` | Fixed-grid VT emulator (`TerminalGrid.kt`, 20/20 JVM tests), Share-as-link v1 (upload-to-Drive flow), range video streaming |
| `9257d86` | Termux-style inline terminal input (`TerminalInputView`; fixed IME-desync + focus-steal `fullScroll` bug), single-path back navigation (`OnBackPressedCallback` + `popSubScreen()` in MainActivity) |
| `21c5df0` | Profile row tap → opens Terminal; ⋮ menu → Edit/Files/Delete; terminal no longer auto-connects (opens Disconnected, input disabled until Connect) |

### Device verification (needs user's phone on ADB)
- Back-nav fix: open Connections → ← must return to Services AND reset app
  app bar (title + hide Back arrow). Repeat from SFTP/Clipboard/Terminal.
- Terminal: Connect → type `ls` → output appears; typing must keep working
  after each echo (was fixed but re-verify on next device session).

### Test status (JVM, last run)
`TerminalGridTest` 20/20 · `TerminalScreenBufferTest` 40/40 ·
`TerminalCommandSubmitTest` 5/5 · `RcloneShareLinkTest` 15/15 ·
`RcloneRemoteManagerTest` 13/13 · `compileDebugKotlin` + `assembleDebug` green.

---

## Section B — Share as Link (owner: session that did terminal work)

### Status: IMPLEMENTATION COMPLETE — code + tests written; verification BLOCKED on Sections D/E mid-edit compile errors in `FileBrowserActivity.kt` (their symbols, not mine). When D/E land, run:
###   `./gradlew :app:testDebugUnitTest --tests RcloneDirectLinkTest --tests RcloneShareLinkTest` then `assembleDebug`.

**Done (uncommitted, in working tree):**
- `storage/RcloneShareLink.kt`: `DirectLinkRequest(fs, remote)` +
  sealed `DirectLinkResult` (Success/Unsupported/NotFound/AuthFailed/Network/
  Failure) + `directLink()` (single `operations/publiclink`, exact fs+remote,
  no copy ever) + pure `classifyLinkError()` pinned to rclone v1.75.1 messages.
  Old upload `share()` kept intact (still 15/15 tested).
- `FileBrowserActivity.kt`: `shareAsLink` → `confirmDirectLink` (consent
  dialog distinguishing retrieve-existing vs create-permission) →
  `runDirectLink` (IO coroutine, cancel-aware, guarded by `transferring`) →
  `renderDirectLink` (per-cause messages) → existing `showLinkReady`
  (copy/open/share). Drive-picker path removed. Share-as-link now offered for
  directories too (publiclink supports folders).
- `strings_files_open.xml`: direct-link wording + 4 new per-cause strings;
  dead Drive-picker strings removed.
- New `RcloneDirectLinkTest.kt`: 13 tests (exact fs/remote, no copyfile/list,
  unicode verbatim, success URL, no-url=failure, 5 classifier cases,
  case-insensitivity, RcloneException classification).

### Task: generate link directly at the file's existing remote (NOT started — investigation done)

**Problem:** "Share as link" currently asks for a Drive destination, copies the
file there (`operations/copyfile`), then links. User wants the link generated
in place, like `rclone link remote:path/to/file`.

**Confirmed API (rclone v1.75.1, verified against upstream source):**
- RC method `operations/publiclink` — params `fs` (string), `remote` (string),
  optional `expire`, `unlink`; returns `{"url": "…"}`. Exactly `rclone link`.
  Works on files AND folders. Unsupported backends return
  `"<fs> doesn't support public links"` (from `fs/operations/operations.go`).
- Docs: https://rclone.org/rc/#operations-publiclink · https://rclone.org/commands/rclone_link/

**Current flow (the thing to change):**
- `FileBrowserActivity.shareAsLink` (line ~1193) → `pickDriveRemote` (Drive-only
  picker via `RcloneRemoteManager.driveRemotes()`) → `confirmShareLink` →
  `runShareLink` → `RcloneShareLink.share()` = list + copyfile + publiclink.
- Menu item `MENU_SHARE_LINK` only added for files (`!entry.isDir`) — direct
  linking should offer it for directories too (publiclink supports them).
- `fs` field (line 107) + `entry.path` = exact src pair needed; entry model:
  `FileEntry(name, path, isDir, size, mimeType, modified)`.
- Link-ready dialog already exists (`dialog_share_link.xml`: copy/open/share
  actions) — reuse it. `showLinkReady()` at line ~1329.

**Plan:**
1. New `DirectLinkRequest(fs, remote)` + sealed `DirectLinkResult`
   (Success / Unsupported / NotFound / AuthFailed / Failure) in
   `storage/RcloneShareLink.kt` (or new file). Single `operations/publiclink`
   call; parse `{"error":…}` envelope to classify. No copy, ever.
2. `shareAsLink()` → consent dialog (public-access warning; distinguish
   "retrieve existing link" vs "change sharing permissions" — publiclink may
   CREATE a Drive permission) → `runDirectLink` → reuse `showLinkReady`.
3. Delete destination-picker path for this action; keep upload code intact
   (download/upload features must stay green).
4. Tests: exact fs/remote passed; no copyfile/list called; success URL;
   error classification; existing `RcloneShareLinkTest` 15/15 stays green.

**Constraint:** user's consent dialog must remain — never silently make public.

---

## Section C — Credential storage audit & hardening (owner: credential-hardening agent)

### Status: DONE — implemented, JVM 363/363 green, not committed (owner to review/push)

**Implemented:**
- `CredentialVault.kt` (new): AES key + bearer token in Keystore-backed
  `SecretStore`; lazy non-destructive migration from legacy prefs (plaintext
  deleted only after vault read-back verifies; vault failure keeps legacy).
- `ServerConfig.kt`: `get/setAesKey` + `get/setAuthToken` route via vault;
  same public API; `secretsAreVaultBacked()` diagnostic; `secretStoreOverride`
  test seam. Non-secrets stay in prefs.
- `RcloneLogRedactor.kt` (new): redaction + `describeFailure`; `RcloneEngine`
  error path uses it. rclone.conf stays plaintext-on-disk (required by
  `config.SetConfigPath`/gomobile) — protected by app-private 600 perms
  (`RcloneConfigManager.repairPermissions()` on every `ensureReady`),
  `allowBackup=false`, FileProvider root excludes `filesDir`.
- `EarthQuackService.kt`: clipboard contents no longer logged/broadcast
  (lengths only); token never logged (verified).
- `strings_sub.xml` + `activity_security.xml` + `SecurityActivity.kt` docs:
  Keystore wording replaces plaintext warning.
- `.gitignore`: `config.local.json`, `*-local.json`, `.env*`, `rclone.conf(+.old)`.
- `docs/CREDENTIAL-ROTATION.md` (new): rotation steps, no auto-revoke/rewrite.
- Tests: `CredentialVaultTest` 6/6, `RcloneLogRedactorTest` 5/5 (incl.
  host-key no-disable guard). Full suite 363/363, 0 failures.

**Verified (read-only):** host-key verification has no accept-all branch
(`HostKeyVerifier` + `KnownHostsStore`: mismatch always fails, STRICT refuses
unknown); `file_paths.xml` serves only `cache/opened/`; no `backupRules` /
`fullBackupContent` re-includes secrets; current-tree secret grep clean
(apart from documented test-only `TEST_TOKEN` shape constant).

**Outstanding manual actions:** rotate `thisisworking` token (still in history
`5f016f7`); run gitleaks (not installed here); full-history scan incomplete —
do NOT treat history as clean; device test of vault migration + restart.

**Task:** Audit and harden EarthQuack credential storage (Android app +
embedded rclone config + SSH/SFTP auth + repo history). This agent owns
only this section. Do not edit Sections A/B.

**Confirmed so far (read-only, no code edits yet):**
- `ServerConfig.kt` stores AES key (`aes_key_b64`) + bearer token
  (`auth_token`) in plaintext `earthquack_prefs` SharedPreferences.
- UI admits it: `strings_sub.xml:20 security_storage_warning`.
- `KeystoreSecretStore` (`ssh/SecretStore.kt`) already exists and is the
  migration target — SFTP password + SSH keys already use it.
- `RcloneConfigManager.kt` = plaintext `files/rclone/rclone.conf`
  (OAuth tokens/passwords), app-private + `allowBackup=false`, no logging
  of contents seen so far.
- Git history: `config.json` with live token `thisisworking` added in
  `5f016f7`, deleted in `17b1875`. Still reachable via history. Full
  history scan + secret-scanner run still pending.

### Files this agent WILL touch (credential lane only)
- `app/src/main/kotlin/com/example/earthquack/ServerConfig.kt`
- `app/src/main/kotlin/com/example/earthquack/ssh/SecretStore.kt` (reuse/extend) or new secure-prefs wrapper
- `app/src/main/kotlin/com/example/earthquack/storage/RcloneConfigManager.kt` (protect, no content logging)
- `app/src/main/kotlin/com/example/earthquack/ui/sub/SecurityActivity.kt`
- `app/src/main/res/values/strings_sub.xml` (`security_storage_warning`)
- `app/src/main/AndroidManifest.xml` + backup rules (verify only, no blanket disable)
- `.gitignore` (local secret-bearing files only)
- New tests: migration, restart-usability, no-clobber, log-redaction, host-key-verify, backup handling
- New docs: credential-rotation procedure (no auto-revoke, no history rewrite)

### Files this agent will NOT touch (owned by Section A/B agent — "share as link")
- `FileBrowserActivity.kt` — `shareAsLink` / `pickDriveRemote` / `confirmShareLink` / `runShareLink` / `showLinkReady`, `fs` field, `MENU_SHARE_LINK`
- `storage/RcloneShareLink.kt` + `RcloneShareLinkTest.kt`
- `storage/RcloneRemoteManager.kt` (`driveRemotes()`) + `RcloneRemoteManagerTest.kt`
- `dialog_share_link.xml`, `strings_remote.xml`, `item_remote_field.xml`
- Terminal/VT files: `TerminalGrid.kt`, `TerminalInputView.kt`, `TerminalFragment.kt`, `MainActivity.kt` back-nav/`popSubScreen`
- `RangeHttpServer` / streaming files

**Coordination rule:** if a credential fix needs a line inside a NOT-touch
file, stop and note it here instead of editing — Section A/B owner applies it.

---

## Section D — Transfer manager + unified SSH/SFTP connection profiles (owner: transfer-profile agent)

### Status: DONE — both tasks implemented; tests green except one pre-existing failure owned by another agent

This agent owns ONLY this section. Tasks claimed:

1. ~~**Persistent, shared transfer manager**~~ — **DONE** (see below).
2. **Unified SSH/SFTP connection profiles** — **DONE** (see below).

### Task 1 — transfer manager: what landed (all new files)

- `transfer/TransferTask.kt` — model: id, type, backend payload (rclone /
  sftp), bytes (never a fake percent), timestamps, status
  (QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED/INTERRUPTED), error, attempts;
  JSON (de)serialisation; RUNNING-in-storage always recovers as INTERRUPTED.
- `transfer/TransferStore.kt` + `PrefsTransferStore.kt` — persistence
  (SharedPreferences JSON, `earthquack_transfers`, 50-entry history cap,
  live tasks never trimmed) + `InMemoryTransferStore` for tests.
- `transfer/TransferManager.kt` — process singleton queue: dedupe by
  (type, backend, destination) so double-taps/config changes can't double
  submit; bounded concurrency (default 2, FIFO); every transition persisted
  before it is published; honest cancel (executor cleanup runs first);
  retry resets bytes unless the executor proves resume; attempts counted
  once, at promotion to RUNNING.
- `transfer/TransferExecutor.kt` + `RcloneTransferExecutor.kt` +
  `SftpTransferExecutor.kt` — executors wrapping the EXISTING backends
  unchanged: rclone path reuses `RcloneFileTransfer` (`.part` staging,
  atomic rename, size verification, partial cleanup); SFTP path reuses
  `SshConnectionFactory` + `SftpSession`, adds `.part` staging + rename in
  both directions (local source of an upload never touched). SFTP download
  resume only when 4 conditions hold (recorded total + kept bytes + local
  `.part` exactly that long + remote size unchanged); uploads never resume.
- `transfer/TransferService.kt` — foreground service, `dataSync` type,
  notification (channel `earthquack_transfers`), summary notification on
  completion, stops itself when nothing is active. Manager is the brains,
  service is the OS priority + honesty.
- `ui/sub/TransfersActivity.kt` + `activity_transfers.xml` +
  `item_transfer_row.xml` + `strings_transfer.xml` — active list (bar,
  bytes, Stop) + recent history (error, Retry), clear-history.

### Task 2 — unified connection profiles: what landed

- Reused the existing model/stores untouched: `ssh/ConnectionProfile.kt`,
  `ConnectionProfileStore.kt` (JSON, schema v1 — no legacy store existed,
  so no migration path was needed; verified), `KnownHostsStore.kt`,
  `SecretStore.kt`/`IdentityKeyStore.kt`.
- `ssh/InteractiveHostKeyTrust.kt` (new) — parks an unfamiliar host key
  WITHOUT recording, until an explicit accept; changed keys pass through
  as Mismatch, never parked.
- `ssh/SshProfileProbe.kt` (new) — one connect-and-close test; strict
  policy so an unknown key surfaces with its fingerprint.
- `ui/ProfileTester.kt` (new) — shared Test-connection dialog: fingerprint
  shown before first trust, changed key blocks, retry only when retryable.
- `ui/sub/SftpBrowserActivity.kt` + layouts + `strings_sftp.xml` (new) —
  SFTP browser for the same profile: explicit host-key consent, listing,
  upload/download handed to the transfer manager.
- `ui/ProfileEditorActivity.kt` — Test button (`candidateFromForm()`
  extracted so Test exercises exactly what Save writes); layout + menu gains.
- `ui/ProfilesFragment.kt` — Files row menu now opens the browser (was a
  toast); Test row menu added. `profile_row_menu.xml` + `strings_profiles.xml`.
- No Compose, no new dependencies, no SSH server, no Tailscale.

### Manifest + verification
- Additive manifest entries only: `FOREGROUND_SERVICE_DATA_SYNC`,
  `.transfer.TransferService` (dataSync), `.ui.sub.TransfersActivity`,
  `.ui.sub.SftpBrowserActivity`.
- `compileDebugKotlin` green. `testDebugUnitTest`: **419 tests, 1 failure —
  `RcloneDirectLinkTest` (another agent's uncommitted, in-flight test +
  `RcloneShareLink.kt` working-tree change; not mine, untouched by me).**
  All Section D tests green: `TransferTaskTest`, `InMemoryTransferStoreTest`,
  `TransferManagerTest` (dedupe, FIFO + concurrency, progress, cancel +
  cleanup, failure/retry/attempts, resume-only-when-declared,
  executor-exception, RUNNING→INTERRUPTED recovery), `InteractiveHostKeyTrustTest`.
  Existing `ConnectionProfileTest`, `SftpEndToEndTest`, `RcloneFileTransferTest`
  all still pass — no backend regression.

### SSH/SFTP host-key pairing (2026-10-12, user-requested)

Design decision recorded: **SSHFP over tailnet DNS** was chosen (not manual
known_hosts sharing), and the honest limit is documented in the UI itself:
Tailscale MagicDNS does not serve SSHFP, so the records need a resolver that
does (CoreDNS/unbound on the tailnet, or the laptop's own resolver). The
zero-infrastructure path (a ready-to-paste known_hosts line) is offered
alongside it.

- `sftp/HostKeyPairing.kt` (new) — derives three artifacts from the server's
  **persisted** host key file: the public key line (for the computer's
  authorized_keys), a `[ip]:port type base64` known_hosts line, and the
  `IN SSHFP 3 2/1 <hex>` records. All computed from the wire blob (same bytes
  a fingerprint is), never re-parsing a key object. 6 JVM tests, including the
  writer→reader round-trip and a base64↔hex cross-check that the SSHFP hex
  equals the fingerprint the app reports.
- `SftpServerEngine.readHostKeyPublicLine()` — reads the key file through
  sshd's own parser, so the card works while the server is stopped.
- SFTP screen: "Pair with a computer" card (copy buttons per artifact + the
  honest DNS note); host-key fingerprint now shows even when stopped,
  with a share button.
- Profile editor: host-key share button (SSH side of the same story).
- Verified on device: the card emits the real key's artifacts, and the
  SSHFP hex decodes to exactly the server's reported fingerprint.

### Also this session (all verified on device)

- Passphrase dialog restyled with the app's field drawable (the platform
  default's full-width underline).
- Permissions screen now always lists all three permissions
  (Notifications / Overlay / **All files access**) with their state, instead
  of hiding granted rows and leaving a dead "all granted" state.
- File browser: Back unwinds an active selection before navigating; the
  selection bar is gone, the count sits beside the storage name in the crumb
  strip, and Copy/Move/Delete moved into the row's own 3-dots (as the user
  asked). Share-as-link and Save-to-downloads are remotes-only.
- `ConnectionProfile.kt` import dedupe: keys imported into IdentityKeyStore
  are matched by fingerprint, so a repeated import is one key, not two.

### Explicitly NOT done (documented, not blocked)
1. Flag-gated wiring of `FileBrowserActivity` downloads behind the manager.
   The agent actively rewriting `FileBrowserActivity.kt` /
   `RcloneShareLink.kt` is mid-flight in the working tree; I have not touched
   those files at all (per coordination). The manager + executor are ready;
   the wiring is a ~10-line additive change once their tree settles.
2. Entry point from Settings → TransfersActivity (row).
3. Device (on-device) verification of the foreground service and SFTP
   session flows — JVM tests only.

### Files this agent WILL touch (new work)
- New: `app/src/main/kotlin/com/example/earthquack/transfer/` (model, manager,
  repository, worker/foreground service, notifications)
- New: `app/src/main/kotlin/com/example/earthquack/profile/` (ConnectionProfile
  model, repository, validation, migration)
- New transfer screen: `app/src/main/kotlin/com/example/earthquack/ui/sub/TransfersActivity.kt`
  + `res/layout/activity_transfers.xml` (XML/ViewBinding, no Compose)
- New profile screen (list/edit/test): `app/src/main/kotlin/com/example/earthquack/ui/sub/ProfilesActivity.kt`
  + `res/layout/activity_profiles.xml`
- `app/src/main/AndroidManifest.xml` (register activities + foreground service)
- New: `app/src/test/kotlin/com/example/earthquack/transfer/**` and
  `app/src/test/kotlin/com/example/earthquack/profile/**` (JVM tests)
- Wiring (additive, feature-flagged per-callsite, direct paths preserved):
  `ssh/` SFTP upload/download callsites, terminal connection, file browser
  download/upload entries

### Files this agent will NOT touch (owned by other sections / risky)
- Section A/B-owned: `FileBrowserActivity.kt` share-link methods
  (`shareAsLink`/`pickDriveRemote`/`confirmShareLink`/`runShareLink`/`showLinkReady`),
  `storage/RcloneShareLink.kt` (+test), `storage/RcloneRemoteManager.kt` (+test),
  `dialog_share_link.xml`, `strings_remote.xml`, `item_remote_field.xml`,
  `TerminalGrid.kt`, `TerminalInputView.kt`, `TerminalFragment.kt`,
  `MainActivity.kt` back-nav/`popSubScreen`, `RangeHttpServer`
- Section C-owned: `ServerConfig.kt`, `CredentialVault.kt`, `ssh/SecretStore.kt`
  internals, `storage/RcloneConfigManager.kt`, `storage/RcloneLogRedactor.kt`,
  `ui/sub/SecurityActivity.kt`, backup rules
  (NOTE: this agent may READ SecretStore and reuse it as the credential
   backend for profiles per the task spec; changes to SecretStore go through
   Section C owner / additive-only)
- No Compose migration, no Tailscale, no SSH server on Android

### Investigation findings (2026-10-11, read-only)

**Unified profiles (task 2) are ~80% already implemented** — by a previous
session in `ssh/`:
- `ssh/ConnectionProfile.kt` — model with id/name/host/port/username/authMethod
  (PUBLIC_KEY|PASSWORD)/identityKeyAlias/passwordAlias (references only, no
  secrets)/hostKeyPolicy (TOUF|STRICT) + `problems()` validation.
- `ssh/ConnectionProfileStore.kt` — SharedPreferences JSON store, schema
  version 1, corrupt-blob tolerant.
- `ssh/KnownHostsStore.kt` — per-profile host key fingerprint, TOUF/STRICT,
  mismatch = hard fail. Enforced in `ssh/SshConnection.kt` `HostKeyVerifier`.
- `ssh/SecretStore.kt` / `IdentityKeyStore.kt` — Keystore AES/GCM credential store.
- `ui/ProfileEditorActivity.kt` + `ui/ProfilesFragment.kt` — create/edit/delete/list.
- Remaining gaps for task 2: (a) no profile *test* action; (b) "Files" row menu
  is a stub (`profiles_files_not_implemented` toast) — must open the SFTP
  browser on the same profile; (c) no migration needed yet (no legacy store
  found — verify: rclone remotes live in a *different* config, not SSH hosts).

**Transfer manager (task 1) does not exist.** Existing direct paths:
- rclone download: `storage/RcloneFileTransfer.kt` (operations/copyfile into
  cache `opened/<key>/<name>.part` → rename; size-verified via
  `DownloadVerification`; progress polled from file length; per-process
  `DownloadTracker` guards duplicate taps).
- SFTP: `ssh/SftpSession.kt` `upload`/`download` with `TransferProgress`
  bytesTransferred/totalBytes, offset param (resume-capable in principle),
  `TransferCancelledException`; called from `ui/SftpFragment.kt` +
  `FileBrowserActivity.kt` (to confirm).
- Services exist: `EarthQuackService.kt`, `FileTransferService.kt` (claipboard
  SFTP server related — must read before touching).

**Plan (incremental, direct paths preserved):**
1. New `transfer/` package: `TransferTask` model (stable id, src, dst, type,
  bytes, timestamps, status enum, error), `TransferRepository` (JSON in
   SharedPreferences — matches existing convention, JVM-testable),
   `TransferManager` (queue, dedupe, concurrency cap, StateFlow),
   `TransferExecutor` interface with rclone + sftp backends.
2. Foreground service `TransferService` for long-running work + notification.
3. Wire rclone download + sftp upload/download behind the manager *behind
   per-callsite flags*; direct calls stay until tested green.
4. `ui/sub/TransfersActivity.kt` (active + history + errors + cancel).
5. Profiles: add "Test connection" + route Files row menu to SFTP browser;
   no model rewrite needed.

**Coordination rule:** if a change needs a line inside a NOT-touch file,
stop and note it here instead of editing.

**2026-10-11 note to the FileBrowser/Home/Storage agent:** my Section D is
complete and green; I did NOT touch `FileBrowserActivity.kt`,
`HomeFragment.kt`, `StorageFragment.kt`, `RcloneShareLink.kt` or your
untracked `RcloneDirectLinkTest.kt` (that test is currently failing — it is
yours to finish; it is the only failure in the 419-test JVM run).

---

## Section E — rclone remote file management (owner: file-management agent)

### Status: IMPLEMENTING — pure helpers + tests written, UI wiring in progress

### Investigation findings (2026-10-11, read-only)

**Browser today (`FileBrowserActivity.kt`, ~1787 lines):** `operations/list`
with `fs` + relative `remote`; rows parsed from Name/Path/IsDir/Size/MimeType/
ModTime; fixed sort (folders-first, case-insensitive name); current-dir name
filter only; breadcrumbs; back = search → up → exit; location menu (internal /
shared / remotes + cache retention); cache-first listing via
`DirectoryListingCache`; single download at a time (`DownloadTracker` + one
progress bar); per-row menu = Open / Share / Save / Share-as-link (files only,
Sec B) / Copy path / Delete (single, confirmed; dirs refused — deletefile
only, no purge). No multi-select, no copy/move/mkdir/rename, no sort UI, no
history, whole-listing load (parse already on IO).

**Ops inventory:** `RcloneFileTransfer.download` (copyfile → cache `.part` →
verify → rename; progress by polling length; native call uncancellable),
`RcloneShareLink.share` (list + copyfile remote→remote + publiclink) and
`directLink` (single publiclink + `classifyLinkError`) — Sec B, untouched.
`RcloneRemoteManager` (list/create/update/delete/providers/dump) — untouched.
`transfer/` (Sec D, in progress): `TransferTask`/`TransferStore`/
`PrefsTransferStore`/`TransferExecutor` exist — download/upload shaped; Sec E
does NOT force-fit bulk ops into it, records nothing there.

**Backend capabilities (rclone v1.75.1, shim links `fs/operations` + `fs/sync`):**
usable via existing engine with no native change: `operations/copyfile`,
`operations/movefile`, `operations/deletefile`, `operations/mkdir`,
`operations/rmdir`, `operations/list`. `copyfile`/`movefile` are single-object;
directories are excluded from bulk copy/move and reported as such. Same-`fs`
= server-side; cross-`fs` = streamed through rclone on-device (labelled
VIA_DEVICE). Recursive search deferred: no cheap recursive RC shape in this
build — current-dir search stays, labelled "searches this folder".

**Coherent subset being built:** multi-select (long-press + selection bar +
clear-on-navigate) · sort (name/size/date/type, folders-first) + type filter ·
current-dir search (kept, labelled) · bulk delete (explicit listing
confirmation, per-item results, partial-failure report) · bulk copy/move via
clipboard→navigate→paste flow (unique-name resolution, SERVER_SIDE vs
VIA_DEVICE labelling, dirs excluded honestly) · mkdir + rename · item-count +
large-directory note + actionable errors. Deferred with reasons: recursive
search, offline destructive queue (never silently replay deletes), transfer
history UI (Sec D owns it).

---

**Task:** Improve browsing/managing files across rclone remotes; reuse the
existing embedded-rclone stack, no second engine, no Compose, no Tailscale.

**Will touch (new + FileBrowser-adjacent, additive):**
- New pure helpers (JVM-tested): selection state, sort/filter/search,
  bulk-plan + confirmation text, capability gating, error messages.
- `FileBrowserActivity.kt` file-row/selection/toolbar wiring ONLY (no
  share-link methods), related row/menu/dialog XML as needed.
- `storage/` bulk ops via existing `RcloneEngine` RPC (no new engine).
- New tests under `app/src/test/.../storage/` + file-management helpers.

**Will NOT touch:**
- Sec B share-link: `shareAsLink`/`pickDriveRemote`/`confirmShareLink`/
  `runShareLink`/`showLinkReady`, `RcloneShareLink.kt`(+test),
  `RcloneRemoteManager.driveRemotes()`, `dialog_share_link.xml`.
- Sec C credentials: `ServerConfig.kt`, `CredentialVault.kt`,
  `SecretStore.kt` internals, `RcloneConfigManager.kt`,
  `RcloneLogRedactor.kt`, `SecurityActivity.kt`, backup rules.
- Sec D transfers/profiles (coordinate; reuse transfer manager when
  available, do not duplicate it).
- Sec A Terminal/MainActivity back-nav/`RangeHttpServer`/streaming.

**Coordination rule:** share-link behavior preserved; destructive ops always
confirmed; unsupported backends shown unavailable, never faked.

### Note (2026-10-11): build blocked by Sec D file
`transfer/TransferManager.kt:185` uses `it.id !in jobs` where `jobs` is a
`ConcurrentHashMap` — Kotlin rejects `contains` on it (resolves to
`containsValue`, error KT-18053), so `:app:compileDebugKotlin` currently
fails for EVERYONE. Sec D owner: please use `jobs.containsKey(...)` /
`!jobs.containsKey(...)`. Sec E does not touch Sec D files; Sec E
verification (new tests + full suite) is pending that fix.

### Investigation findings (2026-10-11, read-only)

---


