package com.palash.opencodeserver.backend.core

/**
 * Spec 52 - standardized error taxonomy.
 *
 * Every failure that can reach the UI is expressed as a [BackendError]. Each error carries both a
 * user facing message (spec 98: never lead with raw errno strings) and the raw technical detail for
 * the Advanced / Diagnostics view (spec 42).
 *
 * The three structural properties are `abstract` rather than constructor parameters so each subtype
 * can define them from its own fields; several subtypes derive `technical` from their own state.
 */
sealed class BackendError {

    /** Coarse bucket used for grouping in logs and for retry decisions (spec 107). */
    abstract val category: ErrorCategory

    /** Technical detail. Redacted before display by the log layer (spec 108). */
    abstract val technical: String?

    /** Whether an identical request could plausibly succeed later. */
    abstract val retryable: Boolean

    abstract val userTitle: String
    abstract val userMessage: String

    /** A single string combining both layers for snackbars and toasts. */
    fun display(): String = buildString {
        append(userTitle)
        append('\n')
        append(userMessage)
        technical?.takeIf { it.isNotBlank() }?.let {
            append("\n\nTechnical details:\n")
            append(it)
        }
    }

    // ---- Connection -------------------------------------------------------------------

    data class Connection(
        val host: String,
        val port: Int,
        override val technical: String?
    ) : BackendError() {
        override val category = ErrorCategory.CONNECTION
        override val retryable = true
        override val userTitle = "OpenCode is not running."
        override val userMessage =
            "The local server did not respond at $host:$port. Start the local server and try again."
    }

    data class ServerUnavailable(
        val endpoint: String,
        val reason: String
    ) : BackendError() {
        override val category = ErrorCategory.CONNECTION
        override val retryable = true
        override val technical = "$endpoint - $reason"
        override val userTitle = "The local server stopped responding."
        override val userMessage = "Start the OpenCode server again to continue."
    }

    data class Authentication(
        override val technical: String?
    ) : BackendError() {
        override val category = ErrorCategory.AUTHENTICATION
        override val retryable = false
        override val userTitle = "The local server rejected the request."
        override val userMessage =
            "The saved server password no longer matches. Repair the server credentials to reconnect."
    }

    // ---- API --------------------------------------------------------------------------

    data class ApiVersion(
        val installed: String,
        val expected: String,
        val detail: String?
    ) : BackendError() {
        override val category = ErrorCategory.API_VERSION
        override val retryable = false
        override val technical = detail
        override val userTitle = "OpenCode version is incompatible with this application."
        override val userMessage =
            "This app was built for OpenCode $expected but version $installed is installed. " +
                "Update OpenCode, then try again."
    }

    data class MalformedResponse(
        val endpoint: String,
        override val technical: String?
    ) : BackendError() {
        override val category = ErrorCategory.API
        override val retryable = false
        override val userTitle = "The server sent an unexpected response."
        override val userMessage =
            "The OpenCode API at $endpoint did not return data this app understands. " +
                "Diagnostics were recorded."
    }

    // ---- Timeouts ---------------------------------------------------------------------

    data class Timeout(
        val operation: String,
        val timeoutMs: Long,
        val isStreaming: Boolean = false
    ) : BackendError() {
        override val category = ErrorCategory.TIMEOUT
        // Spec 106/107: a stalled read on an intentionally-open stream is not a retry candidate.
        override val retryable = !isStreaming
        override val technical = "$operation timed out after ${timeoutMs}ms"
        override val userTitle =
            if (isStreaming) "The event stream stalled." else "The request took too long."
        override val userMessage = if (isStreaming) {
            "Reconnecting to the live event stream."
        } else {
            "OpenCode did not answer in time. Try again, or raise the timeout in Advanced settings."
        }
    }

    // ---- Project ----------------------------------------------------------------------

    data class Project(
        val projectName: String,
        override val technical: String?,
        val requiresSelection: Boolean = false
    ) : BackendError() {
        override val category = ErrorCategory.PROJECT
        override val retryable = false
        override val userTitle =
            if (requiresSelection) "Select a project first." else "That project could not be used."
        override val userMessage = if (requiresSelection) {
            "Choose or create a project before running coding-agent commands."
        } else {
            "Open or create the project again. Your files were not changed."
        }
    }

    data class InvalidPath(val requestedPath: String, val reason: String) : BackendError() {
        override val category = ErrorCategory.PROJECT
        override val retryable = false
        override val technical = "$requestedPath: $reason"
        override val userTitle = "That folder is not available."
        override val userMessage =
            "The selected location is not a readable folder. Pick a different folder and try again."
    }

    data class LargeFile(
        val path: String,
        val sizeBytes: Long,
        val thresholdBytes: Long
    ) : BackendError() {
        override val category = ErrorCategory.PROJECT
        override val retryable = true
        override val technical = "$sizeBytes bytes exceeds threshold $thresholdBytes"
        override val userTitle = "Large file"
        override val userMessage =
            "This file is ${Format.bytes(sizeBytes)}. Loading it fully may use a lot of memory. " +
                "Load anyway?"
    }

    // ---- Session ----------------------------------------------------------------------

    data class Session(
        val sessionId: String?,
        override val technical: String?
    ) : BackendError() {
        override val category = ErrorCategory.SESSION
        override val retryable = false
        override val userTitle = "That session could not be used."
        override val userMessage =
            "The session is missing or was created by an incompatible OpenCode version."
    }

    // ---- Runtime / install ------------------------------------------------------------

    data class Runtime(
        val detail: String?,
        override val retryable: Boolean = true
    ) : BackendError() {
        override val category = ErrorCategory.RUNTIME
        override val technical = detail
        override val userTitle = "The terminal runtime could not start."
        override val userMessage =
            "The embedded Linux environment is unavailable. Repair the runtime from Settings and try again."
    }

    data class Dependency(
        val dependency: String,
        val reason: String,
        override val technical: String?,
        override val retryable: Boolean = true
    ) : BackendError() {
        override val category = ErrorCategory.DEPENDENCY
        override val userTitle = "$dependency could not be installed."
        override val userMessage =
            "Reason: $reason\n\nCheck your internet connection and free up storage, then retry."
    }

    data class ArchitectureUnsupported(
        val detected: String,
        val required: String,
        override val technical: String?
    ) : BackendError() {
        override val category = ErrorCategory.DEPENDENCY
        override val retryable = false
        override val userTitle =
            "OpenCode is not currently available for this device/runtime."
        override val userMessage =
            "Detected: $detected\nRequired: $required\n\n" +
                "Only a compatible build can be installed on this device."
    }

    data class Network(
        val reason: String,
        override val retryable: Boolean = true
    ) : BackendError() {
        override val category = ErrorCategory.NETWORK
        override val technical = reason
        override val userTitle = "Internet connection required."
        override val userMessage =
            "Downloading development tools needs a network connection. Local tools keep working offline."
    }

    data class Storage(
        val requiredBytes: Long,
        val availableBytes: Long
    ) : BackendError() {
        override val category = ErrorCategory.DEPENDENCY
        override val retryable = false
        override val technical = "need=$requiredBytes available=$availableBytes"
        override val userTitle = "Not enough storage."
        override val userMessage =
            "Required: ~${Format.bytes(requiredBytes)}\nAvailable: ${Format.bytes(availableBytes)}\n\n" +
                "Free up storage and try again."
    }

    /** Escape hatch for failures a transport layer has already classified. */
    data class Categorized(
        override val technical: String?,
        override val category: ErrorCategory,
        override val retryable: Boolean,
        override val userTitle: String,
        override val userMessage: String
    ) : BackendError()

    data class Unknown(
        override val technical: String?,
        override val userTitle: String = "Something went wrong.",
        override val userMessage: String = "The operation did not finish. Diagnostics were saved."
    ) : BackendError() {
        override val category = ErrorCategory.UNKNOWN
        override val retryable = true
    }

    companion object {
        /**
         * Wrap an arbitrary throwable without leaking a raw message as the primary UI text.
         *
         * [category] lets a transport layer classify the failure before the UI sees it, which keeps
         * the retry policy in [ErrorCategory.isAutoRetryable] authoritative.
         */
        fun wrap(
            throwable: Throwable,
            category: ErrorCategory = ErrorCategory.UNKNOWN,
            userTitle: String = "Something went wrong.",
            userMessage: String = "The operation did not finish. Diagnostics were saved."
        ): BackendError = when (throwable) {
            is BackendException -> throwable.error
            else -> Categorized(
                technical = throwable.describe(),
                category = category,
                retryable = category.isAutoRetryable,
                userTitle = userTitle,
                userMessage = userMessage
            )
        }
    }
}

/** Thrown internally to carry a fully built [BackendError] across coroutine boundaries. */
class BackendException(val error: BackendError) : RuntimeException(error.technical ?: error.userTitle)

enum class ErrorCategory {
    CONNECTION,
    AUTHENTICATION,
    API,
    API_VERSION,
    TIMEOUT,
    PROJECT,
    SESSION,
    RUNTIME,
    DEPENDENCY,
    NETWORK,
    STORAGE,
    PERMISSION,
    UNKNOWN;

    /**
     * Spec 107 - retry only transient classes.
     *
     * Authentication, invalid project, invalid request, unsupported API and permission denied are
     * explicitly never retried automatically.
     */
    val isAutoRetryable: Boolean
        get() = this == CONNECTION || this == TIMEOUT || this == NETWORK ||
            this == RUNTIME || this == DEPENDENCY || this == UNKNOWN
}

/**
 * Render a throwable for diagnostics without dumping a Java stack trace into the UI.
 *
 * Only the exception types and messages appear: a stack trace would be noise in a snackbar, and the
 * full trace is already captured by the log manager.
 */
internal fun Throwable.describe(): String = buildString {
    append(this@describe::class.java.simpleName)
    message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    val cause = this@describe.cause
    if (cause != null && cause !== this@describe) {
        append(" <- ").append(cause::class.java.simpleName)
        cause.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    }
}