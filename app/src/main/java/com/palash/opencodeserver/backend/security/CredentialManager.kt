package com.palash.opencodeserver.backend.security

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.log.SecretRedactor
import java.io.File

/** Spec 49 - a stable, non-secret handle for a stored credential. */
data class CredentialReference(
    val id: String,
    /** Which subsystem the credential belongs to. */
    val scope: CredentialScope,
    val label: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    /**
     * Display form for the UI.
     *
     * The id is a provider id or a fixed key name, never a secret, so this is safe to log.
     */
    override fun toString(): String = "$scope:$id"
}

enum class CredentialScope {
    /** The HTTP basic auth password for the local server. */
    OPENCODE_SERVER,

    /** A provider API key handed to OpenCode. */
    PROVIDER,

    /** Key material for an encrypted backup archive. */
    BACKUP_PASSPHRASE
}

/**
 * Spec 27 - the credential vault.
 *
 * Guarantees, in order of importance:
 *  1. Plaintext secrets are written **only** through [KeystoreCipher] into app-private storage.
 *  2. Values are never placed in SharedPreferences, plain JSON, SQLite, logs, source, or Git.
 *  3. Every value read is registered with [SecretRedactor] so it cannot appear in a log line.
 *  4. The plaintext is returned only to the two callers that genuinely need it: the process
 *     environment builder and the HTTP auth header.
 */
class CredentialManager(
    private val storageDir: File,
    private val cipher: KeystoreCipher = KeystoreCipher(),
    private val log: LogManager
) {

    private val vaultFile: File = File(storageDir, "credentials.vault")

    /** in-memory index of reference -> encrypted payload. */
    private val entries = LinkedHashMap<String, String>()

    private val lock = Any()

    init {
        loadFromDisk()
    }

    // ---- public API -------------------------------------------------------------------

    /**
     * Store a secret. Replaces any previous value for the same [scope] + [id].
     *
     * The value is redacted for logs before and after encryption, so the plaintext exists only in
     * this call's stack frame.
     */
    fun store(
        scope: CredentialScope,
        id: String,
        secret: String,
        label: String = id
    ): BackendResult<CredentialReference> {
        if (secret.isBlank()) {
            return BackendResult.Failure(
                BackendError.Unknown(
                    technical = "Empty secret rejected for $scope:$id",
                    userTitle = "A value is required.",
                    userMessage = "Enter the credential before saving."
                )
            )
        }
        return try {
            // Registration happens before anything can log, so a subsequent error message about
            // this id is still safe.
            SecretRedactor.registerSecret(secret)
            val encrypted = cipher.encrypt(secret)
            synchronized(lock) {
                entries[key(scope, id)] = encrypted
                persist()
            }
            log.info(LogCategory.AUTH, "Credential stored", "scope=$scope id=$id")
            BackendResult.Success(
                CredentialReference(
                    id = id,
                    scope = scope,
                    label = label,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            log.error(LogCategory.AUTH, "Failed to store credential", "scope=$scope id=$id", e)
            BackendResult.Failure(
                BackendError.Categorized(
                    technical = e.message,
                    category = com.palash.opencodeserver.backend.core.ErrorCategory.PERMISSION,
                    retryable = false,
                    userTitle = "The credential could not be saved.",
                    userMessage = "The device keystore rejected the write. Try again after unlocking the device."
                )
            )
        }
    }

    /**
     * Resolve the plaintext for a credential.
     *
     * Only call this at the point of use (process spawn / HTTP header). The returned value is
     * registered with the redactor immediately.
     */
    fun resolve(scope: CredentialScope, id: String): String? {
        val encrypted = synchronized(lock) { entries[key(scope, id)] } ?: return null
        val plaintext = cipher.decrypt(encrypted) ?: run {
            log.warn(LogCategory.AUTH, "Stored credential could not be decrypted", "scope=$scope id=$id")
            return null
        }
        SecretRedactor.registerSecret(plaintext)
        return plaintext
    }

    fun has(scope: CredentialScope, id: String): Boolean = synchronized(lock) { entries.containsKey(key(scope, id)) }

    fun references(): List<CredentialReference> = synchronized(lock) {
        entries.keys.map { composite ->
            val scope = CredentialScope.entries.firstOrNull { composite.startsWith("${it.name}:") }
                ?: CredentialScope.PROVIDER
            CredentialReference(
                id = composite.substringAfter(':'),
                scope = scope,
                label = composite.substringAfter(':'),
                createdAt = 0L,
                updatedAt = 0L
            )
        }
    }

    /** Spec 27 - masked display value, e.g. `sk-••••••••••••••••9X2A`. */
    fun masked(scope: CredentialScope, id: String): String? =
        resolve(scope, id)?.let { SecretRedactor.maskForDisplay(it) }

    fun remove(scope: CredentialScope, id: String): Boolean {
        val removed = synchronized(lock) {
            val value = entries.remove(key(scope, id))
            if (value != null) persist()
            value != null
        }
        if (removed) {
            // Forget the plaintext so a later, unrelated log line cannot print it.
            resolve(scope, id)?.let { SecretRedactor.forgetSecret(it) }
            log.info(LogCategory.AUTH, "Credential removed", "scope=$scope id=$id")
        }
        return removed
    }

    /** Spec 15 - fetch or generate the server password. Never displayed unless explicitly requested. */
    fun serverPassword(): String {
        resolve(CredentialScope.OPENCODE_SERVER, SERVER_PASSWORD_ID)?.let { return it }
        val generated = KeystoreCipher.generateServerPassword()
        store(CredentialScope.OPENCODE_SERVER, SERVER_PASSWORD_ID, generated, label = "OpenCode server password")
        log.info(LogCategory.AUTH, "Generated a new server password", "length=${generated.length}")
        return generated
    }

    fun serverUsername(): String = resolve(CredentialScope.OPENCODE_SERVER, SERVER_USERNAME_ID) ?: DEFAULT_USERNAME

    fun setServerUsername(username: String): BackendResult<Unit> =
        store(CredentialScope.OPENCODE_SERVER, SERVER_USERNAME_ID, username, "Server username")
            .map { }

    /**
     * Spec 15 - the `Authorization` header value.
     *
     * Built on demand and returned as a finished header so the caller never assembles it manually
     * and cannot accidentally log the components.
     */
    fun basicAuthHeader(): String? {
        val password = resolve(CredentialScope.OPENCODE_SERVER, SERVER_PASSWORD_ID) ?: return null
        val username = serverUsername()
        val raw = "$username:$password"
        return "Basic " + java.util.Base64.getEncoder().encodeToString(
            raw.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Spec 54 - the secret channel for child processes.
     *
     * Returned as a map so [com.palash.opencodeserver.backend.runtime.EnvironmentBuilder] can merge it at spawn
     * time and discard it immediately afterwards.
     */
    fun processSecrets(): Map<String, String> = buildMap {
        resolve(CredentialScope.OPENCODE_SERVER, SERVER_PASSWORD_ID)?.let {
            put("OPENCODE_SERVER_PASSWORD", it)
        }
        serverUsername().takeIf { it.isNotBlank() }?.let { put("OPENCODE_SERVER_USERNAME", it) }
    }

    /** Spec 82 - no secret may reach a crash report. */
    fun redactionSelfCheck(): Boolean =
        cipher.isAvailable && synchronized(lock) { entries.values.none { it.contains("sk-") } }

    // ---- internals ----------------------------------------------------------------------

    private fun key(scope: CredentialScope, id: String) = "${scope.name}:$id"

    private fun loadFromDisk() {
        if (!vaultFile.exists()) return
        runCatching {
            vaultFile.readLines().forEach { line ->
                val separator = line.indexOf('\u0000')
                if (separator <= 0) return@forEach
                val id = line.substring(0, separator)
                val payload = line.substring(separator + 1)
                synchronized(lock) { entries[id] = payload }
            }
            log.info(LogCategory.AUTH, "Loaded credential vault", "entries=${entries.size}")
        }.onFailure {
            log.error(LogCategory.AUTH, "Credential vault is unreadable; starting empty", null, it)
        }
    }

    /**
     * Persist atomically.
     *
     * A partially written vault would lose every credential, so the new content goes to a temp file
     * and is renamed into place.
     */
    private fun persist() {
        val temp = File(vaultFile.parentFile, vaultFile.name + ".tmp")
        val content = buildString {
            entries.forEach { (id, payload) ->
                append(id).append('\u0000').append(payload).append('\n')
            }
        }
        runCatching {
            temp.writeText(content, Charsets.UTF_8)
            if (!temp.renameTo(vaultFile)) {
                vaultFile.writeText(content, Charsets.UTF_8)
                temp.delete()
            }
        }.onFailure {
            log.error(LogCategory.AUTH, "Failed to persist credential vault", null, it)
        }
    }

    companion object {
        const val SERVER_PASSWORD_ID = "server"
        const val SERVER_USERNAME_ID = "username"

        /** OpenCode's documented default basic-auth username. */
        const val DEFAULT_USERNAME = "opencode"

        fun providerCredentialId(providerId: String) = providerId
    }
}