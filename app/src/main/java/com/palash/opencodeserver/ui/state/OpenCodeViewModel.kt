package com.palash.opencodeserver.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palash.opencodeserver.data.*
import com.palash.opencodeserver.model.*
import com.palash.opencodeserver.service.*
import com.palash.opencodeserver.ui.navigation.NavigationTab
import com.palash.opencodeserver.ui.navigation.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OpenCodeViewModel(
    private val terminalService: TerminalService = MockTerminalService(),
    private val openCodeService: OpenCodeService = MockOpenCodeService(),
    private val projectService: ProjectService = MockProjectService(),
    private val sessionService: SessionService = MockSessionService(),
    private val gitService: GitService = MockGitService(),
    private val fileService: FileService = MockFileService(),
    private val serverService: ServerService = MockServerService()
) : ViewModel() {

    // Navigation Stack - Default to Server Dashboard
    private val _currentScreen = MutableStateFlow<Screen>(Screen.Server)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val backStack = mutableListOf<Screen>()

    private val _selectedTab = MutableStateFlow(NavigationTab.SERVER)
    val selectedTab: StateFlow<NavigationTab> = _selectedTab.asStateFlow()

    // Connection & Server Telemetry
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _serverConfig = MutableStateFlow(MockData.mockServerConfig)
    val serverConfig: StateFlow<ServerConfig> = _serverConfig.asStateFlow()

    // Server Logs
    private val _serverLogs = MutableStateFlow<List<ServerLogEntry>>(MockData.mockServerLogs)
    val serverLogs: StateFlow<List<ServerLogEntry>> = _serverLogs.asStateFlow()

    private val _logLevelFilter = MutableStateFlow("ALL")
    val logLevelFilter: StateFlow<String> = _logLevelFilter.asStateFlow()

    private val _logSubsystemFilter = MutableStateFlow("ALL")
    val logSubsystemFilter: StateFlow<String> = _logSubsystemFilter.asStateFlow()

    private val _logSearchQuery = MutableStateFlow("")
    val logSearchQuery: StateFlow<String> = _logSearchQuery.asStateFlow()

    private val _isLogStreamPaused = MutableStateFlow(false)
    val isLogStreamPaused: StateFlow<Boolean> = _isLogStreamPaused.asStateFlow()

    // Server Endpoints & Health Checks
    private val _endpoints = MutableStateFlow<List<ServerEndpoint>>(MockData.mockEndpoints)
    val endpoints: StateFlow<List<ServerEndpoint>> = _endpoints.asStateFlow()

    private val _selectedEndpoint = MutableStateFlow<ServerEndpoint?>(MockData.mockEndpoints.first())
    val selectedEndpoint: StateFlow<ServerEndpoint?> = _selectedEndpoint.asStateFlow()

    private val _endpointTestResult = MutableStateFlow<String?>(MockData.mockEndpoints.first().responseBody)
    val endpointTestResult: StateFlow<String?> = _endpointTestResult.asStateFlow()

    private val _healthChecks = MutableStateFlow<List<ServerHealthCheck>>(MockData.mockHealthChecks)
    val healthChecks: StateFlow<List<ServerHealthCheck>> = _healthChecks.asStateFlow()

    // Projects & Workspaces
    private val _projects = MutableStateFlow(MockData.mockProjects)
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _currentProject = MutableStateFlow<Project?>(MockData.mockProjects.first())
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()

    // Sessions
    private val _sessions = MutableStateFlow(MockData.mockSessions)
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _currentSession = MutableStateFlow<Session?>(MockData.mockSessions.first())
    val currentSession: StateFlow<Session?> = _currentSession.asStateFlow()

    // Terminal
    private val _terminalLines = MutableStateFlow<List<String>>(terminalService.getInitialOutput())
    val terminalLines: StateFlow<List<String>> = _terminalLines.asStateFlow()

    private val _terminalInput = MutableStateFlow("")
    val terminalInput: StateFlow<String> = _terminalInput.asStateFlow()

    // Files & Code Editor
    private val _fileTree = MutableStateFlow<List<FileNode>>(MockData.mockFileTree)
    val fileTree: StateFlow<List<FileNode>> = _fileTree.asStateFlow()

    private val _expandedFolders = MutableStateFlow<Set<String>>(setOf("src", "components"))
    val expandedFolders: StateFlow<Set<String>> = _expandedFolders.asStateFlow()

    private val _activeEditorFilePath = MutableStateFlow("src/App.tsx")
    val activeEditorFilePath: StateFlow<String> = _activeEditorFilePath.asStateFlow()

    private val _activeEditorFileName = MutableStateFlow("App.tsx")
    val activeEditorFileName: StateFlow<String> = _activeEditorFileName.asStateFlow()

    private val _editorContent = MutableStateFlow(fileService.getFileContent("src/App.tsx"))
    val editorContent: StateFlow<String> = _editorContent.asStateFlow()

    private val _isEditorModified = MutableStateFlow(false)
    val isEditorModified: StateFlow<Boolean> = _isEditorModified.asStateFlow()

    // Git Changes
    private val _gitChanges = MutableStateFlow(MockData.mockGitChanges)
    val gitChanges: StateFlow<List<FileDiff>> = _gitChanges.asStateFlow()

    // Settings
    private val _settings = MutableStateFlow(MockData.mockSettings)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // Modals
    private val _isCommandPaletteVisible = MutableStateFlow(false)
    val isCommandPaletteVisible: StateFlow<Boolean> = _isCommandPaletteVisible.asStateFlow()

    private val _isGlobalSearchVisible = MutableStateFlow(false)
    val isGlobalSearchVisible: StateFlow<Boolean> = _isGlobalSearchVisible.asStateFlow()

    private val _isNewProjectDialogVisible = MutableStateFlow(false)
    val isNewProjectDialogVisible: StateFlow<Boolean> = _isNewProjectDialogVisible.asStateFlow()

    private val _isNewSessionDialogVisible = MutableStateFlow(false)
    val isNewSessionDialogVisible: StateFlow<Boolean> = _isNewSessionDialogVisible.asStateFlow()

    private val _isProjectActionSheetVisible = MutableStateFlow(false)
    val isProjectActionSheetVisible: StateFlow<Boolean> = _isProjectActionSheetVisible.asStateFlow()

    private val _selectedProjectForAction = MutableStateFlow<Project?>(null)
    val selectedProjectForAction: StateFlow<Project?> = _selectedProjectForAction.asStateFlow()

    // Global Snackbar
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // Navigation Methods
    fun navigateTo(screen: Screen) {
        if (_currentScreen.value != screen) {
            backStack.add(_currentScreen.value)
            _currentScreen.value = screen
            updateTabForScreen(screen)
        }
    }

    fun navigateBack(): Boolean {
        if (backStack.isNotEmpty()) {
            val previous = backStack.removeAt(backStack.size - 1)
            _currentScreen.value = previous
            updateTabForScreen(previous)
            return true
        }
        return false
    }

    fun selectTab(tab: NavigationTab) {
        _selectedTab.value = tab
        if (_currentScreen.value != tab.screen) {
            backStack.add(_currentScreen.value)
            _currentScreen.value = tab.screen
        }
    }

    private fun updateTabForScreen(screen: Screen) {
        when (screen) {
            is Screen.Server -> _selectedTab.value = NavigationTab.SERVER
            is Screen.Logs -> _selectedTab.value = NavigationTab.LOGS
            is Screen.Terminal -> _selectedTab.value = NavigationTab.TERMINAL
            is Screen.Endpoints -> _selectedTab.value = NavigationTab.ENDPOINTS
            is Screen.Config -> _selectedTab.value = NavigationTab.CONFIG
            else -> {}
        }
    }

    // Server Operations
    fun startServer() {
        viewModelScope.launch {
            _serverConfig.value = _serverConfig.value.copy(status = ServerStatus.STARTING)
            appendLog(LogLevel.INFO, "daemon", "Received start command. Booting OpenCode local daemon...")
            delay(500)
            _serverConfig.value = _serverConfig.value.copy(
                status = ServerStatus.RUNNING,
                pid = 18492 + (1..99).random(),
                activeConnections = 2,
                uptimeSeconds = 0
            )
            appendLog(LogLevel.INFO, "http", "Server bound to ${_serverConfig.value.host}:${_serverConfig.value.port}")
            appendLog(LogLevel.INFO, "daemon", "OpenCode server ready. Telemetry gauges active.")
            showSnackbar("OpenCode daemon is now RUNNING (PID ${_serverConfig.value.pid})")
        }
    }

    fun stopServer() {
        viewModelScope.launch {
            appendLog(LogLevel.WARN, "daemon", "Gracefully terminating daemon PID ${_serverConfig.value.pid}...")
            _serverConfig.value = _serverConfig.value.copy(
                status = ServerStatus.STOPPED,
                pid = 0,
                activeConnections = 0,
                cpuUsagePercent = 0.0f
            )
            appendLog(LogLevel.INFO, "daemon", "Server daemon stopped.")
            showSnackbar("OpenCode server daemon STOPPED")
        }
    }

    fun restartServer() {
        viewModelScope.launch {
            appendLog(LogLevel.WARN, "daemon", "Reloading OpenCode server daemon...")
            _serverConfig.value = _serverConfig.value.copy(status = ServerStatus.STARTING)
            delay(600)
            val newPid = 18500 + (1..99).random()
            _serverConfig.value = _serverConfig.value.copy(
                status = ServerStatus.RUNNING,
                pid = newPid,
                uptimeSeconds = 0,
                cpuUsagePercent = 1.8f
            )
            appendLog(LogLevel.INFO, "daemon", "Restart successful. New daemon PID: $newPid")
            showSnackbar("Server restarted successfully (PID $newPid)")
        }
    }

    fun toggleWakeLock() {
        val current = _serverConfig.value.wakeLockActive
        _serverConfig.value = _serverConfig.value.copy(wakeLockActive = !current)
        val msg = if (!current) "Acquired Android PARTIAL_WAKE_LOCK" else "Released Android wakelock"
        appendLog(LogLevel.INFO, "daemon", msg)
        showSnackbar(if (!current) "Wakelock enabled: Daemon will run in background" else "Wakelock disabled")
    }

    fun toggleLan(allowed: Boolean) {
        val newHost = if (allowed) "0.0.0.0" else "127.0.0.1"
        _serverConfig.value = _serverConfig.value.copy(
            allowLanConnections = allowed,
            host = newHost
        )
        appendLog(LogLevel.INFO, "http", "Updated bind address to $newHost:${_serverConfig.value.port}")
        showSnackbar("Binding updated to $newHost")
    }

    fun updatePort(port: Int) {
        _serverConfig.value = _serverConfig.value.copy(port = port)
        appendLog(LogLevel.INFO, "http", "Updated listener port to $port")
        showSnackbar("Configured port $port")
    }

    // Log Stream Actions
    fun setLogLevelFilter(level: String) {
        _logLevelFilter.value = level
    }

    fun setLogSubsystemFilter(subsystem: String) {
        _logSubsystemFilter.value = subsystem
    }

    fun setLogSearchQuery(query: String) {
        _logSearchQuery.value = query
    }

    fun togglePauseLogs() {
        _isLogStreamPaused.value = !_isLogStreamPaused.value
    }

    fun clearLogs() {
        _serverLogs.value = emptyList()
        showSnackbar("Server logs cleared")
    }

    fun appendLog(level: LogLevel, subsystem: String, message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val entry = ServerLogEntry("log-${System.currentTimeMillis()}", time, level, subsystem, message)
        _serverLogs.value = _serverLogs.value + entry
    }

    // Endpoints Actions
    fun selectEndpoint(endpoint: ServerEndpoint) {
        _selectedEndpoint.value = endpoint
        _endpointTestResult.value = endpoint.responseBody
    }

    fun testEndpoint(endpoint: ServerEndpoint) {
        viewModelScope.launch {
            showSnackbar("Testing ${endpoint.method} ${endpoint.path}...")
            delay(200)
            _selectedEndpoint.value = endpoint
            _endpointTestResult.value = endpoint.responseBody
            appendLog(LogLevel.INFO, "http", "${endpoint.method} ${endpoint.path} -> ${endpoint.status} (${endpoint.latencyMs}ms)")
            showSnackbar("Received status ${endpoint.status} in ${endpoint.latencyMs}ms")
        }
    }

    // Terminal Commands
    fun onTerminalInputChange(value: String) {
        _terminalInput.value = value
    }

    fun executeTerminalCommand(customCmd: String? = null) {
        val cmd = (customCmd ?: _terminalInput.value).trim()
        if (cmd.isEmpty()) return

        val newLines = _terminalLines.value.toMutableList()
        newLines.add("$ $cmd")
        _terminalInput.value = ""

        viewModelScope.launch {
            terminalService.executeCommand(cmd).collect { output ->
                if (output.isNotEmpty()) {
                    newLines.addAll(output.lines())
                }
                _terminalLines.value = newLines
            }
        }
    }

    fun clearTerminal() {
        terminalService.clearHistory()
        _terminalLines.value = listOf("$ ", "Terminal cleared.")
        showSnackbar("Terminal cleared")
    }

    fun copyTerminalOutput() {
        showSnackbar("Terminal output copied to clipboard")
    }

    // Workspace & Sessions (subordinate)
    fun selectProject(projectId: String) {
        projectService.selectProject(projectId)
        val selected = _projects.value.find { it.id == projectId }
        if (selected != null) {
            _currentProject.value = selected
            _projects.value = _projects.value.map {
                it.copy(isCurrent = it.id == projectId)
            }
            appendLog(LogLevel.INFO, "daemon", "Workspace root switched to ${selected.path}")
            showSnackbar("Switched workspace to ${selected.name}")
        }
    }

    fun createProject(name: String, path: String, language: String) {
        val newProj = projectService.createProject(name, path, language)
        _projects.value = listOf(newProj) + _projects.value
        _isNewProjectDialogVisible.value = false
        showSnackbar("Created workspace: $name")
    }

    fun openSession(sessionId: String) {
        val found = _sessions.value.find { it.id == sessionId }
        if (found != null) {
            _currentSession.value = found
            navigateTo(Screen.ChatSession(sessionId))
        }
    }

    fun createNewSession(title: String) {
        val newSession = sessionService.createSession(title)
        _sessions.value = listOf(newSession) + _sessions.value
        _currentSession.value = newSession
        _isNewSessionDialogVisible.value = false
        navigateTo(Screen.ChatSession(newSession.id))
        showSnackbar("Created session: ${newSession.title}")
    }

    fun sendChatMessage(prompt: String) {
        if (prompt.isBlank()) return
        val currentSess = _currentSession.value ?: return

        val userMsg = ChatMessage(
            id = "msg-${System.currentTimeMillis()}",
            role = SenderRole.USER,
            timestamp = "Just now",
            text = prompt.trim()
        )

        val updatedMessages = currentSess.messages + userMsg
        val updatedSession = currentSess.copy(
            messages = updatedMessages,
            messageCount = updatedMessages.size,
            status = SessionStatus.THINKING
        )
        updateSession(updatedSession)

        viewModelScope.launch {
            openCodeService.sendMessage(currentSess.id, prompt).collect { assistantMsg ->
                val finalMessages = updatedSession.messages + assistantMsg
                val finishedSession = updatedSession.copy(
                    messages = finalMessages,
                    messageCount = finalMessages.size,
                    status = SessionStatus.IDLE
                )
                updateSession(finishedSession)
            }
        }
    }

    private fun updateSession(session: Session) {
        _currentSession.value = session
        _sessions.value = _sessions.value.map {
            if (it.id == session.id) session else it
        }
    }

    fun toggleFolder(folderPath: String) {
        val current = _expandedFolders.value.toMutableSet()
        if (current.contains(folderPath)) {
            current.remove(folderPath)
        } else {
            current.add(folderPath)
        }
        _expandedFolders.value = current
    }

    fun openFileInEditor(filePath: String, fileName: String) {
        _activeEditorFilePath.value = filePath
        _activeEditorFileName.value = fileName
        _editorContent.value = fileService.getFileContent(filePath)
        _isEditorModified.value = false
        navigateTo(Screen.CodeEditor(filePath, fileName))
    }

    fun onEditorContentChange(newContent: String) {
        _editorContent.value = newContent
        _isEditorModified.value = true
    }

    fun saveEditorContent() {
        _isEditorModified.value = false
        showSnackbar("Saved ${_activeEditorFileName.value}")
    }

    // Dialogs & Sheets
    fun showCommandPalette(show: Boolean) {
        _isCommandPaletteVisible.value = show
    }

    fun showGlobalSearch(show: Boolean) {
        _isGlobalSearchVisible.value = show
    }

    fun showNewProjectDialog(show: Boolean) {
        _isNewProjectDialogVisible.value = show
    }

    fun showNewSessionDialog(show: Boolean) {
        _isNewSessionDialogVisible.value = show
    }

    fun openProjectActions(project: Project) {
        _selectedProjectForAction.value = project
        _isProjectActionSheetVisible.value = true
    }

    fun dismissProjectActionSheet() {
        _isProjectActionSheetVisible.value = false
        _selectedProjectForAction.value = null
    }

    // Settings
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        _settings.value = transform(_settings.value)
    }

    // Toast/Snackbar
    fun showSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    fun dismissSnackbar() {
        _snackbarMessage.value = null
    }
}
