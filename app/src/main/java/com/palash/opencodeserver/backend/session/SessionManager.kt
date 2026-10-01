package com.palash.opencodeserver.backend.session

import com.palash.opencodeserver.backend.api.OcMessage
import com.palash.opencodeserver.backend.api.OcMessageWithParts
import com.palash.opencodeserver.backend.api.OcPart
import com.palash.opencodeserver.backend.api.OcSession
import com.palash.opencodeserver.backend.api.OcSessionStatus
import com.palash.opencodeserver.backend.api.OpenCodeApiClient
import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.MessageRole
import com.palash.opencodeserver.backend.core.ModelSelection
import com.palash.opencodeserver.backend.core.SessionActivityStatus
import com.palash.opencodeserver.backend.core.SessionMessage
import com.palash.opencodeserver.backend.core.SessionRecord
import com.palash.opencodeserver.backend.core.ToolInvocation
import com.palash.opencodeserver.backend.core.ToolStatus
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager

/**
 * Spec 24 - session management.
 *
 * ```
 * createSession()  listSessions()  getSession()
 * deleteSession()  continueSession()  abortSession()
 * ```
 *
 * Everything goes through the HTTP API rather than the CLI, because the API returns typed JSON and
 * the CLI's human-readable table would require parsing (spec 92). `opencode session` remains the
 * documented fallback if the API is unavailable for a capability, and that preference is recorded
 * per operation rather than hard-coded.
 */
class SessionManager(
    private val api: OpenCodeApiClient,
    private val log: LogManager
) {

    suspend fun listSessions(projectDirectory: String?): BackendResult<List<SessionRecord>> =
        api.listSessions(projectDirectory).map { sessions ->
            // Fetch real activity status so the UI never guesses whether a session is busy.
            val statuses = api.sessionStatuses(projectDirectory).valueOrNull().orEmpty()
            sessions.map { toRecord(it, statuses[it.resolvedId]) }
        }

    suspend fun createSession(
        title: String?,
        projectDirectory: String?
    ): BackendResult<SessionRecord> =
        api.createSession(title, null, projectDirectory).map { toRecord(it, null) }

    suspend fun getSession(sessionId: String, projectDirectory: String?): BackendResult<SessionRecord> =
        api.getSession(sessionId, projectDirectory).map { toRecord(it, null) }

    suspend fun deleteSession(sessionId: String, projectDirectory: String?): BackendResult<Unit> =
        api.deleteSession(sessionId, projectDirectory).map { }

    /** Spec 24 - continue an existing session by sending a further prompt to it. */
    suspend fun continueSession(
        sessionId: String,
        prompt: String,
        model: ModelSelection?,
        agent: String?,
        projectDirectory: String?
    ): BackendResult<Unit> = sendPrompt(sessionId, prompt, model, agent, projectDirectory)

    /** Spec 24/25 - abort a running session. */
    suspend fun abortSession(sessionId: String, projectDirectory: String?): BackendResult<Unit> =
        api.abortSession(sessionId, projectDirectory).map { }

    suspend fun renameSession(
        sessionId: String,
        title: String,
        projectDirectory: String?
    ): BackendResult<SessionRecord> =
        api.updateSessionTitle(sessionId, title, projectDirectory).map { toRecord(it, null) }

    /**
     * Spec 25/69 - send a prompt.
     *
     * The async endpoint is preferred because the assistant reply streams in over SSE; blocking on
     * the request would hold the HTTP connection for the whole generation and stall the UI.
     */
    suspend fun sendPrompt(
        sessionId: String,
        prompt: String,
        model: ModelSelection?,
        agent: String?,
        projectDirectory: String?
    ): BackendResult<Unit> {
        val selection = model?.let { com.palash.opencodeserver.backend.api.OcModelSelection(it.providerId, it.modelId) }

        val asyncResult = api.sendPromptAsync(sessionId, prompt, selection, agent, projectDirectory)
        if (asyncResult.isSuccess) {
            log.info(LogCategory.API, "Prompt accepted", "session=$sessionId chars=${prompt.length}")
            return asyncResult
        }

        // Fall back to the blocking endpoint only for failures that are not auth or request errors
        // (spec 107): a 404 there means this release lacks the async route.
        val error = asyncResult.errorOrNull()
        val shouldFallback = error?.technical?.contains("404") == true
        if (!shouldFallback) {
            return BackendResult.Failure(
                error ?: BackendError.Session(sessionId, "Prompt was rejected")
            )
        }

        log.info(LogCategory.API, "Falling back to the blocking prompt endpoint", "session=$sessionId")
        return api.sendPrompt(sessionId, prompt, selection, agent, projectDirectory).map { }
    }

    /** Spec 25 - load history so a reopened session is not empty (spec 120). */
    suspend fun messages(
        sessionId: String,
        projectDirectory: String?,
        limit: Int? = null
    ): BackendResult<List<SessionMessage>> =
        api.sessionMessages(sessionId, limit, projectDirectory).map { list ->
            list.map { toMessage(it) }
        }

    // ---- mapping -------------------------------------------------------------------------

    private fun toRecord(session: OcSession, status: OcSessionStatus?): SessionRecord = SessionRecord(
        id = session.resolvedId,
        title = session.resolvedTitle,
        directory = session.directory.orEmpty(),
        parentId = session.parentID,
        createdAt = session.createdAtMs,
        updatedAt = session.updatedAtMs,
        status = toActivity(status),
        // The API does not report a message count; it is derived from history when the UI asks,
        // and 0 here means "not yet loaded" rather than "empty".
        messageCount = 0,
        filesChanged = session.summary?.files ?: 0,
        version = session.version
    )

    private fun toActivity(status: OcSessionStatus?): SessionActivityStatus = when {
        status == null -> SessionActivityStatus.IDLE
        status.isRetrying -> SessionActivityStatus.RETRYING
        status.isBusy -> SessionActivityStatus.WORKING
        status.isIdle -> SessionActivityStatus.IDLE
        else -> SessionActivityStatus.IDLE
    }

    /**
     * Convert a message with its parts.
     *
     * Tool invocations are collected rather than rendered, so the UI can show them progressively as
     * the event stream reports each state change.
     */
    private fun toMessage(withParts: OcMessageWithParts): SessionMessage {
        val info = withParts.info
        val parts = withParts.parts.orEmpty()

        val text = parts.filter { it.isText }.joinToString("\n") { it.text.orEmpty() }.trim()
        val reasoning = parts.filter { it.isReasoning }.joinToString("\n") { it.text.orEmpty() }.trim()
        val tools = parts.filter { it.isTool }.map { toTool(it) }

        val errorMessage = info?.error?.let { err ->
            err.messageText() ?: err.name
        }

        return SessionMessage(
            id = info?.id.orEmpty(),
            sessionId = info?.sessionID.orEmpty(),
            role = when (info?.role) {
                "user" -> MessageRole.USER
                "assistant" -> MessageRole.ASSISTANT
                else -> MessageRole.SYSTEM
            },
            createdAt = info?.time?.created ?: 0L,
            text = text,
            toolActivity = tools,
            reasoning = reasoning.takeIf { it.isNotBlank() },
            isError = errorMessage != null,
            errorMessage = errorMessage
        )
    }

    private fun toTool(part: OcPart): ToolInvocation = ToolInvocation(
        callId = part.callID.orEmpty(),
        tool = part.tool.orEmpty(),
        title = part.toolTitle().orEmpty(),
        status = when (part.toolStatus()) {
            "pending" -> ToolStatus.PENDING
            "running" -> ToolStatus.RUNNING
            "completed" -> ToolStatus.COMPLETED
            "error" -> ToolStatus.ERROR
            else -> ToolStatus.PENDING
        },
        output = part.toolOutput(),
        error = part.toolError()
    )
}