package com.palash.opencodeserver.backend.runtime.pty

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import java.io.Closeable

/**
 * Spec 33 - a PTY-backed pseudo terminal.
 *
 * This interface is deliberately narrow so the terminal layer never learns whether the PTY came
 * from a JNI `forkpty` binding or from the pipe-based fallback. The fallback loses full-screen
 * programs but keeps every ordinary command working, which is the correct degradation order.
 *
 * The one non-negotiable property (spec 32): commands run inside the app's Linux runtime, so `pwd`
 * reports the runtime home and `git --version` reports the installed Git.
 */
interface PtyHost : Closeable {

    val id: String
    val pid: Int

    /** Spec 64 - resize in place. Never restarts the shell. */
    fun resize(columns: Int, rows: Int)

    /** Raw bytes to the child's stdin. */
    fun write(data: String)

    /** Deliver a control sequence (Ctrl+C, arrows, ESC-prefixed input). */
    fun writeBytes(data: ByteArray)

    /** Spec 85 - graceful interrupt (SIGINT), never a tree kill. */
    fun interrupt()

    /** EOF, equivalent of Ctrl+D. */
    fun sendEof()

    fun isAlive(): Boolean

    override fun close()

    companion object {
        /**
         * Spec 33 - a real PTY gives full-screen programs, colour, job control and escape sequences.
         * The fallback is transparent apart from this flag, which the UI uses to warn the user.
         */
        const val CAPABILITY_FULL_SCREEN = "full_screen"
        const val CAPABILITY_RESIZE = "resize"
        const val CAPABILITY_SIGNALS = "signals"
    }
}

/** Factory so the runtime manager can select an implementation at runtime. */
interface PtyHostFactory {
    val id: String
    val displayName: String

    /** True when [PtyHost.resize] produces a real TIOCSWINSZ on the child. */
    val supportsResize: Boolean

    /** True when the child has a controlling terminal (line editing, colour, full-screen TUI). */
    val supportsFullScreen: Boolean

    fun isAvailable(): Boolean

    /** Why the factory is unavailable, for diagnostics. Null when available. */
    fun unavailableReason(): String? = null

    suspend fun create(request: PtyRequest): BackendResult<PtyHost>
}

/**
 * Spec 31/34 - how a terminal session should be created.
 *
 * [columns] and [rows] are the initial geometry; the UI resizes later via [PtyHost.resize].
 * [secrets] are injected into this child only and are never logged (spec 15).
 */
data class PtyRequest(
    val shell: String,
    val shellArguments: List<String> = listOf("-i"),
    val workingDirectory: String,
    val environment: Map<String, String>,
    val secrets: Map<String, String> = emptyMap(),
    val columns: Int = 80,
    val rows: Int = 24,
    val term: String = "xterm-256color",
    val onOutput: (ByteArray, isStderr: Boolean) -> Unit
)

/** Spec 43 - convert a PTY construction failure into an actionable error. */
internal fun ptyUnavailable(reason: String): BackendError =
    BackendError.Categorized(
        technical = reason,
        category = com.palash.opencodeserver.backend.core.ErrorCategory.RUNTIME,
        retryable = false,
        userTitle = "The terminal runtime could not start.",
        userMessage = "Repair the Linux runtime from Settings and try again."
    )