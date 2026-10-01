package com.palash.opencodeserver.backend.log

/**
 * Spec 108 - every log write and every user-visible technical detail passes through this.
 *
 * The redactor is deliberately conservative: it errs toward redacting, because a false positive
 * costs a diagnostic line while a false negative leaks a credential (spec 82).
 */
object SecretRedactor {

    private const val MASK = "[REDACTED]"

    /**
     * Literals shorter than this are not registered.
     *
     * Redacting a short string would corrupt ordinary log text ("the add failed" containing "add"),
     * while the secrets this app handles are all considerably longer.
     */
    private const val MIN_SECRET_LENGTH = 8

    /** Long-lived secrets registered at runtime (server password, provider keys). */
    private val knownSecrets = LinkedHashSet<String>()

    private val patterns: List<Regex> = listOf(
        // Authorization headers of every scheme.
        Regex("""(?i)\b(authorization)\s*[:=]\s*(\S+\s+\S+|bearer\s+[A-Za-z0-9._\-+/=]+)"""),
        // Key/value assignments whose key names a secret.
        //
        // The key is captured whole rather than just the keyword, so `OPENCODE_SERVER_PASSWORD`
        // is recognised: a plain `\b` before "password" would not match, because the preceding
        // underscore is itself a word character. The prefix character class also keeps the match
        // anchored to a real key rather than matching inside a word.
        Regex(
            """(?i)([A-Za-z0-9_.\-]*(?:password|passwd|pwd|secret|token|api[_-]?key|apikey|access[_-]?key|client[_-]?secret|credential|cookie)[A-Za-z0-9_.\-]*)\s*[:=]\s*["']?([^\s"',;)}\]]+)"""
        ),
        // Well known key shapes.
        Regex("""\bsk-[A-Za-z0-9._\-]{8,}"""),
        Regex("""\bsk-ant-[A-Za-z0-9._\-]{8,}"""),
        Regex("""\bgh[pousr]_[A-Za-z0-9]{16,}"""),
        Regex("""\bxox[baprs]-[A-Za-z0-9-]{10,}"""),
        Regex("""\bAIza[0-9A-Za-z_\-]{20,}"""),
        Regex("""\bey[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"""),
        // Basic auth embedded in a URL.
        Regex("""(?i)\b([a-z][a-z0-9+.\-]*://)[^/\s:@]+:([^/\s@]+)@"""),
        // Private key blocks.
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
        // Cookie headers.
        Regex("""(?i)\b(cookie|set-cookie)\s*[:=]\s*[^\n]+""")
    )

    /**
     * Register a literal secret so it is scrubbed even when it does not match a shape-based rule.
     * Values shorter than 8 characters are ignored: redacting them would destroy ordinary text.
     */
    @Synchronized
    fun registerSecret(secret: String?) {
        val trimmed = secret?.trim().orEmpty()
        if (trimmed.length >= MIN_SECRET_LENGTH) knownSecrets.add(trimmed)
    }

    @Synchronized
    fun forgetSecret(secret: String?) {
        val trimmed = secret?.trim().orEmpty()
        if (trimmed.length >= MIN_SECRET_LENGTH) knownSecrets.remove(trimmed)
    }

    @Synchronized
    fun clearRegisteredSecrets() = knownSecrets.clear()

    /** Redact a free-form string. Returns an empty string for null or blank input. */
    fun redact(input: String?): String {
        val source = input.orEmpty()
        if (source.isEmpty()) return ""
        var working = source

        // Literal secrets first: they are unambiguous and cheap.
        for (literal in snapshotSecrets()) {
            if (literal.isNotBlank() && working.contains(literal)) {
                working = working.replace(literal, MASK)
            }
        }

        for (pattern in patterns) {
            working = pattern.replace(working) { match ->
                redactMatch(match.groupValues)
            }
        }

        return working
    }

    /**
     * Decide how a single pattern match renders.
     *
     * Each pattern keeps its capturing groups meaningful: group 1 is the key or prefix to preserve,
     * and any later group holds the secret. Preserving the key is what keeps a redacted log line
     * useful for diagnosis.
     */
    private fun redactMatch(groups: List<String>): String = when {
        groups.size >= 3 && groups[1].isNotBlank() && groups[2].isNotBlank() ->
            "${groups[1]}=${MASK}"

        groups.size >= 2 && groups[1].isNotBlank() ->
            "${groups[1]}=${MASK}"

        // Group 0 only: the whole match is secret-shaped, so nothing is preserved.
        else -> MASK
    }

    /** A consistent snapshot of the registered literals, taken under the lock. */
    private fun snapshotSecrets(): List<String> = synchronized(this) { knownSecrets.toList() }

    /** Redact a whole map, used before writing process environment variables anywhere. */
    fun redactAll(values: Map<String, String>): Map<String, String> =
        values.mapValues { (key, value) ->
            if (isSensitiveKey(key)) MASK else redact(value)
        }

    /** Keys whose values must never be printed, regardless of value shape. */
    fun isSensitiveKey(key: String): Boolean {
        val k = key.lowercase()
        return k.contains("password") || k.contains("secret") || k.contains("token") ||
            k.contains("api_key") || k.contains("apikey") || k.contains("api-key") ||
            k.contains("credential") || k.contains("cookie") || k.contains("authorization") ||
            k == "key" || k.endsWith("_key") || k.endsWith(".key")
    }

    /**
     * Spec 27 - display masking for the UI, e.g. `sk-••••••••••••••••9X2A`.
     *
     * The visible prefix is limited to a vendor-shaped prefix and a 4 character suffix; nothing in
     * between is ever produced.
     */
    fun maskForDisplay(secret: String?, visibleSuffix: Int = 4, bullets: Int = 12): String {
        if (secret.isNullOrEmpty()) return ""
        val prefix = when {
            secret.startsWith("sk-ant-") -> "sk-ant-"
            secret.startsWith("sk-") -> "sk-"
            secret.startsWith("ghp_") -> "ghp_"
            secret.startsWith("xox") -> secret.take(4)
            else -> ""
        }
        if (prefix.isEmpty() && secret.length <= visibleSuffix) return "•".repeat(8)
        val suffix = if (secret.length > visibleSuffix) secret.takeLast(visibleSuffix) else ""
        return buildString {
            append(prefix)
            append("•".repeat(bullets))
            if (suffix.isNotEmpty()) append(suffix)
        }
    }

    /**
     * Spec 59 - redact personal paths in a copyable diagnostics blob while keeping the shape
     * recognisable enough to be useful.
     */
    fun redactPaths(text: String, homeDir: String?): String {
        var out = text
        homeDir?.takeIf { it.isNotBlank() }?.let { out = out.replace(it, "~") }
        return out
    }
}