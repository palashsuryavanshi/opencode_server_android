package com.palash.opencodeserver.backend.diagnostics

import android.content.Context
import android.os.Build
import com.palash.opencodeserver.backend.api.ApiVersionGate
import com.palash.opencodeserver.backend.api.OpenCodeApiClient
import com.palash.opencodeserver.backend.core.DiagnosticsReport
import com.palash.opencodeserver.backend.core.HealthCheckResult
import com.palash.opencodeserver.backend.core.ProviderHealth
import com.palash.opencodeserver.backend.core.ServerProcessState
import com.palash.opencodeserver.backend.core.TroubleshootingSuggestion
import com.palash.opencodeserver.backend.core.ConnectivityState
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.log.SecretRedactor
import com.palash.opencodeserver.backend.opencode.OpenCodeManager
import com.palash.opencodeserver.backend.runtime.EnvironmentManager
import com.palash.opencodeserver.backend.runtime.RuntimeLayout
import com.palash.opencodeserver.backend.server.OpenCodeServerManager

/**
 * Spec 59/110/116 - diagnostics, health checks and troubleshooting suggestions.
 *
 * The copyable report is redacted by construction: it is assembled from named fields and then passed
 * through [SecretRedactor], with the runtime home collapsed to `~` so personal paths do not leak into
 * a bug report (spec 59).
 */
class DiagnosticsService(
    private val context: Context,
    private val layout: RuntimeLayout,
    private val environment: EnvironmentManager,
    private val server: OpenCodeServerManager,
    private val openCode: OpenCodeManager,
    private val api: OpenCodeApiClient,
    private val versionGate: ApiVersionGate,
    private val log: LogManager
) {

    /** Spec 59 - the full report. */
    suspend fun buildReport(
        includeLogs: Boolean = true,
        terminalSessions: Int = 0,
        eventStreamConnected: Boolean = false,
        providerHealth: ProviderHealth = ProviderHealth.UNKNOWN,
        networkStatus: ConnectivityState = ConnectivityState.UNKNOWN
    ): DiagnosticsReport {
        val info = server.info.value
        val report = environment.report.value
        val compat = runCatching { versionGate.evaluate() }.getOrNull()

        return DiagnosticsReport(
            generatedAt = System.currentTimeMillis(),
            appVersion = appVersion(),
            appBuildType = BuildConfigType.detect(),
            androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            deviceAbi = Build.SUPPORTED_ABIS?.firstOrNull().orEmpty(),
            runtimeVersion = report?.userlandName,
            linuxEnvironment = report?.shell,
            shell = report?.shell,
            packageManager = report?.packageManager,
            nodeVersion = report?.nodeVersion,
            gitVersion = report?.gitVersion,
            openCodeVersion = report?.openCodeVersion,
            serverStatus = info.state,
            serverEndpoint = info.baseUrl,
            serverPort = info.port,
            apiHealth = info.healthy,
            storageFreeBytes = layout.freeBytes(),
            storageTotalBytes = RuntimeLayout.totalBytes(layout.runtimeRoot),
            networkStatus = networkStatus.name,
            providerHealth = providerHealth,
            eventStreamConnected = eventStreamConnected,
            terminalSessions = terminalSessions,
            recentLogs = if (includeLogs) log.snapshotLines(120) else emptyList(),
            checks = runHealthCheck(terminalSessions, eventStreamConnected, providerHealth)
        )
    }

    /**
     * Spec 59 - the string the Copy Diagnostics button puts on the clipboard.
     *
     * Secrets are stripped and the runtime home is replaced with `~`.
     */
    fun redactedText(report: DiagnosticsReport): String {
        val text = buildString {
            appendLine("=== Application ===")
            appendLine("App version: ${report.appVersion} (${report.appBuildType})")
            appendLine("Generated: ${report.generatedAt}")
            appendLine()
            appendLine("=== Device ===")
            appendLine("Android: ${report.androidVersion}")
            appendLine("Model: ${report.deviceModel}")
            appendLine("ABI: ${report.deviceAbi}")
            appendLine()
            appendLine("=== Runtime ===")
            appendLine("Runtime: ${report.runtimeVersion ?: "unknown"}")
            appendLine("Shell: ${report.shell ?: "unknown"}")
            appendLine("Package manager: ${report.packageManager ?: "unknown"}")
            appendLine("Storage free: ${com.palash.opencodeserver.backend.core.Format.bytes(report.storageFreeBytes)}")
            appendLine()
            appendLine("=== Tools ===")
            appendLine("Node: ${report.nodeVersion ?: "not installed"}")
            appendLine("Git: ${report.gitVersion ?: "not installed"}")
            appendLine("OpenCode: ${report.openCodeVersion ?: "not installed"}")
            appendLine()
            appendLine("=== Server ===")
            appendLine("State: ${report.serverStatus.name}")
            appendLine("Endpoint: ${report.serverEndpoint ?: "none"}")
            appendLine("Port: ${report.serverPort ?: "none"}")
            appendLine("API healthy: ${report.apiHealth}")
            appendLine("Event stream: ${if (report.eventStreamConnected) "connected" else "disconnected"}")
            appendLine("Terminal sessions: ${report.terminalSessions}")
            appendLine("Network: ${report.networkStatus}")
            appendLine("Provider: ${report.providerHealth.name}")
            if (report.recentLogs.isNotEmpty()) {
                appendLine()
                appendLine("=== Recent logs ===")
                report.recentLogs.forEach { appendLine(it) }
            }
            if (report.checks.isNotEmpty()) {
                appendLine()
                appendLine("=== Health checks ===")
                report.checks.forEach { appendLine("${if (it.passed) "[ok]" else "[!!]"} ${it.name}: ${it.detail}") }
            }
        }
        return SecretRedactor.redactPaths(SecretRedactor.redact(text), layout.home.absolutePath)
    }

    /**
     * Spec 110 - the one-tap health check.
     *
     * Every row states what was checked, and every failing row carries a remediation, because a
     * check that reports failure without a next step is not useful to the user.
     */
    suspend fun runHealthCheck(
        terminalSessions: Int = 0,
        eventStreamConnected: Boolean = false,
        providerHealth: ProviderHealth = ProviderHealth.UNKNOWN
    ): List<HealthCheckResult> {
        val report = environment.report.value ?: environment.evaluate(null)
        val info = server.info.value
        val checks = mutableListOf<HealthCheckResult>()

        checks += HealthCheckResult(
            name = "Runtime",
            passed = report.runtimeInstalled,
            detail = if (report.runtimeInstalled) {
                report.userlandName ?: "Linux environment available"
            } else {
                report.blockingReason ?: "Linux runtime is not installed"
            },
            remediation = if (report.runtimeInstalled) null else "Run Repair Linux runtime from Settings."
        )

        checks += HealthCheckResult(
            name = "Shell",
            passed = report.shell != null,
            detail = report.shell ?: "No shell found",
            remediation = if (report.shell != null) null else "Repair the runtime so a shell is available."
        )

        checks += HealthCheckResult(
            name = "Git",
            passed = report.gitVersion != null,
            detail = report.gitVersion ?: "Git is not installed",
            remediation = if (report.gitVersion != null) null else "Run Repair Git from Settings."
        )

        checks += HealthCheckResult(
            name = "Node",
            passed = report.nodeVersion != null,
            detail = report.nodeVersion ?: "Node.js is not installed",
            remediation = if (report.nodeVersion != null) null else "Run Repair Node.js from Settings."
        )

        checks += HealthCheckResult(
            name = "OpenCode",
            passed = report.openCodeVersion != null,
            detail = report.openCodeVersion ?: "OpenCode is not installed",
            remediation = if (report.openCodeVersion != null) null else "Run Repair OpenCode from Settings."
        )

        checks += HealthCheckResult(
            name = "Server",
            passed = info.state == ServerProcessState.RUNNING,
            detail = when (info.state) {
                ServerProcessState.RUNNING -> "Running on ${info.baseUrl}"
                ServerProcessState.CRASHED -> "Crashed. See the server logs."
                ServerProcessState.STARTING -> "Starting..."
                else -> info.state.name.lowercase().replaceFirstChar { it.uppercase() }
            },
            remediation = if (info.state == ServerProcessState.RUNNING) null else "Start the OpenCode server."
        )

        // Spec 113 - API connected means a request actually succeeded.
        val health = api.health()
        checks += HealthCheckResult(
            name = "API",
            passed = health.isSuccess && health.valueOrNull()?.healthy == true,
            detail = when {
                health.isSuccess -> "Healthy (version ${health.valueOrNull()?.version ?: "unknown"})"
                else -> health.errorOrNull()?.userTitle ?: "Unreachable"
            },
            remediation = if (health.isSuccess) null else "Check the server is running, then retry."
        )

        checks += HealthCheckResult(
            name = "Event stream",
            passed = eventStreamConnected,
            detail = if (eventStreamConnected) "Connected" else "Disconnected",
            remediation = if (eventStreamConnected) null else "Restart the server to restore live updates."
        )

        checks += HealthCheckResult(
            name = "Project",
            passed = report.projectDirectory != null,
            detail = report.projectDirectory ?: "No project selected",
            remediation = if (report.projectDirectory != null) null else "Create or select a project."
        )

        checks += HealthCheckResult(
            name = "Provider",
            passed = providerHealth == ProviderHealth.AUTHENTICATED,
            detail = when (providerHealth) {
                ProviderHealth.AUTHENTICATED -> "Authenticated"
                ProviderHealth.NOT_CONFIGURED -> "API key not configured."
                ProviderHealth.INVALID_CREDENTIALS -> "The stored API key was rejected."
                ProviderHealth.UNREACHABLE -> "Cannot reach provider."
                ProviderHealth.UNKNOWN -> "Not checked yet."
            },
            remediation = if (providerHealth == ProviderHealth.AUTHENTICATED) {
                null
            } else {
                "Add an API key in Providers."
            }
        )

        return checks
    }

    /** Spec 116 - automatic troubleshooting suggestions derived from the current state. */
    suspend fun troubleshootingSuggestions(
        terminalSessions: Int = 0,
        eventStreamConnected: Boolean = false,
        providerHealth: ProviderHealth = ProviderHealth.UNKNOWN,
        networkState: ConnectivityState = ConnectivityState.ONLINE
    ): List<TroubleshootingSuggestion> {
        val report = environment.report.value ?: environment.evaluate(null)
        val info = server.info.value
        val suggestions = mutableListOf<TroubleshootingSuggestion>()

        // The spec's worked example: "OpenCode won't start".
        if (info.state != ServerProcessState.RUNNING) {
            val architectureNote = if (!openCode.isArchitectureSupported()) {
                "Architecture is ${report.architecture.displayName}, which has no compatible OpenCode build."
            } else {
                "Architecture is ${report.architecture.displayName}."
            }
            suggestions += TroubleshootingSuggestion(
                title = "OpenCode won't start",
                cause = "The server is ${info.state.name.lowercase()}.",
                steps = buildList {
                    add("Architecture: $architectureNote")
                    add("Binary: ${report.openCodeVersion ?: "not installed"}")
                    add("Permissions: ${if (report.missingPermissions.isEmpty()) "all granted" else report.missingPermissions.joinToString()}")
                    add("PATH: ${report.packageManager ?: "unknown package manager"}")
                    add("Port: ${info.port ?: "not selected"}")
                    add("Configuration: ${layout.openCodeConfigDir.absolutePath}")
                    add("Logs: open Advanced details to read the server output.")
                    add("Suggested fix: ${suggestedFix(info.state, report.openCodeVersion)}")
                },
                severity = TroubleshootingSuggestion.Severity.CRITICAL
            )
        }

        if (report.openCodeVersion == null && openCode.isArchitectureSupported()) {
            suggestions += TroubleshootingSuggestion(
                title = "OpenCode is missing",
                cause = "The coding agent is not installed.",
                steps = listOf(
                    "Open Settings, then choose Repair OpenCode.",
                    "Make sure the device has an internet connection.",
                    "The install is verified after it completes; a successful message means the binary runs."
                ),
                severity = TroubleshootingSuggestion.Severity.WARNING
            )
        }

        if (report.gitVersion == null) {
            suggestions += TroubleshootingSuggestion(
                title = "Git is unavailable",
                cause = "Version control features will not work.",
                steps = listOf(
                    "Open Settings, then choose Repair Git.",
                    "Git is optional: the local server and terminal work without it."
                ),
                severity = TroubleshootingSuggestion.Severity.INFO
            )
        }

        if (providerHealth == ProviderHealth.NOT_CONFIGURED) {
            suggestions += TroubleshootingSuggestion(
                title = "No AI provider connected",
                cause = "OpenCode has no credentials, so prompts cannot be sent.",
                steps = listOf(
                    "Open Providers.",
                    "Choose a provider and enter its API key.",
                    "The key is stored in the Android keystore, not in plain text."
                ),
                severity = TroubleshootingSuggestion.Severity.WARNING
            )
        }

        if (networkState == ConnectivityState.OFFLINE && info.state == ServerProcessState.RUNNING) {
            suggestions += TroubleshootingSuggestion(
                title = "Working offline",
                cause = "The device has no internet connection.",
                steps = listOf(
                    "The terminal, files and Git all keep working.",
                    "Prompts need an internet connection to reach the provider.",
                    "The local server does not need a network connection."
                ),
                severity = TroubleshootingSuggestion.Severity.INFO
            )
        }

        if (!eventStreamConnected && info.state == ServerProcessState.RUNNING) {
            suggestions += TroubleshootingSuggestion(
                title = "Live updates are not arriving",
                cause = "The server-sent event stream is disconnected.",
                steps = listOf(
                    "The app reconnects automatically with a 1s, 2s, 5s backoff.",
                    "If it does not recover, restart the server."
                ),
                severity = TroubleshootingSuggestion.Severity.INFO
            )
        }

        if (terminalSessions > 8) {
            suggestions += TroubleshootingSuggestion(
                title = "Many terminal sessions are open",
                cause = "$terminalSessions terminal sessions are running.",
                steps = listOf(
                    "Close unused sessions to free memory.",
                    "Each session keeps its own scrollback buffer."
                ),
                severity = TroubleshootingSuggestion.Severity.INFO
            )
        }

        return suggestions
    }

    private fun suggestedFix(state: ServerProcessState, version: String?): String = when {
        state == ServerProcessState.CRASHED ->
            "The server crashed. Check the logs, then use Restart Server."
        version == null ->
            "Install OpenCode first: Settings, then Repair OpenCode."
        state == ServerProcessState.STARTING ->
            "It is still starting. Wait for the health check to complete."
        else ->
            "Choose Start Server, then check the log for the first error."
    }

    private fun appVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")
}

/** Reads the build type from the generated BuildConfig without hard-coding a dependency on it. */
internal object BuildConfigType {
    fun detect(): String = runCatching {
        val clazz = Class.forName("${BuildConfigPackage}.BuildConfig")
        clazz.getField("DEBUG").getBoolean(null).let { debug ->
            if (debug) "debug" else "release"
        }
    }.getOrDefault("unknown")

    private val BuildConfigPackage: String
        get() = runCatching {
            val clazz = Class.forName("com.palash.opencodeserver.BuildConfig")
            clazz.getPackage()?.name ?: "com.palash.opencodeserver"
        }.getOrDefault("com.palash.opencodeserver")
}