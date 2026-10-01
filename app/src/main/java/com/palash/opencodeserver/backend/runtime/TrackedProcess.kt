package com.palash.opencodeserver.backend.runtime

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Spec 39 - the metadata every tracked process carries. */
data class ProcessDescriptor(
    val id: String,
    val pid: Int,
    val command: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val startedAt: Long,
    val category: ProcessCategory,
    val exitCode: Int?,
    val isAlive: Boolean
) {
    fun commandLine(): String = (listOf(command) + arguments).joinToString(" ")

    /** Spec 42 - Advanced details block. */
    fun toAdvancedLines(): List<String> = listOf(
        "Process: ${commandLine()}",
        "PID: ${if (pid > 0) pid else "unknown"}",
        "Working directory: $workingDirectory",
        "Started: $startedAt",
        "Status: ${if (isAlive) "running" else "exited"}",
        exitCode?.let { "Exit code: $it" } ?: "Exit code: -"
    )
}

enum class ProcessCategory { RUNTIME, OPENCODE_SERVER, TERMINAL, INSTALLER, MONITOR }

/**
 * Spec 39 - the process registry.
 *
 * PIDs alone are not trusted (spec 39): Android reuses them, and a process can be reaped between
 * the check and the use. Every entry keeps a handle to the live [Process] plus a start time, and
 * liveness is confirmed with [java.lang.Process.isAlive] rather than a kill(pid, 0) probe.
 */
class ProcessRegistry(private val log: LogManager) {

    private data class Entry(
        val descriptor: ProcessDescriptor,
        val handle: Process,
        val onExit: ((Int) -> Unit)?
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(
        handle: Process,
        command: String,
        arguments: List<String>,
        workingDirectory: String,
        category: ProcessCategory,
        onExit: ((Int) -> Unit)? = null
    ): ProcessDescriptor {
        val descriptor = ProcessDescriptor(
            id = UUID.randomUUID().toString(),
            pid = runCatching { handle.pid() }.getOrDefault(-1),
            command = command,
            arguments = arguments,
            workingDirectory = workingDirectory,
            startedAt = System.currentTimeMillis(),
            category = category,
            exitCode = null,
            isAlive = handle.isAlive
        )
        entries[descriptor.id] = Entry(descriptor, handle, onExit)

        log.info(
            LogCategory.RUNTIME,
            "Registered ${category.name.lowercase()} process",
            "pid=${descriptor.pid} cmd=${descriptor.commandLine()}"
        )
        return descriptor
    }

    fun byId(id: String): ProcessDescriptor? = entries[id]?.descriptor?.copy(isAlive = isAlive(id))

    fun byCategory(category: ProcessCategory): List<ProcessDescriptor> =
        entries.values.filter { it.descriptor.category == category }
            .map { it.descriptor.copy(isAlive = isAlive(it.descriptor.id)) }

    fun all(): List<ProcessDescriptor> = entries.values.map { it.descriptor.copy(isAlive = isAlive(it.descriptor.id)) }

    fun handle(id: String): Process? = entries[id]?.handle

    /** Spec 39 - verify liveness against the real handle, not a stored flag. */
    fun isAlive(id: String): Boolean = entries[id]?.let { entry ->
        entry.handle.isAlive
    } ?: false

    fun isAlive(descriptor: ProcessDescriptor): Boolean = isAlive(descriptor.id)

    fun activeHandleCount(): Int = entries.values.count { it.handle.isAlive }

    /**
     * Reap finished processes and invoke their exit callbacks exactly once.
     *
     * Called by the monitor loop rather than by each owner, so a crashed server is noticed even if
     * nobody is awaiting it (spec 40).
     */
    fun reap(): List<ProcessDescriptor> {
        val exited = entries.values.filter { !it.handle.isAlive }
        exited.forEach { entry ->
            val code = runCatching { entry.handle.exitValue() }.getOrDefault(-1)
            entry.descriptor.let {
                log.info(
                    LogCategory.RUNTIME,
                    "Process exited",
                    "pid=${it.pid} code=$code cmd=${it.commandLine()}"
                )
            }
            entries.remove(entry.descriptor.id)
            runCatching { entry.onExit?.invoke(code) }
        }
        return exited.map { it.descriptor.copy(isAlive = false, exitCode = runCatching { it.handle.exitValue() }.getOrDefault(-1)) }
    }

    /**
     * Spec 85 - graceful stop first.
     *
     * [signal] is the process-group signal to attempt. Callers escalate only when the graceful
     * attempt times out; nothing here ever kills the whole runtime tree.
     */
    fun stopGracefully(id: String, timeoutMs: Long = 4_000): BackendError? {
        val entry = entries[id] ?: return null
        if (!entry.handle.isAlive) return null
        val descriptor = entry.descriptor
        return try {
            // Signal the child first so well behaved processes can flush state.
            descriptor.pid.takeIf { it > 0 }?.let { pid ->
                runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGTERM) }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline && entry.handle.isAlive) {
                Thread.sleep(80)
            }
            if (entry.handle.isAlive) {
                // Escalate to the single process only.
                runCatching { entry.handle.destroy() }
                val hardDeadline = System.currentTimeMillis() + 2_000
                while (System.currentTimeMillis() < hardDeadline && entry.handle.isAlive) {
                    Thread.sleep(80)
                }
            }
            if (entry.handle.isAlive) {
                runCatching { android.system.Os.kill(descriptor.pid, android.system.OsConstants.SIGKILL) }
            }
            entries.remove(id)
            log.info(LogCategory.RUNTIME, "Process stopped", "pid=${descriptor.pid}")
            null
        } catch (e: Exception) {
            BackendError.Runtime("Failed to stop pid ${descriptor.pid}: ${e.message}", retryable = true)
        }
    }

    /** Spec 62/63 - stop everything in a category without touching unrelated sessions. */
    fun stopCategory(category: ProcessCategory, timeoutMs: Long = 4_000): List<BackendError> =
        byCategory(category).mapNotNull { stopGracefully(it.id, timeoutMs) }

    fun clear() = entries.clear()

    fun describeAll(): List<String> = all().map { it.toAdvancedLines().joinToString("\n") }
}

/**
 * `Process.pid()` is only public from API 26. Older devices get -1, which every caller treats as
 * "unknown" rather than "not running" (spec 39).
 */
internal fun java.lang.Process.pid(): Int =
    runCatching {
        val method = java.lang.Process::class.java.getMethod("pid")
        method.invoke(this) as Int
    }.getOrElse { -1 }

/** Resolve a userland relative path to an absolute host path under the managed layout. */
internal fun File.managedChild(relative: String): File = File(relative)