package com.palash.opencodeserver.backend.events

import com.palash.opencodeserver.backend.core.AppState
import com.palash.opencodeserver.backend.core.ConnectivityState
import com.palash.opencodeserver.backend.core.ProviderHealth
import com.palash.opencodeserver.backend.core.ProjectFileChange
import com.palash.opencodeserver.backend.core.ServerInfo

/**
 * Spec 70 - the single event vocabulary the frontend consumes.
 *
 * These map onto documented OpenCode event types. The mapping lives in [OpenCodeEventMapper]; the
 * UI only ever sees this sealed type, so a server-side event rename cannot leak into the UI layer.
 */
sealed class BackendEvent {

    // ---- lifecycle ---------------------------------------------------------------------

    data class StateChanged(val state: AppState) : BackendEvent()

    data class ServerStateChanged(val info: ServerInfo) : BackendEvent()

    /** Spec 5 - environment readiness changed. */
    data class EnvironmentChanged(val state: com.palash.opencodeserver.backend.core.EnvironmentState) : BackendEvent()

    /** Spec 22/69 - the event stream connected. */
    data class EventStreamConnected(val serverVersion: String?) : BackendEvent()

    /** Spec 23 - the stream dropped; the UI can show a "reconnecting" affordance. */
    data class EventStreamDisconnected(val reason: String, val willRetry: Boolean) : BackendEvent()

    /** Spec 22 - reconnect attempt counter, surfaced for diagnostics. */
    data class EventStreamReconnecting(val attempt: Int, val delayMs: Long) : BackendEvent()

    /** Spec 45/76 - connectivity changed. */
    data class ConnectivityChanged(val state: ConnectivityState) : BackendEvent()

    /** Spec 26/78 - provider configuration changed. */
    data class ProviderHealthChanged(val health: ProviderHealth) : BackendEvent()

    // ---- agent activity -------------------------------------------------------------------

    /** Spec 69 - incremental assistant text. */
    data class MessageDelta(
        val sessionId: String,
        val messageId: String,
        val partId: String?,
        val delta: String
    ) : BackendEvent()

    /** A complete text part replaced whatever was streamed for it. */
    data class MessageText(
        val sessionId: String,
        val messageId: String,
        val partId: String?,
        val text: String
    ) : BackendEvent()

    /** Spec 70 `thinking` - reasoning content, which some users keep hidden. */
    data class Reasoning(
        val sessionId: String,
        val messageId: String,
        val partId: String?,
        val text: String
    ) : BackendEvent()

    /** Spec 70 `tool_started`. */
    data class ToolStarted(
        val sessionId: String,
        val messageId: String,
        val callId: String,
        val tool: String,
        val title: String
    ) : BackendEvent()

    /** Spec 70 `tool_output`. */
    data class ToolOutput(
        val sessionId: String,
        val messageId: String,
        val callId: String,
        val status: String,
        val output: String?,
        val error: String?
    ) : BackendEvent()

    /** Spec 70 `file_changed`. */
    data class FileChanged(val path: String) : BackendEvent()

    /** Spec 70 `command_started` / `command_finished`. */
    data class SessionStatusChanged(
        val sessionId: String,
        val status: String,
        val detail: String?
    ) : BackendEvent()

    /** Spec 70 `message_completed`. */
    data class MessageCompleted(
        val sessionId: String,
        val messageId: String,
        val finishReason: String?,
        val errorMessage: String?
    ) : BackendEvent()

    /** Spec 70 `session_completed` - the server signalled the session went idle. */
    data class SessionIdle(val sessionId: String) : BackendEvent()

    /** Spec 70 `error`. */
    data class Error(val scope: String, val message: String, val retryable: Boolean) : BackendEvent()

    // ---- session / project metadata ---------------------------------------------------------

    data class SessionCreated(val sessionId: String, val title: String) : BackendEvent()

    data class SessionUpdated(val sessionId: String, val title: String) : BackendEvent()

    data class SessionDeleted(val sessionId: String) : BackendEvent()

    /** Spec 71 - files changed on disk (from our watcher or the server's watcher event). */
    data class WatcherChanged(val change: ProjectFileChange) : BackendEvent()

    /** Spec 22 - server reported a new version is available. */
    data class ServerUpdateAvailable(val version: String) : BackendEvent()

    /** Spec 111 - terminal output for a session. */
    data class TerminalOutput(
        val sessionId: String,
        val text: String,
        val isStderr: Boolean
    ) : BackendEvent()

    data class TerminalExited(val sessionId: String, val exitCode: Int) : BackendEvent()

    /** Spec 62 - a short status line suitable for a snackbar. */
    data class Notice(val message: String, val isError: Boolean) : BackendEvent()
}