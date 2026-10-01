package com.palash.opencodeserver.backend.server

import com.palash.opencodeserver.backend.api.ApiTimeouts
import com.palash.opencodeserver.backend.api.OpenCodeApiClient
import com.palash.opencodeserver.backend.core.ApiConnectionStatus
import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.OpenCodeConnection
import com.palash.opencodeserver.backend.core.ServerAdvancedDetails
import com.palash.opencodeserver.backend.core.ServerConfiguration
import com.palash.opencodeserver.backend.core.ServerInfo
import com.palash.opencodeserver.backend.core.ServerProcessState
import com.palash.opencodeserver.backend.core.ServerRegistration
import com.palash.opencodeserver.backend.core.StartPolicy
import com.palash.opencodeserver.backend.core.TestScenario
import com.palash.opencodeserver.backend.events.OpenCodeEventManager
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.runtime.EnvironmentBuilder
import com.palash.opencodeserver.backend.runtime.ProcessCategory
import com.palash.opencodeserver.backend.runtime.ProcessDescriptor
import com.palash.opencodeserver.backend.runtime.ProcessRegistry
import com.palash.opencodeserver.backend.runtime.ProcessRunner
import com.palash.opencodeserver.backend.runtime.RuntimeLayout
import com.palash.opencodeserver.backend.security.CredentialManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Spec 16/17/38/40/62/89/94/95/96 - the OpenCode server process manager.
 *
 * The start sequence is fixed by spec 17 and each stage is observable:
 *
 * ```
 *  1. validate runtime          7. capture stdout/stderr
 *  2. validate opencode         8. wait for readiness
 *  3. validate project dir      9. call /global/health
 *  4. select port              10. establish API connection
 *  5. load credentials         11. establish event stream
 *  6. start opencode           12. notify frontend
 * ```
 *
 * The state only becomes RUNNING at step 9, after a real health response. A started process alone
 * is never treated as success (spec 113).
 */
class OpenCodeServerManager(
    private val layout: RuntimeLayout,
    private val environment: EnvironmentBuilder,
    private val runner: ProcessRunner,
    private val registry: ProcessRegistry,
    private val credentials: CredentialManager,
    private val apiClient: OpenCodeApiClient,
    private val events: OpenCodeEventManager,
    private val log: LogManager,
    private val portSelector: PortSelector = PortSelector(log),
    private val timeouts: ApiTimeouts = ApiTimeouts()
) {

    private val _info = MutableStateFlow(ServerInfo.unknown())
    val info: StateFlow<ServerInfo> = _info.asStateFlow()

    @Volatile
    private var descriptor: ProcessDescriptor? = null

    @Volatile
    private var command: String = "opencode"

    @Volatile
    private var arguments: List<String> = emptyList()

    private val logTail = ArrayDeque<String>()

    @Volatile
    private var configuration = ServerConfiguration()

    @Volatile
    private var restartAttempts = 0

    @Volatile
    private var deliberateStop = true

    /** Set by the test-mode controller so failures can be injected without touching real plumbing. */
    @Volatile
    var scenario: TestScenario = TestScenario.NONE

    // ---- public API -------------------------------------------------------------------

    /**
     * Spec 17 - the ordered start sequence.
     *
     * Returns [BackendResult.Failure] with a stage-specific error rather than throwing, so the
     * wizard can offer the right remediation for the stage that actually failed.
     */
    suspend fun start(config: ServerConfiguration): BackendResult<ServerInfo> {
        configuration = config
        deliberateStop = false

        // Spec 96 - refuse to start a second instance.
        discoverRunningServer().onSuccess { existing ->
            if (existing.isRunning) {
                log.info(LogCategory.SERVER, "A healthy server is already running", "pid=${existing.pid}")
                return BackendResult.Success(existing)
            }
        }

        // Step 1: runtime.
        layout.ensureDirectories()?.let { return BackendResult.Failure(it) }

        // Step 2: OpenCode presence and version.
        val binary = locateOpenCode()
            ?: return BackendResult.Failure(
                BackendError.Categorized(
                    technical = "opencode not found on PATH (${environment.pathEntries().joinToString(":")})",
                    category = com.palash.opencodeserver.backend.core.ErrorCategory.RUNTIME,
                    retryable = false,
                    userTitle = "OpenCode is not installed.",
                    userMessage = "Run Repair OpenCode from Settings, then start the server again."
                )
            )

        // Step 3: project directory. Spec 30 - never run against an arbitrary directory.
        val workingDirectory = resolveWorkingDirectory(config)
            ?: return BackendResult.Failure(
                BackendError.Project(
                    projectName = "project",
                    technical = "No usable project directory for the server",
                    requiresSelection = true
                )
            )

        // Step 4: port.
        val selection = when (val picked = portSelector.select(config.preferredPort, strict = false)) {
            is BackendResult.Failure -> return picked
            is BackendResult.Success -> picked.value
        }

        // Step 5: credentials.
        credentials.serverPassword() // ensures one exists
        val serverUsername = credentials.serverUsername()

        // Step 6/7: start and capture output.
        _info.update {
            it.copy(state = ServerProcessState.STARTING, port = selection.port, host = bindHost(config))
        }

        val argv = buildServeArguments(config, selection.port)
        command = binary
        arguments = argv

        val started = runner.start(
            spec = ProcessRunner.CommandSpec(
                command = binary,
                arguments = argv,
                workingDirectory = workingDirectory.absolutePath,
                shell = false,
                category = ProcessCategory.OPENCODE_SERVER,
                // Spec 15 - the password reaches the child only through the secret channel.
                extraEnv = credentials.processSecrets(),
                onStdout = { chunk -> captureLog("out", chunk) },
                onStderr = { chunk -> captureLog("err", chunk) }
            ),
            onExit = { exitCode -> onProcessExit(exitCode) }
        ) ?: run {
            _info.update { it.copy(state = ServerProcessState.ERROR) }
            return BackendResult.Failure(
                BackendError.Runtime("Failed to spawn $binary ${argv.joinToString(" ")}", retryable = true)
            )
        }
        descriptor = started.first

        log.info(
            LogCategory.SERVER,
            "Starting OpenCode server",
            "cmd=$binary ${argv.joinToString(" ")} pid=${started.first.pid} cwd=${workingDirectory.absolutePath}"
        )

        if (selection.wasDefaulted) {
            log.info(LogCategory.SERVER, selection.reason, "port=${selection.port}")
        }

        // Spec 81 - injected slow startup, used to exercise the UI's progress states.
        if (scenario == TestScenario.SLOW_STARTUP) delay(3_000)

        // Step 8/9: readiness then health. This is the only path to RUNNING.
        val ready = waitUntilReady(selection.port, timeouts.startupMs)
        if (!ready) {
            val failure = lastStartFailure
                ?: BackendError.Timeout("opencode startup", timeouts.startupMs)
            stopInternal(recordReason = "Startup failed")
            return BackendResult.Failure(failure)
        }

        // Step 10: API connection proof.
        val health = apiClient.health()
        val version = health.valueOrNull()?.version
        val healthy = health.valueOrNull()?.healthy == true

        if (!healthy) {
            val failure = health.errorOrNull() ?: BackendError.Unknown(
                technical = "/global/health did not report healthy",
                userTitle = "The server started but is not healthy.",
                userMessage = "Open the Advanced details to see the server output."
            )
            stopInternal(recordReason = "Unhealthy after start")
            return BackendResult.Failure(failure)
        }

        log.info(
            LogCategory.API,
            "Health check successful",
            "endpoint=http://${bindHost(config)}:$selection.port/global/health version=$version"
        )

        val baseUrl = "http://${bindHost(config)}:$selection.port"
        publishConnection(baseUrl, serverUsername, version)

        // Step 11: event stream.
        events.onServerStarted(version)
        events.connect()

        // Step 12: publish the terminal state.
        val startedAt = System.currentTimeMillis()
        val registration = ServerRegistration(
            pid = started.first.pid,
            host = bindHost(config),
            port = selection.port,
            version = version,
            startedAt = startedAt,
            projectDirectory = workingDirectory.absolutePath
        )
        persistRegistration(registration)

        _info.update {
            it.copy(
                state = ServerProcessState.RUNNING,
                baseUrl = baseUrl,
                host = registration.host,
                port = selection.port,
                pid = registration.pid,
                version = version,
                healthy = true,
                startedAt = startedAt,
                projectDirectory = workingDirectory.absolutePath,
                endpointRecorded = true,
                lastError = null,
                restartAttempts = 0
            )
        }
        restartAttempts = 0

        log.info(
            LogCategory.SERVER,
            "OpenCode server is running",
            "endpoint=$baseUrl pid=${registration.pid} version=$version"
        )
        return BackendResult.Success(_info.value)
    }

    /**
     * Spec 89/62 - clean shutdown in order.
     *
     * Terminal sessions are untouched unless explicitly requested (spec 62).
     */
    suspend fun stop(closeTerminals: Boolean = false): BackendResult<ServerInfo> {
        log.info(LogCategory.SERVER, "Stopping OpenCode server", "closeTerminals=$closeTerminals")
        deliberateStop = true
        stopInternal(recordReason = "Stopped by user")
        return BackendResult.Success(_info.value)
    }

    /** Spec 16 `restart()`. */
    suspend fun restart(config: ServerConfiguration = configuration): BackendResult<ServerInfo> {
        log.info(LogCategory.SERVER, "Restarting OpenCode server")
        deliberateStop = true
        stopInternal(recordReason = "Restart")
        events.disconnect()
        // Give the OS a moment to release the port before rebinding.
        delay(400)
        deliberateStop = false
        return start(config)
    }

    /** Spec 16 `status()`. */
    fun status(): ServerProcessState = _info.value.state

    fun getEndpoint(): String? = _info.value.baseUrl

    fun getPid(): Int? = _info.value.pid

    fun getLogs(limit: Int = 200): List<String> = synchronized(logTail) { logTail.toList().takeLast(limit) }

    /** Spec 42 - the Advanced details block. */
    fun advancedDetails(): ServerAdvancedDetails {
        val snapshot = _info.value
        return ServerAdvancedDetails(
            command = command,
            arguments = arguments,
            pid = snapshot.pid,
            host = snapshot.host,
            port = snapshot.port,
            version = snapshot.version,
            health = when {
                snapshot.healthy && snapshot.isRunning -> "healthy"
                snapshot.state == ServerProcessState.STARTING -> "starting"
                else -> "unavailable"
            },
            environmentSummary = environment.redactedSummary(),
            logTail = getLogs(120)
        )
    }

    /**
     * Spec 8/95 - poll readiness, then prove health.
     *
     * Two signals are used deliberately: a listening socket says the bind succeeded, and
     * `/global/health` says the server is actually functional. Only the second sets RUNNING.
     */
    suspend fun waitUntilReady(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var listeningSeen = false

        while (System.currentTimeMillis() < deadline) {
            if (deliberateStop) return false
            descriptor?.let { proc ->
                if (!registry.isAlive(proc.id)) {
                    lastStartFailure = BackendError.Categorized(
                        technical = "opencode exited with code ${runCatching { registry.byId(proc.id)?.exitCode }.getOrNull()}",
                        category = com.palash.opencodeserver.backend.core.ErrorCategory.RUNTIME,
                        retryable = true,
                        userTitle = "OpenCode stopped during startup.",
                        userMessage = "The server process exited before it became ready. See the logs."
                    )
                    return false
                }
            }

            if (!listeningSeen && portSelector.isListening("127.0.0.1", port)) {
                listeningSeen = true
                log.info(LogCategory.SERVER, "Port is listening", "port=$port")
            }

            if (listeningSeen) {
                when (val probe = apiClient.health()) {
                    is BackendResult.Success -> {
                        if (probe.value.healthy == true) return true
                    }
                    is BackendResult.Failure -> {
                        // Retry only transient classes (spec 107).
                        if (!probe.error.category.isAutoRetryable) {
                            lastStartFailure = probe.error
                            return false
                        }
                    }
                }
            }
            delay(400)
        }

        lastStartFailure = if (listeningSeen) {
            BackendError.Timeout("opencode health check", timeoutMs)
        } else {
            BackendError.Categorized(
                technical = "opencode never bound 127.0.0.1:$port within ${timeoutMs}ms",
                category = com.palash.opencodeserver.backend.core.ErrorCategory.TIMEOUT,
                retryable = true,
                userTitle = "The server did not start listening.",
                userMessage = "OpenCode did not open a local port. Check the logs in Advanced details."
            )
        }
        return false
    }

    /**
     * Spec 38/95/96 - discovery.
     *
     * Verifies stored state against the live process and a real health response before claiming the
     * server is up. A stale registration is cleaned rather than trusted.
     */
    suspend fun discoverRunningServer(): BackendResult<ServerInfo> {
        val registration = readRegistration()
            ?: return BackendResult.Failure(
                BackendError.Unknown(
                    technical = "No stored server registration",
                    userTitle = "No server is recorded.",
                    userMessage = "Start the local server to begin."
                )
            )

        // Spec 39 - verify the process, do not trust the stored PID.
        val procAlive = descriptor?.let { registry.isAlive(it.id) } ?: registrationAlive(registration)
        if (!procAlive) {
            log.info(LogCategory.SERVER, "Stored server process is gone; clearing registration")
            clearRegistration()
            return BackendResult.Failure(
                BackendError.ServerUnavailable(
                    registration.host + ":" + registration.port,
                    "process is not alive"
                )
            )
        }

        if (!portSelector.isListening(registration.host, registration.port)) {
            clearRegistration()
            return BackendResult.Failure(
                BackendError.ServerUnavailable(
                    registration.host + ":" + registration.port,
                    "port is not listening"
                )
            )
        }

        // Adopt the discovered server into this manager so API calls can use it.
        publishConnection(
            baseUrl = "http://${registration.host}:${registration.port}",
            username = credentials.serverUsername(),
            version = registration.version
        )
        apiClient.health().onSuccess { probe ->
            if (probe.healthy == true) {
                _info.update {
                    registration.toInfo(ServerProcessState.RUNNING, healthy = true)
                }
                log.info(LogCategory.SERVER, "Reconnected to existing server", "pid=${registration.pid}")
                return BackendResult.Success(_info.value)
            }
        }

        clearRegistration()
        return BackendResult.Failure(
            BackendError.ServerUnavailable(
                registration.host + ":" + registration.port,
                "health check did not succeed"
            )
        )
    }

    /**
     * Spec 37 - apply the persisted start policy on app open.
     *
     * Discovery comes first in every non-NEVER case, so an existing server is reused rather than a
     * second one being started. Returns null when the policy says not to start anything.
     */
    suspend fun applyStartPolicy(hasProject: Boolean): BackendResult<ServerInfo>? = when (configuration.startPolicy) {
        StartPolicy.NEVER -> null

        // Spec 37 - this policy is project-scoped: with no project there is nothing to serve.
        StartPolicy.WHEN_APP_OPENS ->
            if (!hasProject) null else discoverThenStart()

        StartPolicy.KEEP_RUNNING -> discoverThenStart()
    }

    private suspend fun discoverThenStart(): BackendResult<ServerInfo> {
        val discovered = discoverRunningServer().valueOrNull()
        return if (discovered != null) {
            BackendResult.Success(discovered)
        } else {
            start(configuration)
        }
    }

    /** Spec 40 - decide whether to retry after a crash, within a hard attempt cap. */
    fun shouldAutoRestart(): Boolean =
        !deliberateStop && configuration.autoRestart && restartAttempts < configuration.maxAutoRestarts

    /** Spec 40 - record a failed start so the cap is enforced across attempts, not per call. */
    fun noteStartFailure() {
        restartAttempts++
        log.warn(
            LogCategory.SERVER,
            "Automatic restart attempt recorded",
            "attempt=$restartAttempts max=${configuration.maxAutoRestarts}"
        )
    }

    /** Spec 40 - after the cap, surface a dedicated error with diagnostics. */
    fun exhaustedRestartError(): BackendError = BackendError.Categorized(
        technical = "restart attempts=$restartAttempts lastExit=$lastExitCode",
        category = com.palash.opencodeserver.backend.core.ErrorCategory.RUNTIME,
        retryable = false,
        userTitle = "OpenCode failed to start repeatedly.",
        userMessage =
            "Automatic restarts stopped after ${configuration.maxAutoRestarts} attempts. " +
                "Review the diagnostics, then start the server manually."
    )

    fun currentConfiguration(): ServerConfiguration = configuration

    // ---- internals -----------------------------------------------------------------------

    private var lastStartFailure: BackendError? = null
    private var lastExitCode: Int? = null

    private fun locateOpenCode(): String? =
        layout.binDir.resolve("opencode").takeIf { it.canExecute() }?.absolutePath
            ?: runner.which("opencode")

    private fun resolveWorkingDirectory(config: ServerConfiguration): File? {
        val requested = config.requestedProjectDirectory
        if (requested != null) {
            val dir = layout.resolveUserPath(requested)
            if (dir.isDirectory) return dir
            log.warn(LogCategory.SERVER, "Configured project directory is unusable", requested)
        }
        // Spec 30 - fall back to the managed projects root, never to an arbitrary directory.
        return layout.projects.takeIf { it.isDirectory }
    }

    /**
     * Spec 14/50/51 - bind address and CORS.
     *
     * Loopback by default. LAN binding is an explicit opt-in that the UI gates behind a warning
     * (spec 50). CORS is only added when the caller supplied specific origins; a wildcard is never
     * sent (spec 51).
     */
    private fun bindHost(config: ServerConfiguration): String =
        if (config.bindToLan) "0.0.0.0" else "127.0.0.1"

    private fun buildServeArguments(config: ServerConfiguration, port: Int): List<String> = buildList {
        add("serve")
        add("--port")
        add(port.toString())
        add("--hostname")
        add(bindHost(config))
        // Spec 51 - only explicit origins, never "*".
        config.additionalCorsOrigins.forEach { origin ->
            add("--cors")
            add(origin)
        }
    }

    private fun publishConnection(baseUrl: String, username: String, version: String?) {
        connectionHolder = OpenCodeConnection(
            baseUrl = baseUrl,
            username = username,
            // Spec 49 - a reference, never the password.
            credentialReference = "keystore:OPENCODE_SERVER",
            serverVersion = version,
            status = ApiConnectionStatus.CONNECTING,
            lastConnectedAt = null
        )
    }

    @Volatile
    private var connectionHolder: OpenCodeConnection? = null

    fun connection(): OpenCodeConnection? = connectionHolder

    fun markConnected(version: String?) {
        connectionHolder = connectionHolder?.copy(
            status = ApiConnectionStatus.CONNECTED,
            serverVersion = version ?: connectionHolder?.serverVersion,
            lastConnectedAt = System.currentTimeMillis()
        )
    }

    fun markDisconnected() {
        connectionHolder = connectionHolder?.copy(status = ApiConnectionStatus.DISCONNECTED)
    }

    private fun captureLog(stream: String, chunk: String) {
        val lines = chunk.lineSequence()
        lines.forEach { raw ->
            val line = com.palash.opencodeserver.backend.log.SecretRedactor.redact(raw).trimEnd()
            if (line.isBlank()) return@forEach
            synchronized(logTail) {
                logTail.addLast("[$stream] $line")
                while (logTail.size > 400) logTail.removeFirst()
            }
        }
    }

    /**
     * Spec 40 - process termination handling.
     *
     * Runs on a watcher thread, so a crash is noticed even if nobody awaited the process.
     */
    private fun onProcessExit(exitCode: Int) {
        lastExitCode = exitCode
        val wasDeliberate = deliberateStop
        log.warn(
            LogCategory.SERVER,
            "OpenCode server process exited",
            "code=$exitCode deliberate=$wasDeliberate"
        )

        events.onServerStopped("Server process exited (code $exitCode)")

        if (wasDeliberate) {
            _info.update {
                it.copy(
                    state = ServerProcessState.STOPPED,
                    healthy = false,
                    pid = null
                )
            }
            clearRegistration()
            markDisconnected()
            return
        }

        // Spec 40 - unexpected termination. Capture stderr for diagnostics before anything else.
        val stderrTail = getLogs(40).filter { it.startsWith("[err]") }.takeLast(20)
        _info.update {
            it.copy(
                state = ServerProcessState.CRASHED,
                healthy = false,
                pid = null,
                lastError = BackendError.Categorized(
                    technical = "exit=$exitCode\n${stderrTail.joinToString("\n")}",
                    category = com.palash.opencodeserver.backend.core.ErrorCategory.RUNTIME,
                    retryable = true,
                    userTitle = "The OpenCode server stopped unexpectedly.",
                    userMessage = "Diagnostics were saved. Restart the server to continue."
                )
            )
        }
        log.error(LogCategory.SERVER, "Server crashed", "exit=$exitCode stderr=${stderrTail.size} lines")
    }

    /** Shared teardown used by stop, restart and failed start. */
    private fun stopInternal(recordReason: String) {
        descriptor?.let { proc ->
            registry.stopGracefully(proc.id)
            descriptor = null
        }
        registry.stopCategory(ProcessCategory.OPENCODE_SERVER)
        events.onServerStopped(recordReason)
        clearRegistration()
        markDisconnected()
        _info.update {
            it.copy(
                state = ServerProcessState.STOPPED,
                healthy = false,
                pid = null,
                endpointRecorded = false,
                lastError = null
            )
        }
    }

    private fun persistRegistration(registration: ServerRegistration) {
        runCatching {
            layout.serverRegistrationFile.writeText(
                buildString {
                    appendLine("pid=${registration.pid}")
                    appendLine("host=${registration.host}")
                    appendLine("port=${registration.port}")
                    appendLine("version=${registration.version.orEmpty()}")
                    appendLine("startedAt=${registration.startedAt}")
                    appendLine("projectDirectory=${registration.projectDirectory.orEmpty()}")
                },
                Charsets.UTF_8
            )
        }.onFailure { log.error(LogCategory.SERVER, "Failed to persist registration", null, it) }
    }

    private fun readRegistration(): ServerRegistration? = runCatching {
        val file = layout.serverRegistrationFile
        if (!file.exists()) return null
        val values = file.readLines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()
        val pid = values["pid"]?.toIntOrNull() ?: return null
        val port = values["port"]?.toIntOrNull() ?: return null
        ServerRegistration(
            pid = pid,
            host = values["host"] ?: "127.0.0.1",
            port = port,
            version = values["version"]?.takeIf { it.isNotBlank() },
            startedAt = values["startedAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
            projectDirectory = values["projectDirectory"]?.takeIf { it.isNotBlank() }
        )
    }.getOrNull()

    private fun clearRegistration() {
        runCatching { layout.serverRegistrationFile.delete() }
    }

    /**
     * Verify a stored PID without a handle.
     *
     * Used only for discovery after a process restart of the app. `kill(pid, 0)` returning success
     * means a process exists with that id; combined with the port and health checks below, that is
     * sufficient for adoption, and the endpoint is still proven by a real health response.
     */
    private fun registrationAlive(registration: ServerRegistration): Boolean = runCatching {
        android.system.Os.kill(registration.pid, 0)
        true
    }.getOrDefault(false)
}