package com.palash.opencodeserver.backend.runtime.pty

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spec 33 - the real PTY implementation.
 *
 * Backed by JNI (`libopencodepty.so`), which wraps `forkpty()` from the Android NDK. `forkpty`
 * creates a genuine controlling terminal, which is what makes `vim`, `less`, `top`, `git log -p`,
 * job control and colour output behave correctly. No shell grammar is reimplemented here.
 *
 * The native library loads lazily and its absence is not fatal: [ForkPtyHostFactory.isAvailable]
 * reports the truth and the runtime falls back to [PipePtyHost], degrading full-screen programs
 * rather than the whole terminal.
 */
class ForkPtyHost private constructor(
    private val request: PtyRequest,
    private val log: LogManager,
    private val nativeHandle: Long,
    override val pid: Int
) : PtyHost {

    private val closed = AtomicBoolean(false)
    override val id: String = "pty-${UUID.randomUUID()}"

    override fun resize(columns: Int, rows: Int) {
        if (closed.get()) return
        NativePty.resizeFd(nativeHandle, columns, rows)
        log.debug(LogCategory.TERMINAL, "PTY resized", "cols=$columns rows=$rows pid=$pid")
    }

    override fun write(data: String) = writeBytes(data.toByteArray(Charsets.UTF_8))

    override fun writeBytes(data: ByteArray) {
        if (closed.get() || data.isEmpty()) return
        NativePty.writeFd(nativeHandle, data, data.size)
    }

    override fun interrupt() {
        if (closed.get() || pid <= 0) return
        // SIGINT, not SIGKILL: the child gets a chance to clean up (spec 85).
        runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGINT) }
        log.debug(LogCategory.TERMINAL, "Sent SIGINT to PTY child", "pid=$pid")
    }

    override fun sendEof() = writeBytes(byteArrayOf(4))

    override fun isAlive(): Boolean = !closed.get() && NativePty.isAliveFd(nativeHandle)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        NativePty.closeFd(nativeHandle)
        log.info(LogCategory.TERMINAL, "PTY session closed", "pid=$pid")
    }

    companion object {
        fun open(request: PtyRequest, log: LogManager): BackendResult<ForkPtyHost> {
            val (available, reason) = NativePty.availability()
            if (!available) return BackendResult.Failure(ptyUnavailable(reason ?: "native PTY unavailable"))

            val argv = (listOf(request.shell) + request.shellArguments).toTypedArray()
            val env = (request.environment + request.secrets + mapOf("TERM" to request.term))
                .map { "${it.key}=${it.value}" }
                .toTypedArray()

            // The sink is handed to the native side, which calls back into it from its reader
            // thread. It is retained for the session's lifetime.
            val sink = NativePty.NativeDataSink(request.onOutput)

            val handle = runCatching {
                NativePty.open(
                    workingDirectory = request.workingDirectory,
                    argv = argv,
                    envp = env,
                    columns = request.columns,
                    rows = request.rows,
                    sink = sink
                )
            }.getOrElse {
                return BackendResult.Failure(
                    ptyUnavailable("nOpen threw ${it::class.java.simpleName}: ${it.message}")
                )
            }

            if (handle == 0L) {
                return BackendResult.Failure(
                    ptyUnavailable(
                        NativePty.lastErrorMessage().ifBlank { "forkpty returned no descriptor" }
                    )
                )
            }

            val childPid = NativePty.openPid(handle)

            log.info(
                LogCategory.TERMINAL,
                "PTY session opened",
                "pid=$childPid shell=${request.shell} cwd=${request.workingDirectory}"
            )
            return BackendResult.Success(ForkPtyHost(request, log, handle, childPid))
        }
    }
}

/**
 * Spec 33 - the JNI surface.
 *
 * Design: the native reader thread calls `onNativeData` / `onNativeExit` directly on the sink
 * object handed to `nOpen`, so there is no shared lookup table and no handle bookkeeping on the
 * Kotlin side. Every other symbol is guarded, so a partially available library degrades rather than
 * crashing the process.
 */
internal object NativePty {

    private const val LIBRARY = "opencodepty"

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary(LIBRARY)
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private val loadFailure: String? by lazy {
        if (loaded) null else "lib$LIBRARY.so could not be loaded"
    }

    fun availability(): Pair<Boolean, String?> = Pair(loaded, loadFailure)

    // ---- native symbols -------------------------------------------------------------------

    private external fun nOpen(
        workingDirectory: String,
        argv: Array<String>,
        envp: Array<String>,
        columns: Int,
        rows: Int,
        callback: Any
    ): Long

    private external fun nOpenPid(handle: Long): Int
    private external fun nLastError(): String
    private external fun nWrite(handle: Long, data: ByteArray, length: Int): Int
    private external fun nResize(handle: Long, columns: Int, rows: Int): Int
    private external fun nIsAlive(handle: Long): Boolean
    private external fun nClose(handle: Long)

    // ---- guarded wrappers ----------------------------------------------------------------

    fun open(
        workingDirectory: String,
        argv: Array<String>,
        envp: Array<String>,
        columns: Int,
        rows: Int,
        sink: NativeDataSink
    ): Long = if (!loaded) 0L
    else runCatching { nOpen(workingDirectory, argv, envp, columns, rows, sink) }.getOrDefault(0L)

    fun openPid(handle: Long): Int =
        if (!loaded) -1 else runCatching { nOpenPid(handle) }.getOrDefault(-1)

    fun lastErrorMessage(): String =
        if (!loaded) loadFailure.orEmpty() else runCatching { nLastError() }.getOrDefault("")

    fun writeFd(handle: Long, data: ByteArray, length: Int): Int =
        if (!loaded) -1 else runCatching { nWrite(handle, data, length) }.getOrDefault(-1)

    fun resizeFd(handle: Long, columns: Int, rows: Int): Int =
        if (!loaded) -1 else runCatching { nResize(handle, columns, rows) }.getOrDefault(-1)

    fun isAliveFd(handle: Long): Boolean =
        if (!loaded) false else runCatching { nIsAlive(handle) }.getOrDefault(false)

    fun closeFd(handle: Long) {
        if (!loaded) return
        runCatching { nClose(handle) }
    }

    /**
     * The object the native reader thread calls back into.
     *
     * It must be a named class rather than a lambda, because JNI resolves the callback methods by
     * name on this class. The proguard rules keep both method names, since they are looked up from
     * C++ and cannot be discovered from Kotlin.
     */
    class NativeDataSink(private val onData: (ByteArray, Boolean) -> Unit) {

        fun onNativeData(data: ByteArray, isStderr: Boolean) {
            runCatching { onData(data, isStderr) }
        }

        fun onNativeExit(exitCode: Int) {
            // Reserved for a future exit notification; the Kotlin liveness watcher already observes
            // the process, so nothing is emitted here yet.
        }
    }
}

/** Spec 33 - advertises the real PTY when the native library is present. */
class ForkPtyHostFactory(private val log: LogManager) : PtyHostFactory {

    override val id = "forkpty"
    override val displayName = "Native pseudo terminal (forkpty)"
    override val supportsResize = true
    override val supportsFullScreen = true

    override fun isAvailable(): Boolean = NativePty.availability().first

    override fun unavailableReason(): String? = NativePty.availability().second

    override suspend fun create(request: PtyRequest): BackendResult<PtyHost> =
        when (val opened = ForkPtyHost.open(request, log)) {
            is BackendResult.Success -> BackendResult.Success(opened.value)
            is BackendResult.Failure -> BackendResult.Failure(
                // Preserve the specific reason the native layer reported (spec 43).
                ptyUnavailable(opened.error.technical ?: "PTY unavailable")
            )
        }
}

/** Spec 34 - shell discovery, preferring bash and falling back to sh. */
object ShellLocator {

    private val bashCandidates = listOf(
        "/data/data/com.termux/files/usr/bin/bash",
        "/data/user/0/com.termux/files/usr/bin/bash",
        "/usr/bin/bash",
        "/system/bin/bash"
    )

    private val shCandidates = listOf(
        "/data/data/com.termux/files/usr/bin/sh",
        "/data/user/0/com.termux/files/usr/bin/sh",
        "/usr/bin/sh",
        "/system/bin/sh"
    )

    /** Returns the shell path plus the arguments that make it interactive. */
    fun preferredShell(explicit: String? = null): Pair<String, List<String>> {
        explicit?.trim()?.takeIf { it.isNotEmpty() }?.let { return it to listOf("-i") }
        bashCandidates.firstOrNull { File(it).canExecute() }?.let { return it to listOf("-i") }
        shCandidates.firstOrNull { File(it).canExecute() }?.let { return it to listOf("-i") }
        return "/system/bin/sh" to listOf("-i")
    }

    fun availableShells(): List<String> = (bashCandidates + shCandidates).filter { File(it).canExecute() }

    fun displayName(path: String): String = File(path).name.ifBlank { path }
}
