# Credential rotation (EarthQuack)

Manual steps. Nothing here is automated: the app never revokes credentials
and Git history is never rewritten without explicit owner action.

## What is confirmed exposed

| Secret | Evidence | Status |
|---|---|---|
| Node auth token (`thisisworking`) | `git show 5f016f7:config.json` → `{"auth":{"token":"thisisworking"}}`; deleted in `17b1875` but `5f016f7` remains reachable in history | **Confirmed exposed — rotate** |
| Clipboard AES key | Mentioned in `docs/HOMESERVER.md` as local-only (`cline` checkpoints); no tracked file with a key value found in current tree | Suspected local-history only — verify before rotating |
| Android AES key / bearer token (plaintext prefs on upgraded installs) | `ServerConfig.kt` pre-hardening + `strings_sub.xml security_storage_warning` | Migrated to Keystore vault on first run post-update; no remote exposure |
| rclone.conf OAuth tokens / passwords | By design of rclone remotes; file is app-private, 600, backup-excluded | At-risk on rooted/adb-backup extraction pre-`allowBackup=false`; no evidence of commit |

Full-history scan was NOT completed (history is large with many checkpoint
merges); do not treat history as clean. A `gitleaks detect --source . --verbose`
run is still outstanding — see tracker Section C.

## Rotate the node auth token

1. Generate: `openssl rand -hex 32`
2. Desktop: update `~/.config/earthquack/config.json` (`auth.token`) **or**
   `export EARTHQUACK_AUTH_TOKEN=<new>` (env wins over file).
3. Phone: Settings → Security → paste token into Bearer-token field.
4. Restart node (`--host <tailnet-ip>`) and force-stop/restart the app.
5. Verify: `curl -H "Authorization: Bearer <new>" http://<host>:8890/api/node`
   → 200; old token → 401/503.

## Rotate the clipboard AES key

1. Phone: Settings → Security → Generate (or paste a 44-char Base64 32-byte key).
2. Desktop: set the identical value as `CLIPBOARD_AES_KEY` / `clipboard_aes_key`.
3. Both sides must match — mismatched keys produce undecryptable `AES:` payloads
   (the app surfaces "Key does not match" rather than failing silently).

## Rotate rclone remotes

Per-remote in rclone.conf (`filesDir/rclone/rclone.conf`, app-private):
- OAuth remotes (Drive/Dropbox/OneDrive): re-authorise via the provider's
  flow and `config/update`; old refresh tokens die provider-side.
- Password remotes (SFTP/FTP/S3): change the server-side password, then update
  the remote in-app. Delete + recreate the remote for certainty
  (`config/delete` → `config/create`).

## Purge history (owner decision, destructive)

Rotation first — purging without rotating protects nothing.

```sh
git filter-repo --path config.json --invert-paths
git push --force --all && git push --force --tags
# Then ask GitHub Support to GC unreachable objects; assume forks/archives
# (Software Heritage, grep.app) already hold the old value.
```

## Backup posture

- `android:allowBackup="false"` — app files (vault blobs, rclone.conf,
  prefs) are excluded from adb/cloud backup. Verified in
  `app/src/main/AndroidManifest.xml:51`. No `backupRules`/`fullBackupContent`
  file re-includes them (none exists in tree).
- Do NOT flip `allowBackup` on without a per-domain rules file excluding
  `files/secrets/`, `files/rclone/`, and `shared_prefs/earthquack_prefs.xml`.
