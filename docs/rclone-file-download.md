# Task: rclone file download and external open (Files screen)

**Status:** in progress
**Owner:** agent working from this file (see *Handover* at the end)
**Branch/commit:** work on current branch; commit as `feat(files): download and open rclone files`

---

## 1. Root cause (found)

`FileBrowserActivity.onCreate` wires the adapter as:

```kotlin
adapter = FileEntryAdapter(
    onOpen = { entry -> if (entry.isDir) openFolder(entry) },
    ...
)
```

A tap on a **directory** navigates. A tap on a **file** is dropped on the floor: the
callback body is an `if (entry.isDir)` with no `else`, so the tap is acknowledged
by the row's click listener and then does nothing. It is neither a missing listener
nor a disabled view — the listener exists and intentionally only handles
directories. The per-row overflow menu's "Download" item is also present but
hard-disabled with `download.isEnabled = false` and the note "Not available: needs
a desktop file id".

Conclusion: no click listener is missing and nothing crashes; the click handler
has no file branch.

## 2. Existing APIs to reuse (do not reimplement)

| Concern | Existing thing |
|---|---|
| Native surface | `RcloneEngine.call(method, params)` → `librclone.RPC` via `NativeRcloneBridge` |
| Remote config | `RcloneConfigManager`, `RcloneRemoteManager` (unchanged) |
| Path model | `"fs"` + `"remote"`-relative path (`path` list in `FileBrowserActivity`) |
| Listing | `operations/list` with both `fs` and `remote` |
| Entry model | `ui.FileEntry` (`name`, `path`, `isDir`, `size`, `mimeType`, `modified`) |
| Row click | `FileEntryAdapter.onOpen` |
| Coroutines | `lifecycleScope` + `Dispatchers.IO` (already the pattern in this screen) |
| Errors | `RcloneException` + `describe()` in the activity |

## 3. Transfer mechanism (chosen)

`operations/copyfile` — srcFs/srcRemote (the remote) → dstFs/dstRemote (a local
`:local:` filesystem rooted at an app cache dir).

Rationale:
- It is registered in `fs/operations`, which the shim **already** blank-imports
  (`_ "github.com/rclone/rclone/fs/operations"`), so it is callable today with
  no Go change. Proven by `RcloneConfigInstrumentedTest.fileOperationsAgainstConfiguredRemote`.
- rclone copies the object **directly from the backend to the local file**, streaming
  in chunks. Nothing is loaded into a Kotlin `ByteArray` — the Kotlin side only
  sees the JSON envelope.
- It preserves every byte, so arbitrary binary types work and the result can be
  compared byte-for-byte with the source.

Bridge status: **no rclone bridge change is needed.** `operations/copyfile` is
sufficient and already linked. `_async` + `job/status` were evaluated as well;
they need `fs/rc/jobs` linked in (not currently imported) so they are left out
of scope. Progress is reported by polling the local file's size during the copy.

## 4. Plan

1. `storage/RcloneDownloader.kt` — pure-Kotlin request/result types, local-cache
   destination naming, filename sanitising, duplicate guard, path helpers
   (unit-testable, no Android deps).
2. `storage/RcloneFileTransfer.kt` — runs `operations/copyfile` on `Dispatchers.IO`
   via the existing `RcloneEngine`; no in-memory buffering; error mapping.
3. `ui/FileOpener.kt` — MIME resolution + `FileProvider.getUriForFile` +
   `ACTION_VIEW` + `ActivityNotFoundException` handling.
4. `res/xml/file_paths.xml` — narrow `<cache-path name="opened" path="opened/" />`.
5. `AndroidManifest.xml` — register `FileProvider` with authority
   `${applicationId}.fileprovider` (none exists today).
6. `FileBrowserActivity` — wire `onOpen` to `openFile(entry)`, show progress,
   surface the six UI states, guard duplicate taps, update the overflow menu.
7. Tests: `app/src/test/.../storage/RcloneDownloaderTest.kt`,
   `RcloneFileTransferTest.kt`, `ui/FileOpenerTest.kt` (MIME only).
8. `./gradlew test assembleDebug`.

## 5. UI states to cover

- downloading (indeterminate progress bar, already in the layout)
- opening
- download failed
- file unavailable / permission denied
- no compatible viewer installed
- download cancelled (back / job cancelled → partial file removed)

## 6. Progress log

- [x] Root cause identified
- [ ] Implementation
- [ ] Tests
- [ ] Build
- [ ] On-device verification (NEEDS A REAL DEVICE — see limitations)

## 7. Handover notes (for the next agent)

- Do **not** claim device-tested unless a device run happened. Section 8 must stay
  honest.
- Testable logic is kept out of the Activity so it survives on the JVM.
- The existing `FileTransferService` (desktop-id based) is a different feature and
  is untouched deliberately.

## 8. Actual results (fill in, distinguish "ran" from "not run")

TBD.
