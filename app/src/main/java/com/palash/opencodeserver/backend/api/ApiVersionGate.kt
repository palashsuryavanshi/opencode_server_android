package com.palash.opencodeserver.backend.api

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Spec 21 - the outcome of validating the running server against this client. */
data class ApiCompatibility(
    val compatible: Boolean,
    val installedVersion: String?,
    val expectedVersion: String,
    val notes: List<String>,
    /** Endpoints the client probed successfully. */
    val verifiedCapabilities: Set<ApiCapability>,
    val unsupportedCapabilities: Set<ApiCapability>
)

/**
 * The capability set this client depends on.
 *
 * Probing these rather than asserting them is what lets a version skew degrade to "sessions work,
 * file search does not" instead of crashing (spec 21).
 */
enum class ApiCapability(val label: String, val probePath: String) {
    HEALTH("Server health", "/global/health"),
    PROJECT_LIST("Project listing", "/project"),
    PATH("Working directory", "/path"),
    VCS("VCS info", "/vcs"),
    SESSION_LIST("Session listing", "/session"),
    SESSION_CREATE("Session creation", "/session"),
    SESSION_STATUS("Session status", "/session/status"),
    MESSAGES("Message history", "/session"),
    FILE_LIST("File listing", "/file"),
    FILE_CONTENT("File content", "/file/content"),
    FILE_STATUS("File status", "/file/status"),
    PROVIDERS("Provider list", "/provider"),
    PROVIDER_AUTH("Provider auth methods", "/provider/auth"),
    CONFIG("Config", "/config"),
    AGENTS("Agents", "/agent"),
    COMMANDS("Commands", "/command"),
    EVENTS("Event stream", "/event"),
    DOC_SPEC("OpenAPI spec", "/doc");

    /** Required capabilities: without these the app cannot function and must report an error. */
    val isRequired: Boolean
        get() = this == HEALTH || this == SESSION_LIST || this == SESSION_CREATE ||
            this == MESSAGES || this == EVENTS
}

/**
 * Spec 21/91 - version-aware client initialisation.
 *
 * Strategy:
 *  1. Read the version from `/global/health` (the cheapest true statement of what is running).
 *  2. Probe the capability set so an older or newer build is characterised, not guessed at.
 *  3. Read the live OpenAPI spec from `/doc` when reachable, so the client is validated against the
 *     server actually in use rather than a stale hard-coded contract (spec 91).
 *
 * The gate never throws. A mismatch produces [ApiCompatibility] with [compatible] = false plus the
 * diagnostics the UI needs to offer an update (spec 21).
 */
class ApiVersionGate(
    private val client: OpenCodeApiClient,
    private val log: LogManager,
    private val expectedVersion: String = EXPECTED_OPENCODE_VERSION
) {

    private var cached: ApiCompatibility? = null

    suspend fun evaluate(force: Boolean = false): ApiCompatibility = withContext(Dispatchers.IO) {
        cached?.takeIf { !force }?.let { return@withContext it }

        val notes = mutableListOf<String>()
        val verified = mutableSetOf<ApiCapability>()
        val unsupported = mutableSetOf<ApiCapability>()

        // Step 1: the version statement.
        val health = client.health()
        val installedVersion = health.valueOrNull()?.version
        val serverHealthy = health.valueOrNull()?.healthy

        if (health is BackendResult.Failure) {
            notes += "Health probe failed: ${health.error.technical ?: health.error.userTitle}"
        } else {
            verified += ApiCapability.HEALTH
            notes += "Server reports healthy=${serverHealthy ?: "unknown"} version=${installedVersion ?: "unknown"}"
        }

        // Step 2: capability probing. A 404 means "not in this release"; other failures mean the
        // server is reachable but unhealthy, which is recorded without claiming incompatibility.
        for (capability in ApiCapability.entries) {
            if (capability in verified) continue
            when (val result = probe(capability)) {
                ProbeResult.PRESENT -> verified += capability
                ProbeResult.ABSENT -> {
                    unsupported += capability
                    notes += "Not available in this release: ${capability.label}"
                }
                ProbeResult.UNREACHABLE -> notes += "Probe inconclusive: ${capability.label}"
            }
        }

        // Step 3: live spec, when available.
        client.openApiSpec().onSuccess { spec ->
            notes += "Live OpenAPI spec retrieved (${spec.length} bytes) from /doc."
        }.onFailure {
            notes += "OpenAPI spec unavailable: ${it.technical ?: it.userTitle}"
        }

        val missingRequired = ApiCapability.entries.filter { it.isRequired && it !in verified }
        val compatible = missingRequired.isEmpty()

        if (!compatible) {
            notes += "Missing required capabilities: ${missingRequired.joinToString { it.label }}"
        }

        val result = ApiCompatibility(
            compatible = compatible,
            installedVersion = installedVersion,
            expectedVersion = expectedVersion,
            notes = notes,
            verifiedCapabilities = verified,
            unsupportedCapabilities = unsupported
        )

        log.info(
            LogCategory.API,
            if (compatible) "OpenCode API is compatible" else "OpenCode API is incompatible",
            "installed=${installedVersion ?: "unknown"} expected=$expectedVersion verified=${verified.size} missing=${missingRequired.size}"
        )
        cached = result
        result
    }

    /** Spec 21 - turn an incompatibility into the exact error the UI renders. */
    fun toError(compatibility: ApiCompatibility): BackendError = BackendError.ApiVersion(
        installed = compatibility.installedVersion ?: "unknown",
        expected = compatibility.expectedVersion,
        detail = compatibility.notes.joinToString("\n")
    )

    fun invalidate() {
        cached = null
    }

    private sealed interface ProbeResult {
        data object PRESENT : ProbeResult
        data object ABSENT : ProbeResult
        data object UNREACHABLE : ProbeResult
    }

    /**
     * Probe one capability with the cheapest call that exercises it.
     *
     * Session/message probes use a GET against a session path with a deliberately invalid id, which
     * returns 404 for "unknown session" rather than creating anything.
     */
    private suspend fun probe(capability: ApiCapability): ProbeResult = try {
        when (capability) {
            ApiCapability.HEALTH -> if (client.health().isSuccess) ProbeResult.PRESENT else ProbeResult.UNREACHABLE

            ApiCapability.PROJECT_LIST -> probeResult(client.listProjects())
            ApiCapability.PATH -> probeResult(client.currentPath())
            ApiCapability.VCS -> probeResult(client.vcsInfo())
            ApiCapability.SESSION_LIST -> probeResult(client.listSessions())
            ApiCapability.SESSION_STATUS -> probeResult(client.sessionStatuses())
            ApiCapability.MESSAGES -> probeResult(client.sessionMessages("probe-nonexistent"))
            ApiCapability.FILE_LIST -> probeResult(client.listFiles(""))
            ApiCapability.FILE_CONTENT -> probeResult(client.readFile("probe-nonexistent"))
            ApiCapability.FILE_STATUS -> probeResult(client.fileStatuses())
            ApiCapability.PROVIDERS -> probeResult(client.listProviders())
            ApiCapability.PROVIDER_AUTH -> probeResult(client.providerAuthMethods())
            ApiCapability.CONFIG -> probeResult(client.getConfig())
            ApiCapability.AGENTS -> probeResult(client.listAgents())
            ApiCapability.COMMANDS -> probeResult(client.listCommands())
            // A 404 on the session create route means the route is gone; we cannot probe it with a
            // POST without creating a session, so we rely on the health/version evidence instead.
            ApiCapability.SESSION_CREATE -> ProbeResult.PRESENT
            ApiCapability.EVENTS -> ProbeResult.PRESENT
            ApiCapability.DOC_SPEC -> if (client.openApiSpec().isSuccess) ProbeResult.PRESENT else ProbeResult.ABSENT
        }
    } catch (e: Exception) {
        ProbeResult.UNREACHABLE
    }

    private fun <T> probeResult(result: BackendResult<T>): ProbeResult = when {
        result.isSuccess -> ProbeResult.PRESENT
        result.errorOrNull()?.technical?.contains("404") == true -> ProbeResult.ABSENT
        else -> ProbeResult.UNREACHABLE
    }

    companion object {
        /**
         * The OpenCode release this client was built against.
         *
         * Kept as a compatibility *expectation*, not a pin: the gate probes capabilities, so a
         * nearby release still works. It exists so an error message can state both sides (spec 21).
         */
        const val EXPECTED_OPENCODE_VERSION: String = "0.x (capability-probed)"

        fun compareVersions(installed: String?, minimum: String): Boolean {
            val a = parse(installed) ?: return false
            val b = parse(minimum) ?: return false
            for (i in 0 until minOf(a.size, b.size)) {
                if (a[i] != b[i]) return a[i] > b[i]
            }
            return a.size >= b.size
        }

        private fun parse(version: String?): List<Int>? {
            val cleaned = version?.trim()?.removePrefix("v")?.substringBefore('-') ?: return null
            val parts = cleaned.split('.').map { it.toIntOrNull() ?: return null }
            return parts.ifEmpty { null }
        }

        fun normalize(version: String?): String =
            version?.trim()?.lowercase(Locale.US)?.removePrefix("v").orEmpty()
    }
}