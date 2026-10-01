package com.palash.opencodeserver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.palash.opencodeserver.model.ConnectionStatus
import com.palash.opencodeserver.ui.components.*
import com.palash.opencodeserver.ui.navigation.NavigationTab
import com.palash.opencodeserver.ui.navigation.Screen
import com.palash.opencodeserver.ui.screens.*
import com.palash.opencodeserver.ui.state.OpenCodeViewModel
import com.palash.opencodeserver.ui.theme.DevBackground
import com.palash.opencodeserver.ui.theme.DevSurface
import com.palash.opencodeserver.ui.theme.OpenCodeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: OpenCodeViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            OpenCodeTheme(darkTheme = settings.darkMode) {
                OpenCodeApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun OpenCodeApp(viewModel: OpenCodeViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val serverConfig by viewModel.serverConfig.collectAsStateWithLifecycle()
    val serverLogs by viewModel.serverLogs.collectAsStateWithLifecycle()
    val logLevelFilter by viewModel.logLevelFilter.collectAsStateWithLifecycle()
    val logSubsystemFilter by viewModel.logSubsystemFilter.collectAsStateWithLifecycle()
    val logSearchQuery by viewModel.logSearchQuery.collectAsStateWithLifecycle()
    val isLogStreamPaused by viewModel.isLogStreamPaused.collectAsStateWithLifecycle()
    val endpoints by viewModel.endpoints.collectAsStateWithLifecycle()
    val selectedEndpoint by viewModel.selectedEndpoint.collectAsStateWithLifecycle()
    val endpointTestResult by viewModel.endpointTestResult.collectAsStateWithLifecycle()
    val healthChecks by viewModel.healthChecks.collectAsStateWithLifecycle()

    val currentProject by viewModel.currentProject.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val currentSession by viewModel.currentSession.collectAsStateWithLifecycle()
    val terminalLines by viewModel.terminalLines.collectAsStateWithLifecycle()
    val terminalInput by viewModel.terminalInput.collectAsStateWithLifecycle()
    val fileTree by viewModel.fileTree.collectAsStateWithLifecycle()
    val expandedFolders by viewModel.expandedFolders.collectAsStateWithLifecycle()
    val activeFilePath by viewModel.activeEditorFilePath.collectAsStateWithLifecycle()
    val activeFileName by viewModel.activeEditorFileName.collectAsStateWithLifecycle()
    val editorContent by viewModel.editorContent.collectAsStateWithLifecycle()
    val isEditorModified by viewModel.isEditorModified.collectAsStateWithLifecycle()
    val gitChanges by viewModel.gitChanges.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // Dialog & Sheet States
    val isCommandPaletteVisible by viewModel.isCommandPaletteVisible.collectAsStateWithLifecycle()
    val isGlobalSearchVisible by viewModel.isGlobalSearchVisible.collectAsStateWithLifecycle()
    val isProjectActionSheetVisible by viewModel.isProjectActionSheetVisible.collectAsStateWithLifecycle()
    val selectedProjectForAction by viewModel.selectedProjectForAction.collectAsStateWithLifecycle()

    // Snackbar Host
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMsg by viewModel.snackbarMessage.collectAsStateWithLifecycle()

    LaunchedEffect(snackbarMsg) {
        snackbarMsg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSnackbar()
        }
    }

    // Android System Back Navigation Handler
    val isSubScreen = currentScreen !is Screen.Server &&
            currentScreen !is Screen.Logs &&
            currentScreen !is Screen.Terminal &&
            currentScreen !is Screen.Endpoints &&
            currentScreen !is Screen.Config

    BackHandler(enabled = isSubScreen) {
        viewModel.navigateBack()
    }

    // Determine TopBar Title & Subtitle
    val (topTitle, topSubtitle) = when (currentScreen) {
        is Screen.Server -> "OpenCode Server" to "Daemon Control & Telemetry"
        is Screen.Logs -> "Daemon Logs" to "Live stdout/stderr stream"
        is Screen.Terminal -> "PTY Terminal" to "opencodectl shell"
        is Screen.Endpoints -> "Server API" to "REST & WebSocket routes"
        is Screen.Config -> "Daemon Config" to "127.0.0.1:4096"
        is Screen.Projects -> "Workspaces" to "${projects.size} registered"
        is Screen.Sessions -> "Coding Sessions" to "AI Assistant Threads"
        is Screen.Settings -> "App Settings" to "Preferences"
        is Screen.ProjectDetail -> (currentProject?.name ?: "Project Detail") to "Overview"
        is Screen.FileExplorer -> "Files" to (currentProject?.path ?: "~/projects/demo-project")
        is Screen.CodeEditor -> activeFileName to activeFilePath
        is Screen.ChatSession -> "AI Session" to (currentSession?.title ?: "OpenCode")
        is Screen.GitChanges -> "Changes" to "${gitChanges.size} modified files"
    }

    val showBottomBar = currentScreen is Screen.Server ||
            currentScreen is Screen.Logs ||
            currentScreen is Screen.Terminal ||
            currentScreen is Screen.Endpoints ||
            currentScreen is Screen.Config

    Scaffold(
        containerColor = DevBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopBar(
                title = topTitle,
                subtitle = topSubtitle,
                showBackButton = isSubScreen,
                connectionStatus = connectionStatus,
                onBackClick = { viewModel.navigateBack() },
                onCommandPaletteClick = { viewModel.showCommandPalette(true) },
                onSearchClick = { viewModel.showGlobalSearch(true) },
                onSettingsClick = { viewModel.navigateTo(Screen.Settings) }
            )
        },
        bottomBar = {
            if (showBottomBar) {
                AppBottomNavigation(
                    selectedTab = selectedTab,
                    onTabSelected = { tab ->
                        viewModel.selectTab(tab)
                    }
                )
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = if (showBottomBar) 70.dp else 16.dp)
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = DevSurface,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                )
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(DevBackground)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(160))
                },
                label = "screen_transition"
            ) { target ->
                when (target) {
                    is Screen.Server -> {
                        ServerDashboardScreen(
                            serverConfig = serverConfig,
                            healthChecks = healthChecks,
                            recentLogs = serverLogs,
                            currentProject = currentProject,
                            onStartServer = { viewModel.startServer() },
                            onStopServer = { viewModel.stopServer() },
                            onRestartServer = { viewModel.restartServer() },
                            onOpenLogs = { viewModel.selectTab(NavigationTab.LOGS) },
                            onOpenTerminal = { viewModel.selectTab(NavigationTab.TERMINAL) },
                            onOpenEndpoints = { viewModel.selectTab(NavigationTab.ENDPOINTS) },
                            onOpenConfig = { viewModel.selectTab(NavigationTab.CONFIG) },
                            onOpenWorkspaces = { viewModel.navigateTo(Screen.Projects) }
                        )
                    }

                    is Screen.Logs -> {
                        ServerLogsScreen(
                            logs = serverLogs,
                            logLevelFilter = logLevelFilter,
                            subsystemFilter = logSubsystemFilter,
                            searchQuery = logSearchQuery,
                            isPaused = isLogStreamPaused,
                            onLogLevelSelected = { viewModel.setLogLevelFilter(it) },
                            onSubsystemSelected = { viewModel.setLogSubsystemFilter(it) },
                            onSearchQueryChange = { viewModel.setLogSearchQuery(it) },
                            onTogglePause = { viewModel.togglePauseLogs() },
                            onClearLogs = { viewModel.clearLogs() },
                            onCopyLogs = { viewModel.showSnackbar("Logs copied to clipboard") }
                        )
                    }

                    is Screen.Terminal -> {
                        TerminalScreen(
                            projectName = "opencode-daemon (PID ${serverConfig.pid})",
                            terminalLines = terminalLines,
                            inputCommand = terminalInput,
                            connectionStatus = connectionStatus,
                            onInputChange = { viewModel.onTerminalInputChange(it) },
                            onSendCommand = { cmd -> viewModel.executeTerminalCommand(cmd) },
                            onClearTerminal = { viewModel.clearTerminal() },
                            onCopyOutput = { viewModel.copyTerminalOutput() }
                        )
                    }

                    is Screen.Endpoints -> {
                        EndpointsScreen(
                            endpoints = endpoints,
                            selectedEndpoint = selectedEndpoint,
                            testResult = endpointTestResult,
                            onTestEndpoint = { ep -> viewModel.testEndpoint(ep) }
                        )
                    }

                    is Screen.Config -> {
                        ServerConfigScreen(
                            config = serverConfig,
                            onStartServer = { viewModel.startServer() },
                            onStopServer = { viewModel.stopServer() },
                            onRestartServer = { viewModel.restartServer() },
                            onToggleWakeLock = { viewModel.toggleWakeLock() },
                            onToggleLan = { viewModel.toggleLan(it) },
                            onUpdatePort = { viewModel.updatePort(it) },
                            onUpdateAutoStart = { auto ->
                                viewModel.showSnackbar("Auto-start ${if (auto) "enabled" else "disabled"}")
                            }
                        )
                    }

                    is Screen.Projects -> {
                        ProjectsScreen(
                            projects = projects,
                            onSelectProject = { projectId ->
                                viewModel.selectProject(projectId)
                                viewModel.navigateTo(Screen.ProjectDetail(projectId))
                            },
                            onOpenProjectActions = { proj ->
                                viewModel.openProjectActions(proj)
                            },
                            onCreateProject = { name, path, lang ->
                                viewModel.createProject(name, path, lang)
                            }
                        )
                    }

                    is Screen.ProjectDetail -> {
                        val proj = currentProject ?: projects.first()
                        ProjectDetailScreen(
                            project = proj,
                            onOpenFiles = { viewModel.navigateTo(Screen.FileExplorer(proj.id)) },
                            onOpenTerminal = { viewModel.navigateTo(Screen.Terminal) },
                            onOpenSessions = { viewModel.navigateTo(Screen.Sessions) },
                            onOpenChanges = { viewModel.navigateTo(Screen.GitChanges(proj.id)) }
                        )
                    }

                    is Screen.FileExplorer -> {
                        FileExplorerScreen(
                            currentPath = currentProject?.path ?: "~/projects/demo-project",
                            files = fileTree,
                            expandedFolders = expandedFolders,
                            onToggleFolder = { viewModel.toggleFolder(it) },
                            onOpenFile = { path, name ->
                                viewModel.openFileInEditor(path, name)
                            }
                        )
                    }

                    is Screen.CodeEditor -> {
                        CodeEditorScreen(
                            fileName = activeFileName,
                            filePath = activeFilePath,
                            content = editorContent,
                            isModified = isEditorModified,
                            onContentChange = { viewModel.onEditorContentChange(it) },
                            onSave = { viewModel.saveEditorContent() }
                        )
                    }

                    is Screen.Sessions -> {
                        SessionsScreen(
                            sessions = sessions,
                            onOpenSession = { sessionId ->
                                viewModel.openSession(sessionId)
                            },
                            onCreateSession = { title ->
                                viewModel.createNewSession(title)
                            }
                        )
                    }

                    is Screen.ChatSession -> {
                        val session = currentSession ?: sessions.first()
                        ChatSessionScreen(
                            session = session,
                            onSendMessage = { prompt ->
                                viewModel.sendChatMessage(prompt)
                            },
                            onOpenTerminal = { viewModel.navigateTo(Screen.Terminal) },
                            onViewChanges = {
                                currentProject?.let { viewModel.navigateTo(Screen.GitChanges(it.id)) }
                            },
                            onOpenFile = { path, name ->
                                viewModel.openFileInEditor(path, name)
                            }
                        )
                    }

                    is Screen.GitChanges -> {
                        GitChangesScreen(
                            gitChanges = gitChanges,
                            onStageFile = { file ->
                                viewModel.showSnackbar("Staged $file")
                            },
                            onStageAll = {
                                viewModel.showSnackbar("Staged all ${gitChanges.size} files")
                            },
                            onCommit = { msg ->
                                viewModel.showSnackbar("Committed: '$msg' (Demo)")
                            }
                        )
                    }

                    is Screen.Settings -> {
                        SettingsScreen(
                            settings = settings,
                            onUpdateSettings = { transform ->
                                viewModel.updateSettings(transform)
                            },
                            onOpenServerConfig = { viewModel.selectTab(NavigationTab.CONFIG) }
                        )
                    }
                }
            }
        }
    }

    // Command Palette Dialog Overlay
    CommandPaletteDialog(
        isVisible = isCommandPaletteVisible,
        onDismiss = { viewModel.showCommandPalette(false) },
        onOpenTerminal = { viewModel.selectTab(NavigationTab.TERMINAL) },
        onOpenProjects = { viewModel.navigateTo(Screen.Projects) },
        onNewSession = { viewModel.showNewSessionDialog(true) },
        onStartServer = { viewModel.startServer() },
        onStopServer = { viewModel.stopServer() },
        onSearchFiles = {
            currentProject?.let { viewModel.navigateTo(Screen.FileExplorer(it.id)) }
        },
        onOpenSettings = { viewModel.selectTab(NavigationTab.CONFIG) },
        onOpenChanges = {
            currentProject?.let { viewModel.navigateTo(Screen.GitChanges(it.id)) }
        }
    )

    // Global Search Overlay
    GlobalSearchDialog(
        isVisible = isGlobalSearchVisible,
        projects = projects,
        sessions = sessions,
        onDismiss = { viewModel.showGlobalSearch(false) },
        onOpenFile = { path, name ->
            viewModel.openFileInEditor(path, name)
        },
        onOpenSession = { sessionId ->
            viewModel.openSession(sessionId)
        },
        onSelectProject = { projectId ->
            viewModel.selectProject(projectId)
            viewModel.navigateTo(Screen.ProjectDetail(projectId))
        }
    )

    // Project Actions Bottom Sheet
    ProjectActionBottomSheet(
        project = selectedProjectForAction,
        isVisible = isProjectActionSheetVisible,
        onDismiss = { viewModel.dismissProjectActionSheet() },
        onOpenProject = { proj ->
            viewModel.selectProject(proj.id)
            viewModel.navigateTo(Screen.ProjectDetail(proj.id))
        },
        onOpenTerminal = { proj ->
            viewModel.selectProject(proj.id)
            viewModel.selectTab(NavigationTab.TERMINAL)
        },
        onOpenFiles = { proj ->
            viewModel.selectProject(proj.id)
            viewModel.navigateTo(Screen.FileExplorer(proj.id))
        },
        onViewChanges = { proj ->
            viewModel.selectProject(proj.id)
            viewModel.navigateTo(Screen.GitChanges(proj.id))
        }
    )
}
