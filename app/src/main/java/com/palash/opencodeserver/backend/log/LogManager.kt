package com.palash.opencodeserver.backend.log

import android.content.Context
import android.util.Log
import com.palash.opencodeserver.backend.core.BackendLogEntry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LogLevel(val priority: Int, val label: String) {
    DEBUG(0, "DEBUG"),
    INFO(1, "INFO"),
    WARN(2, "WARN"),
    ERROR(3, "ERROR");

    companion object {
        fun from(value: String?): LogLevel = when (value?.uppercase(Locale.US)) {
            "DEBUG", "TRACE" -> DEBUG
            "WARN", "WARNING" -> WARN
            "ERROR", "FATAL" -> ERROR
            "INFO" -> INFO
            else -> INFO
        }
    }
}

/** Spec 41 - fixed category vocabulary so log filters are stable. */
enum class LogCategory(val label: String) {
    RUNTIME("RUNTIME"),
    DEPENDENCY("DEPENDENCY"),
    TERMINAL("TERMINAL"),
    OPENCODE("OPENCODE"),
    API("API"),
    SERVER("SERVER"),
    EVENT("EVENT"),
    AUTH("AUTH"),
    PROJECT("PROJECT"),
    SECURITY("SECURITY"),
    STORAGE("STORAGE"),
    NETWORK("NETWORK"),
    TERMINAL_ERROR("ERROR");

    companion object {
        fun from(value: String?): LogCategory = entries.firstOrNull {
            it.label.equals(value, ignoreCase = true)
        } ?: TERMINAL_ERROR
    }
}

/**
 * Spec 41/42/43/105 - the single structured log sink for the whole backend.
 *
 * Responsibilities:
 *  - redact everything through [SecretRedactor] before it is stored anywhere
 *  - keep a bounded in-memory ring buffer (spec 67/105) so log volume cannot exhaust RAM
 *  - append to a size-capped file so diagnostics survive a process restart
 *  - publish a hot flow the UI subscribes to
 *
 * The UI never sees a raw message: [BackendLogEntry] carries already-redacted text.
 */
class LogManager(
    private val context: Context? = null,
    private val memoryLimit: Int = 2_000,
    private val fileLimitBytes: Long = 2L * 1024 * 1024,
    private val minimumLevel: LogLevel = LogLevel.INFO
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    private val buffer = ArrayDeque<BackendLogEntry>(memoryLimit)
    private val fileWriteMutex = Mutex()

    private val _entries = MutableSharedFlow<BackendLogEntry>(
        replay = 128,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val entries: SharedFlow<BackendLogEntry> = _entries.asSharedFlow()

    private val _count = MutableStateFlow(0L)
    val count: StateFlow<Long> = _count.asStateFlow()

    @Volatile
    private var level: LogLevel = minimumLevel

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val logFile: File?
        get() = context?.let { ctx ->
            runCatching {
                val dir = File(ctx.filesDir, "logs").apply { if (!exists()) mkdirs() }
                File(dir, "backend.log")
            }.getOrNull()
        }

    // ---- public API ------------------------------------------------------------------

    fun setMinimumLevel(newLevel: LogLevel) {
        level = newLevel
    }

    fun currentMinimumLevel(): LogLevel = level

    fun debug(category: LogCategory, message: String, detail: String? = null) =
        log(LogLevel.DEBUG, category, message, detail)

    fun info(category: LogCategory, message: String, detail: String? = null) =
        log(LogLevel.INFO, category, message, detail)

    fun warn(category: LogCategory, message: String, detail: String? = null) =
        log(LogLevel.WARN, category, message, detail)

    fun error(category: LogCategory, message: String, detail: String? = null, throwable: Throwable? = null) =
        log(LogLevel.ERROR, category, message, detail ?: throwable?.let { stackTraceOf(it) })

    /**
     * The one funnel every message passes through.
     *
     * [redacted] skips the redactor only for strings that are already known-safe constants
     * (state names, enums). Never pass user input or process output through it.
     */
    fun log(
        logLevel: LogLevel,
        category: LogCategory,
        message: String,
        detail: String? = null,
        redacted: Boolean = false
    ) {
        if (logLevel.priority < level.priority) return

        val safeMessage = if (redacted) message else SecretRedactor.redact(message)
        val safeDetail = detail?.let { if (redacted) it else SecretRedactor.redact(it) }

        val entry = BackendLogEntry(
            id = "log-${System.nanoTime()}",
            timestamp = System.currentTimeMillis(),
            level = logLevel,
            category = category,
            message = safeMessage,
            detail = safeDetail
        )

        androidLog(logLevel, category, safeMessage, safeDetail)
        appendToMemory(entry)

        scope.launch {
            appendToFile(entry)
            _entries.emit(entry)
        }
        _count.update { it + 1 }
    }

    /** Snapshot for the diagnostics screen. */
    fun snapshot(limit: Int = 500): List<BackendLogEntry> =
        synchronized(buffer) { buffer.toList().takeLast(limit) }

    fun snapshotLines(limit: Int = 500): List<String> =
        snapshot(limit).map { render(it) }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
    }

    /** Spec 59 - the copyable diagnostics payload with secrets removed. */
    fun exportRedacted(lines: Int = 800): String =
        snapshotLines(lines).joinToString("\n") { it }

    fun rotateIfNeeded() {
        scope.launch {
            val file = logFile ?: return@launch
            if (!file.exists() || file.length() < fileLimitBytes) return@launch
            val rotated = File(file.parentFile, "backend.previous.log")
            runCatching {
                if (rotated.exists()) rotated.delete()
                file.renameTo(rotated)
            }
        }
    }

    // ---- internals -------------------------------------------------------------------

    private fun appendToMemory(entry: BackendLogEntry) {
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > memoryLimit) buffer.removeFirst()
        }
    }

    private suspend fun appendToFile(entry: BackendLogEntry) {
        val file = logFile ?: return
        val line = render(entry)
        fileWriteMutex.withLock {
            // Over cap: drop the write rather than growing the file without bound.
            // rotateIfNeeded() handles the actual rollover.
            if (file.exists() && file.length() > fileLimitBytes) return@withLock
            writeLine(file, line)
        }
    }

    /** Append one line, swallowing I/O failures: a log write must never surface as an app error. */
    private fun writeLine(file: File, line: String) {
        try {
            FileOutputStream(file, true).use { stream ->
                stream.write((line + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (e: IOException) {
            // Intentionally ignored: losing a log line is preferable to crashing the caller.
        }
    }

    private fun androidLog(level: LogLevel, category: LogCategory, message: String, detail: String?) {
        val tag = "OpenCode/${category.label}"
        val text = if (detail.isNullOrBlank()) message else "$message | ${detail.take(280)}"
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, text)
            LogLevel.INFO -> Log.i(tag, text)
            LogLevel.WARN -> Log.w(tag, text)
            LogLevel.ERROR -> Log.e(tag, text)
        }
    }

    /** Spec 41 example format: `[13:42:18] OPENCODE` then the message. */
    private fun render(entry: BackendLogEntry): String {
        val stamp = synchronized(timeFormat) { timeFormat.format(Date(entry.timestamp)) }
        val head = "[$stamp] ${entry.category.label} ${entry.message}"
        return if (entry.detail.isNullOrBlank()) head else "$head\n    ${entry.detail}"
    }

    private fun stackTraceOf(throwable: Throwable): String = runCatching {
        StringWriter().also { sw ->
            java.io.PrintWriter(sw).use { throwable.printStackTrace(it) }
        }.toString().lineSequence().take(12).joinToString("\n")
    }.getOrElse { throwable::class.java.simpleName }

    companion object {
        private val timePattern = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}