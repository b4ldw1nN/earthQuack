# EarthQuack — Work Tracker

> Multi-agent coordination file. **Edit only your own section.** Each section
> names its owner. Push when your part is done.

Last updated: 2026-10-11 21:30 IST · HEAD `21c5df0` (pushed, build green)

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

## Section C — SFTP (owner: other agent)

<!-- Agent: fill in your part here. Do not edit Sections A/B. -->

(placeholder — SFTP agent updates this section)

---


