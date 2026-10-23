package io.earthquack.app

import io.earthquack.app.ssh.SecretStore

/**
 * Keystore-backed storage for the two clipboard-sync secrets.
 *
 * `ServerConfig` kept the AES key and the bearer token as plaintext strings
 * in SharedPreferences. App-private is not encrypted-at-rest, and `adb`
 * backup extraction (or a rooted-device read) yields them verbatim. The
 * SSH/SFTP side already solved this with [SecretStore]; this object applies
 * the same mechanism to the sync secrets without changing their public API.
 *
 * ## Design
 *
 * - Secrets live in [SecretStore] under fixed aliases, encrypted under a
 *   non-exportable Android Keystore AES/GCM key.
 * - Non-secret settings (host, toggles, cache days) stay in SharedPreferences.
 * - Migration is lazy and non-destructive: a legacy plaintext value is moved
 *   into the vault on first read and the plaintext is deleted only after the
 *   vault read-back verifies. A failed migration keeps the legacy value so a
 *   valid configuration is never lost.
 *
 * ## Testability
 *
 * Everything here operates on [SecretStore] + [LegacySecrets] rather than on
 * a Context, so the migration, no-clobber and failure paths are JVM-testable
 * with `InMemorySecretStore`. `ServerConfig` is the thin Context adapter.
 */
object CredentialVault {

    const val ALIAS_AES_KEY = "server_aes_key_b64"
    const val ALIAS_AUTH_TOKEN = "server_auth_token"

    /**
     * The legacy plaintext backend (a SharedPreferences wrapper in prod, a
     * map in tests). Null value means "absent".
     */
    interface LegacySecrets {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun clear(key: String)
    }

    /**
     * Reads [alias], migrating from [legacyKey] when needed.
     *
     * Order: vault first, legacy second. The legacy value is promoted into
     * the vault and cleared only after a vault read-back proves the write.
     * Any vault exception falls back to the legacy value rather than failing
     * the sync — availability of a valid configuration beats purity, and the
     * next read retries the migration.
     */
    fun resolve(
        secrets: SecretStore,
        legacy: LegacySecrets,
        legacyKey: String,
        alias: String
    ): String {
        val vaultValue = runCatching {
            secrets.get(alias)?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (vaultValue != null) return vaultValue

        val legacyValue = runCatching { legacy.get(legacyKey)?.trim() }.getOrNull().orEmpty()
        if (legacyValue.isBlank()) return ""
        // Promote, then verify before clearing the plaintext.
        val migrated = runCatching {
            secrets.put(alias, legacyValue.toByteArray(Charsets.UTF_8))
            secrets.get(alias)?.toString(Charsets.UTF_8)?.trim() == legacyValue
        }.getOrNull() == true
        if (migrated) runCatching { legacy.clear(legacyKey) }
        return legacyValue
    }

    /**
     * Stores [value] in the vault and clears the legacy plaintext.
     *
     * Blank means "forget": both backends are cleared. A vault failure keeps
     * the legacy write as a fallback so the value is not lost; the next read
     * retries migration. Never throws.
     */
    fun store(
        secrets: SecretStore,
        legacy: LegacySecrets,
        legacyKey: String,
        alias: String,
        value: String
    ) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            runCatching { secrets.delete(alias) }
            runCatching { legacy.clear(legacyKey) }
            return
        }
        val vaultOk = runCatching {
            secrets.put(alias, trimmed.toByteArray(Charsets.UTF_8))
            secrets.get(alias)?.toString(Charsets.UTF_8)?.trim() == trimmed
        }.getOrNull() == true
        if (vaultOk) {
            runCatching { legacy.clear(legacyKey) }
        } else {
            // Vault unavailable (e.g. Keystore locked/removed): preserve the
            // value in legacy storage rather than losing a valid config.
            runCatching { legacy.put(legacyKey, trimmed) }
        }
    }

    /** True when a vault entry exists (used for diagnostics, never logs values). */
    fun hasVaultEntry(secrets: SecretStore, alias: String): Boolean =
        runCatching { secrets.has(alias) }.getOrNull() == true
}
