package com.palash.opencodeserver.backend.runtime.pty

import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.runtime.ProcessCategory
import com.palash.opencodeserver.backend.runtime.ProcessRunner
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spec 33 - pipe-backed fallback terminal.
 *
 * Used when the native `forkpty` library is unavailable. It is a real process with real stdin,
 * stdout and stderr, so every ordinary command, pipe, redirection and exit code works. What it
 * cannot do is present a controlling terminal, so full-screen programs, colour hints from the TTY
 * and job control degrade. That is reported honestly through [supportsFullScreen] rather than
 * pretending otherwise.
 *
 * The shell still does all the parsing (spec 33): we exec `bash -i` / `sh -i` and write raw bytes.
 */
class PipePtyHost private constructor(
    private val process: Process,
    override val pid: Int,
    private val writer: BufferedWriter,
    private val log: LogManager,
    private val request: PtyRequest
) : PtyHost {

    private val closed = AtomicBoolean(false)
    override val id: String = "pty-pipe-$pid"

    override fun resize(columns: Int, rows: Int) {
        // No controlling terminal means no TIOCSWINSZ. Export COLUMNS/LINES instead so line-wrapped
        // tools at least have the right width, and keep the shell alive (spec 64).
        if (closed.get()) return
        runCatching {
            writer.write("export COLUMNS=$columns LINES=$rows\n")
            writer.flush()
        }
        log.debug(LogCategory.TERMINAL, "Pipe terminal resize applied via COLUMNS/LINES", "cols=$columns rows=$rows")
    }

    override fun write(data: String) = writeBytes(data.toByteArray(Charsets.UTF_8))

    override fun writeBytes(data: ByteArray) {
        if (closed.get() || data.isEmpty()) return
        runCatching {
            writer.write(String(data, Charsets.UTF_8))
            writer.flush()
        }.onFailure { log.debug(LogCategory.TERMINAL, "stdin write failed", it.message) }
    }

    override fun interrupt() {
        if (closed.get() || pid <= 0) return
        runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGINT) }
    }

    override fun sendEof() = writeBytes(byteArrayOf(4))

    override fun isAlive(): Boolean = !closed.get() && process.isAlive

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { writer.close() }
        runCatching { process.destroy() }
        if (process.isAlive) runCatching { process.destroyForcibly() }
        log.info(LogCategory.TERMINAL, "Pipe terminal closed", "pid=$pid")
    }

    companion object {

        fun open(
            request: PtyRequest,
            runner: ProcessRunner,
            log: LogManager
        ): BackendResult<PipePtyHost> {
            val env = request.environment + request.secrets + mapOf("TERM" to request.term)

            // Sized geometry is expressed through COLUMNS/LINES because there is no winsize.
            val result = runner.start(
                spec = ProcessRunner.CommandSpec(
                    command = request.shell,
                    arguments = request.shellArguments,
                    workingDirectory = request.workingDirectory,
                    shell = false,
                    category = ProcessCategory.TERMINAL,
                    extraEnv = env + mapOf(
                        "COLUMNS" to request.columns.toString(),
                        "LINES" to request.rows.toString()
                    ),
                    onStdout = { chunk -> request.onOutput(chunk.toByteArray(Charsets.UTF_8), false) },
                    onStderr = { chunk -> request.onOutput(chunk.toByteArray(Charsets.UTF_8), true) }
                )
            ) ?: return BackendResult.Failure(
                ptyUnavailable("Failed to spawn ${request.shell} in ${request.workingDirectory}")
            )

            val (descriptor, process) = result
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))

            log.info(
                LogCategory.TERMINAL,
                "Pipe terminal opened (full-screen programs unavailable)",
                "pid=${descriptor.pid} shell=${request.shell} cwd=${request.workingDirectory}"
            )
            return BackendResult.Success(PipePtyHost(process, descriptor.pid, writer, log, request))
        }
    }
}

/**
 * Spec 33 - chooses the best available PTY backend.
 *
 * Selection is explicit and reported, so the UI can show "Terminal: native PTY" versus
 * "Terminal: compatibility mode" in Advanced details instead of silently degrading.
 */
class PtyHostSelector(
    private val forkFactory: ForkPtyHostFactory,
    private val pipeFactory: PipePtyHostFactory,
    private val log: LogManager
) {

    fun select(): PtyHostFactory =
        if (forkFactory.isAvailable()) {
            log.info(LogCategory.RUNTIME, "Using native PTY backend", "factory=${forkFactory.id}")
            forkFactory
        } else {
            log.warn(
                LogCategory.RUNTIME,
                "Native PTY backend unavailable; using compatibility mode",
                forkFactory.unavailableReason() ?: "unknown reason"
            )
            pipeFactory
        }

    fun describe(): List<String> = listOf(
        "Native PTY: ${if (forkFactory.isAvailable()) "available" else "unavailable"}",
        "Compatibility PTY: available",
        "Selected: ${select().id}"
    )
}

/** Spec 33 - pipe-backed factory. Always constructible, so it is the guaranteed fallback. */
class PipePtyHostFactory(private val runner: ProcessRunner, private val log: LogManager) : PtyHostFactory {

    override val id = "pipe"
    override val displayName = "Compatibility terminal (pipes)"
    override val supportsResize = false
    override val supportsFullScreen = false

    override fun isAvailable(): Boolean = true

    override suspend fun create(request: PtyRequest): BackendResult<PtyHost> =
        PipePtyHost.open(request, runner, log)
}

/** Spec 34 - expose the detected shell for the settings screen. */
fun currentShellLabel(): String {
    val (path, _) = ShellLocator.preferredShell()
    return ShellLocator.displayName(path)
}

/** Small helper for tests and the diagnostics screen. */
fun ptyWorkingDirectoryOrHome(home: File, requested: String?): File =
    requested?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory } ?: home