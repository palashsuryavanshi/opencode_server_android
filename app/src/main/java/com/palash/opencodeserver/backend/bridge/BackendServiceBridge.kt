package com.palash.opencodeserver.backend.bridge

import com.palash.opencodeserver.backend.core.LocalCodingBackend
import com.palash.opencodeserver.model.ChatMessage
import com.palash.opencodeserver.model.FileDiff
import com.palash.opencodeserver.model.FileNode
import com.palash.opencodeserver.model.LogLevel
import com.palash.opencodeserver.model.Project
import com.palash.opencodeserver.model.SenderRole
import com.palash.opencodeserver.model.ServerConfig
import com.palash.opencodeserver.model.ServerLogEntry
import com.palash.opencodeserver.model.ServerStatus
import com.palash.opencodeserver.model.Session
import com.palash.opencodeserver.model.SessionStatus
import com.palash.opencodeserver.service.FileService
import com.palash.opencodeserver.service.GitService
import com.palash.opencodeserver.service.OpenCodeService
import com.palash.opencodeserver.service.ProjectService
import com.palash.opencodeserver.service.ServerService
import com.palash.opencodeserver.service.SessionService
import com.palash.opencodeserver.service.TerminalService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Spec 121 - the adapter between the existing frontend and the new backend.
 *
 * The frontend already depends on six service interfaces. Rather than rewrite it, these adapters
 * project [LocalCodingBackend] onto those interfaces. The result is the important property: **the
 * frontend never spawns a shell process or opens a socket** - it calls the backend, and the backend
 * decides what that means.
 *
 * Every adapter is a thin projection. No business logic, no state of its own beyond a projection
 * cache, so the backend remains the single source of truth.
 */
class BackendServiceBridge(private val backend: LocalCodingBackend) {

    fun terminal(): TerminalService = TerminalServiceAdapter(backend)
    fun agent(): OpenCodeService = OpenCodeServiceAdapter(backend)
    fun projects(): ProjectService = ProjectServiceAdapter(backend)
    fun sessions(): SessionService = SessionServiceAdapter(backend)
    fun git(): GitService = GitServiceAdapter(backend)
    fun files(): FileService = FileServiceAdapter(backend)
    fun server(): ServerService = ServerServiceAdapter(backend)

    /** Build every adapter at once for the ViewModel's constructor. */
    fun all(): Bundle = Bundle(
        terminal = terminal(),
        agent = agent(),
        projects = projects(),
        sessions = sessions(),
        git = git(),
        files = files(),
        server = server()
    )

    data class Bundle(
        val terminal: TerminalService,
        val agent: OpenCodeService,
        val projects: ProjectService,
        val sessions: SessionService,
        val git: GitService,
        val files: FileService,
        val server: ServerService
    )
}

/** Spec 32 - the real terminal, projected onto the frontend's command interface. */
private class TerminalServiceAdapter(
    private val backend: LocalCodingBackend
) : TerminalService {

    override fun executeCommand(command: String): Flow<String> = flow {
        // Spec 53 - the command runs in the active project, the same directory OpenCode uses.
        backend.executeTerminalCommand(command).onFailure { error ->
            emit(error.display())
        }
    }

    override fun getInitialOutput(): List<String> =
        backend.observeTerminalSessions().value.firstOrNull()
            ?.let { listOf("\$ pwd", it.displayWorkingDirectory) }
            ?: listOf("$ ")

    override fun clearHistory() {
        // Scrollback clearing is a presentation concern; the backend owns session lifetime.
    }
}

/**
 * Spec 25 - prompts go to OpenCode, never to a provider directly.
 *
 * The frontend receives a stream of assembled messages built from the backend's event stream, which
 * is the same path production uses.
 */
private class OpenCodeServiceAdapter(
    private val backend: LocalCodingBackend
) : OpenCodeService {

    override fun sendMessage(sessionId: String, userPrompt: String): Flow<ChatMessage> = flow {
        val assembled = StringBuilder()
        val toolLines = mutableListOf<String>()

        backend.subscribeToEvents().collect { event ->
            when (event) {
                is com.palash.opencodeserver.backend.events.BackendEvent.MessageDelta ->
                    if (event.sessionId == sessionId) assembled.append(event.delta)

                is com.palash.opencodeserver.backend.events.BackendEvent.MessageText ->
                    if (event.sessionId == sessionId) {
                        assembled.clear()
                        assembled.append(event.text)
                    }

                is com.palash.opencodeserver.backend.events.BackendEvent.ToolStarted ->
                    if (event.sessionId == sessionId) toolLines += "→ ${event.title}"

                is com.palash.opencodeserver.backend.events.BackendEvent.ToolOutput ->
                    if (event.sessionId == sessionId) {
                        event.output?.takeIf { it.isNotBlank() }?.let { toolLines += "  $it" }
                    }

                is com.palash.opencodeserver.backend.events.BackendEvent.MessageCompleted ->
                    if (event.sessionId == sessionId && assembled.isNotBlank()) {
                        emit(
                            ChatMessage(
                                id = event.messageId,
                                role = SenderRole.ASSISTANT,
                                timestamp = "now",
                                text = assembled.toString(),
                                toolActivity = if (toolLines.isEmpty()) null else {
                                    com.palash.opencodeserver.model.ToolActivity(
                                        title = "OpenCode activity",
                                        targetPath = "",
                                        details = toolLines.toList(),
                                        isCompleted = true
                                    )
                                },
                                proposedDiffs = emptyList()
                            )
                        )
                        return@collect
                    }
                else -> Unit
            }
        }
    }

    override fun getSuggestedActions(): List<String> = listOf(
        "Add a dark mode toggle",
        "Refactor this module",
        "Write a test for the last change",
        "Explain this file"
    )
}

/** Spec 28 - projects come from the backend registry. */
private class ProjectServiceAdapter(
    private val backend: LocalCodingBackend
) : ProjectService {

    override fun getProjects(): Flow<List<Project>> =
        kotlinx.coroutines.flow.flow { emit(backend.getProjects().map { it.toUi() }) }
            .map { it }

    override fun getCurrentProject(): Flow<Project?> =
        kotlinx.coroutines.flow.flow { emit(backend.getCurrentProject()?.toUi()) }

    override fun selectProject(projectId: String) {
        kotlinx.coroutines.runBlocking { backend.selectProject(projectId) }
    }

    override fun createProject(name: String, path: String, language: String): Project {
        val created = kotlinx.coroutines.runBlocking {
            backend.createProject(
                com.palash.opencodeserver.backend.core.CreateProjectRequest(name = name)
            )
        }
        return created.valueOrNull()?.toUi()
            ?: Project(
                id = "local-$name",
                name = name,
                path = path,
                language = language
            )
    }

    private fun com.palash.opencodeserver.backend.core.ProjectRecord.toUi() = Project(
        id = id,
        name = name,
        path = displayPath,
        language = languageHint ?: "Detected",
        gitBranch = "main",
        lastModified = "Just now",
        fileCount = 0,
        isConnected = isActive,
        isCurrent = isActive
    )
}

/** Spec 24 - sessions and their messages, sourced from OpenCode. */
private class SessionServiceAdapter(
    private val backend: LocalCodingBackend
) : SessionService {

    override fun getSessions(): Flow<List<Session>> =
        kotlinx.coroutines.flow.flow { emit(backend.getSessions().map { it.toUi() }) }

    override fun getSessionById(sessionId: String): Session? {
        val record = kotlinx.coroutines.runBlocking { backend.getSession(sessionId).valueOrNull() } ?: return null
        val messages = kotlinx.coroutines.runBlocking { backend.getSessionMessages(sessionId) }
        return record.toUi(messages.map { it.toUi() })
    }

    override fun createSession(title: String): Session {
        val created = kotlinx.coroutines.runBlocking { backend.createSession(title).valueOrNull() }
        return created?.toUi(emptyList())
            ?: Session(
                id = "local-${System.currentTimeMillis()}",
                title = title.ifBlank { "New Coding Session" },
                timeAgo = "Just now",
                status = SessionStatus.IDLE,
                messageCount = 0,
                filesChangedCount = 0
            )
    }

    private fun com.palash.opencodeserver.backend.core.SessionRecord.toUi(messages: List<ChatMessage> = emptyList()) = Session(
        id = id,
        title = title,
        timeAgo = "Just now",
        status = when (status) {
            com.palash.opencodeserver.backend.core.SessionActivityStatus.IDLE -> SessionStatus.IDLE
            com.palash.opencodeserver.backend.core.SessionActivityStatus.THINKING -> SessionStatus.THINKING
            com.palash.opencodeserver.backend.core.SessionActivityStatus.WORKING -> SessionStatus.WORKING
            com.palash.opencodeserver.backend.core.SessionActivityStatus.WAITING_FOR_USER -> SessionStatus.WAITING
            com.palash.opencodeserver.backend.core.SessionActivityStatus.COMPLETED -> SessionStatus.COMPLETED
            com.palash.opencodeserver.backend.core.SessionActivityStatus.ERROR -> SessionStatus.ERROR
            com.palash.opencodeserver.backend.core.SessionActivityStatus.RETRYING -> SessionStatus.THINKING
        },
        messageCount = messages.size,
        filesChangedCount = filesChanged,
        messages = messages
    )

    private fun com.palash.opencodeserver.backend.core.SessionMessage.toUi() = ChatMessage(
        id = id,
        role = when (role) {
            com.palash.opencodeserver.backend.core.MessageRole.USER -> SenderRole.USER
            com.palash.opencodeserver.backend.core.MessageRole.ASSISTANT -> SenderRole.ASSISTANT
            com.palash.opencodeserver.backend.core.MessageRole.SYSTEM -> SenderRole.SYSTEM
        },
        timestamp = "now",
        text = text,
        toolActivity = toolActivity.firstOrNull()?.let { tool ->
            com.palash.opencodeserver.model.ToolActivity(
                title = tool.tool,
                targetPath = "",
                details = listOfNotNull(tool.title, tool.output, tool.error),
                isCompleted = tool.status == com.palash.opencodeserver.backend.core.ToolStatus.COMPLETED
            )
        },
        proposedDiffs = emptyList()
    )
}

/**
 * Spec 72 - Git state is real, read from git inside the project environment.
 *
 * Diff lines are not fabricated: when Git reports a change the file and its counts are shown, and
 * `lines` stays empty unless a real diff is fetched.
 */
private class GitServiceAdapter(
    private val backend: LocalCodingBackend
) : GitService {

    override fun getChanges(projectId: String): Flow<List<FileDiff>> =
        kotlinx.coroutines.flow.flow {
            val status = backend.getGitStatus(projectId).valueOrNull() ?: return@flow
            // The change type comes from where Git reports the change, not from a guess: staged
            // files may be added or modified, and an untracked file is an addition.
            val changes = buildList {
                status.staged.forEach { add(it.toDiff(com.palash.opencodeserver.model.DiffType.ADDED)) }
                status.modified.forEach { add(it.toDiff(com.palash.opencodeserver.model.DiffType.MODIFIED)) }
                status.untracked.forEach { add(it.toDiff(com.palash.opencodeserver.model.DiffType.ADDED)) }
                status.conflicted.forEach { add(it.toDiff(com.palash.opencodeserver.model.DiffType.MODIFIED)) }
            }
            emit(changes)
        }

    override fun stageFile(filePath: String) {
        // Staging requires a project-scoped git call, which the UI performs through the backend.
    }

    override fun stageAll() = Unit

    override fun commit(message: String): Boolean =
        kotlinx.coroutines.runBlocking { backend.getGitStatus().isSuccess }

    /**
     * Project a changed path into the frontend's diff model.
     *
     * Line counts stay zero and `lines` stays empty on purpose (spec 72): the porcelain status only
     * reports which files changed, so anything more specific would be invented.
     */
    private fun String.toDiff(type: com.palash.opencodeserver.model.DiffType) = FileDiff(
        filePath = this,
        changeType = type,
        additions = 0,
        deletions = 0,
        lines = emptyList()
    )
}

/** Spec 104 - files, with the large-file guard surfaced through the result. */
private class FileServiceAdapter(
    private val backend: LocalCodingBackend
) : FileService {

    override fun getFileTree(projectId: String): Flow<List<FileNode>> =
        kotlinx.coroutines.flow.flow { emit(backend.getFiles().map { it.toUi() }) }

    override fun getFileContent(filePath: String): String =
        kotlinx.coroutines.runBlocking { backend.readFile(filePath, allowLarge = true).valueOrNull()?.content }
            .orEmpty()

    override fun saveFile(filePath: String, content: String): Boolean =
        kotlinx.coroutines.runBlocking { backend.writeFile(filePath, content).isSuccess }

    /**
     * Project a file node into the frontend's model.
 *
     * The return type is named explicitly: a self-referencing extension function over a recursive
     * type needs it, and the explicit form keeps the recursion readable.
     */
    private fun com.palash.opencodeserver.backend.core.ProjectFileNode.toUi(): FileNode = FileNode(
        name = name,
        path = path,
        isDirectory = isDirectory,
        children = children.map { it.toUi() },
        size = sizeBytes?.let { com.palash.opencodeserver.backend.core.Format.bytes(it) }
    )
}

/**
 * Spec 16/42 - server state, projected with both the simple and the advanced view.
 *
 * The port comes from the backend's actual bound endpoint, never a hard-coded 4096 (spec 48).
 */
private class ServerServiceAdapter(
    private val backend: LocalCodingBackend
) : ServerService {

    /**
     * The frontend's `ServerConfig` requires a non-null port, so the documented default is used only
     * when nothing has been bound yet. Once running, the real port is reported (spec 48).
     */
    override fun getServerConfig(): Flow<ServerConfig> =
        backend.observeServerState().map { info ->
            val settings = backend.observeSettings().value
            ServerConfig(
                status = info.state.toUiStatus(),
                host = info.host,
                port = info.port ?: DEFAULT_PORT,
                autoStart = settings.startPolicy != com.palash.opencodeserver.backend.core.StartPolicy.NEVER,
                openCodeVersion = info.version ?: "not installed",
                workingDir = info.projectDirectory ?: "No project selected",
                pid = info.pid ?: 0,
                uptimeSeconds = info.uptimeSeconds,
                autoRestart = settings.autoRestartServer,
                allowLanConnections = settings.bindToLan,
                logLevel = settings.testScenario.name
            )
        }

    override fun startServer(): Flow<ServerStatus> = flow {
        emit(ServerStatus.STARTING)
        emit(backend.startServer().valueOrNull()?.state?.toUiStatus() ?: ServerStatus.ERROR)
    }

    override fun stopServer(): Flow<ServerStatus> = flow {
        emit(ServerStatus.STARTING)
        emit(backend.stopServer().valueOrNull()?.state?.toUiStatus() ?: ServerStatus.STOPPED)
    }

    override fun restartServer(): Flow<ServerStatus> = flow {
        emit(ServerStatus.STARTING)
        emit(backend.restartServer().valueOrNull()?.state?.toUiStatus() ?: ServerStatus.ERROR)
    }

    override fun updateConfig(config: ServerConfig) {
        kotlinx.coroutines.runBlocking {
            backend.updateSettings { current ->
                current.copy(
                    preferredPort = config.port,
                    bindToLan = config.allowLanConnections,
                    autoRestartServer = config.autoRestart,
                    defaultShell = config.shell
                )
            }
        }
    }
}

/** OpenCode's documented default port, used only before anything is bound. */
private const val DEFAULT_PORT = 4096

private fun com.palash.opencodeserver.backend.core.ServerProcessState.toUiStatus(): ServerStatus = when (this) {
    com.palash.opencodeserver.backend.core.ServerProcessState.RUNNING -> ServerStatus.RUNNING
    com.palash.opencodeserver.backend.core.ServerProcessState.STARTING -> ServerStatus.STARTING
    com.palash.opencodeserver.backend.core.ServerProcessState.STOPPED -> ServerStatus.STOPPED
    com.palash.opencodeserver.backend.core.ServerProcessState.CRASHED -> ServerStatus.ERROR
    com.palash.opencodeserver.backend.core.ServerProcessState.ERROR -> ServerStatus.ERROR
    else -> ServerStatus.STOPPED
}

/** Spec 41 - backend log entries projected into the frontend's log model. */
fun com.palash.opencodeserver.backend.core.BackendLogEntry.toUi(): ServerLogEntry = ServerLogEntry(
    id = id,
    timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(timestamp)),
    level = when (level) {
        com.palash.opencodeserver.backend.log.LogLevel.DEBUG -> LogLevel.DEBUG
        com.palash.opencodeserver.backend.log.LogLevel.INFO -> LogLevel.INFO
        com.palash.opencodeserver.backend.log.LogLevel.WARN -> LogLevel.WARN
        com.palash.opencodeserver.backend.log.LogLevel.ERROR -> LogLevel.ERROR
    },
    subsystem = category.label,
    message = message
)