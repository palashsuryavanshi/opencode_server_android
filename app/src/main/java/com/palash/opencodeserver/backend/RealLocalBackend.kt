package com.palash.opencodeserver.backend

import android.content.Context
import com.palash.opencodeserver.backend.api.ApiTimeouts
import com.palash.opencodeserver.backend.api.ApiVersionGate
import com.palash.opencodeserver.backend.api.HttpOpenCodeApiClient
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
import com.palash.opencodeserver.backend.core.RepairAction
import com.palash.opencodeserver.backend.core.RuntimeMetrics
import com.palash.opencodeserver.backend.core.ServerConfiguration
import com.palash.opencodeserver.backend.core.ServerInfo
import com.palash.opencodeserver.backend.core.ServerProcessState
import com.palash.opencodeserver.backend.core.SessionMessage
import com.palash.opencodeserver.backend.core.SessionRecord
import com.palash.opencodeserver.backend.core.SetupCheckpoint
import com.palash.opencodeserver.backend.core.SetupProgress
import com.palash.opencodeserver.backend.core.SetupStep
import com.palash.opencodeserver.backend.core.StartPolicy
import com.palash.opencodeserver.backend.core.TroubleshootingSuggestion
import com.palash.opencodeserver.backend.core.UpdateAvailability
import com.palash.opencodeserver.backend.core.backendCatching
import com.palash.opencodeserver.backend.deps.DependencyId
import com.palash.opencodeserver.backend.deps.DependencyManager
import com.palash.opencodeserver.backend.diagnostics.DiagnosticsService
import com.palash.opencodeserver.backend.events.BackendEvent
import com.palash.opencodeserver.backend.events.OpenCodeEventManager
import com.palash.opencodeserver.backend.files.FileService
import com.palash.opencodeserver.backend.files.FileWatcher
import com.palash.opencodeserver.backend.git.GitService
import com.palash.opencodeserver.backend.install.SetupOrchestrator
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.log.SecretRedactor
import com.palash.opencodeserver.backend.monitor.RuntimeMonitor
import com.palash.opencodeserver.backend.net.ConnectivityMonitor
import com.palash.opencodeserver.backend.opencode.OpenCodeConfigManager
import com.palash.opencodeserver.backend.opencode.OpenCodeManager
import com.palash.opencodeserver.backend.project.ProjectManager
import com.palash.opencodeserver.backend.project.ProjectRecordInternal
import com.palash.opencodeserver.backend.runtime.ArchitectureDetector
import com.palash.opencodeserver.backend.runtime.EnvironmentBuilder
import com.palash.opencodeserver.backend.runtime.EnvironmentManager
import com.palash.opencodeserver.backend.runtime.ProcessCategory
import com.palash.opencodeserver.backend.runtime.ProcessRegistry
import com.palash.opencodeserver.backend.runtime.ProcessRunner
import com.palash.opencodeserver.backend.runtime.RuntimeLayout
import com.palash.opencodeserver.backend.security.CredentialManager
import com.palash.opencodeserver.backend.security.CredentialScope
import com.palash.opencodeserver.backend.security.KeystoreCipher
import com.palash.opencodeserver.backend.server.OpenCodeServerManager
import com.palash.opencodeserver.backend.server.PortSelector
import com.palash.opencodeserver.backend.session.SessionManager
import com.palash.opencodeserver.backend.terminal.TerminalManager
import com.palash.opencodeserver.backend.terminal.TerminalSession
import com.palash.opencodeserver.backend.userland.PackageManagerDetector
import com.palash.opencodeserver.backend.userland.PlatformShellUserlandProvider
import com.palash.opencodeserver.backend.userland.ProvisionedUserlandProvider
import com.palash.opencodeserver.backend.userland.TermuxBridgeUserlandProvider
import com.palash.opencodeserver.backend.userland.UserlandInstaller
import com.palash.opencodeserver.backend.userland.UserlandProviderRegistry
import com.squareup.moshi.Moshi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Spec 80/121 - the production backend.
 *
 * Layering, exactly as spec 121 requires:
 * ```
 * Android UI â†’ LocalCodingBackend â†’ managers â†’ Linux runtime â†’ OpenCode process
 *             â†’ typed HTTP client â†’ SSE event manager â†’ Android UI
 * ```
 *
 * The frontend never sees a manager, a process or a socket. Every public method here returns a value,
 * a typed [BackendResult], or emits a [BackendEvent].
 *
 * Construction order is deliberate, not incidental:
 * ```
 * credentials  â†’  environment  â†’  runner  â†’  server manager
 *                                     â†˜  api client (reads endpoint from server manager)
 * ```
 * The API client needs the server's endpoint and the server needs an API client for its health check,
 * so the connection is resolved lazily through a provider function rather than being passed by value.
 */
class RealLocalBackend(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : LocalCodingBackend {

    // ---- layer 0: logging (spec 41) ---------------------------------------------------------
    private val log = LogManager(context)

    // ---- layer 1: runtime foundations ------------------------------------------------------
    private val layout = RuntimeLayout(context)
    private val architectureDetector = ArchitectureDetector()
    private val architecture = architectureDetector.detect()
    private val environment = EnvironmentBuilder(layout, architecture, log)
    private val registry = ProcessRegistry(log)
    private val runner = ProcessRunner(environment, registry, log, layout)

    // ---- layer 2: capabilities -------------------------------------------------------------
    private val termuxProvider = TermuxBridgeUserlandProvider(log).also { it.attachContext(context) }
    private val userlandInstaller = UserlandInstaller(
        userlandDir = layout.userlandDir,
        log = log,
        assets = UserlandInstaller.AndroidAssets(context)
    )
    private val userlandRegistry = UserlandProviderRegistry(
        providers = listOf(
            ProvisionedUserlandProvider(layout, runner, log, installer = userlandInstaller),
            termuxProvider,
            PlatformShellUserlandProvider(log)
        ),
        architecture = architecture,
        log = log
    )
    private val packageManagerDetector = PackageManagerDetector(runner, environment, log)
    private val credentials = CredentialManager(layout.stateDir, KeystoreCipher(), log)
    private val dependencies =
        DependencyManager(layout, architecture, runner, environment, packageManagerDetector, log)
    private val openCode = OpenCodeManager(layout, architecture, runner, dependencies, log)
    private val openCodeConfig = OpenCodeConfigManager(context, log)
    private val environmentManager = EnvironmentManager(
        context, layout, architectureDetector, dependencies, packageManagerDetector,
        openCode, userlandRegistry, log
    )

    // ---- layer 3: transport + server -------------------------------------------------------
    // Spec 48 - the endpoint always comes from the server manager, never a hard-coded URL. Passing a
    // provider rather than an instance breaks the construction cycle between these three objects.
    private val events = OpenCodeEventManager({ apiClient }, scope)
    private val apiClient: HttpOpenCodeApiClient by lazy {
        HttpOpenCodeApiClient(
            connectionProvider = { server.connection() },
            authProvider = { credentials.basicAuthHeader() },
            log = log,
            timeouts = ApiTimeouts()
        )
    }
    private val server = OpenCodeServerManager(
        layout, environment, runner, registry, credentials,
        apiClient = apiClient,
        events = events,
        log = log,
        portSelector = PortSelector(log)
    )
    private val versionGate = ApiVersionGate(apiClient, log)

    // ---- layer 4: feature managers --------------------------------------------------------
    private val files = FileService(apiClient, log)
    private val fileWatcher = FileWatcher(log)
    private val sessions = SessionManager(apiClient, log)
    private val projects = ProjectManager(layout, log)
    private val git = GitService(runner, log)
    private val terminals = TerminalManager(layout, environment, runner, log)
    private val connectivity = ConnectivityMonitor(context, log)
    private val monitor = RuntimeMonitor(context, layout, log)
    private val diagnostics = DiagnosticsService(
        context, layout, environmentManager, server, openCode, apiClient, versionGate, log
    )
    // Spec 81 - fault injection, wired to the settings toggle.
    private val testMode = TestModeController(events, server, log).also { it.bindScope(scope) }

    private val setup = SetupOrchestrator(
        context, layout, environmentManager, dependencies, openCode, openCodeConfig,
        userlandRegistry, credentials, log
    ).also { it.attachEnvironmentProvider { environment } }

    private val moshi = Moshi.Builder().build()
    private val settingsAdapter by lazy {
        moshi.adapter(BackendSettings::class.java)
    }

    // ---- observable state (spec 112) ------------------------------------------------------

    private val _state = MutableStateFlow(AppState.INITIALIZING)
    override fun observeState(): StateFlow<AppState> = _state.asStateFlow()

    private val _connection = MutableStateFlow<OpenCodeConnection?>(null)
    override fun observeConnection(): StateFlow<OpenCodeConnection?> = _connection.asStateFlow()

    private val _projectsFlow = MutableStateFlow<List<ProjectRecord>>(emptyList())
    override fun observeProjects(): StateFlow<List<ProjectRecord>> = _projectsFlow.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    override fun observeSettings(): StateFlow<BackendSettings> = _settings.asStateFlow()

    private val _eventsFlow = MutableSharedFlow<BackendEvent>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Spec 22/69 - the single event stream the frontend renders from. */
    override fun subscribeToEvents(): Flow<BackendEvent> = _eventsFlow.asSharedFlow()

    init {
        applySettings(_settings.value)
        scope.launch { bridgeTerminalOutput() }
        scope.launch { bridgeTerminalExits() }
        scope.launch { bridgeServerEvents() }
        scope.launch { bridgeServerState() }
        scope.launch { bridgeEnvironment() }
        scope.launch { bridgeConnectivity() }
        scope.launch { bridgeProviderHealth() }
    }

    // ---- spec 111: lifecycle --------------------------------------------------------------

    override suspend fun getStatus(): BackendStatus {
        val project = activeProjectRecord()
        return BackendStatus(
            appState = _state.value,
            environment = environmentManager.report.value ?: environmentManager.evaluate(project?.path),
            server = server.info.value,
            connection = _connection.value,
            currentProject = project,
            sessionCount = 0,
            terminalCount = terminals.sessionCount(),
            providerHealth = openCode.providerHealth.value,
            connectivity = connectivity.state.value,
            metrics = null,
            pendingSetupCheckpoint = setup.checkpoint.value
        )
    }

    /**
     * Spec 6/99/100 - run or resume setup.
     *
     * Progress is also published on [observeSetupProgress]; this returns the terminal outcome so a
     * caller that only needs the result does not have to collect a flow.
     */
    override suspend fun setup(force: Boolean): SetupProgress {
        var latest: SetupProgress? = null
        val projectPath = activeProjectRecord()?.path
        setup.run(force = force, projectDirectory = projectPath).collect { latest = it }
        return latest ?: terminalProgress(
            "Setup complete",
            "Your local coding environment is ready.",
            null
        )
    }

    override fun observeSetupProgress(): StateFlow<SetupProgress?> = setup.progress

    override suspend fun resetEnvironment(confirm: Boolean): BackendResult<Unit> {
        val result = setup.resetEnvironment(confirm)
        if (result.isSuccess) {
            stopServer()
            terminals.closeAll()
            fileWatcher.stop()
            _eventsFlow.tryEmit(BackendEvent.Notice("Development environment reset.", isError = false))
        }
        return result
    }

    /** Spec 117 - repair one component instead of resetting everything. */
    override suspend fun repair(action: RepairAction): SetupProgress {
        val flow = when (action) {
            RepairAction.REPAIR_OPENCODE -> setup.repair(DependencyId.OPENCODE)
            RepairAction.REPAIR_NODE -> setup.repair(DependencyId.NODE)
            RepairAction.REPAIR_GIT -> setup.repair(DependencyId.GIT)
            RepairAction.REPAIR_RUNTIME -> setup.repair(DependencyId.RUNTIME)
            RepairAction.REINSTALL_DEPENDENCIES -> setup.run(force = true)
            RepairAction.RESET_OPENCODE_CONFIG -> {
                val reset = openCodeConfig.reset(layout.openCodeConfigDir)
                kotlinx.coroutines.flow.flowOf(
                    terminalProgress(
                        action.label,
                        reset.valueOrNull()?.let { "OpenCode configuration was reset." }
                            ?: (reset.errorOrNull()?.userMessage ?: "Reset failed."),
                        reset.errorOrNull()
                    )
                )
            }
            RepairAction.RESET_ALL -> kotlinx.coroutines.flow.flowOf(
                resetEnvironment(confirm = true).let { result ->
                    terminalProgress(
                        action.label,
                        result.valueOrNull()?.let { "The development environment was reset." }
                            ?: (result.errorOrNull()?.userMessage ?: "Reset failed."),
                        result.errorOrNull()
                    )
                }
            )
        }

        var latest: SetupProgress? = null
        flow.collect { latest = it }
        return latest ?: terminalProgress(action.label, "Done.", null)
    }

    // ---- spec 16/17: server ----------------------------------------------------------------

    override suspend fun startServer(): BackendResult<ServerInfo> {
        val result = server.start(currentConfiguration())
        when (result) {
            is BackendResult.Success -> {
                // Spec 21 - validate the API now, so a mismatch surfaces before the first prompt.
                versionGate.invalidate()
                val compat = versionGate.evaluate()
                if (!compat.compatible) {
                    log.warn(LogCategory.API, "OpenCode API version mismatch", compat.notes.joinToString("\n"))
                    _eventsFlow.tryEmit(
                        BackendEvent.Notice(compat.notes.joinToString(" "), isError = false)
                    )
                }
                publishConnection()
                _eventsFlow.tryEmit(BackendEvent.Notice("Server running.", isError = false))
            }
            is BackendResult.Failure -> {
                log.error(LogCategory.SERVER, "Server start failed", result.error.technical)
                _eventsFlow.tryEmit(BackendEvent.Notice(result.error.userTitle, isError = true))
                // Spec 40 - bounded automatic retry, never an infinite loop.
                if (server.shouldAutoRestart()) server.noteStartFailure()
            }
        }
        return result
    }

    override suspend fun stopServer(): BackendResult<ServerInfo> {
        // Spec 62 - terminal processes are not touched.
        val result = server.stop(closeTerminals = false)
        fileWatcher.stop()
        publishConnection()
        _eventsFlow.tryEmit(BackendEvent.Notice("Server stopped.", isError = false))
        return result
    }

    override suspend fun restartServer(): BackendResult<ServerInfo> {
        val result = server.restart()
        if (result.isSuccess) {
            versionGate.invalidate()
            publishConnection()
        }
        return result
    }

    override fun observeServerState(): StateFlow<ServerInfo> = server.info

    override suspend fun getServerStatus(): ServerInfo = server.info.value

    override suspend fun getServerAdvancedDetails() = server.advancedDetails()

    override suspend fun configureServer(configuration: ServerConfiguration): BackendResult<ServerInfo> =
        updateSettings { current ->
            current.copy(
                preferredPort = configuration.preferredPort,
                bindToLan = configuration.bindToLan,
                additionalCorsOrigins = configuration.additionalCorsOrigins,
                startPolicy = configuration.startPolicy,
                autoRestartServer = configuration.autoRestart,
                maxAutoRestarts = configuration.maxAutoRestarts
            )
        }.map { server.info.value }

    /** Spec 95 - verify an existing server instead of starting another one. */
    override suspend fun discoverRunningServer(): BackendResult<ServerInfo> =
        server.discoverRunningServer().also { if (it.isSuccess) publishConnection() }

    // ---- spec 28: projects ------------------------------------------------------------------

    override suspend fun getProjects(): List<ProjectRecord> {
        val mapped = projects.listProjects().valueOrNull().orEmpty().map { it.toPublic() }
        _projectsFlow.value = mapped
        return mapped
    }

    override suspend fun getCurrentProject(): ProjectRecord? = activeProjectRecord()

    override suspend fun selectProject(projectId: String): BackendResult<ProjectRecord> {
        val result = projects.selectProject(projectId)
        if (result.isSuccess) {
            getProjects()
            startFileWatching()
            _eventsFlow.tryEmit(
                BackendEvent.Notice("Project: ${result.valueOrNull()?.displayPath}", isError = false)
            )
        }
        return result.map { it.toPublic() }
    }

    override suspend fun createProject(request: CreateProjectRequest): BackendResult<ProjectRecord> {
        val created = if (request.externalPath != null) {
            projects.addExternalProject(request.externalPath)
        } else {
            projects.createManagedProject(
                name = request.name,
                initializeGit = request.initializeGit,
                seedTestProject = request.seedTestProject,
                runGit = { dir, args ->
                    // Git initialisation is best effort: a project without Git is still usable, so
                    // this degrades rather than blocking project creation.
                    val outcome = runner.run(
                        ProcessRunner.CommandSpec(
                            command = "git",
                            arguments = args,
                            workingDirectory = dir.absolutePath,
                            timeoutMs = 20_000
                        )
                    )
                    if (outcome.isSuccess) BackendResult.Success(Unit)
                    else BackendResult.Failure(
                        BackendError.Unknown("git ${args.joinToString(" ")} exited ${outcome.exitCode}")
                    )
                }
            )
        }

        if (created.isSuccess) {
            getProjects()
            created.valueOrNull()?.let { projects.selectProject(it.id) }
            startFileWatching()
            _eventsFlow.tryEmit(
                BackendEvent.Notice("Created project ${created.valueOrNull()?.name}", isError = false)
            )
        }
        return created.map { it.toPublic() }
    }

    override suspend fun renameProject(projectId: String, newName: String): BackendResult<ProjectRecord> =
        projects.renameProject(projectId, newName).also { if (it.isSuccess) getProjects() }.map { it.toPublic() }

    /** Spec 28 - unregisters only; files stay on disk. */
    override suspend fun removeProject(projectId: String): BackendResult<Unit> =
        projects.removeProject(projectId).also { if (it.isSuccess) getProjects() }

    override suspend fun deleteProjectFiles(projectId: String, confirm: Boolean): BackendResult<Unit> =
        projects.deleteProjectFiles(projectId, confirm).also { if (it.isSuccess) getProjects() }

    // ---- spec 104: files -----------------------------------------------------------------------

    override suspend fun getFiles(relativePath: String, depth: Int): List<ProjectFileNode> {
        val project = activeProjectRecord() ?: return emptyList()
        return files.list(project.path, relativePath, depth).valueOrNull().orEmpty()
    }

    override suspend fun readFile(relativePath: String, allowLarge: Boolean): BackendResult<FileContentResult> {
        val project = activeProjectRecord() ?: return BackendResult.Failure(projectRequiredError())
        return files.read(project.path, relativePath, allowLarge)
    }

    override suspend fun writeFile(relativePath: String, content: String): BackendResult<Unit> {
        val project = activeProjectRecord() ?: return BackendResult.Failure(projectRequiredError())
        val written = files.write(project.path, relativePath, content) { target, text ->
            backendCatching {
                target.parentFile?.mkdirs()
                // Atomic replace so an interrupted save cannot truncate a source file.
                val temp = File(target.parentFile, "${target.name}.tmp")
                temp.writeText(text, Charsets.UTF_8)
                if (!temp.renameTo(target)) {
                    target.writeText(text, Charsets.UTF_8)
                    temp.delete()
                }
            }
        }
        if (written.isSuccess) startFileWatching()
        return written
    }

    /** Spec 71 - only the active project is watched, never the Android filesystem. */
    override fun observeFileChanges(): Flow<ProjectFileChange> =
        fileWatcher.changes(fallbackRoot = layout.projects)

    // ---- spec 24/25: sessions ---------------------------------------------------------------------

    override suspend fun getSessions(): List<SessionRecord> {
        val project = activeProjectRecord() ?: return emptyList()
        return sessions.listSessions(project.path).valueOrNull().orEmpty()
    }

    override suspend fun getSession(sessionId: String): BackendResult<SessionRecord> {
        val project = activeProjectRecord() ?: return BackendResult.Failure(projectRequiredError())
        return sessions.getSession(sessionId, project.path)
    }

    override suspend fun createSession(title: String?): BackendResult<SessionRecord> {
        val project = activeProjectRecord() ?: return BackendResult.Failure(projectRequiredError())
        return sessions.createSession(title, project.path)
    }

    override suspend fun continueSession(sessionId: String, prompt: String): BackendResult<Unit> {
        val project = activeProjectRecord() ?: return BackendResult.Failure(projectRequiredError())
        return sessions.continueSession(sessionId, prompt, null, null, project.path)
    }

    override suspend fun abortSession(sessionId: String): BackendResult<Unit> {
        val project = activeProjectRecord() ?: return BackendResult.Failure(projectRequiredError())
        return sessions.abortSession(sessionId, project.path)
    }

    override suspend fun deleteSession(sessionId: String): BackendResult<Unit> {
        val project = activeProjectRecord() ?: return BackendResult.Failure(projectRequiredError())
        return sessions.deleteSession(sessionId, project.path)
    }

    /** Spec 120 - reopen the app and the session history is still there. */
    override suspend fun getSessionMessages(sessionId: String): List<SessionMessage> {
        val project = activeProjectRecord() ?: return emptyList()
        return sessions.messages(sessionId, project.path).valueOrNull().orEmpty()
    }

    override suspend fun sendMessage(
        sessionId: String,
        prompt: String,
        model: ModelSelection?,
        agent: String?
    ): BackendResult<Unit> {
        val project = activeProjectRecord() ?: return BackendResult.Failure(projectRequiredError())
        return sessions.sendPrompt(sessionId, prompt, model, agent, project.path).also { result ->
            if (result is BackendResult.Failure) {
                _eventsFlow.tryEmit(BackendEvent.Notice(result.error.userTitle, isError = true))
            }
        }
    }

    // ---- spec 31: terminal ------------------------------------------------------------------------

    override suspend fun openTerminal(workingDirectory: String?): BackendResult<TerminalSession> {
        val directory = workingDirectory ?: activeProjectRecord()?.path ?: layout.home.absolutePath
        val result = terminals.createSession(
            workingDirectory = directory,
            shellOverride = _settings.value.defaultShell,
            columns = _settings.value.defaultTerminalColumns,
            rows = _settings.value.defaultTerminalRows
        )
        if (result.isSuccess) _eventsFlow.tryEmit(BackendEvent.Notice("Terminal opened.", isError = false))
        return result
    }

    override suspend fun executeTerminalCommand(
        command: String,
        workingDirectory: String?
    ): BackendResult<Unit> {
        val directory = workingDirectory ?: activeProjectRecord()?.path ?: layout.home.absolutePath
        return when (val outcome = terminals.execute(command, directory)) {
            is BackendResult.Success -> {
                // Spec 33/35 - output is surfaced, never swallowed.
                val result = outcome.value
                if (result.stdout.isNotBlank()) {
                    _eventsFlow.tryEmit(BackendEvent.TerminalOutput("command", result.stdout, false))
                }
                if (result.stderr.isNotBlank()) {
                    _eventsFlow.tryEmit(BackendEvent.TerminalOutput("command", result.stderr, true))
                }
                BackendResult.Success(Unit)
            }
            is BackendResult.Failure -> outcome
        }
    }

    override fun observeTerminalSessions(): StateFlow<List<TerminalSession>> = terminals.sessions

    // ---- spec 72: git -----------------------------------------------------------------------------

    override suspend fun getGitStatus(projectId: String?): BackendResult<com.palash.opencodeserver.backend.core.GitStatus> {
        val records = projects.listProjects().valueOrNull().orEmpty()
        val record = projectId?.let { id -> records.firstOrNull { it.id == id } }
            ?: records.firstOrNull { it.isActive }
            ?: return BackendResult.Failure(projectRequiredError())
        return git.status(record.path)
    }

    // ---- spec 26/78: providers ----------------------------------------------------------------------

    override suspend fun listProviders(): List<ProviderDescriptor> {
        val project = activeProjectRecord()
        val response = apiClient.listProviders(project?.path).valueOrNull() ?: return emptyList()
        val authMethods = apiClient.providerAuthMethods(project?.path).valueOrNull().orEmpty()
        val connected = response.connected.orEmpty()

        openCode.updateProviderHealth(connected, connectivity.state.value == ConnectivityState.ONLINE)

        return response.all.orEmpty().map { provider ->
            ProviderDescriptor(
                id = provider.resolvedId,
                name = provider.resolvedName,
                source = provider.source.orEmpty(),
                isConfigured = provider.resolvedId in connected,
                authMethods = authMethods[provider.resolvedId].orEmpty().mapNotNull { it.type },
                modelCount = provider.modelCount
            )
        }
    }

    /**
     * Spec 26/27 - hand a key to OpenCode's own auth store.
     *
     * The plaintext travels once over loopback and is registered with the redactor immediately, so it
     * cannot appear in a log line afterwards. The app keeps no second copy (spec 27).
     */
    override suspend fun saveProviderCredential(providerId: String, apiKey: String): BackendResult<Unit> {
        SecretRedactor.registerSecret(apiKey)
        val project = activeProjectRecord()
        val result = apiClient.setApiAuth(providerId, apiKey, project?.path)
        if (result.isSuccess) {
            openCode.updateProviderHealth(listOf(providerId), true)
            _eventsFlow.tryEmit(BackendEvent.Notice("Provider saved.", isError = false))
        }
        return result.map { }
    }

    override suspend fun removeProviderCredential(providerId: String): BackendResult<Unit> {
        credentials.remove(CredentialScope.PROVIDER, providerId)
        val project = activeProjectRecord()
        // Spec 92 - no documented delete route exists, so the documented CLI is the fallback.
        val result = runner.run(
            ProcessRunner.CommandSpec(
                command = "opencode",
                arguments = listOf("auth", "logout", providerId),
                workingDirectory = project?.path ?: layout.home.absolutePath,
                timeoutMs = 30_000
            )
        )
        return if (result.isSuccess) {
            openCode.updateProviderHealth(emptyList(), true)
            BackendResult.Success(Unit)
        } else {
            BackendResult.Failure(
                BackendError.Categorized(
                    technical = "opencode auth logout exited ${result.exitCode}: ${result.stderr.take(200)}",
                    category = com.palash.opencodeserver.backend.core.ErrorCategory.AUTHENTICATION,
                    retryable = false,
                    userTitle = "The provider could not be disconnected.",
                    userMessage = "Reconnect the provider to remove the stored key."
                )
            )
        }
    }

    override suspend fun maskedCredential(providerId: String): String? =
        credentials.masked(CredentialScope.PROVIDER, providerId)

    override fun observeProviderHealth(): StateFlow<ProviderHealth> = openCode.providerHealth

    // ---- spec 59/110/116: diagnostics -------------------------------------------------------------------

    override suspend fun getDiagnostics(includeLogs: Boolean): DiagnosticsReport =
        diagnostics.buildReport(
            includeLogs = includeLogs,
            terminalSessions = terminals.sessionCount(),
            eventStreamConnected = events.state.value.connected,
            providerHealth = openCode.providerHealth.value,
            networkStatus = connectivity.state.value
        )

    override suspend fun runHealthCheck(): List<HealthCheckResult> = diagnostics.runHealthCheck(
        terminalSessions = terminals.sessionCount(),
        eventStreamConnected = events.state.value.connected,
        providerHealth = openCode.providerHealth.value
    )

    override suspend fun getTroubleshooting(): List<TroubleshootingSuggestion> =
        diagnostics.troubleshootingSuggestions(
            terminalSessions = terminals.sessionCount(),
            eventStreamConnected = events.state.value.connected,
            providerHealth = openCode.providerHealth.value,
            networkState = connectivity.state.value
        )

    /** Spec 59 - the redacted text the Copy Diagnostics button places on the clipboard. */
    fun redactedDiagnosticsText(): String = kotlinx.coroutines.runBlocking {
        diagnostics.redactedText(getDiagnostics(includeLogs = true))
    }

    // ---- spec 46: updates -----------------------------------------------------------------------------

    override suspend fun checkForUpdates(): List<UpdateAvailability> =
        listOfNotNull(openCode.checkLatestCompatibleVersion().valueOrNull())

    override suspend fun applyUpdate(component: String, confirm: Boolean): BackendResult<Unit> =
        when (component.lowercase()) {
            "opencode" -> openCode.update(confirm).map { }
            else -> BackendResult.Failure(
                BackendError.Unknown(
                    technical = "unknown component: $component",
                    userTitle = "That component cannot be updated.",
                    userMessage = "Only OpenCode can be updated from within the app."
                )
            )
        }

    override fun observeMetrics(): Flow<RuntimeMetrics> = monitor.observe(
        RuntimeMonitor.Providers(
            isServerRunning = { server.status() == ServerProcessState.RUNNING },
            serverPid = { server.getPid() },
            terminalProcessCount = { terminals.sessionCount() },
            environmentState = { environmentManager.state.value },
            activeConnections = { if (events.state.value.connected) 1 else 0 }
        )
    )

    override suspend fun getConnectivity(): ConnectivityReport = connectivity.buildReport(
        serverState = server.status(),
        terminalAvailable = layout.isRuntimeInstalled(),
        providerHealth = openCode.providerHealth.value
    )

    // ---- settings / backup (spec 37/90) ------------------------------------------------------------------

    override suspend fun updateSettings(
        transform: (BackendSettings) -> BackendSettings
    ): BackendResult<BackendSettings> = backendCatching {
        val updated = transform(_settings.value)
        _settings.value = updated
        persistSettings(updated)
        applySettings(updated)
        updated
    }

    override suspend fun exportBackup(includeCredentials: Boolean): BackendResult<String> = backendCatching {
        val settingsJson = settingsAdapter.toJson(_settings.value)
        val projectsJson = moshi.adapter(List::class.java).toJson(
            projects.listProjects().valueOrNull().orEmpty()
        )
        val configBackup = openCodeConfig.backup(layout.openCodeConfigDir, layout.backupDir)

        val payload = buildString {
            appendLine("{")
            appendLine("  \"version\": 1,")
            appendLine("  \"settings\": $settingsJson,")
            appendLine("  \"projects\": $projectsJson,")
            appendLine("  \"configBackup\": \"${configBackup.valueOrNull()?.absolutePath.orEmpty()}\",")
            if (includeCredentials) {
                // Spec 90 - an archive containing credentials is encrypted, never plaintext.
                val passphrase = credentials.serverPassword()
                val cipher = KeystoreCipher()
                val encrypted = cipher.encryptForBackup(passphrase, passphrase.toCharArray())
                appendLine("  \"credentials\": \"$encrypted\"")
            } else {
                appendLine("  \"credentials\": null")
            }
            appendLine("}")
        }

        val target = File(layout.backupDir, "backup-${System.currentTimeMillis()}.json")
        target.parentFile?.mkdirs()
        target.writeText(payload, Charsets.UTF_8)
        target.absolutePath
    }

    override suspend fun importBackup(path: String): BackendResult<Unit> = backendCatching {
        val file = File(path)
        require(file.exists()) { "Backup file not found: $path" }
        val text = file.readText(Charsets.UTF_8)
        val settingsJson = Regex("\"settings\"\\s*:\\s*(\\{.*?\\})", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.getOrNull(1)
        requireNotNull(settingsJson) { "Backup does not contain a settings block" }
        val imported = requireNotNull(settingsAdapter.fromJson(settingsJson)) { "Settings block is unreadable" }
        // Credentials are deliberately not restored: a leaked archive must not grant API access.
        updateSettings { current -> current.copy(
            startPolicy = imported.startPolicy,
            autoRestartServer = imported.autoRestartServer,
            maxAutoRestarts = imported.maxAutoRestarts,
            preferredPort = imported.preferredPort,
            scrollbackLimit = imported.scrollbackLimit,
            largeFileThresholdBytes = imported.largeFileThresholdBytes,
            useMockBackend = imported.useMockBackend
        ) }
        Unit
    }

    // ---- app lifecycle (spec 86/88) ----------------------------------------------------------------------

    /**
     * Spec 38/95 - process start.
     *
     * Discovery comes first so the app never starts a second server, and the persisted start policy
     * is only applied after discovery has failed.
     */
    suspend fun onProcessStart() {
        val project = activeProjectRecord()
        environmentManager.evaluate(project?.path)

        if (setup.checkpoint.value == SetupCheckpoint.COMPLETE) {
            val discovered = server.discoverRunningServer()
            if (discovered.isSuccess) {
                server.markConnected(server.info.value.version)
                events.onServerStarted(server.info.value.version)
                events.connect()
                publishConnection()
                _eventsFlow.tryEmit(
                    BackendEvent.Notice("Reconnected to the running OpenCode server.", isError = false)
                )
            } else {
                server.applyStartPolicy(project != null)
            }
        } else {
            _state.value = AppState.SETUP_REQUIRED
            if (setup.checkpoint.value != SetupCheckpoint.STARTED) {
                // Spec 99 - tell the user setup was interrupted and will resume.
                _eventsFlow.tryEmit(BackendEvent.Notice(setup.describeResumeState(), isError = false))
            }
        }

        publishConnection()
        recomputeState()
    }

    /** Spec 22/86 - foreground: restore the event stream and refresh the environment view. */
    suspend fun onForeground() {
        events.onForeground()
        registry.reap()
        environmentManager.evaluate(activeProjectRecord()?.path)
        publishConnection()
        recomputeState()
    }

    /** Spec 22/86 - background: release the SSE socket instead of holding a wakeful connection. */
    fun onBackground() {
        events.onBackground()
    }

    /** Spec 89 - explicit shutdown: disconnect, stop the server, release resources, keep data. */
    suspend fun shutdown() {
        events.disconnect()
        server.stop(closeTerminals = true)
        fileWatcher.stop()
        registry.stopCategory(ProcessCategory.INSTALLER)
    }

    /** Spec 36/75 - expose whether the foreground service should be running. */
    fun shouldRunForegroundService(): Boolean =
        _settings.value.enableForegroundService && server.status() == ServerProcessState.RUNNING

    /** Spec 42 - terminal subsystem details for the Advanced view. */
    fun terminalDetails(): List<String> = terminals.describe()

    // ---- internals -------------------------------------------------------------------------------------------

    /** Spec 48 - the project directory the server should serve, resolved once per call. */
    private suspend fun currentConfiguration() = ServerConfiguration(
        preferredPort = _settings.value.preferredPort,
        bindToLan = _settings.value.bindToLan,
        additionalCorsOrigins = _settings.value.additionalCorsOrigins,
        startPolicy = _settings.value.startPolicy,
        autoRestart = _settings.value.autoRestartServer,
        maxAutoRestarts = _settings.value.maxAutoRestarts,
        requestedProjectDirectory = activeProjectRecord()?.path
    )

    private suspend fun activeProjectRecord(): ProjectRecord? =
        projects.listProjects().valueOrNull()?.firstOrNull { it.isActive }?.toPublic()

    private fun ProjectRecordInternal.toPublic() = ProjectRecord(
        id = id,
        name = name,
        path = path,
        displayPath = displayPath,
        isManaged = isManaged,
        isActive = isActive,
        createdAt = createdAt,
        lastOpenedAt = lastOpenedAt,
        languageHint = languageHint
    )

    private fun projectRequiredError() = BackendError.Project(
        projectName = "project",
        technical = "No active project is selected",
        requiresSelection = true
    )

    /** Spec 71 - watch only the active project directory. */
    private suspend fun startFileWatching() {
        val project = projects.activeProject() ?: return
        fileWatcher.start(File(project.path)) { change ->
            _eventsFlow.tryEmit(BackendEvent.WatcherChanged(change))
        }
    }

    private fun publishConnection() {
        if (server.info.value.isRunning) server.markConnected(server.info.value.version)
        _connection.value = server.connection()
    }

    /** Spec 112 - derive the one app state the frontend renders. */
    private fun recomputeState() {
        _state.value = when {
            _settings.value.testScenario.isActive -> AppState.READY
            environmentManager.state.value == EnvironmentState.INSTALLING -> AppState.INSTALLING
            environmentManager.state.value == EnvironmentState.NOT_INITIALIZED -> AppState.SETUP_REQUIRED
            environmentManager.state.value == EnvironmentState.ERROR -> AppState.DEGRADED
            environmentManager.state.value == EnvironmentState.CHECKING -> AppState.INITIALIZING
            server.status() == ServerProcessState.STARTING -> AppState.SERVER_STARTING
            server.status() == ServerProcessState.STOPPING -> AppState.SERVER_STOPPING
            server.status() == ServerProcessState.CRASHED -> AppState.SERVER_ERROR
            server.status() == ServerProcessState.ERROR -> AppState.SERVER_ERROR
            server.status() == ServerProcessState.RUNNING ->
                if (events.state.value.connected) AppState.API_CONNECTED else AppState.API_CONNECTING
            server.status() == ServerProcessState.STOPPED -> AppState.SERVER_STOPPED
            else -> AppState.READY
        }
        _eventsFlow.tryEmit(BackendEvent.StateChanged(_state.value))
    }

    private fun terminalProgress(headline: String, detail: String, error: BackendError?): SetupProgress =
        SetupProgress(
            step = SetupStep.DONE,
            stepIndex = 11,
            stepCount = 11,
            headline = headline,
            detail = detail,
            percent = 100,
            advancedCommands = emptyList(),
            checkpoint = setup.checkpoint.value,
            error = error,
            isTerminal = true
        )

    private suspend fun bridgeTerminalOutput() {
        terminals.output.collect { chunk ->
            _eventsFlow.tryEmit(
                BackendEvent.TerminalOutput(chunk.sessionId, chunk.text, chunk.isStderr)
            )
        }
    }

    private suspend fun bridgeTerminalExits() {
        terminals.exits.collect { exit ->
            _eventsFlow.tryEmit(BackendEvent.TerminalExited(exit.sessionId, exit.exitCode))
        }
    }

    private suspend fun bridgeServerEvents() {
        events.events.collect { event ->
            _eventsFlow.tryEmit(event)
            if (event is BackendEvent.EventStreamConnected) publishConnection()
        }
    }

    private suspend fun bridgeServerState() {
        server.info.collect { info ->
            _eventsFlow.tryEmit(BackendEvent.ServerStateChanged(info))
            publishConnection()
            recomputeState()
        }
    }

    private suspend fun bridgeEnvironment() {
        environmentManager.state.collect { state ->
            _eventsFlow.tryEmit(BackendEvent.EnvironmentChanged(state))
            recomputeState()
        }
    }

    private suspend fun bridgeConnectivity() {
        connectivity.observe().collect { state ->
            _eventsFlow.tryEmit(BackendEvent.ConnectivityChanged(state))
        }
    }

    private suspend fun bridgeProviderHealth() {
        openCode.providerHealth.collect { health ->
            _eventsFlow.tryEmit(BackendEvent.ProviderHealthChanged(health))
            recomputeState()
        }
    }

    private fun applySettings(settings: BackendSettings) {
        terminals.setScrollbackLimit(settings.scrollbackLimit.lines)
        files.largeFileThresholdBytes = settings.largeFileThresholdBytes
        testMode.apply(settings.testScenario)
        server.scenario = settings.testScenario
    }

    private fun loadSettings(): BackendSettings = runCatching {
        if (!layout.settingsFile.exists()) {
            BackendSettings()
        } else {
            settingsAdapter.fromJson(layout.settingsFile.readText(Charsets.UTF_8)) ?: BackendSettings()
        }
    }.getOrDefault(BackendSettings())

    private fun persistSettings(settings: BackendSettings) {
        runCatching {
            layout.settingsFile.parentFile?.mkdirs()
            layout.settingsFile.writeText(settingsAdapter.toJson(settings), Charsets.UTF_8)
        }
    }
}

