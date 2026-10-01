package com.palash.opencodeserver.backend.events

import com.palash.opencodeserver.backend.api.OcEvent
import com.palash.opencodeserver.backend.api.OcMessage
import com.palash.opencodeserver.backend.api.OcPart

/**
 * Spec 70 - translate documented OpenCode events into the app's event vocabulary.
 *
 * Every branch uses a real event type from the server's contract. Unknown types are surfaced as a
 * generic [BackendEvent.Error] with the raw type name rather than being silently discarded, so a
 * server-side addition is visible in diagnostics instead of vanishing.
 */
object OpenCodeEventMapper {

    /**
     * Map one raw event. Returns null for events the app deliberately ignores, which are listed
     * explicitly below so the choice is auditable.
     */
    fun map(event: OcEvent): List<BackendEvent> {
        val type = event.resolvedType
        return when (type) {
            // The first event on a fresh connection; used as the "stream is really up" signal.
            "server.connected" -> listOf(BackendEvent.EventStreamConnected(null))

            // ---- sessions
            "session.created" -> sessionFromInfo(event, BackendEvent::SessionCreated)
            "session.updated" -> sessionFromInfo(event, BackendEvent::SessionUpdated)
            "session.deleted" -> event.map("info")?.let { info ->
                val id = info["id"]?.toString().orEmpty()
                if (id.isBlank()) emptyList()
                else listOf(BackendEvent.SessionDeleted(id))
            }.orEmpty()

            "session.status" -> listOfNotNull(
                event.string("sessionID")?.let { sessionId ->
                    val status = event.map("status")
                    BackendEvent.SessionStatusChanged(
                        sessionId = sessionId,
                        status = status?.get("type")?.toString() ?: "unknown",
                        detail = status?.get("message")?.toString()
                    )
                }
            )

            "session.idle" -> event.string("sessionID")
                ?.let { listOf(BackendEvent.SessionIdle(it)) }
                .orEmpty()

            "session.error" -> listOf(
                BackendEvent.Error(
                    scope = "session",
                    message = extractErrorMessage(event.map("error"))
                        ?: "The session reported an error.",
                    retryable = false
                )
            )

            "session.diff" -> emptyList() // Diffs arrive through the diff endpoint on demand.

            // ---- messages
            "message.updated" -> messageUpdated(event)
            "message.removed" -> emptyList() // The UI already holds the message it is removing.

            "message.part.updated" -> partUpdated(event)

            "message.part.removed" -> emptyList()

            // ---- tools / files
            "file.edited" -> event.string("file")
                ?.let { listOf(BackendEvent.FileChanged(it)) }
                .orEmpty()

            "file.watcher.updated" -> watcherUpdated(event)

            "permission.updated" -> listOf(
                BackendEvent.Notice("OpenCode is asking for permission to continue.", isError = false)
            )

            "permission.replied" -> emptyList()

            // ---- installation
            "installation.updated", "installation.update-available" ->
                event.map("version")?.get("version")?.toString()?.let { version ->
                    listOf(BackendEvent.ServerUpdateAvailable(version))
                }.orEmpty()

            // ---- misc, deliberately ignored ------------------------------------------------
            "lsp.updated", "lsp.client.diagnostics" -> emptyList()
            "todo.updated" -> emptyList() // The UI fetches todos explicitly when needed.
            "command.executed" -> emptyList()
            "vcs.branch.updated" -> event.string("branch")
                ?.let { listOf(BackendEvent.Notice("Branch: $it", isError = false)) }
                .orEmpty()
            "tui.prompt.append", "tui.command.execute", "tui.toast.show" -> emptyList()
            "pty.created", "pty.updated", "pty.exited", "pty.deleted" -> emptyList()
            "server.instance.disposed" -> listOf(
                BackendEvent.Error(
                    scope = "server",
                    message = "The OpenCode instance was disposed.",
                    retryable = true
                )
            )

            // Unknown type: surface it instead of dropping it (spec 70).
            "" -> emptyList()
            else -> listOf(
                BackendEvent.Error(
                    scope = "event",
                    message = "Unhandled OpenCode event type \"$type\". The app may need an update.",
                    retryable = false
                )
            )
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    private inline fun sessionFromInfo(
        event: OcEvent,
        factory: (String, String) -> BackendEvent
    ): List<BackendEvent> {
        val info = event.map("info") ?: return emptyList()
        val id = info["id"]?.toString().orEmpty()
        if (id.isBlank()) return emptyList()
        val title = info["title"]?.toString()?.takeIf { it.isNotBlank() } ?: "Untitled session"
        return listOf(factory(id, title))
    }

    /**
     * Spec 69 - `message.updated` carries the whole message object, not a delta.
     *
     * The assistant message body arrives as parts; the text itself is only useful once its parts
     * exist, so the assistant's aggregate status is derived from the message's role and finish.
     */
    private fun messageUpdated(event: OcEvent): List<BackendEvent> {
        val info = event.map("info") ?: return emptyList()
        val sessionId = info["sessionID"]?.toString().orEmpty()
        val messageId = info["id"]?.toString().orEmpty()
        if (sessionId.isBlank() || messageId.isBlank()) return emptyList()

        val role = info["role"]?.toString().orEmpty()
        val errorInfo = info["error"] as? Map<*, *>
        if (errorInfo != null) {
            return listOf(
                BackendEvent.MessageCompleted(
                    sessionId = sessionId,
                    messageId = messageId,
                    finishReason = info["finish"]?.toString(),
                    errorMessage = errorInfo["data"]?.let { data ->
                        (data as? Map<*, *>)?.get("message")?.toString()
                    } ?: errorInfo["name"]?.toString()
                )
            )
        }

        val finished = info["finish"] != null
        return if (finished) {
            listOf(
                BackendEvent.MessageCompleted(
                    sessionId = sessionId,
                    messageId = messageId,
                    finishReason = info["finish"]?.toString(),
                    errorMessage = null
                )
            )
        } else if (role == "assistant") {
            // An assistant message exists but is still generating.
            listOf(BackendEvent.SessionStatusChanged(sessionId, "busy", null))
        } else {
            emptyList()
        }
    }

    /**
     * Spec 69/70 - the streaming hot path.
     *
     * `message.part.updated` carries an optional `delta`. When present it is a true incremental
     * append and must reach the UI immediately; when absent the part is the full value and should
     * replace what is on screen.
     */
    private fun partUpdated(event: OcEvent): List<BackendEvent> {
        val part = event.map("part") ?: return emptyList()
        val sessionId = part["sessionID"]?.toString().orEmpty()
        val messageId = part["messageID"]?.toString().orEmpty()
        val partId = part["id"]?.toString()
        if (sessionId.isBlank() || messageId.isBlank()) return emptyList()

        val delta = event.string("delta")?.takeIf { it.isNotEmpty() }
        val type = part["type"]?.toString().orEmpty()

        return when (type) {
            "text" -> {
                val text = part["text"]?.toString().orEmpty()
                if (delta != null) {
                    listOf(BackendEvent.MessageDelta(sessionId, messageId, partId, delta))
                } else {
                    listOf(BackendEvent.MessageText(sessionId, messageId, partId, text))
                }
            }

            "reasoning" -> listOf(
                BackendEvent.Reasoning(sessionId, messageId, partId, part["text"]?.toString().orEmpty())
            )

            "tool" -> {
                val callId = part["callID"]?.toString().orEmpty()
                val tool = part["tool"]?.toString().orEmpty()
                val state = part["state"] as? Map<*, *>
                val status = state?.get("status")?.toString().orEmpty()
                val title = state?.get("title")?.toString().orEmpty()

                when (status) {
                    "pending", "running" -> listOf(
                        BackendEvent.ToolStarted(sessionId, messageId, callId, tool, title)
                    )
                    "completed", "error" -> listOf(
                        BackendEvent.ToolOutput(
                            sessionId = sessionId,
                            messageId = messageId,
                            callId = callId,
                            status = status,
                            output = state?.get("output")?.toString(),
                            error = state?.get("error")?.toString()
                        )
                    )
                    // No state yet: the tool has been announced but not scheduled.
                    else -> listOf(BackendEvent.ToolStarted(sessionId, messageId, callId, tool, title))
                }
            }

            "file" -> part["url"]?.toString()?.let { url ->
                listOf(BackendEvent.FileChanged(url.removePrefix("file://")))
            }.orEmpty()

            "patch" -> (part["files"] as? List<*>)
                ?.mapNotNull { it?.toString() }
                ?.map { BackendEvent.FileChanged(it) }
                .orEmpty()

            "retry" -> listOf(
                BackendEvent.Error(
                    scope = "provider",
                    message = "The provider request failed and is being retried.",
                    retryable = true
                )
            )

            "step-start", "step-finish", "agent", "snapshot", "compaction", "subtask" -> emptyList()
            else -> emptyList()
        }
    }

    private fun watcherUpdated(event: OcEvent): List<BackendEvent> {
        val file = event.string("file") ?: return emptyList()
        val kind = event.string("event").orEmpty()
        val change = when (kind) {
            "add" -> com.palash.opencodeserver.backend.core.ProjectFileChange(
                file,
                com.palash.opencodeserver.backend.core.ProjectFileChange.FileChangeType.CREATED
            )
            "change" -> com.palash.opencodeserver.backend.core.ProjectFileChange(
                file,
                com.palash.opencodeserver.backend.core.ProjectFileChange.FileChangeType.MODIFIED
            )
            "unlink" -> com.palash.opencodeserver.backend.core.ProjectFileChange(
                file,
                com.palash.opencodeserver.backend.core.ProjectFileChange.FileChangeType.DELETED
            )
            else -> return emptyList()
        }
        return listOf(BackendEvent.WatcherChanged(change))
    }

    private fun extractErrorMessage(error: Map<String, Any?>?): String? {
        if (error == null) return null
        val data = error["data"] as? Map<*, *>
        return data?.get("message")?.toString()?.takeIf { it.isNotBlank() }
            ?: error["name"]?.toString()
    }
}