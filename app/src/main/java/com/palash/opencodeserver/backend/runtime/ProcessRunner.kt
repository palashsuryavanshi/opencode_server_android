package com.palash.opencodeserver.backend.runtime

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.ErrorCategory
import com.palash.opencodeserver.backend.core.describe
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.log.SecretRedactor
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Spec 84 - structured process execution.
 *
 * Every child process in the backend goes through this class. The rules it enforces:
 *
 *  - arguments are passed as an **array**; no command string is ever built by concatenation
 *  - a shell is used only when the caller explicitly asks for it (`shell = true`), which the
 *    installer needs and the API layer does not
 *  - stdout and stderr are drained on background threads, so a chatty child can never fill a pipe
 *    buffer and deadlock, and can never block the Android main thread
 *  - the whole environment comes from [EnvironmentBuilder], so secrets are opt-in
 */
class ProcessRunner(
    private val environment: EnvironmentBuilder,
    private val registry: ProcessRegistry,
    private val log: LogManager,
    private val layout: RuntimeLayout
) {

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val timedOut: Boolean = false
    ) {
        val isSuccess: Boolean get() = exitCode == 0 && !timedOut
    }

    data class CommandSpec(
        val command: String,
        val arguments: List<String> = emptyList(),
        val workingDirectory: String? = null,
        /** Only the installer and the terminal set this; API calls never do. */
        val shell: Boolean = false,
        val timeoutMs: Long = 60_000,
        val category: ProcessCategory = ProcessCategory.RUNTIME,
        val extraEnv: Map<String, String> = emptyMap(),
        val onStdout: ((String) -> Unit)? = null,
        val onStderr: ((String) -> Unit)? = null
    )

    /**
     * Run a command to completion.
     *
     * Used by detection (`git --version`), installers, and the health check. Nothing here touches
     * the Android main thread: callers are expected to be on `Dispatchers.IO` already, and this
     * method never touches a UI resource.
     */
    fun run(spec: CommandSpec): CommandResult {
        val started = System.currentTimeMillis()
        val cwd = File(spec.workingDirectory ?: layout.home.absolutePath)
        if (!cwd.isDirectory) {
            return CommandResult(-1, "", "Working directory does not exist: $cwd", 0)
        }

        val processBuilder = ProcessBuilder().apply {
            directory(cwd)
            if (spec.shell) {
                // The single sanctioned place a shell is invoked. The command string is supplied
                // by installers that own both halves of it, never by a UI text field.
                command(listOf(shellBinary(), "-c", buildShellCommand(spec.command, spec.arguments)))
            } else {
                command(listOf(spec.command) + spec.arguments)
            }
            environment().clear()
            environment().putAll(environment.processEnvironment(spec.extraEnv))
        }

        return try {
            val process = processBuilder.start()
            registry.register(
                handle = process,
                command = spec.command,
                arguments = spec.arguments,
                workingDirectory = cwd.absolutePath,
                category = spec.category
            )

            val out = StreamCollector(process.inputStream, spec.onStdout)
            val err = StreamCollector(process.errorStream, spec.onStderr)
            val outThread = Thread(out, "proc-stdout").apply { isDaemon = true; start() }
            val errThread = Thread(err, "proc-stderr").apply { isDaemon = true; start() }

            val finished = process.waitFor(spec.timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                // Escalation is bounded and scoped to this single process (spec 85).
                process.destroyForcibly()
            }
            outThread.join(1_500)
            errThread.join(1_500)

            CommandResult(
                exitCode = if (finished) process.exitValue() else -1,
                stdout = out.text(),
                stderr = err.text(),
                durationMs = System.currentTimeMillis() - started,
                timedOut = !finished
            )
        } catch (e: Exception) {
            log.error(LogCategory.RUNTIME, "Process launch failed", "cmd=${spec.command}", e)
            CommandResult(-1, "", e.describe(), System.currentTimeMillis() - started)
        }
    }

    /** Convenience wrapper that turns a non-zero exit into a typed error. */
    fun runOrThrow(spec: CommandSpec): CommandResult {
        val result = run(spec)
        if (!result.isSuccess) {
            throw BackendException(result.toError(spec))
        }
        return result
    }

    private fun CommandResult.toError(spec: CommandSpec): BackendError {
        val technical = buildString {
            append("exit=$exitCode")
            if (timedOut) append(" timedOut=true after ${spec.timeoutMs}ms")
            stderr.takeIf { it.isNotBlank() }?.let { append("\n").append(SecretRedactor.redact(it).take(600)) }
        }
        return BackendError.Categorized(
            technical = technical,
            category = if (timedOut) ErrorCategory.TIMEOUT else ErrorCategory.RUNTIME,
            retryable = timedOut,
            userTitle = if (timedOut) "The command took too long." else "A required tool could not run.",
            userMessage = "Repair the development environment from Settings and try again."
        )
    }

    /**
     * Start a long-lived process and return its descriptor immediately.
     *
     * The server and every terminal session use this. Output is delivered through [onStdout] and
     * [onStderr] on background threads.
     */
    fun start(
        spec: CommandSpec,
        onExit: ((Int) -> Unit)? = null
    ): Pair<ProcessDescriptor, Process>? {
        val cwd = File(spec.workingDirectory ?: layout.home.absolutePath)
        if (!cwd.isDirectory) return null

        return try {
            val builder = ProcessBuilder().apply {
                directory(cwd)
                if (spec.shell) {
                    command(listOf(shellBinary(), "-c", buildShellCommand(spec.command, spec.arguments)))
                } else {
                    command(listOf(spec.command) + spec.arguments)
                }
                environment().clear()
                environment().putAll(environment.processEnvironment(spec.extraEnv))
            }
            val process = builder.start()

            spec.onStdout?.let { sink ->
                Thread(StreamCollector(process.inputStream, sink), "proc-out").apply {
                    isDaemon = true
                    start()
                }
            }
            spec.onStderr?.let { sink ->
                Thread(StreamCollector(process.errorStream, sink), "proc-err").apply {
                    isDaemon = true
                    start()
                }
            }

            val descriptor = registry.register(
                handle = process,
                command = spec.command,
                arguments = spec.arguments,
                workingDirectory = cwd.absolutePath,
                category = spec.category,
                onExit = onExit
            )
            descriptor to process
        } catch (e: Exception) {
            log.error(LogCategory.RUNTIME, "Failed to start long-lived process", "cmd=${spec.command}", e)
            null
        }
    }

    /** Spec 8 - `command -v` inside the runtime, not on the host JVM. */
    fun which(command: String): String? {
        environment.which(command)?.let { return it }
        val result = run(
            CommandSpec(
                command = shellBinary(),
                arguments = listOf("-c", "command -v ${shellQuote(command)}"),
                shell = false,
                timeoutMs = 8_000
            )
        )
        return result.stdout.trim().lineSequence().firstOrNull()?.takeIf { it.isNotBlank() && it != command }
    }

    /** Spec 8 - a version string, returning null when the tool is absent. */
    fun versionOf(command: String, versionArgs: List<String> = listOf("--version")): String? {
        val result = run(CommandSpec(command = command, arguments = versionArgs, timeoutMs = 15_000))
        if (result.timedOut) return null
        val combined = (result.stdout + "\n" + result.stderr).trim()
        // An exec failure prints to stderr on most shells; treat that as "not installed".
        if (combined.isEmpty() || result.exitCode == 127) return null
        return combined.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun shellBinary(): String =
        environment.snapshot()["SHELL"] ?: "/system/bin/sh"

    /**
     * Compose a shell command from an already-owned command plus arguments.
     *
     * Each argument is single-quoted, so a value containing spaces, `;` or `$(...)` is passed to the
     * shell as a literal. Callers still must not pass raw UI text here; this is defence in depth.
     */
    internal fun buildShellCommand(command: String, arguments: List<String>): String =
        (listOf(command) + arguments).joinToString(" ") { shellQuote(it) }

    internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Drains a process stream without buffering without bound (spec 105).
     *
     * Callers that only need the tail get a bounded buffer; callers that stream pass [onStdout] and
     * get chunks as they arrive.
     */
    private class StreamCollector(
        private val stream: java.io.InputStream,
        private val onChunk: ((String) -> Unit)?
    ) : Runnable {
        private val builder = StringBuilder()

        /** Spec 105 - bounded retention; a chatty child cannot exhaust the heap. */
        private val maxRetained = 64 * 1024

        override fun run() {
            runCatching {
                stream.bufferedReader().use { reader ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val read = runCatching { reader.read(chunk) }.getOrDefault(-1)
                        if (read <= 0) break
                        val text = String(chunk, 0, read)
                        synchronized(builder) {
                            builder.append(text)
                            if (builder.length > maxRetained) {
                                builder.delete(0, builder.length - maxRetained)
                            }
                        }
                        onChunk?.invoke(text)
                    }
                }
            }
        }

        fun text(): String = synchronized(builder) { builder.toString() }
    }
}

private typealias BackendException = com.palash.opencodeserver.backend.core.BackendException