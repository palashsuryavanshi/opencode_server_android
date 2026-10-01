package com.palash.opencodeserver.backend

import com.palash.opencodeserver.backend.core.AppState
import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.BackendSettings
import com.palash.opencodeserver.backend.core.BackendStatus
import com.palash.opencodeserver.backend.core.ConnectivityReport
import com.palash.opencodeserver.backend.core.ConnectivityState
import com.palash.opencodeserver.backend.core.CreateProjectRequest
import com.palash.opencodeserver.backend.core.DiagnosticsReport
import com.palash.opencodeserver.backend.core.EnvironmentState
import com.palash.opencodeserver.backend.core.FileContentResult
import com.palash.opencodeserver.backend.core.HealthCheckResult
import com.palash.opencodeserver.backend.core.LocalCodingBackend
import com.palash.opencodeserver.backend.core.ModelSelection
import com.palash.opencodeserver.backend.core.OpenCodeConnection
import com.palash.opencodeserver.backend.core.ProjectFileChange
import com.palash.opencodeserver.backend.core.ProjectFileNode
import com.palash.opencodeserver.backend.core.ProjectRecord
import com.palash.opencodeserver.backend.core.ProviderDescriptor
import com.palash.opencodeserver.backend.core.ProviderHealth
import com.palash.opencodeserver.backend.project.PathGuard
import com.palash.opencodeserver.backend.core.RepairAction
import com.palash.opencodeserver.backend.core.RuntimeMetrics
import com.palash.opencodeserver.backend.core.ServerConfiguration
import com.palash.opencodeserver.backend.core.ServerInfo
import com.palash.opencodeserver.backend.core.ServerProcessState
import com.palash.opencodeserver.backend.core.SessionActivityStatus
import com.palash.opencodeserver.backend.core.SessionMessage
import com.palash.opencodeserver.backend.core.SessionRecord
import com.palash.opencodeserver.backend.core.SetupCheckpoint
import com.palash.opencodeserver.backend.core.SetupProgress
import com.palash.opencodeserver.backend.core.SetupStep
import com.palash.opencodeserver.backend.core.StartPolicy
import com.palash.opencodeserver.backend.core.TroubleshootingSuggestion
import com.palash.opencodeserver.backend.core.UpdateAvailability
import com.palash.opencodeserver.backend.events.BackendEvent
import com.palash.opencodeserver.backend.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Spec 80/114 - the development backend.
 *
 * This exists so the frontend can be built and reviewed without a provisioned Linux runtime, an
 * installed OpenCode, or a device that can host one. It implements the same [LocalCodingBackend]
 * contract, so a screen cannot accidentally depend on behaviour that only the mock provides.
 *
 * What is simulated: setup progress, server startup, health, sessions, streaming responses,
 * terminal output, Git state, events.
 *
 * What is *not* faked: the contract. Every method returns the same types, and every failure returns a
 * real [BackendError] with the same user-facing wording, so the UI states tested here are the states
 * users will see.
 */
class MockBackend(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : LocalCodingBackend {

    private val _state = MutableStateFlow(AppState.INITIALIZING)
    override fun observeState(): StateFlow<AppState> = _state.asStateFlow()

    private val _server = MutableStateFlow(ServerInfo.unknown())
    override fun observeServerState(): StateFlow<ServerInfo> = _server.asStateFlow()

    private val _connection = MutableStateFlow<OpenCodeConnection?>(null)
    override fun observeConnection(): StateFlow<OpenCodeConnection?> = _connection.asStateFlow()

    private val _projects = MutableStateFlow<List<ProjectRecord>>(emptyList())
    override fun observeProjects(): StateFlow<List<ProjectRecord>> = _projects.asStateFlow()

    private val _terminals = MutableStateFlow<List<TerminalSession>>(emptyList())
    override fun observeTerminalSessions(): StateFlow<List<TerminalSession>> = _terminals.asStateFlow()

    private val _settings = MutableStateFlow(BackendSettings(useMockBackend = true))
    override fun observeSettings(): StateFlow<BackendSettings> = _settings.asStateFlow()

    private val _events = MutableSharedFlow<BackendEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override fun subscribeToEvents(): Flow<BackendEvent> = _events

    /**
     * Spec 6 - wizard progress, as a state flow so a late subscriber still sees the current step.
     *
     * A state flow is used rather than a shared flow because the wizard always wants the latest
     * value, and never wants a replay of every step it has already passed.
     */
    private val _setupProgress = MutableStateFlow<SetupProgress?>(null)
    override fun observeSetupProgress(): StateFlow<SetupProgress?> = _setupProgress.asStateFlow()

    private var checkpoint = SetupCheckpoint.STARTED
    private val sessions = mutableListOf<SessionRecord>()
    private val messages = mutableMapOf<String, MutableList<SessionMessage>>()
    private var nextPort = 4096

    init {
        seedProjects()
        // The initial state is set synchronously: spec 113 requires the backend to report
        // SETUP_REQUIRED immediately, not one dispatch later, or a caller could read INITIALIZING and
        // conclude nothing had been checked yet.
        _state.value = AppState.SETUP_REQUIRED
        scope.launch {
            _events.emit(BackendEvent.Notice("Mock backend active. No runtime required.", isError = false))
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    override suspend fun getStatus(): BackendStatus = BackendStatus(
        appState = _state.value,
        environment = mockEnvironment(),
        server = _server.value,
        connection = _connection.value,
        currentProject = _projects.value.firstOrNull { it.isActive },
        sessionCount = sessions.size,
        terminalCount = _terminals.value.size,
        providerHealth = ProviderHealth.AUTHENTICATED,
        connectivity = ConnectivityState.ONLINE,
        metrics = null,
        pendingSetupCheckpoint = checkpoint
    )

    override suspend fun setup(force: Boolean): SetupProgress {
        var latest: SetupProgress? = null
        runMockSetup().collect { latest = it }
        return latest ?: setupProgress(SetupStep.DONE, "Setup complete", 100, isTerminal = true)
    }

    /** Spec 6/114 - the wizard's ten-line experience, produced without touching the device. */
    private fun runMockSetup() = kotlinx.coroutines.flow.flow {
        val steps = listOf(
            SetupStep.CHECKING_DEVICE to "Checking device",
            SetupStep.RUNTIME to "Terminal runtime installed",
            SetupStep.PACKAGE_MANAGER to "Package manager ready",
            SetupStep.CORE_TOOLS to "Core utilities installed",
            SetupStep.GIT to "Git installed",
            SetupStep.NODE to "Node.js installed",
            SetupStep.OPENCODE to "OpenCode installed",
            SetupStep.CONFIG to "OpenCode configuration created",
            SetupStep.SERVER to "Local server ready"
        )

        steps.forEachIndexed { index, (step, detail) ->
            checkpoint = when (step) {
                SetupStep.RUNTIME -> SetupCheckpoint.RUNTIME_READY
                SetupStep.PACKAGE_MANAGER -> SetupCheckpoint.PACKAGE_MANAGER_READY
                SetupStep.GIT -> SetupCheckpoint.GIT_READY
                SetupStep.NODE -> SetupCheckpoint.NODE_READY
                SetupStep.OPENCODE -> SetupCheckpoint.OPENCODE_READY
                SetupStep.CONFIG -> SetupCheckpoint.CONFIG_READY
                else -> checkpoint
            }
            val progress = setupProgress(step, detail, ((index + 1) * 100) / steps.size)
            _setupProgress.value = progress
            emit(progress)
            delay(120)
        }

        checkpoint = SetupCheckpoint.COMPLETE
        val ready = SetupProgress(
            step = SetupStep.DONE,
            stepIndex = 10,
            stepCount = 10,
            headline = "Setup complete",
            detail = "Your local coding environment is ready.",
            percent = 100,
            advancedCommands = listOf("mock: no commands are executed"),
            checkpoint = SetupCheckpoint.COMPLETE,
            isTerminal = true
        )
        _setupProgress.value = ready
        _state.value = AppState.READY
        emit(ready)
    }

    private fun setupProgress(
        step: SetupStep,
        detail: String,
        percent: Int,
        isTerminal: Boolean = false
    ) = SetupProgress(
        step = step,
        stepIndex = percent / 10,
        stepCount = 10,
        headline = step.title,
        detail = detail,
        percent = percent,
        advancedCommands = emptyList(),
        checkpoint = checkpoint,
        isTerminal = isTerminal
    )

    override suspend fun resetEnvironment(confirm: Boolean): BackendResult<Unit> =
        if (!confirm) {
            BackendResult.Failure(
                BackendError.Unknown(
                    technical = "mock reset refused",
                    userTitle = "Confirmation required.",
                    userMessage = "Resetting the environment removes installed tools."
                )
            )
        } else {
            checkpoint = SetupCheckpoint.STARTED
            _state.value = AppState.SETUP_REQUIRED
            _server.value = ServerInfo.unknown()
            _connection.value = null
            BackendResult.Success(Unit)
        }

    override suspend fun repair(action: RepairAction): SetupProgress {
        val detail = when (action) {
            RepairAction.RESET_OPENCODE_CONFIG -> "OpenCode configuration was reset."
            RepairAction.RESET_ALL -> "The development environment was reset."
            else -> "${action.label} completed."
        }
        return setupProgress(SetupStep.DONE, detail, 100, isTerminal = true)
    }

    // ---- server ------------------------------------------------------------------------------

    override suspend fun startServer(): BackendResult<ServerInfo> {
        _state.value = AppState.SERVER_STARTING
        _server.value = ServerInfo.unknown().copy(state = ServerProcessState.STARTING)
        _events.tryEmit(BackendEvent.Notice("Preparing environment...", isError = false))
        delay(300)

        // Spec 13 - simulate port selection rather than assuming the default is free.
        nextPort += 1
        val startedAt = System.currentTimeMillis()
        val info = ServerInfo(
            state = ServerProcessState.RUNNING,
            baseUrl = "http://127.0.0.1:$nextPort",
            host = "127.0.0.1",
            port = nextPort,
            pid = 18_000 + nextPort % 1_000,
            version = "mock-1.0.0",
            healthy = true,
            startedAt = startedAt,
            uptimeSeconds = 0,
            projectDirectory = _projects.value.firstOrNull { it.isActive }?.path,
            endpointRecorded = true
        )
        _server.value = info
        _state.value = AppState.API_CONNECTING

        delay(200)
        _connection.value = OpenCodeConnection(
            baseUrl = info.baseUrl!!,
            username = "opencode",
            credentialReference = "mock",
            serverVersion = info.version,
            status = com.palash.opencodeserver.backend.core.ApiConnectionStatus.CONNECTED,
            lastConnectedAt = System.currentTimeMillis()
        )
        _state.value = AppState.API_CONNECTED
        _events.tryEmit(BackendEvent.EventStreamConnected(info.version))
        _events.tryEmit(BackendEvent.Notice("Server running.", isError = false))
        return BackendResult.Success(info)
    }

    override suspend fun stopServer(): BackendResult<ServerInfo> {
        _state.value = AppState.SERVER_STOPPING
        delay(150)
        _server.value = _server.value.copy(
            state = ServerProcessState.STOPPED,
            healthy = false,
            pid = null,
            baseUrl = null
        )
        _connection.value = _connection.value?.copy(
            status = com.palash.opencodeserver.backend.core.ApiConnectionStatus.DISCONNECTED
        )
        _state.value = AppState.SERVER_STOPPED
        return BackendResult.Success(_server.value)
    }

    override suspend fun restartServer(): BackendResult<ServerInfo> {
        stopServer()
        delay(150)
        return startServer()
    }

    override suspend fun getServerStatus(): ServerInfo = _server.value

    override suspend fun getServerAdvancedDetails() = com.palash.opencodeserver.backend.core.ServerAdvancedDetails(
        command = "opencode",
        arguments = listOf("serve", "--port", (_server.value.port ?: 4096).toString(), "--hostname", "127.0.0.1"),
        pid = _server.value.pid,
        host = _server.value.host,
        port = _server.value.port,
        version = _server.value.version,
        health = if (_server.value.healthy) "healthy" else "unavailable",
        environmentSummary = mapOf("HOME" to "~/", "PATH" to "~/bin:/usr/bin"),
        logTail = listOf("[out] mock server log line")
    )

    override suspend fun configureServer(configuration: ServerConfiguration): BackendResult<ServerInfo> {
        _settings.value = _settings.value.copy(
            preferredPort = configuration.preferredPort,
            bindToLan = configuration.bindToLan,
            startPolicy = configuration.startPolicy
        )
        return BackendResult.Success(_server.value)
    }

    override suspend fun discoverRunningServer(): BackendResult<ServerInfo> =
        if (_server.value.isRunning) BackendResult.Success(_server.value)
        else BackendResult.Failure(
            BackendError.ServerUnavailable("127.0.0.1:${_server.value.port ?: 4096}", "no mock server running")
        )

    // ---- projects -------------------------------------------------------------------------------

    override suspend fun getProjects(): List<ProjectRecord> = _projects.value

    override suspend fun getCurrentProject(): ProjectRecord? = _projects.value.firstOrNull { it.isActive }

    override suspend fun selectProject(projectId: String): BackendResult<ProjectRecord> {
        val target = _projects.value.firstOrNull { it.id == projectId }
            ?: return BackendResult.Failure(
                BackendError.Project(projectId, "No project with id $projectId")
            )
        _projects.value = _projects.value.map { it.copy(isActive = it.id == projectId) }
        return BackendResult.Success(target)
    }

    override suspend fun createProject(request: CreateProjectRequest): BackendResult<ProjectRecord> {
        val record = ProjectRecord(
            id = "proj-${System.currentTimeMillis()}",
            name = request.name,
            path = request.externalPath ?: "~/projects/${request.name}",
            displayPath = request.externalPath ?: "~/projects/${request.name}",
            isManaged = request.externalPath == null,
            isActive = true,
            createdAt = System.currentTimeMillis(),
            lastOpenedAt = null,
            languageHint = null
        )
        _projects.value = _projects.value.map { it.copy(isActive = false) } + record
        return BackendResult.Success(record)
    }

    override suspend fun renameProject(projectId: String, newName: String): BackendResult<ProjectRecord> {
        val target = _projects.value.firstOrNull { it.id == projectId }
            ?: return BackendResult.Failure(BackendError.Project(projectId, "No project with id $projectId"))
        val renamed = target.copy(name = newName)
        _projects.value = _projects.value.map { if (it.id == projectId) renamed else it }
        return BackendResult.Success(renamed)
    }

    override suspend fun removeProject(projectId: String): BackendResult<Unit> {
        _projects.value = _projects.value.filterNot { it.id == projectId }
        return BackendResult.Success(Unit)
    }

    override suspend fun deleteProjectFiles(projectId: String, confirm: Boolean): BackendResult<Unit> =
        if (!confirm) {
            BackendResult.Failure(
                BackendError.Unknown(
                    technical = "mock delete refused",
                    userTitle = "Confirmation required.",
                    userMessage = "Deleting project files cannot be undone."
                )
            )
        } else {
            removeProject(projectId)
        }

    // ---- files ---------------------------------------------------------------------------------

    override suspend fun getFiles(relativePath: String, depth: Int): List<ProjectFileNode> {
        if (getCurrentProject() == null) return emptyList()
        val traversal = PathGuard.rejectTraversal(relativePath)
        if (!traversal.isValid) return emptyList()
        return mockFileTree()
    }

    override suspend fun readFile(relativePath: String, allowLarge: Boolean): BackendResult<FileContentResult> {
        // Spec 30 - the same project requirement production enforces.
        if (getCurrentProject() == null) return BackendResult.Failure(projectRequiredError())
        // Spec 104 - the large-file guard.
        if (!allowLarge && relativePath.endsWith(".bin")) {
            return BackendResult.Failure(BackendError.LargeFile(relativePath, 18L * 1024 * 1024, 2L * 1024 * 1024))
        }
        val traversal = PathGuard.rejectTraversal(relativePath)
        if (!traversal.isValid) {
            return BackendResult.Failure(BackendError.InvalidPath(relativePath, traversal.reason!!))
        }
        return BackendResult.Failure(
            BackendError.InvalidPath(relativePath, "mock backend has no file contents")
        )
    }

    override suspend fun writeFile(relativePath: String, content: String): BackendResult<Unit> {
        // Spec 83 - the mock enforces the same path rules through the same guard, so a screen cannot
        // be built against a laxer contract than production has.
        val traversal = PathGuard.rejectTraversal(relativePath)
        if (!traversal.isValid) {
            return BackendResult.Failure(BackendError.InvalidPath(relativePath, traversal.reason!!))
        }
        if (getCurrentProject() == null) return BackendResult.Failure(projectRequiredError())
        return BackendResult.Success(Unit)
    }

    /** Spec 30 - no project, so no operation that needs one may proceed. */
    private fun projectRequiredError() = BackendError.Project(
        projectName = "project",
        technical = "No active project is selected",
        requiresSelection = true
    )

    override fun observeFileChanges(): Flow<ProjectFileChange> = kotlinx.coroutines.flow.flow {
        while (true) {
            delay(5_000)
            emit(ProjectFileChange("mock/file.txt", ProjectFileChange.FileChangeType.MODIFIED))
        }
    }

    // ---- sessions --------------------------------------------------------------------------------

    override suspend fun getSessions(): List<SessionRecord> = sessions.toList()

    override suspend fun getSession(sessionId: String): BackendResult<SessionRecord> =
        sessions.firstOrNull { it.id == sessionId }
            ?.let { BackendResult.Success(it) }
            ?: BackendResult.Failure(BackendError.Session(sessionId, "No such mock session"))

    override suspend fun createSession(title: String?): BackendResult<SessionRecord> {
        val record = SessionRecord(
            id = "sess-${System.currentTimeMillis()}",
            title = title?.takeIf { it.isNotBlank() } ?: "New session",
            directory = getCurrentProject()?.path.orEmpty(),
            parentId = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            status = SessionActivityStatus.IDLE,
            messageCount = 0,
            filesChanged = 0,
            version = "mock-1.0.0"
        )
        sessions.add(0, record)
        messages[record.id] = mutableListOf()
        return BackendResult.Success(record)
    }

    override suspend fun continueSession(sessionId: String, prompt: String): BackendResult<Unit> =
        sendMessage(sessionId, prompt, null, null)

    override suspend fun abortSession(sessionId: String): BackendResult<Unit> {
        val record = sessions.firstOrNull { it.id == sessionId }
            ?: return BackendResult.Failure(BackendError.Session(sessionId, "No such mock session"))
        val index = sessions.indexOfFirst { it.id == sessionId }
        sessions[index] = record.copy(status = SessionActivityStatus.IDLE)
        _events.tryEmit(BackendEvent.SessionIdle(sessionId))
        return BackendResult.Success(Unit)
    }

    override suspend fun deleteSession(sessionId: String): BackendResult<Unit> {
        sessions.removeAll { it.id == sessionId }
        messages.remove(sessionId)
        return BackendResult.Success(Unit)
    }

    override suspend fun getSessionMessages(sessionId: String): List<SessionMessage> =
        messages[sessionId].orEmpty().toList()

    /**
     * Spec 69 - the streaming hot path.
     *
     * Emits a tool call, then text in small deltas, then completion, so the UI is exercised against
     * incremental updates rather than a single terminal message.
     */
    override suspend fun sendMessage(
        sessionId: String,
        prompt: String,
        model: ModelSelection?,
        agent: String?
    ): BackendResult<Unit> {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index < 0) return BackendResult.Failure(BackendError.Session(sessionId, "No such mock session"))

        val messageId = "msg-${System.currentTimeMillis()}"
        val list = messages.getOrPut(sessionId) { mutableListOf() }

        list += SessionMessage(
            id = messageId,
            sessionId = sessionId,
            role = com.palash.opencodeserver.backend.core.MessageRole.USER,
            createdAt = System.currentTimeMillis(),
            text = prompt,
            toolActivity = emptyList(),
            reasoning = null,
            isError = false,
            errorMessage = null
        )
        sessions[index] = sessions[index].copy(
            status = SessionActivityStatus.THINKING,
            messageCount = list.size
        )
        _events.tryEmit(BackendEvent.SessionStatusChanged(sessionId, "busy", null))

        scope.launch {
            val assistantId = "msg-${System.nanoTime()}"
            _events.tryEmit(
                BackendEvent.ToolStarted(sessionId, assistantId, "call-1", "read", "Reading project files")
            )
            delay(200)
            _events.tryEmit(
                BackendEvent.ToolOutput(sessionId, assistantId, "call-1", "completed", "3 files", null)
            )

            val response = "Mock response: I would look at \"$prompt\" and propose a change."
            val builder = StringBuilder()
            response.split(" ").forEach { word ->
                val piece = "$word "
                builder.append(piece)
                _events.tryEmit(
                    BackendEvent.MessageDelta(sessionId, assistantId, "part-1", piece)
                )
                delay(40)
            }

            list += SessionMessage(
                id = assistantId,
                sessionId = sessionId,
                role = com.palash.opencodeserver.backend.core.MessageRole.ASSISTANT,
                createdAt = System.currentTimeMillis(),
                text = builder.toString(),
                toolActivity = emptyList(),
                reasoning = "Considering the request.",
                isError = false,
                errorMessage = null
            )
            val record = sessions.firstOrNull { it.id == sessionId } ?: return@launch
            val position = sessions.indexOfFirst { it.id == sessionId }
            sessions[position] = record.copy(
                status = SessionActivityStatus.IDLE,
                messageCount = list.size
            )
            _events.tryEmit(
                BackendEvent.MessageCompleted(sessionId, assistantId, "stop", null)
            )
            _events.tryEmit(BackendEvent.SessionIdle(sessionId))
        }

        return BackendResult.Success(Unit)
    }

    // ---- terminal -----------------------------------------------------------------------------------

    override suspend fun openTerminal(workingDirectory: String?): BackendResult<TerminalSession> {
        val session = TerminalSession(
            sessionId = TerminalSession.newId(),
            workingDirectory = workingDirectory ?: getCurrentProject()?.path ?: "~/",
            displayWorkingDirectory = workingDirectory ?: "~/",
            shell = "bash",
            processId = 20_000 + _terminals.value.size,
            status = com.palash.opencodeserver.backend.core.TerminalStatus.RUNNING,
            createdAt = System.currentTimeMillis(),
            columns = 80,
            rows = 24,
            backendId = "mock",
            supportsFullScreen = true
        )
        _terminals.value = _terminals.value + session
        scope.launch {
            _events.tryEmit(BackendEvent.TerminalOutput(session.sessionId, "Mock terminal ready.\n", false))
        }
        return BackendResult.Success(session)
    }

    override suspend fun executeTerminalCommand(
        command: String,
        workingDirectory: String?
    ): BackendResult<Unit> {
        scope.launch {
            _events.tryEmit(BackendEvent.TerminalOutput("command", "\$ $command\n", false))
            _events.tryEmit(BackendEvent.TerminalOutput("command", "mock: executed in demo shell\n", false))
        }
        return BackendResult.Success(Unit)
    }

    // ---- git -------------------------------------------------------------------------------------------

    override suspend fun getGitStatus(projectId: String?): BackendResult<com.palash.opencodeserver.backend.core.GitStatus> =
        BackendResult.Success(
            com.palash.opencodeserver.backend.core.GitStatus(
                isRepository = true,
                branch = "main",
                ahead = 0,
                behind = 0,
                modified = listOf("src/index.js"),
                staged = emptyList(),
                untracked = listOf("notes.md"),
                conflicted = emptyList()
            )
        )

    // ---- providers ---------------------------------------------------------------------------------------

    override suspend fun listProviders(): List<ProviderDescriptor> = listOf(
        ProviderDescriptor(
            id = "mock",
            name = "Mock provider",
            source = "config",
            isConfigured = true,
            authMethods = listOf("api"),
            modelCount = 2
        )
    )

    override suspend fun saveProviderCredential(providerId: String, apiKey: String): BackendResult<Unit> =
        if (apiKey.length < 8) {
            BackendResult.Failure(
                BackendError.Categorized(
                    technical = "mock key too short",
                    category = com.palash.opencodeserver.backend.core.ErrorCategory.AUTHENTICATION,
                    retryable = false,
                    userTitle = "That API key looks too short.",
                    userMessage = "Paste the full key from your provider and try again."
                )
            )
        } else {
            BackendResult.Success(Unit)
        }

    override suspend fun removeProviderCredential(providerId: String): BackendResult<Unit> =
        BackendResult.Success(Unit)

    override suspend fun maskedCredential(providerId: String): String? = "sk-••••••••••••••••9X2A"

    override fun observeProviderHealth(): StateFlow<ProviderHealth> =
        MutableStateFlow(ProviderHealth.AUTHENTICATED)

    // ---- diagnostics ----------------------------------------------------------------------------------------

    override suspend fun getDiagnostics(includeLogs: Boolean): DiagnosticsReport = DiagnosticsReport(
        generatedAt = System.currentTimeMillis(),
        appVersion = "mock",
        appBuildType = "debug",
        androidVersion = "mock",
        deviceModel = "mock",
        deviceAbi = "mock",
        runtimeVersion = "Mock runtime",
        linuxEnvironment = "bash",
        shell = "bash",
        packageManager = "mock",
        nodeVersion = "v22.0.0",
        gitVersion = "git version 2.44.0",
        openCodeVersion = "mock-1.0.0",
        serverStatus = _server.value.state,
        serverEndpoint = _server.value.baseUrl,
        serverPort = _server.value.port,
        apiHealth = _server.value.healthy,
        storageFreeBytes = 4L * 1024 * 1024 * 1024,
        storageTotalBytes = 64L * 1024 * 1024 * 1024,
        networkStatus = "ONLINE",
        providerHealth = ProviderHealth.AUTHENTICATED,
        eventStreamConnected = _connection.value?.status == com.palash.opencodeserver.backend.core.ApiConnectionStatus.CONNECTED,
        terminalSessions = _terminals.value.size,
        recentLogs = emptyList(),
        checks = runHealthCheck()
    )

    /**
     * Spec 110 - every failing row carries a remediation.
     *
     * The mock produces the same shape production does, so a screen that renders remediations can be
     * developed without a runtime present.
     */
    override suspend fun runHealthCheck(): List<HealthCheckResult> {
        val serverRunning = _server.value.state == ServerProcessState.RUNNING
        val healthy = _server.value.healthy
        val project = getCurrentProject()

        return listOf(
            HealthCheckResult("Runtime", true, "Mock runtime active", null),
            HealthCheckResult("Shell", true, "bash", null),
            HealthCheckResult("Git", true, "git version 2.44.0", null),
            HealthCheckResult("Node", true, "v22.0.0", null),
            HealthCheckResult("OpenCode", true, "mock-1.0.0", null),
            HealthCheckResult(
                name = "Server",
                passed = serverRunning,
                detail = _server.value.state.name,
                remediation = if (serverRunning) null else "Start the OpenCode server."
            ),
            HealthCheckResult(
                name = "API",
                passed = healthy,
                detail = if (healthy) "healthy" else "unreachable",
                remediation = if (healthy) null else "Start the server, then retry."
            ),
            HealthCheckResult(
                name = "Event stream",
                passed = serverRunning,
                detail = if (serverRunning) "connected" else "disconnected",
                remediation = if (serverRunning) null else "Restart the server to restore live updates."
            ),
            HealthCheckResult(
                name = "Project",
                passed = project != null,
                detail = project?.displayPath ?: "No project selected",
                remediation = if (project != null) null else "Create or select a project."
            ),
            HealthCheckResult("Provider", true, "Authenticated", null)
        )
    }

    override suspend fun getTroubleshooting(): List<TroubleshootingSuggestion> = emptyList()

    // ---- updates / metrics -------------------------------------------------------------------------------------

    override suspend fun checkForUpdates(): List<UpdateAvailability> = listOf(
        UpdateAvailability("OpenCode", "mock-1.0.0", null, false, false, "Mock build; no updates are checked.")
    )

    override suspend fun applyUpdate(component: String, confirm: Boolean): BackendResult<Unit> =
        BackendResult.Success(Unit)

    override fun observeMetrics(): Flow<RuntimeMetrics> = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(
                RuntimeMetrics(
                    openCodeRunning = _server.value.state == ServerProcessState.RUNNING,
                    openCodePid = _server.value.pid,
                    memoryUsageMb = 48.6f,
                    memoryLimitMb = 512f,
                    cpuUsagePercent = null,
                    terminalProcessCount = _terminals.value.size,
                    storageFreeBytes = 4L * 1024 * 1024 * 1024,
                    storageTotalBytes = 64L * 1024 * 1024 * 1024,
                    runtimeState = EnvironmentState.READY,
                    activeServerConnections = if (_connection.value == null) 0 else 1
                )
            )
            delay(5_000)
        }
    }

    override suspend fun getConnectivity(): ConnectivityReport = ConnectivityReport(
        connectivity = ConnectivityState.ONLINE,
        localServerRunning = _server.value.state == ServerProcessState.RUNNING,
        terminalAvailable = true,
        providerHealth = ProviderHealth.AUTHENTICATED,
        detail = "Mock backend: all layers reported as healthy."
    )

    // ---- settings / backup ------------------------------------------------------------------------------------------

    override suspend fun updateSettings(
        transform: (BackendSettings) -> BackendSettings
    ): BackendResult<BackendSettings> = BackendResult.Success(
        transform(_settings.value).also { _settings.value = it }
    )

    override suspend fun exportBackup(includeCredentials: Boolean): BackendResult<String> =
        BackendResult.Success("/tmp/mock-backup.json")

    override suspend fun importBackup(path: String): BackendResult<Unit> = BackendResult.Success(Unit)

    // ---- seed data ---------------------------------------------------------------------------------------------

    private fun seedProjects() {
        _projects.value = listOf(
            ProjectRecord(
                id = "proj-mock-1",
                name = "hello-opencode",
                path = "~/projects/hello-opencode",
                displayPath = "~/projects/hello-opencode",
                isManaged = true,
                isActive = true,
                createdAt = System.currentTimeMillis(),
                lastOpenedAt = null,
                languageHint = "JavaScript"
            )
        )
    }

    private fun mockEnvironment() = com.palash.opencodeserver.backend.core.EnvironmentReport(
        state = EnvironmentState.READY,
        architecture = com.palash.opencodeserver.backend.core.DeviceArchitecture(
            androidAbi = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            is64Bit = true,
            runtimeAbi = "arm64-v8a",
            libcKind = com.palash.opencodeserver.backend.core.DeviceArchitecture.LibcKind.BIONIC
        ),
        runtimeInstalled = true,
        userlandName = "Mock runtime",
        shell = "/bin/bash",
        packageManager = "mock",
        nodeVersion = "v22.0.0",
        gitVersion = "git version 2.44.0",
        openCodeVersion = "mock-1.0.0",
        projectDirectory = "~/projects/hello-opencode",
        storageFreeBytes = 4L * 1024 * 1024 * 1024,
        missingPermissions = emptyList()
    )

    private fun mockFileTree() = listOf(
        ProjectFileNode("README.md", "README.md", false, 624L, false),
        ProjectFileNode("package.json", "package.json", false, 220L, false),
        ProjectFileNode("src", "src", true, null, false, listOf(
            ProjectFileNode("index.js", "src/index.js", false, 180L, false)
        ))
    )
}