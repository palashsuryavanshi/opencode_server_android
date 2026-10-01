package com.palash.opencodeserver.backend.terminal

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.TerminalStatus
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.runtime.EnvironmentBuilder
import com.palash.opencodeserver.backend.runtime.RuntimeLayout
import com.palash.opencodeserver.backend.runtime.pty.PipePtyHostFactory
import com.palash.opencodeserver.backend.runtime.pty.PtyHost
import com.palash.opencodeserver.backend.runtime.pty.PtyHostFactory
import com.palash.opencodeserver.backend.runtime.pty.PtyHostSelector
import com.palash.opencodeserver.backend.runtime.pty.PtyRequest
import com.palash.opencodeserver.backend.runtime.pty.ShellLocator
import com.palash.opencodeserver.backend.runtime.ProcessRunner
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Spec 31 - output delivered for one terminal session. */
data class TerminalOutputChunk(
    val sessionId: String,
    val data: ByteArray,
    val isStderr: Boolean,
    val timestamp: Long
) {
    val text: String get() = String(data, Charsets.UTF_8)

    // ByteArray requires identity-based equals; the UI compares by content via text.
    override fun equals(other: Any?): Boolean =
        other is TerminalOutputChunk && other.sessionId == sessionId &&
            other.isStderr == isStderr && other.timestamp == timestamp && other.data.contentEquals(data)

    override fun hashCode(): Int =
        ((sessionId.hashCode() * 31) + isStderr.hashCode() * 17 + data.contentHashCode())
}

/** Spec 63 - a session's exit notification. */
data class TerminalExit(val sessionId: String, val exitCode: Int)

/**
 * Spec 31/32/63/64/85 - the terminal engine.
 *
 * Each session owns its own PTY and scrollback buffer. Closing one session never affects another
 * (spec 63), and stopping the OpenCode server never closes terminal sessions (spec 62).
 *
 * Everything here runs off the main thread: PTY reads happen on native/dedicated threads and are
 * published through a [SharedFlow], so a fast-producing command cannot jank the UI (spec 68).
 */
class TerminalManager(
    private val layout: RuntimeLayout,
    private val environment: EnvironmentBuilder,
    private val runner: ProcessRunner,
    private val log: LogManager
) {

    private class Session(
        @Volatile var descriptor: TerminalSession,
        val host: PtyHost,
        val buffer: ScrollbackBuffer
    ) {
        @Volatile
        var status: TerminalStatus = TerminalStatus.RUNNING
    }

    /**
     * The live session registry.
     *
     * Named distinctly from the public [sessions] state flow so the two cannot be confused: this is
     * the mutable map of [Session] objects, that is the immutable snapshot the UI renders.
     */
    private val liveSessions = ConcurrentHashMap<String, Session>()

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    private val _output = MutableSharedFlow<TerminalOutputChunk>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val output: SharedFlow<TerminalOutputChunk> = _output.asSharedFlow()

    private val _exits = MutableSharedFlow<TerminalExit>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val exits: SharedFlow<TerminalExit> = _exits.asSharedFlow()

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val factorySelector = PtyHostSelector(
        forkFactory = com.palash.opencodeserver.backend.runtime.pty.ForkPtyHostFactory(log),
        pipeFactory = PipePtyHostFactory(runner, log),
        log = log
    )

    /** Spec 67 - applied to every new session and retroactively on preference change. */
    @Volatile
    private var scrollbackLimit: Int = 5_000

    fun setScrollbackLimit(maxLines: Int) {
        scrollbackLimit = maxLines
        liveSessions.values.forEach { it.buffer.changeLimit(maxLines) }
    }

    /** Register a per-session sink; the UI uses this instead of collecting the global stream. */
    fun addListener(listener: (String) -> Unit) = listeners.add(listener)

    fun removeListener(listener: (String) -> Unit) = listeners.remove(listener)

    // ---- lifecycle --------------------------------------------------------------------

    /** Spec 31/34 - create a session in [workingDirectory]. */
    suspend fun createSession(
        workingDirectory: String?,
        shellOverride: String? = null,
        columns: Int = 80,
        rows: Int = 24
    ): BackendResult<TerminalSession> {
        val (shell, shellArgs) = ShellLocator.preferredShell(shellOverride ?: environment.snapshot()["SHELL"])
        val cwd = resolveWorkingDirectory(workingDirectory)
            ?: return BackendResult.Failure(
                BackendError.Project(
                    projectName = cwdName(workingDirectory),
                    technical = "Directory does not exist: $workingDirectory",
                    requiresSelection = workingDirectory == null
                )
            )

        val sessionId = TerminalSession.newId()
        val geometry = defaultGeometry(columns, rows)
        val childEnvironment: Map<String, String> = HashMap(environment.snapshot()).apply {
            put("PWD", cwd.absolutePath)
        }
        val outputSink: (ByteArray, Boolean) -> Unit = { data, isStderr ->
            onPtyData(sessionId, data, isStderr)
        }
        val request = PtyRequest(
            shell = shell,
            shellArguments = shellArgs,
            workingDirectory = cwd.absolutePath,
            environment = childEnvironment,
            secrets = emptyMap(),
            columns = geometry.first,
            rows = geometry.second,
            onOutput = outputSink
        )

        val factory: PtyHostFactory = factorySelector.select()
        return when (val created = factory.create(request)) {
            is BackendResult.Failure -> {
                log.warn(LogCategory.TERMINAL, "Terminal session creation failed", created.error.technical)
                created
            }
            is BackendResult.Success -> {
                val host = created.value
                val descriptor = TerminalSession(
                    sessionId = sessionId,
                    workingDirectory = cwd.absolutePath,
                    displayWorkingDirectory = layout.toUserPath(cwd),
                    shell = ShellLocator.displayName(shell),
                    processId = host.pid,
                    status = TerminalStatus.RUNNING,
                    createdAt = System.currentTimeMillis(),
                    columns = geometry.first,
                    rows = geometry.second,
                    backendId = factory.id,
                    supportsFullScreen = factory.supportsFullScreen
                )
                val session = Session(descriptor, host, ScrollbackBuffer(scrollbackLimit))
                liveSessions[sessionId] = session

                // Poll liveness so a shell that exits on its own is reported as EXITED rather than
                // lingering as RUNNING (spec 32, spec 113 - never fake a live terminal).
                startLivenessWatcher(session)

                publishSessions()
                log.info(
                    LogCategory.TERMINAL,
                    "Terminal session created",
                    "id=$sessionId pid=${host.pid} shell=${descriptor.shell} backend=${factory.id}"
                )
                BackendResult.Success(descriptor)
            }
        }
    }

    /** Spec 63 - close exactly one session. */
    fun close(sessionId: String): BackendResult<Unit> {
        val session = liveSessions.remove(sessionId)
            ?: return BackendResult.Failure(
                BackendError.Session(null, "No terminal session with id $sessionId")
            )
        session.status = TerminalStatus.CLOSING
        runCatching { session.host.close() }
        session.status = TerminalStatus.CLOSED
        publishSessions()
        log.info(LogCategory.TERMINAL, "Terminal session closed by request", "id=$sessionId")
        return BackendResult.Success(Unit)
    }

    /** Spec 62 - used by "Stop Server": close every terminal session. */
    fun closeAll(): Int {
        val ids = liveSessions.keys.toList()
        ids.forEach { close(it) }
        return ids.size
    }

    fun sessionCount(): Int = liveSessions.size

    fun snapshot(sessionId: String): TerminalSession? = liveSessions[sessionId]?.descriptor

    fun scrollback(sessionId: String, lines: Int = 2_000): List<String> =
        liveSessions[sessionId]?.buffer?.tail(lines).orEmpty()

    fun droppedLines(sessionId: String): Long = liveSessions[sessionId]?.buffer?.dropped() ?: 0

    // ---- input ------------------------------------------------------------------------

    /** Spec 31 - raw text input (a typed command plus Enter). */
    fun writeInput(sessionId: String, data: String): BackendResult<Unit> =
        withSession(sessionId) { it.host.write(data) }

    /** Spec 65/66 - special keys from the toolbar. */
    fun writeKey(sessionId: String, key: SpecialKey): BackendResult<Unit> =
        withSession(sessionId) { session -> session.host.writeBytes(key.bytes) }

    /**
     * Spec 65 - encode a toolbar key press honouring latched modifiers, then consume a sticky
     * modifier so the next keystroke is unmodified.
     */
    fun writeSpecial(
        sessionId: String,
        key: SpecialKey,
        modifiers: ModifierState,
        literal: Char? = null
    ): BackendResult<Unit> {
        val encoded = TerminalKeyEncoder.encode(key, modifiers, literal)
            ?: return BackendResult.Success(Unit)
        return withSession(sessionId) { it.host.writeBytes(encoded) }
    }

    /** Spec 64 - geometry change without restarting the shell. */
    fun resize(sessionId: String, columns: Int, rows: Int): BackendResult<Unit> =
        withSession(sessionId) { session ->
            val (cols, rws) = defaultGeometry(columns, rows)
            session.host.resize(cols, rws)
            liveSessions[sessionId]?.let {
                it.descriptor = it.descriptor.copy(columns = cols, rows = rws)
            }
            publishSessions()
        }

    /** Spec 85 - SIGINT, not a tree kill. */
    fun interrupt(sessionId: String): BackendResult<Unit> =
        withSession(sessionId) { it.host.interrupt() }

    /** Ctrl+D semantics. */
    fun sendEof(sessionId: String): BackendResult<Unit> =
        withSession(sessionId) { it.host.sendEof() }

    fun isAlive(sessionId: String): Boolean = liveSessions[sessionId]?.host?.isAlive() == true

    fun setTitle(sessionId: String, title: String): BackendResult<Unit> = withSession(sessionId) { session ->
        session.descriptor = session.descriptor.copy(title = title)
        publishSessions()
    }

    // ---- non-interactive execution ---------------------------------------------------

    /**
     * Spec 31 `execute()` / spec 111 `executeTerminalCommand()`.
     *
     * Runs a command inside the same runtime environment as the terminal sessions and shares the
     * project working directory, so `npm install` here is visible to OpenCode (spec 53).
     *
     * The command runs through the user's own shell because that is the explicit product contract
     * of a developer terminal (spec 35). This is the *only* place a raw UI string reaches a shell,
     * and it is confined to the session's own process.
     */
    suspend fun execute(
        command: String,
        workingDirectory: String?
    ): BackendResult<CommandExecutionResult> {
        val cwd = resolveWorkingDirectory(workingDirectory)
            ?: return BackendResult.Failure(
                BackendError.Project(
                    projectName = cwdName(workingDirectory),
                    technical = "Directory does not exist: $workingDirectory",
                    requiresSelection = workingDirectory == null
                )
            )

        val shell = environment.snapshot()["SHELL"] ?: "/system/bin/sh"
        val started = System.currentTimeMillis()
        val result = runner.run(
            ProcessRunner.CommandSpec(
                command = shell,
                arguments = listOf("-c", command),
                workingDirectory = cwd.absolutePath,
                shell = false,
                timeoutMs = 120_000,
                extraEnv = mapOf("PWD" to cwd.absolutePath)
            )
        )

        val execution = CommandExecutionResult(
            command = command,
            workingDirectory = layout.toUserPath(cwd),
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            durationMs = System.currentTimeMillis() - started,
            timedOut = result.timedOut
        )

        return if (result.timedOut) {
            BackendResult.Failure(
                BackendError.Timeout("command", 120_000)
            )
        } else {
            BackendResult.Success(execution)
        }
    }

    // ---- internals --------------------------------------------------------------------

    private inline fun withSession(
        sessionId: String,
        block: (Session) -> Unit
    ): BackendResult<Unit> {
        val session = liveSessions[sessionId]
            ?: return BackendResult.Failure(
                BackendError.Session(null, "Terminal session $sessionId is not open")
            )
        if (!session.host.isAlive()) {
            return BackendResult.Failure(
                BackendError.Categorized(
                    technical = "pty ${session.host.id} is not alive (status=${session.status})",
                    category = com.palash.opencodeserver.backend.core.ErrorCategory.RUNTIME,
                    retryable = false,
                    userTitle = "This terminal has exited.",
                    userMessage = "Open a new terminal session to run commands."
                )
            )
        }
        return try {
            block(session)
            BackendResult.Success(Unit)
        } catch (e: Exception) {
            BackendResult.Failure(BackendError.wrap(e))
        }
    }

    private fun resolveWorkingDirectory(requested: String?): File? {
        if (requested.isNullOrBlank()) return layout.home
        val file = layout.resolveUserPath(requested)
        return file.takeIf { it.isDirectory }
    }

    private fun cwdName(requested: String?): String =
        requested?.takeIf { it.isNotBlank() }?.let { File(it).name } ?: "home"

    private fun onPtyData(sessionId: String, data: ByteArray, isStderr: Boolean) {
        liveSessions[sessionId]?.buffer?.let { buffer ->
            String(data, Charsets.UTF_8).lineSequence().forEach { buffer.append(it) }
        }
        // Publish the raw bytes: the emulator, not the backend, owns escape-sequence handling.
        _output.tryEmit(TerminalOutputChunk(sessionId, data, isStderr, System.currentTimeMillis()))
        listeners.forEach { runCatching { it(sessionId) } }
    }

    /**
     * Spec 32/113 - verify the process rather than trusting a stored flag.
     *
     * A cheap daemon thread per session keeps the check off both the UI thread and the PTY reader.
     */
    private fun startLivenessWatcher(session: Session) {
        Thread({
            while (liveSessions.containsKey(session.descriptor.sessionId)) {
                Thread.sleep(1_000)
                val stillAlive = runCatching { session.host.isAlive() }.getOrDefault(false)
                if (!stillAlive) {
                    val id = session.descriptor.sessionId
                    liveSessions.remove(id)
                    val code = runCatching { session.host.pid }.getOrDefault(-1)
                    session.status = TerminalStatus.EXITED
                    _exits.tryEmit(TerminalExit(id, code))
                    publishSessions()
                    log.info(LogCategory.TERMINAL, "Terminal session exited", "id=$id")
                    break
                }
            }
        }, "pty-watch-${session.descriptor.sessionId}").apply {
            isDaemon = true
            start()
        }
    }

    private fun publishSessions() {
        _sessions.update { current ->
            liveSessions.values.map { it.descriptor.copy(status = it.status) }
                .sortedBy { it.createdAt }
                .ifEmpty { current }
        }
    }

    /** Spec 42 - Advanced details about the terminal subsystem. */
    fun describe(): List<String> = buildList {
        addAll(factorySelector.describe())
        add("Shell: ${ShellLocator.preferredShell(environment.snapshot()["SHELL"]).first}")
        add("Open sessions: ${liveSessions.size}")
        add("Scrollback limit: ${if (scrollbackLimit == Int.MAX_VALUE) "unlimited" else "$scrollbackLimit lines"}")
    }
}

/**
 * The result of running one non-interactive command.
 *
 * Public because [TerminalManager.execute] returns it and the frontend needs both the output and the
 * exit code to render honestly (spec 33/35 - output is shown, not hidden).
 */
data class CommandExecutionResult(
    val command: String,
    val workingDirectory: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean = false
)
