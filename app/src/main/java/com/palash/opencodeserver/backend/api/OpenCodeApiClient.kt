package com.palash.opencodeserver.backend.api

import com.palash.opencodeserver.backend.core.ApiConnectionStatus
import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.ErrorCategory
import com.palash.opencodeserver.backend.core.OpenCodeConnection
import kotlinx.coroutines.flow.SharedFlow

/**
 * Spec 20 - the API surface the frontend depends on.
 *
 * Every method maps to a documented endpoint. No undocumented route is invented here, and no
 * method bypasses the transport (so auth, timeouts and error mapping apply uniformly).
 */
interface OpenCodeApiClient {

    /** Spec 49 - never contains the password; only a credential reference. */
    val connection: OpenCodeConnection?

    /** Spec 18 - `GET /global/health`. The only proof that the server is truly up. */
    suspend fun health(): BackendResult<HealthResponse>

    // ---- project / path --------------------------------------------------------------

    suspend fun listProjects(directory: String? = null): BackendResult<List<OcProject>>

    suspend fun currentProject(directory: String? = null): BackendResult<OcProject>

    /** `GET /path` - tells us the server's idea of its working directory. */
    suspend fun currentPath(directory: String? = null): BackendResult<OcPath>

    /** `GET /vcs` - real VCS state, never invented. */
    suspend fun vcsInfo(directory: String? = null): BackendResult<OcVcsInfo>

    // ---- sessions --------------------------------------------------------------------

    suspend fun listSessions(directory: String? = null): BackendResult<List<OcSession>>

    suspend fun createSession(
        title: String? = null,
        parentId: String? = null,
        directory: String? = null
    ): BackendResult<OcSession>

    suspend fun getSession(sessionId: String, directory: String? = null): BackendResult<OcSession>

    suspend fun deleteSession(sessionId: String, directory: String? = null): BackendResult<Boolean>

    suspend fun updateSessionTitle(
        sessionId: String,
        title: String,
        directory: String? = null
    ): BackendResult<OcSession>

    /** Spec 24 `continueSession()` - reuse an existing session for another prompt. */
    suspend fun abortSession(sessionId: String, directory: String? = null): BackendResult<Boolean>

    /** Spec 24 - real activity state per session (idle / busy / retry). */
    suspend fun sessionStatuses(directory: String? = null): BackendResult<Map<String, OcSessionStatus>>

    suspend fun sessionMessages(sessionId: String, limit: Int? = null, directory: String? = null):
        BackendResult<List<OcMessageWithParts>>

    suspend fun sessionDiff(sessionId: String, messageId: String? = null, directory: String? = null):
        BackendResult<List<OcFileDiff>>

    /** Spec 25 - blocking prompt. Response arrives on the event stream, not here. */
    suspend fun sendPrompt(
        sessionId: String,
        text: String,
        model: OcModelSelection? = null,
        agent: String? = null,
        directory: String? = null
    ): BackendResult<OcMessageWithParts>

    /**
     * Spec 69 - fire-and-forget prompt.
     *
     * Preferred over [sendPrompt] for the UI: the assistant reply streams in over SSE, so the HTTP
     * call does not need to stay open for the whole generation.
     */
    suspend fun sendPromptAsync(
        sessionId: String,
        text: String,
        model: OcModelSelection? = null,
        agent: String? = null,
        directory: String? = null
    ): BackendResult<Unit>

    // ---- files -------------------------------------------------------------------------

    /** `GET /file` - directory listing with ignore state. */
    suspend fun listFiles(path: String = "", directory: String? = null): BackendResult<List<OcFileNode>>

    /** `GET /file/content`. Binary content is reported, not silently mangled. */
    suspend fun readFile(path: String, directory: String? = null): BackendResult<OcFileContent>

    /** `GET /file/status` - tracked-file change summary from the server's own index. */
    suspend fun fileStatuses(directory: String? = null): BackendResult<List<OcFileStatus>>

    suspend fun findFiles(
        query: String,
        type: String? = null,
        limit: Int = 100,
        directory: String? = null
    ): BackendResult<List<String>>

    suspend fun findText(pattern: String, directory: String? = null): BackendResult<List<Map<String, Any?>>>

    // ---- providers / config -------------------------------------------------------------

    suspend fun listProviders(directory: String? = null): BackendResult<OcProviderListResponse>

    /** `GET /provider/auth` - auth methods, so the UI offers only supported flows. */
    suspend fun providerAuthMethods(directory: String? = null): BackendResult<Map<String, List<OcProviderAuthMethod>>>

    /**
     * Spec 26/27 - hand a key to OpenCode's own auth store.
     *
     * The key is placed in a request body over loopback and is never written to a file by the app
     * before OpenCode persists it.
     */
    suspend fun setApiAuth(providerId: String, apiKey: String, directory: String? = null): BackendResult<Boolean>

    suspend fun getConfig(directory: String? = null): BackendResult<OcConfig>

    /** Spec 47 - read the effective config rather than assuming what the server loaded. */
    suspend fun patchConfig(config: OcConfig, directory: String? = null): BackendResult<OcConfig>

    suspend fun listAgents(directory: String? = null): BackendResult<List<OcAgent>>

    suspend fun listCommands(directory: String? = null): BackendResult<List<OcCommand>>

    /** Spec 41 - push a structured log entry into OpenCode's own log. */
    suspend fun writeLog(request: OcLogRequest, directory: String? = null): BackendResult<Boolean>

    // ---- introspection --------------------------------------------------------------------

    /**
     * Spec 91 - fetch the running server's OpenAPI 3.1 spec from `/doc`.
     *
     * Used during development and by diagnostics so the client is never validated against a stale
     * hard-coded contract.
     */
    suspend fun openApiSpec(): BackendResult<String>

    fun observeConnectionStatus(): SharedFlow<ApiConnectionStatus>
}

/** Spec 106 - one place that defines how long each class of request may take. */
data class ApiTimeouts(
    val connectMs: Long = 5_000,
    /** Read timeout for ordinary request/response calls. */
    val responseMs: Long = 120_000,
    /** Startup budget for the server to become healthy. */
    val startupMs: Long = 45_000,
    /** SSE connections stay open indefinitely by design. */
    val streamingReadTimeoutMs: Long = 0
)

/** Spec 52 - shared mapping helpers used by the HTTP implementation. */
internal fun classifyStatus(code: Int, endpoint: String, body: String?): BackendError? = when (code) {
    in 200..299 -> null
    401, 403 -> BackendError.Authentication("HTTP $code from $endpoint")
    404 -> BackendError.MalformedResponse(endpoint, "HTTP 404 - endpoint may not exist in this OpenCode version")
    408, 504 -> BackendError.Timeout(endpoint, 0)
    else -> BackendError.Categorized(
        technical = "HTTP $code from $endpoint${body?.takeIf { it.isNotBlank() }?.let { ": ${it.take(300)}" } ?: ""}",
        category = ErrorCategory.API,
        retryable = code >= 500,
        userTitle = "The server rejected the request.",
        userMessage = "OpenCode returned an unexpected response. Diagnostics were recorded."
    )
}