package com.palash.opencodeserver.backend.monitor

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.palash.opencodeserver.backend.core.EnvironmentState
import com.palash.opencodeserver.backend.core.RuntimeMetrics
import com.palash.opencodeserver.backend.core.ServerProcessState
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.runtime.RuntimeLayout
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Spec 73 - lightweight resource monitoring.
 *
 * Deliberately cheap:
 *  - a slow interval, because nothing here is worth 1 Hz on a phone
 *  - no per-process polling loop; process liveness comes from the registry, which already tracks it
 *  - memory read once per tick from `Debug`, which is a single syscall
 *
 * CPU percentage is reported as null rather than estimated when it cannot be obtained honestly.
 */
class RuntimeMonitor(
    private val context: Context,
    private val layout: RuntimeLayout,
    private val log: LogManager,
    private val intervalMs: Long = 5_000
) {

    data class Providers(
        val isServerRunning: () -> Boolean,
        val serverPid: () -> Int?,
        val terminalProcessCount: () -> Int,
        val environmentState: () -> EnvironmentState,
        val activeConnections: () -> Int
    )

    fun observe(providers: Providers): Flow<RuntimeMetrics> = flow {
        while (true) {
            emit(sample(providers))
            delay(intervalMs)
        }
    }

    /** One sample. Exposed separately so the dashboard can pull without starting a flow. */
    fun sample(providers: Providers): RuntimeMetrics = RuntimeMetrics(
        openCodeRunning = providers.isServerRunning(),
        openCodePid = providers.serverPid(),
        memoryUsageMb = memoryUsageMb(),
        memoryLimitMb = memoryLimitMb(),
        // Not sampled: a meaningful CPU figure would require a sampling interval of its own, which
        // would cost more than the value provides. null is honest; a guess is not.
        cpuUsagePercent = null,
        terminalProcessCount = providers.terminalProcessCount(),
        storageFreeBytes = layout.freeBytes(),
        storageTotalBytes = RuntimeLayout.totalBytes(layout.runtimeRoot),
        runtimeState = providers.environmentState(),
        activeServerConnections = providers.activeConnections()
    )

    private fun memoryUsageMb(): Float? = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss / 1024f
    }.getOrNull()

    private fun memoryLimitMb(): Float? = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        info.totalMem / (1024f * 1024f)
    }.getOrNull()

    /** Spec 73 - terminal process count for the diagnostics screen. */
    fun terminalProcessCount(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        return runCatching {
            am.runningAppProcesses?.count { it.processName.contains("opencode", ignoreCase = true) } ?: 0
        }.getOrDefault(0)
    }

    /** Spec 42 - a short summary for the Advanced view. */
    fun describe(metrics: RuntimeMetrics): List<String> = buildList {
        add("App memory: ${metrics.memoryUsageMb?.let { "%.1f MB".format(it) } ?: "unavailable"}")
        add("Device memory: ${metrics.memoryLimitMb?.let { "%.0f MB".format(it) } ?: "unavailable"}")
        add("Storage: ${com.palash.opencodeserver.backend.core.Format.bytes(metrics.storageFreeBytes)} free")
        add("Terminal processes: ${metrics.terminalProcessCount}")
        add("Server: ${if (metrics.openCodeRunning) "running (pid ${metrics.openCodePid})" else "stopped"}")
        add("Environment: ${metrics.runtimeState.name}")
    }
}