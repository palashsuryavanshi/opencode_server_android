package com.palash.opencodeserver.backend.core

import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogLevel

/** Shared value types crossing the backend -> frontend boundary. */

/** Spec 12 - what the device and runtime actually are, before anything is installed. */
data class DeviceArchitecture(
    val androidAbi: String,
    val supportedAbis: List<String>,
    val is64Bit: Boolean,
    val runtimeAbi: String,
    val libcKind: LibcKind,
    val notes: List<String> = emptyList()
) {
    enum class LibcKind { BIONIC, GLIBC, MUSL, UNKNOWN }

    /** Human label used in diagnostics and compatibility errors (spec 12, spec 43). */
    val displayName: String
        get() = when {
            androidAbi.startsWith("arm64") || androidAbi.startsWith("aarch64") -> "ARM64"
            androidAbi.startsWith("armeabi") -> "ARM32"
            androidAbi.startsWith("x86_64") -> "x86_64"
            androidAbi.startsWith("x86") -> "x86"
            else -> androidAbi
        }

    val isSupportedTarget: Boolean
        get() = displayName in setOf("ARM64", "ARM32", "x86_64", "x86")
}

/** Spec 5 - the result of a full environment probe. */
data class EnvironmentReport(
    val state: EnvironmentState,
    val architecture: DeviceArchitecture,
    val runtimeInstalled: Boolean,
    val userlandName: String?,
    val shell: String?,
    val packageManager: String?,
    val nodeVersion: String?,
    val gitVersion: String?,
    val openCodeVersion: String?,
    val projectDirectory: String?,
    val storageFreeBytes: Long,
    val missingPermissions: List<String>,
    val blockingReason: String? = null,
    val evaluatedAt: Long = System.currentTimeMillis()
) {
    val isUsable: Boolean get() = state == EnvironmentState.READY || state == EnvironmentState.DEGRADED

    /** Spec 41/42 - the simple one-liner shown in normal UI. */
    fun summaryLine(): String = when (state) {
        EnvironmentState.READY -> "Development environment is ready."
        EnvironmentState.DEGRADED -> "Environment is ready with reduced features."
        EnvironmentState.NOT_INITIALIZED -> "Setup has not been completed yet."
        EnvironmentState.INSTALLING -> "Setting up your development environment."
        EnvironmentState.CHECKING -> "Checking your device."
        EnvironmentState.ERROR -> blockingReason ?: "The environment reported an error."
        EnvironmentState.UNKNOWN -> "Environment status unknown."
    }
}

/** Spec 7 - declarative description of one managed dependency. */
data class DependencyReport(
    val id: String,
    val displayName: String,
    val state: DependencyState,
    val installedVersion: String?,
    val availableVersion: String?,
    val minimumVersion: String?,
    val installMethod: String,
    val message: String,
    val technicalDetail: String? = null,
    val requiredForServer: Boolean = true
)

/** Spec 6/60 - a single human-readable line of setup progress. */
data class SetupProgress(
    val step: SetupStep,
    val stepIndex: Int,
    val stepCount: Int,
    val headline: String,
    val detail: String,
    val percent: Int,
    val componentStates: Map<String, DependencyState> = emptyMap(),
    val advancedCommands: List<String> = emptyList(),
    val checkpoint: SetupCheckpoint,
    val error: BackendError? = null,
    val isTerminal: Boolean = false
) {
    val fraction: Float get() = if (stepCount <= 0) 0f else (stepIndex.toFloat() / stepCount).coerceIn(0f, 1f)

    /** Spec 6 - the short form, e.g. "Installing OpenCode..." */
    fun simple(): String = detail
}

/** Spec 16/48 - authoritative description of the running server. */
data class ServerInfo(
    val state: ServerProcessState,
    val baseUrl: String?,
    val host: String,
    val port: Int?,
    val pid: Int?,
    val version: String?,
    val healthy: Boolean,
    val startedAt: Long?,
    val uptimeSeconds: Long,
    val projectDirectory: String?,
    val endpointRecorded: Boolean,
    val lastError: BackendError? = null,
    val restartAttempts: Int = 0
) {
    val isRunning: Boolean get() = state == ServerProcessState.RUNNING && healthy

    companion object {
        fun unknown() = ServerInfo(
            state = ServerProcessState.UNKNOWN,
            baseUrl = null,
            host = "127.0.0.1",
            port = null,
            pid = null,
            version = null,
            healthy = false,
            startedAt = null,
            uptimeSeconds = 0,
            projectDirectory = null,
            endpointRecorded = false
        )
    }
}

/** Spec 42 - the "Advanced" block of server details. */
data class ServerAdvancedDetails(
    val command: String,
    val arguments: List<String>,
    val pid: Int?,
    val host: String,
    val port: Int?,
    val version: String?,
    val health: String,
    val environmentSummary: Map<String, String>,
    val logTail: List<String>
)

/** Spec 96 - registration of the single active server instance. */
data class ServerRegistration(
    val pid: Int,
    val host: String,
    val port: Int,
    val version: String?,
    val startedAt: Long,
    val projectDirectory: String?
) {
    fun toInfo(state: ServerProcessState, healthy: Boolean) = ServerInfo(
        state = state,
        baseUrl = "http://$host:$port",
        host = host,
        port = port,
        pid = pid,
        version = version,
        healthy = healthy,
        startedAt = startedAt,
        uptimeSeconds = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0),
        projectDirectory = projectDirectory,
        endpointRecorded = true
    )
}

/** Spec 49 - the single connection object handed to the frontend. */
data class OpenCodeConnection(
    val baseUrl: String,
    val username: String,
    val credentialReference: String,
    val serverVersion: String?,
    val status: ApiConnectionStatus,
    val lastConnectedAt: Long?,
    val apiVersionSupported: Boolean = true
) {
    /**
     * Spec 49 - the password is deliberately absent. The transport layer resolves
     * [credentialReference] through the credential vault at request time.
     */
    val hasCredentials: Boolean get() = credentialReference.isNotBlank()
}

/** Spec 73 - lightweight resource snapshot. */
data class RuntimeMetrics(
    val openCodeRunning: Boolean,
    val openCodePid: Int?,
    val memoryUsageMb: Float?,
    val memoryLimitMb: Float?,
    val cpuUsagePercent: Float?,
    val terminalProcessCount: Int,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
    val runtimeState: EnvironmentState,
    val activeServerConnections: Int,
    val sampledAt: Long = System.currentTimeMillis()
)

/** Spec 72 - real git state, read from git inside the project environment. */
data class GitStatus(
    val isRepository: Boolean,
    val branch: String?,
    val ahead: Int,
    val behind: Int,
    val modified: List<String>,
    val staged: List<String>,
    val untracked: List<String>,
    val conflicted: List<String>,
    val rawPorcelain: String? = null
) {
    val hasChanges: Boolean
        get() = modified.isNotEmpty() || staged.isNotEmpty() || untracked.isNotEmpty() || conflicted.isNotEmpty()

    companion object {
        fun notARepository() = GitStatus(false, null, 0, 0, emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** Spec 71 - project file change notification. */
data class ProjectFileChange(
    val path: String,
    val changeType: FileChangeType
) {
    enum class FileChangeType { CREATED, MODIFIED, DELETED, DIRECTORY_CHANGED }
}

/** Spec 104 - large file guard result. */
data class FileContentResult(
    val path: String,
    val content: String,
    val sizeBytes: Long,
    val isTruncated: Boolean,
    val isBinary: Boolean,
    val requiresConfirmation: Boolean
)

/** Spec 82 - input validation performed before any filesystem or shell access. */
data class PathValidation(
    val isValid: Boolean,
    val normalized: String?,
    val reason: String? = null,
    val escapedSandbox: Boolean = false
)

/** Spec 59 - redacted, copyable diagnostics payload. */
data class DiagnosticsReport(
    val generatedAt: Long,
    val appVersion: String,
    val appBuildType: String,
    val androidVersion: String,
    val deviceModel: String,
    val deviceAbi: String,
    val runtimeVersion: String?,
    val linuxEnvironment: String?,
    val shell: String?,
    val packageManager: String?,
    val nodeVersion: String?,
    val gitVersion: String?,
    val openCodeVersion: String?,
    val serverStatus: ServerProcessState,
    val serverEndpoint: String?,
    val serverPort: Int?,
    val apiHealth: Boolean,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
    val networkStatus: String,
    val providerHealth: ProviderHealth,
    val eventStreamConnected: Boolean,
    val terminalSessions: Int,
    val recentLogs: List<String>,
    val checks: List<HealthCheckResult>,
    val redactionApplied: Boolean = true
)

/** Spec 110 - one row of the one-tap health check. */
data class HealthCheckResult(
    val name: String,
    val passed: Boolean,
    val detail: String,
    val remediation: String? = null
)

/** Spec 116 - an actionable suggestion. */
data class TroubleshootingSuggestion(
    val title: String,
    val cause: String,
    val steps: List<String>,
    val severity: Severity
) {
    enum class Severity { INFO, WARNING, CRITICAL }
}

/** Spec 46 - update availability. */
data class UpdateAvailability(
    val component: String,
    val installedVersion: String?,
    val availableVersion: String?,
    val updateAvailable: Boolean,
    val autoUpdateEnabled: Boolean,
    val notes: String?
)

/** Spec 73/41 - a structured log entry safe to render. */
data class BackendLogEntry(
    val id: String,
    val timestamp: Long,
    val level: LogLevel,
    val category: LogCategory,
    val message: String,
    val detail: String? = null
)

/** Spec 87 - permission the UI should request before certain operations. */
data class RuntimePermissionRequest(
    val permission: String,
    val rationale: String,
    val blockingSetup: Boolean
)

/** Spec 117 - selective repair operations. */
enum class RepairAction(val label: String) {
    REPAIR_OPENCODE("Repair OpenCode"),
    REPAIR_NODE("Repair Node.js"),
    REPAIR_GIT("Repair Git"),
    REPAIR_RUNTIME("Repair Linux runtime"),
    RESET_OPENCODE_CONFIG("Reset OpenCode configuration"),
    REINSTALL_DEPENDENCIES("Reinstall dependencies"),
    RESET_ALL("Reset development environment")
}

/** Spec 44/74 - a planned download. */
data class DownloadPlan(
    val id: String,
    val url: String,
    val destinationPath: String,
    val expectedBytes: Long?,
    val requiredFor: String,
    val resumeSupported: Boolean = true
)

/** Spec 110 - network layers reported separately (spec 76/77). */
data class ConnectivityReport(
    val connectivity: ConnectivityState,
    val localServerRunning: Boolean,
    val terminalAvailable: Boolean,
    val providerHealth: ProviderHealth,
    val detail: String
)

/** Shared helpers for byte/size/time rendering. */
object Format {
    fun bytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "%.1f KB".format(value / 1024.0)
        value < 1024L * 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
        else -> "%.2f GB".format(value / (1024.0 * 1024 * 1024))
    }

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    fun uptime(since: Long?): Long =
        since?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) / 1000 } ?: 0
}