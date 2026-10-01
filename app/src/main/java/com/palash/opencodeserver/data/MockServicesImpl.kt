package com.palash.opencodeserver.data

import com.palash.opencodeserver.model.*
import com.palash.opencodeserver.service.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

class MockTerminalService : TerminalService {
    private val history = mutableListOf<String>()

    init {
        history.addAll(MockData.mockTerminalOutput)
    }

    override fun executeCommand(command: String): Flow<String> = flow {
        val trimmed = command.trim()
        val response = when {
            trimmed == "clear" -> {
                history.clear()
                ""
            }
            trimmed.startsWith("ls") -> {
                "src  components  public  package.json  README.md  vite.config.ts"
            }
            trimmed == "pwd" -> {
                "/home/dev/projects/demo-project"
            }
            trimmed == "git status" -> {
                "On branch main\nChanges not staged for commit:\n  modified:   src/App.tsx\n  modified:   src/components/Settings.tsx"
            }
            trimmed == "opencode" || trimmed == "opencode help" -> {
                "OpenCode Mobile AI Assistant (Demo Mode)\nCommands: opencode status, opencode run, opencode diff, opencode session"
            }
            trimmed == "opencode status" -> {
                "Server: 127.0.0.1:4096 [Demo Mode]\nSession: Frontend redesign (24 messages)\nGit: 4 files modified"
            }
            trimmed.startsWith("npm") -> {
                "> demo-project@1.0.0 $trimmed\nMock command executed successfully in demo environment."
            }
            else -> {
                "Command '$trimmed' executed in demo shell environment (simulated)."
            }
        }
        emit(response)
    }

    override fun getInitialOutput(): List<String> = history.toList()

    override fun clearHistory() {
        history.clear()
    }
}

class MockOpenCodeService : OpenCodeService {
    override fun sendMessage(sessionId: String, userPrompt: String): Flow<ChatMessage> = flow {
        delay(600)
        val assistantMsg = ChatMessage(
            id = "msg-${System.currentTimeMillis()}",
            role = SenderRole.ASSISTANT,
            timestamp = "Just now",
            text = "I've reviewed your request: '$userPrompt'. In this prototype environment, code updates are staged for inspection.",
            toolActivity = ToolActivity(
                title = "Inspecting project tree",
                targetPath = "src/App.tsx",
                details = listOf(
                    "Identified matching code targets",
                    "Simulated AST transformations",
                    "Diffs available in Changes tab"
                ),
                isCompleted = true
            )
        )
        emit(assistantMsg)
    }

    override fun getSuggestedActions(): List<String> = listOf(
        "Add dark mode toggle",
        "Refactor Navigation component",
        "Create mobile test case",
        "Check git status"
    )
}

class MockProjectService : ProjectService {
    private val projectsFlow = MutableStateFlow(MockData.mockProjects)
    private val currentProjectFlow = MutableStateFlow<Project?>(MockData.mockProjects.first())

    override fun getProjects(): Flow<List<Project>> = projectsFlow.asStateFlow()

    override fun getCurrentProject(): Flow<Project?> = currentProjectFlow.asStateFlow()

    override fun selectProject(projectId: String) {
        val list = projectsFlow.value.map {
            it.copy(isCurrent = it.id == projectId)
        }
        projectsFlow.value = list
        currentProjectFlow.value = list.find { it.id == projectId }
    }

    override fun createProject(name: String, path: String, language: String): Project {
        val newProj = Project(
            id = "proj-${System.currentTimeMillis()}",
            name = name,
            path = path,
            language = language,
            gitBranch = "main",
            lastModified = "Just now",
            fileCount = 1,
            isConnected = true,
            isCurrent = false
        )
        projectsFlow.value = listOf(newProj) + projectsFlow.value
        return newProj
    }
}

class MockSessionService : SessionService {
    private val sessionsFlow = MutableStateFlow(MockData.mockSessions)

    override fun getSessions(): Flow<List<Session>> = sessionsFlow.asStateFlow()

    override fun getSessionById(sessionId: String): Session? {
        return sessionsFlow.value.find { it.id == sessionId }
    }

    override fun createSession(title: String): Session {
        val newSession = Session(
            id = "sess-${System.currentTimeMillis()}",
            title = if (title.isBlank()) "New Coding Session" else title,
            timeAgo = "Just now",
            status = SessionStatus.IDLE,
            messageCount = 1,
            filesChangedCount = 0,
            messages = listOf(
                ChatMessage(
                    id = "msg-${System.currentTimeMillis()}",
                    role = SenderRole.ASSISTANT,
                    timestamp = "Just now",
                    text = "Hello! I am OpenCode, your local AI coding assistant. What would you like to build or modify in demo-project?"
                )
            )
        )
        sessionsFlow.value = listOf(newSession) + sessionsFlow.value
        return newSession
    }
}

class MockGitService : GitService {
    private val changesFlow = MutableStateFlow(MockData.mockGitChanges)

    override fun getChanges(projectId: String): Flow<List<FileDiff>> = changesFlow.asStateFlow()

    override fun stageFile(filePath: String) {
        // Mock stage
    }

    override fun stageAll() {
        // Mock stage all
    }

    override fun commit(message: String): Boolean {
        return true
    }
}

class MockFileService : FileService {
    private val treeFlow = MutableStateFlow(MockData.mockFileTree)

    override fun getFileTree(projectId: String): Flow<List<FileNode>> = treeFlow.asStateFlow()

    override fun getFileContent(filePath: String): String {
        fun search(nodes: List<FileNode>): String? {
            for (node in nodes) {
                if (node.path == filePath) return node.content
                if (node.isDirectory) {
                    val found = search(node.children)
                    if (found != null) return found
                }
            }
            return null
        }
        return search(treeFlow.value) ?: "// File content for $filePath\n\nexport const placeholder = true;\n"
    }

    override fun saveFile(filePath: String, content: String): Boolean {
        return true
    }
}

class MockServerService : ServerService {
    private val configFlow = MutableStateFlow(MockData.mockServerConfig)

    override fun getServerConfig(): Flow<ServerConfig> = configFlow.asStateFlow()

    override fun startServer(): Flow<ServerStatus> = flow {
        emit(ServerStatus.STARTING)
        delay(800)
        val newConfig = configFlow.value.copy(status = ServerStatus.DEMO)
        configFlow.value = newConfig
        emit(ServerStatus.DEMO)
    }

    override fun stopServer(): Flow<ServerStatus> = flow {
        val newConfig = configFlow.value.copy(status = ServerStatus.STOPPED)
        configFlow.value = newConfig
        emit(ServerStatus.STOPPED)
    }

    override fun restartServer(): Flow<ServerStatus> = flow {
        emit(ServerStatus.STARTING)
        delay(600)
        val newConfig = configFlow.value.copy(status = ServerStatus.DEMO)
        configFlow.value = newConfig
        emit(ServerStatus.DEMO)
    }

    override fun updateConfig(config: ServerConfig) {
        configFlow.value = config
    }
}
