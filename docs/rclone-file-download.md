# Task: rclone File Download and Open (Files screen)

**Status:** open-worth-using; next is video streaming
**Owner:** any agent picking this file up (see *Handover*)
**Commits:** `3729e2a` (open/download/cache), `e2cfabe` (bugfixes + PDF viewer + cache settings), base `ec91eae`

---

## 1. Root cause (confirmed)

`FileBrowserActivity.onCreate` wired the adapter as:

```kotlin
adapter = FileEntryAdapter(
    onOpen = { entry -> if (entry.isDir) openFolder(entry) },
    ...
)
```

A tap on a **directory** navigated; a tap on a **file** fell through the `if`
with no `else`. The row's "Download" overflow item was also hard-disabled with
the note "Not available: needs a desktop file id".

**Second crash found on device:** `listingCache` was a property initialiser
calling `cacheDir`, which runs before the activity is attached to a Context →
`NullPointerException` at construction, i.e. "clicking a drive closes the app".
Fixed with `by lazy`.

## 2. Transfer mechanism (reused, not rebuilt)

`operations/copyfile` with `srcFs`/`srcRemote`/`dstFs`/`dstRemote`. The remote
is the source; the destination is an on-the-fly local filesystem
`:local:<cache>/opened/<key>/<name>`.

- Registered in `fs/operations`, which `rclone-android/rclone/rclone.go`
  **already** blank-imports → no Go/NDK change needed.
- rclone streams the object straight to the local file, so the Kotlin side
  only ever sees the JSON envelope. No `ByteArray`, no in-memory buffering.
- `_async` + `job/status` was evaluated and rejected: it needs `fs/rc/jobs`
  linked, a Go rebuild, and no NDK is installed here.

### The download bug that made "nothing open" (root-caused on device)

The transfer wrote to `<name>.part` and then renamed it, but passed
`dstRemote = <name>` to rclone. So rclone wrote the final name, `.part` never
existed, and the check "copyfile reported success but ... is absent" fired —
a failure, nothing opened. A second tap "worked" only because it opened what
rclone had already written. Fixed by asking rclone for the `.part` name;
`RcloneFileTransferTest` now pins it with a regression test.

### Stable per-file cache keys

Each download used to get a random UUID, so every tap re-downloaded. Now
`RcloneDownloadPlanner.cacheKey(fs, path, size)` gives a stable directory, and
a complete cached copy is opened without a round trip.

## 3. Files changed

| File | What |
|---|---|
| `storage/RcloneDownloader.kt` | Destination naming, sanitising, cache key, completion rule |
| `storage/RcloneFileTransfer.kt` | `operations/copyfile` on IO, `.part`-then-rename, error mapping, cancellation, progress |
| `storage/DownloadCache.kt` | Size/count/clear/prune, `.part` files protected |
| `storage/DirectoryListingCache.kt` | Raw `operations/list` reply cache, SHA-256 key, TTL prune |
| `ui/FileOpener.kt` | MIME resolution, preview classification, external launch |
| `ui/FilePreviewActivity.kt` + `PdfDocument.kt` | Image/Video/Audio/PDF in-app viewing |
| `ui/sub/FilesCacheActivity.kt` | Retention setting, cache size, delete now |
| `ServerConfig.kt` | Retention days for listings and downloads |
| `res/xml/file_paths.xml` | `<cache-path name="opened" path="opened/" />` only |
| `AndroidManifest.xml` | FileProvider, `FilePreviewActivity`, `FilesCacheActivity` |
| `FileBrowserActivity.kt` | File branch, cache-first load, UI states, row actions |
| `strings_files_open.xml`, layouts | New strings, stop button, cache screen, page list |

## 4. Security and cache behaviour — verified

- No `file://` URIs anywhere; every hand-off is `content://` from FileProvider.
- Permission is `FLAG_GRANT_READ_URI_PERMISSION` only, scoped to the one URI.
- Provider root is exactly `cache/opened/`.
- **"Delete the cache" verified on device:** only `cache/opened/` was emptied.
  All 17 remotes in `files/rclone/rclone.conf` are intact and byte-identical,
  because that file lives under `files/`, not `cache/`. `DownloadCacheTest`
  pins the confinement property.
- Retention is a setting; "Off" means nothing is served from cache, and
  changing it prunes immediately.

## 5. Results

- `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL** (231 tests).
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**.
- On device (Android 15, arm64):
  - Sizes now correct: `34.9 KB`, `31.0 KB`, `110.6 KB` (was showing MB/GB).
  - Image downloads and `FilePreviewActivity` opens it automatically.
  - PDF opens in the built-in viewer with the page count and "1 pages".
  - Cache screen showed `32.3 GB in 25 files` — **wrong**, it was 32.3 MB;
    fixed by reusing the tested `formatSize`, now shows `Nothing cached`.
  - Delete emptied `cache/opened/` only; remotes verified untouched.

## 6. OPEN

### 6.1 Video streaming (requested, not started)
User asked: "for videos, instead of completely downloading it, cant we just
stream, like youtube". Needs a local range-capable HTTP server (or a
pipe-based descriptor) plus Media3/ExoPlayer, because `VideoView` cannot
consume a partial file. **This is not a small change — plan before coding.**

### 6.2 Remaining per-row actions (requested, partially done)
Done: Open, Share, Save to Downloads, Copy path, Delete. Still worth adding:
Rename/Move, Properties (size/type/modified), "Open with" (force the chooser).

### 6.3 Download cache is one flat `opened/`
`DownloadCache` walks it; a very large number of files would slow the walk. A
size budget or LRU cap is worth considering before it grows to hundreds.

---

## 7. Handover

- Do **not** claim device-tested results that were not run.
- `docs/rclone-file-download.md` (this file) is the tracker — update it
  before finishing.
- `formatSize` in `ui/FileEntryAdapter.kt` is the single tested byte formatter;
  reuse it, do not write another.
- Cache is deliberately raw-JSON so a cached listing renders through the
  same parse path as a live one.
