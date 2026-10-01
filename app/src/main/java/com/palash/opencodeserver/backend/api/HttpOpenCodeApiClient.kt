package com.palash.opencodeserver.backend.api

import com.palash.opencodeserver.backend.core.ApiConnectionStatus
import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.ErrorCategory
import com.palash.opencodeserver.backend.core.OpenCodeConnection
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/**
 * Spec 19/20 - the HTTP transport for the OpenCode API.
 *
 * Design notes that matter for correctness rather than style:
 *
 *  - All calls run on [Dispatchers.IO]; nothing here may run on the main thread (spec 68).
 *  - `directory` is threaded to the server as a query parameter on every scoped call, which is how
 *    the API selects a project without restarting the server.
 *  - Basic auth is attached by the [AuthProvider] from the credential vault. The password exists
 *    only inside this class and in the header it builds (spec 15/27).
 *  - Streaming calls use a client whose read timeout is zero so SSE stays open (spec 106).
 *  - Failures map through [classifyStatus], so the UI gets [BackendError] rather than an
 *    `IOException` (spec 52/98).
 */
class HttpOpenCodeApiClient(
    private val connectionProvider: () -> OpenCodeConnection?,
    private val authProvider: AuthProvider,
    private val log: LogManager,
    private val timeouts: ApiTimeouts = ApiTimeouts()
) : OpenCodeApiClient {

    /** Resolves credentials on demand so the password is never cached in a field. */
    fun interface AuthProvider {
        fun basicAuth(): String?
    }

    private val moshi: Moshi = Moshi.Builder().build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Spec 106 - ordinary calls get a bounded read timeout. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.responseMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeouts.responseMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeouts.responseMs + 5_000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Spec 106/22 - the SSE client.
     *
     * `readTimeout(0, MILLISECONDS)` is what keeps `/event` alive; the connection is expected to
     * stay open for the lifetime of the session.
     */
    val streamingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.streamingReadTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeouts.responseMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Connection status as a state flow, so a new subscriber immediately receives the current value
     * rather than waiting for the next transition.
     */
    private val _status = MutableStateFlow(ApiConnectionStatus.DISCONNECTED)

    override fun observeConnectionStatus(): SharedFlow<ApiConnectionStatus> = _status.asSharedFlow()

    override val connection: OpenCodeConnection?
        get() = connectionProvider()

    private fun publish(status: ApiConnectionStatus) {
        _status.value = status
    }

    // ---- low level ----------------------------------------------------------------------

    /** Build a URL against the active connection, refusing to guess a port (spec 48). */
    private fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl? {
        val base = connectionProvider()?.baseUrl ?: return null
        val builder = base.toHttpUrl().newBuilder()
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        query.forEach { (key, value) ->
            if (!value.isNullOrBlank()) builder.addQueryParameter(key, value)
        }
        return builder.build()
    }

    private fun newRequest(path: String, query: Map<String, String?> = emptyMap()): Request.Builder {
        val target = requireNotNull(url(path, query)) { "No active connection for $path" }
        val builder = Request.Builder().url(target)
        authProvider.basicAuth()?.let { credentials ->
            builder.header("Authorization", credentials)
        }
        builder.header("Accept", "application/json")
        return builder
    }

    private suspend fun <T> call(
        label: String,
        block: suspend () -> T
    ): BackendResult<T> = withContext(Dispatchers.IO) {
        try {
            BackendResult.Success(block())
        } catch (e: com.palash.opencodeserver.backend.core.BackendException) {
            BackendResult.Failure(e.error)
        } catch (e: IOException) {
            log.warn(LogCategory.API, "API call failed", "$label: ${e::class.java.simpleName}: ${e.message}")
            BackendResult.Failure(connectionError(e))
        } catch (e: Exception) {
            log.error(LogCategory.API, "Unexpected API failure", label, e)
            BackendResult.Failure(BackendError.wrap(e, ErrorCategory.API))
        }
    }

    /** Spec 52 - map a transport exception onto the user-facing taxonomy. */
    private fun connectionError(e: IOException): BackendError {
        val conn = connectionProvider()
        val host = conn?.baseUrl ?: "the local server"
        return BackendError.Categorized(
            technical = "$host - ${e::class.java.simpleName}: ${e.message}",
            category = ErrorCategory.CONNECTION,
            retryable = true,
            userTitle = "OpenCode is not running.",
            userMessage = "Start the local server and try again."
        )
    }

    private fun jsonBody(value: Any): RequestBody {
        val adapter = moshi.adapter(Any::class.java)
        return adapter.toJson(value).toRequestBody(jsonMediaType)
    }

    private inline fun <reified T> Moshi.adapterFor(): com.squareup.moshi.JsonAdapter<T> =
        adapter(T::class.java)

    private fun parseBody(response: Response, endpoint: String): String {
        val body = response.body?.string().orEmpty()
        classifyStatus(response.code, endpoint, body)?.let { throw com.palash.opencodeserver.backend.core.BackendException(it) }
        return body
    }

    private inline fun <reified T> parse(body: String, endpoint: String): T {
        if (body.isBlank()) throw com.palash.opencodeserver.backend.core.BackendException(
            BackendError.MalformedResponse(endpoint, "empty body")
        )
        return try {
            moshi.adapterFor<T>().fromJson(body)
                ?: throw com.palash.opencodeserver.backend.core.BackendException(
                    BackendError.MalformedResponse(endpoint, "body deserialized to null")
                )
        } catch (e: com.palash.opencodeserver.backend.core.BackendException) {
            throw e
        } catch (e: Exception) {
            // Spec 82 - a malformed response must be a clear error, never a crash.
            throw com.palash.opencodeserver.backend.core.BackendException(
                BackendError.MalformedResponse(endpoint, e.message)
            )
        }
    }

    private inline fun <reified T> listAdapter(): com.squareup.moshi.JsonAdapter<List<T>> {
        val type = Types.newParameterizedType(List::class.java, T::class.java)
        return moshi.adapter(type)
    }

    private inline fun <reified K, reified V> mapAdapter(): com.squareup.moshi.JsonAdapter<Map<K, V>> {
        val type = Types.newParameterizedType(Map::class.java, K::class.java, V::class.java)
        return moshi.adapter(type)
    }

    // ---- global --------------------------------------------------------------------------

    override suspend fun health(): BackendResult<HealthResponse> = call("health") {
        val endpoint = "/global/health"
        val request = newRequest(endpoint).get().build()
        client.newCall(request).execute().use { response ->
            parse(parseBody(response, endpoint), endpoint)
        }
    }

    // ---- project / path -------------------------------------------------------------------

    override suspend fun listProjects(directory: String?): BackendResult<List<OcProject>> =
        call("listProjects") {
            val endpoint = "/project"
            val request = newRequest(endpoint, mapOf("directory" to directory)).get().build()
            client.newCall(request).execute().use { response ->
                parse<List<OcProject>>(parseBody(response, endpoint), endpoint)
            }
        }

    override suspend fun currentProject(directory: String?): BackendResult<OcProject> = call("currentProject") {
        val endpoint = "/project/current"
        val request = newRequest(endpoint, mapOf("directory" to directory)).get().build()
        client.newCall(request).execute().use { response -> parse(parseBody(response, endpoint), endpoint) }
    }

    override suspend fun currentPath(directory: String?): BackendResult<OcPath> = call("currentPath") {
        val endpoint = "/path"
        val request = newRequest(endpoint, mapOf("directory" to directory)).get().build()
        client.newCall(request).execute().use { response -> parse(parseBody(response, endpoint), endpoint) }
    }

    override suspend fun vcsInfo(directory: String?): BackendResult<OcVcsInfo> = call("vcsInfo") {
        val endpoint = "/vcs"
        val request = newRequest(endpoint, mapOf("directory" to directory)).get().build()
        client.newCall(request).execute().use { response -> parse(parseBody(response, endpoint), endpoint) }
    }

    // ---- sessions ----------------------------------------------------------------------------

    override suspend fun listSessions(directory: String?): BackendResult<List<OcSession>> = call("listSessions") {
        val endpoint = "/session"
        val request = newRequest(endpoint, mapOf("directory" to directory)).get().build()
        client.newCall(request).execute().use { response ->
            parse<List<OcSession>>(parseBody(response, endpoint), endpoint)
        }
    }

    override suspend fun createSession(
        title: String?,
        parentId: String?,
        directory: String?
    ): BackendResult<OcSession> = call("createSession") {
        val endpoint = "/session"
        val body = OcSessionCreateRequest(parentID = parentId, title = title)
        val request = newRequest(endpoint, mapOf("directory" to directory))
            .post(jsonBody(body))
            .build()
        client.newCall(request).execute().use { response -> parse(parseBody(response, endpoint), endpoint) }
    }

    override suspend fun getSession(sessionId: String, directory: String?): BackendResult<OcSession> =
        call("getSession") {
            val endpoint = "/session/$sessionId"
            val request = newRequest(endpoint, mapOf("directory" to directory)).get().build()
            client.newCall(request).execute().use { response -> parse(parseBody(response, endpoint), endpoint) }
        }

    override suspend fun deleteSession(sessionId: String, directory: String?): BackendResult<Boolean> =
        call("deleteSession") {
            val endpoint = "/session/$sessionId"
            val request = newRequest(endpoint, mapOf("directory" to directory)).delete().build()
            client.newCall(request).execute().use { response ->
                parseBody(response, endpoint).trim().toBooleanStrictOrNull() ?: true
            }
        }

    override suspend fun updateSessionTitle(
        sessionId: String,
        title: String,
        directory: String?
    ): BackendResult<OcSession> = call("updateSessionTitle") {
        val endpoint = "/session/$sessionId"
        val request = newRequest(endpoint, mapOf("directory" to directory))
            .patch(jsonBody(OcSessionUpdateRequest(title = title)))
            .build()
        client.newCall(request).execute().use { response -> parse(parseBody(response, endpoint), endpoint) }
    }

    override suspend fun abortSession(sessionId: String, directory: String?): BackendResult<Boolean> =
        call("abortSession") {
            val endpoint = "/session/$sessionId/abort"
            val request = newRequest(endpoint, mapOf("directory" to directory)).post(jsonBody(Unit)).build()
            client.newCall(request).execute().use { response ->
                parseBody(response, endpoint).trim().toBooleanStrictOrNull() ?: true
            }
        }

    override suspend fun sessionStatuses(directory: String?): BackendResult<Map<String, OcSessionStatus>> =
        call("sessionStatuses") {
            val endpoint = "/session/status"
            val request = newRequest(endpoint, mapOf("directory" to directory)).get().build()
            client.newCall(request).execute().use { response ->
                parse<Map<String, OcSessionStatus>>(parseBody(response, endpoint), endpoint)
            }
        }

    override suspend fun sessionMessages(
        sessionId: String,
        limit: Int?,
        directory: String?
    ): BackendResult<List<OcMessageWithParts>> = call("sessionMessages") {
        val endpoint = "/session/$sessionId/message"
        val request = newRequest(
            endpoint,
            mapOf("directory" to directory, "limit" to limit?.toString())
        ).get().build()
        client.newCall(request).execute().use { response ->
            parse<List<OcMessageWithParts>>(parseBody(response, endpoint), endpoint)
        }
    }

    override suspend fun sessionDiff(
        sessionId: String,
        messageId: String?,
        directory: String?
    ): BackendResult<List<OcFileDiff>> = call("sessionDiff") {
        val endpoint = "/session/$sessionId/diff"
        val request = newRequest(
            endpoint,
            mapOf("directory" to directory, "messageID" to messageId)
        ).get().build()
        client.newCall(request).execute().use { response ->
            parse<List<OcFileDiff>>(parseBody(response, endpoint), endpoint)
        }
    }

    override suspend fun sendPrompt(
        sessionId: String,
        text: String,
        model: OcModelSelection?,
        agent: String?,
        directory: String?
    ): BackendResult<OcMessageWithParts> = call("sendPrompt") {
        val endpoint = "/session/$sessionId/message"
        val body = buildPromptBody(text, model, agent)
        val request = newRequest(endpoint, mapOf("directory" to directory)).post(jsonBody(body)).build()
        client.newCall(request).execute().use { response -> parse(parseBody(response, endpoint), endpoint) }
    }

    override suspend fun sendPromptAsync(
        sessionId: String,
        text: String,
        model: OcModelSelection?,
        agent: String?,
        directory: String?
    ): BackendResult<Unit> = call("sendPromptAsync") {
        val endpoint = "/session/$sessionId/prompt_async"
        val body = buildPromptBody(text, model, agent)
        val request = newRequest(endpoint, mapOf("directory" to directory)).post(jsonBody(body)).build()
        client.newCall(request).execute().use { response ->
            // 204 No Content is the documented success response.
            parseBody(response, endpoint)
        }
        Unit
    }

    private fun buildPromptBody(text: String, model: OcModelSelection?, agent: String?): Map<String, Any?> =
        buildMap {
            put("parts", listOf(mapOf("type" to "text", "text" to text)))
            model?.let {
                put("model", mapOf("providerID" to it.providerID, "modelID" to it.modelID))
            }
            agent?.let { put("agent", it) }
        }

    // ---- files ---------------------------------------------------------------------------------

    override suspend fun listFiles(path: String, directory: String?): BackendResult<List<OcFileNode>> =
        call("listFiles") {
            val endpoint = "/file"
            val request = newRequest(endpoint, mapOf("path" to path, "directory" to directory)).get().build()
            client.newCall(request).execute().use { response ->
                parse<List<OcFileNode>>(parseBody(response, endpoint), endpoint)
            }
        }

    override suspend fun readFile(path: String, directory: String?): BackendResult<OcFileContent> =
        call("readFile") {
            val endpoint = "/file/content"
            val request = newRequest(endpoint, mapOf("path" to path, "directory" to directory)).get().build()
            client.newCall(request).execute().use { response -> parse(parseBody(response, endpoint), endpoint) }
        }

    override suspend fun fileStatuses(directory: String?): BackendResult<List<OcFileStatus>> =
        call("fileStatuses") {
            val endpoint = "/file/status"
            val request = newRequest(endpoint, mapOf("directory" to directory)).get().build()
            client.newCall(request).execute().use { response ->
                parse<List<OcFileStatus>>(parseBody(response, endpoint), endpoint)
            }
        }

    override suspend fun findFiles(
        query: String,
        type: String?,
        limit: Int,
        directory: String?
    ): BackendResult<List<String>> = call("findFiles") {
        val endpoint = "/find/file"
        val request = newRequest(
            endpoint,
            mapOf("query" to query, "type" to type, "limit" to limit.toString(), "directory" to directory)
        ).get().build()
        client.newCall(request).execute().use { response ->
            parse<List<String>>(parseBody(response, endpoint), endpoint)
        }
    }

    override suspend fun findText(pattern: String, directory: String?): BackendResult<List<Map<String, Any?>>> =
        call("findText") {
            val endpoint = "/find"
            val request = newRequest(endpoint, mapOf("pattern" to pattern, "directory" to directory))
                .get().build()
            client.newCall(request).execute().use { response ->
                parse<List<Map<String, Any?>>>(parseBody(response, endpoint), endpoint)
            }
        }

    // ---- providers / config ------------------------------------------------------------------------

    override suspend fun listProviders(directory: String?): BackendResult<OcProviderListResponse> =
        call("listProviders") {
            val endpoint = "/provider"
            val request = newRequest(endpoint, mapOf("directory" to directory)).get().build()
            client.newCall(request).execute().use { response ->
                parse(parseBody(response, endpoint), endpoint)
            }
        }

    override suspend fun providerAuthMethods(directory: String?): BackendResult<Map<String, List<OcProviderAuthMethod>>> =
        call("providerAuthMethods") {
            val endpoint = "/provider/auth"
            val request = newRequest(endpoint, mapOf("directory" to directory)).get().build()
            client.newCall(request).execute().use { response ->
                parse<Map<String, List<OcProviderAuthMethod>>>(parseBody(response, endpoint), endpoint)
            }
        }

    override suspend fun setApiAuth(
        providerId: String,
        apiKey: String,
        directory: String?
    ): BackendResult<Boolean> = call("setApiAuth") {
        val endpoint = "/auth/$providerId"
        // The key is only ever in this request body; it is redacted by the log interceptor's absence
        // and never written to disk by the app.
        val body = OcApiAuthRequest(type = "api", key = apiKey)
        val request = newRequest(endpoint, mapOf("directory" to directory)).put(jsonBody(body)).build()
        client.newCall(request).execute().use { response ->
            parseBody(response, endpoint).trim().toBooleanStrictOrNull() ?: true
        }
    }

    override suspend fun getConfig(directory: String?): BackendResult<OcConfig> = call("getConfig") {
        val endpoint = "/config"
        val request = newRequest(endpoint, mapOf("directory" to directory)).get().build()
        client.newCall(request).execute().use { response -> parse(parseBody(response, endpoint), endpoint) }
    }

    override suspend fun patchConfig(config: OcConfig, directory: String?): BackendResult<OcConfig> =
        call("patchConfig") {
            val endpoint = "/config"
            val request = newRequest(endpoint, mapOf("directory" to directory))
                .patch(jsonBody(config))
                .build()
            client.newCall(request).execute().use { response -> parse(parseBody(response, endpoint), endpoint) }
        }

    override suspend fun listAgents(directory: String?): BackendResult<List<OcAgent>> = call("listAgents") {
        val endpoint = "/agent"
        val request = newRequest(endpoint, mapOf("directory" to directory)).get().build()
        client.newCall(request).execute().use { response ->
            parse<List<OcAgent>>(parseBody(response, endpoint), endpoint)
        }
    }

    override suspend fun listCommands(directory: String?): BackendResult<List<OcCommand>> = call("listCommands") {
        val endpoint = "/command"
        val request = newRequest(endpoint, mapOf("directory" to directory)).get().build()
        client.newCall(request).execute().use { response ->
            parse<List<OcCommand>>(parseBody(response, endpoint), endpoint)
        }
    }

    override suspend fun writeLog(request: OcLogRequest, directory: String?): BackendResult<Boolean> =
        call("writeLog") {
            val endpoint = "/log"
            val httpRequest = newRequest(endpoint, mapOf("directory" to directory))
                .post(jsonBody(request))
                .build()
            client.newCall(httpRequest).execute().use { response ->
                parseBody(response, endpoint).trim().toBooleanStrictOrNull() ?: true
            }
        }

    // ---- introspection --------------------------------------------------------------------------------

    override suspend fun openApiSpec(): BackendResult<String> = call("openApiSpec") {
        val endpoint = "/doc"
        val target = requireNotNull(url(endpoint)) { "No active connection for $endpoint" }
        val builder = Request.Builder().url(target).get()
        authProvider.basicAuth()?.let { builder.header("Authorization", it) }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            classifyStatus(response.code, endpoint, body)?.let {
                throw com.palash.opencodeserver.backend.core.BackendException(it)
            }
            body
        }
    }

    /** Open a streaming call to `/event`. Exposed for [com.palash.opencodeserver.backend.events.OpenCodeEventManager]. */
    fun openEventStream(): Call {
        val request = newRequest("/event").get()
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()
        return streamingClient.newCall(request)
    }

    fun publishStatus(status: ApiConnectionStatus) = publish(status)
}