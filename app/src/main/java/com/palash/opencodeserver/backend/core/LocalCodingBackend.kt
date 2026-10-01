package com.palash.opencodeserver.backend.core

import com.palash.opencodeserver.backend.events.BackendEvent
import com.palash.opencodeserver.backend.terminal.TerminalSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Spec 79 + spec 111 - the single application-facing backend API.
 *
 * The frontend depends on this interface only. It never spawns a shell process, never opens a
 * socket to the local server and never touches credentials directly. Two implementations exist:
 * `RealLocalBackend` (spec 80 production) and `MockBackend` (spec 80/114 development).
 */
interface LocalCodingBackend {

    // ---- lifecycle -------------------------------------------------------------------

    /** Spec 111 - aggregate status used by the dashboard. */
    suspend fun getStatus(): BackendStatus

    /** Spec 6/99/100 - idempotent setup. Resumes from the last reached checkpoint. */
    suspend fun setup(force: Boolean = false): SetupProgress

    /**
     * Spec 6 - setup progress.
     *
     * Nullable because there is nothing to report before setup has run. A state flow rather than a
     * shared flow so a subscriber that arrives mid-wizard still sees the current step instead of
     * waiting for the next one.
     */
    fun observeSetupProgress(): StateFlow<SetupProgress?>

    /**
     * Spec 44/58 - reset the development environment.
     *
     * Destructive, so [confirm] must be true. Returns a typed error rather than throwing, and refuses
     * without confirmation rather than assuming consent.
     */
    suspend fun resetEnvironment(confirm: Boolean): BackendResult<Unit>

    /** Spec 117 - targeted repair without a full reset. */
    suspend fun repair(action: RepairAction): SetupProgress

    // ---- server ----------------------------------------------------------------------

    suspend fun startServer(): BackendResult<ServerInfo>

    suspend fun stopServer(): BackendResult<ServerInfo>

    suspend fun restartServer(): BackendResult<ServerInfo>

    fun observeServerState(): StateFlow<ServerInfo>

    suspend fun getServerStatus(): ServerInfo

    suspend fun getServerAdvancedDetails(): ServerAdvancedDetails

    suspend fun configureServer(configuration: ServerConfiguration): BackendResult<ServerInfo>

    /** Spec 38/95 - discovery + health verification of an already running server. */
    suspend fun discoverRunningServer(): BackendResult<ServerInfo>

    // ---- projects --------------------------------------------------------------------

    suspend fun getProjects(): List<ProjectRecord>

    fun observeProjects(): StateFlow<List<ProjectRecord>>

    suspend fun getCurrentProject(): ProjectRecord?

    suspend fun selectProject(projectId: String): BackendResult<ProjectRecord>

    suspend fun createProject(request: CreateProjectRequest): BackendResult<ProjectRecord>

    suspend fun renameProject(projectId: String, newName: String): BackendResult<ProjectRecord>

    /** Spec 28 - removes the app registration only; user files are never deleted here. */
    suspend fun removeProject(projectId: String): BackendResult<Unit>

    suspend fun deleteProjectFiles(projectId: String, confirm: Boolean): BackendResult<Unit>

    // ---- files -----------------------------------------------------------------------

    suspend fun getFiles(relativePath: String = "", depth: Int = 1): List<ProjectFileNode>

    suspend fun readFile(relativePath: String, allowLarge: Boolean = false): BackendResult<FileContentResult>

    suspend fun writeFile(relativePath: String, content: String): BackendResult<Unit>

    /** Spec 71 - watches only the active project directory. */
    fun observeFileChanges(): Flow<ProjectFileChange>

    // ---- sessions / messages ----------------------------------------------------------

    suspend fun getSessions(): List<SessionRecord>

    suspend fun getSession(sessionId: String): BackendResult<SessionRecord>

    suspend fun createSession(title: String? = null): BackendResult<SessionRecord>

    suspend fun continueSession(sessionId: String, prompt: String): BackendResult<Unit>

    suspend fun abortSession(sessionId: String): BackendResult<Unit>

    suspend fun deleteSession(sessionId: String): BackendResult<Unit>

    suspend fun getSessionMessages(sessionId: String): List<SessionMessage>

    /**
     * Spec 25/69 - sends a prompt and returns immediately. Incremental output arrives on
     * [subscribeToEvents]; the app never talks to the LLM provider directly.
     */
    suspend fun sendMessage(
        sessionId: String,
        prompt: String,
        model: ModelSelection? = null,
        agent: String? = null
    ): BackendResult<Unit>

    // ---- terminal --------------------------------------------------------------------

    suspend fun openTerminal(workingDirectory: String? = null): BackendResult<TerminalSession>

    suspend fun executeTerminalCommand(command: String, workingDirectory: String? = null): BackendResult<Unit>

    fun observeTerminalSessions(): StateFlow<List<TerminalSession>>

    // ---- git -------------------------------------------------------------------------

    suspend fun getGitStatus(projectId: String? = null): BackendResult<GitStatus>

    // ---- events ----------------------------------------------------------------------

    /** Spec 22 - single multiplexed stream of everything the UI needs. */
    fun subscribeToEvents(): Flow<BackendEvent>

    fun observeConnection(): StateFlow<OpenCodeConnection?>

    fun observeState(): StateFlow<AppState>

    // ---- credentials / provider ------------------------------------------------------

    suspend fun listProviders(): List<ProviderDescriptor>

    suspend fun saveProviderCredential(providerId: String, apiKey: String): BackendResult<Unit>

    suspend fun removeProviderCredential(providerId: String): BackendResult<Unit>

    suspend fun maskedCredential(providerId: String): String?

    /** Spec 26/78 - OpenCode remains the source of truth for provider configuration. */
    fun observeProviderHealth(): StateFlow<ProviderHealth>

    // ---- diagnostics -----------------------------------------------------------------

    suspend fun getDiagnostics(includeLogs: Boolean = true): DiagnosticsReport

    suspend fun runHealthCheck(): List<HealthCheckResult>

    suspend fun getTroubleshooting(): List<TroubleshootingSuggestion>

    // ---- updates / metrics -----------------------------------------------------------

    suspend fun checkForUpdates(): List<UpdateAvailability>

    suspend fun applyUpdate(component: String, confirm: Boolean): BackendResult<Unit>

    fun observeMetrics(): Flow<RuntimeMetrics>

    suspend fun getConnectivity(): ConnectivityReport

    // ---- settings / backup -----------------------------------------------------------

    suspend fun updateSettings(transform: (BackendSettings) -> BackendSettings): BackendResult<BackendSettings>

    fun observeSettings(): StateFlow<BackendSettings>

    suspend fun exportBackup(includeCredentials: Boolean): BackendResult<String>

    suspend fun importBackup(path: String): BackendResult<Unit>
}

// -------------------------------------------------------------------------------------------
// Supporting value types for the backend contract
// -------------------------------------------------------------------------------------------

data class BackendStatus(
    val appState: AppState,
    val environment: EnvironmentReport,
    val server: ServerInfo,
    val connection: OpenCodeConnection?,
    val currentProject: ProjectRecord?,
    val sessionCount: Int,
    val terminalCount: Int,
    val providerHealth: ProviderHealth,
    val connectivity: ConnectivityState,
    val metrics: RuntimeMetrics?,
    val pendingSetupCheckpoint: SetupCheckpoint
)

data class ServerConfiguration(
    val preferredPort: Int = 4096,
    val bindToLan: Boolean = false,
    val additionalCorsOrigins: List<String> = emptyList(),
    val startPolicy: StartPolicy = StartPolicy.NEVER,
    val autoRestart: Boolean = true,
    val maxAutoRestarts: Int = 3,
    val keepTerminalSessionsOnStop: Boolean = true,
    val requestedProjectDirectory: String? = null
)

data class ProjectRecord(
    val id: String,
    val name: String,
    /** Absolute path as seen by the runtime. */
    val path: String,
    /** Path as the user recognises it (may be a SAF-backed external directory). */
    val displayPath: String,
    val isManaged: Boolean,
    val isActive: Boolean,
    val createdAt: Long,
    val lastOpenedAt: Long?,
    val languageHint: String?
)

data class CreateProjectRequest(
    val name: String,
    /** Null for a new managed project under the runtime home. */
    val externalPath: String? = null,
    val initializeGit: Boolean = true,
    val seedTestProject: Boolean = false
)

data class ProjectFileNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val isIgnored: Boolean,
    val children: List<ProjectFileNode> = emptyList()
)

data class SessionRecord(
    val id: String,
    val title: String,
    val directory: String,
    val parentId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val status: SessionActivityStatus,
    val messageCount: Int,
    val filesChanged: Int,
    val version: String?
)

enum class SessionActivityStatus { IDLE, THINKING, WORKING, WAITING_FOR_USER, COMPLETED, ERROR, RETRYING }

data class SessionMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val createdAt: Long,
    val text: String,
    val toolActivity: List<ToolInvocation>,
    val reasoning: String?,
    val isError: Boolean,
    val errorMessage: String?
)

enum class MessageRole { USER, ASSISTANT, SYSTEM }

data class ToolInvocation(
    val callId: String,
    val tool: String,
    val title: String,
    val status: ToolStatus,
    val output: String?,
    val error: String?
)

enum class ToolStatus { PENDING, RUNNING, COMPLETED, ERROR }

data class ModelSelection(val providerId: String, val modelId: String)

data class ProviderDescriptor(
    val id: String,
    val name: String,
    val source: String,
    val isConfigured: Boolean,
    val authMethods: List<String>,
    val modelCount: Int
)

data class BackendSettings(
    val startPolicy: StartPolicy = StartPolicy.NEVER,
    val autoRestartServer: Boolean = true,
    val maxAutoRestarts: Int = 3,
    val preferredPort: Int = 4096,
    val bindToLan: Boolean = false,
    val additionalCorsOrigins: List<String> = emptyList(),
    val defaultShell: String? = null,
    val scrollbackLimit: ScrollbackLimit = ScrollbackLimit.LINES_5000,
    val defaultTerminalColumns: Int = 80,
    val defaultTerminalRows: Int = 24,
    val largeFileThresholdBytes: Long = 2L * 1024 * 1024,
    val connectionTimeoutMs: Long = 10_000,
    val responseTimeoutMs: Long = 120_000,
    val startupTimeoutMs: Long = 45_000,
    val enableForegroundService: Boolean = true,
    val keepRunningInBackground: Boolean = false,
    val testScenario: com.palash.opencodeserver.backend.core.TestScenario = com.palash.opencodeserver.backend.core.TestScenario.NONE,
    val useMockBackend: Boolean = false,
    val apiKeys: Map<String, String> = emptyMap()
)

/**
 * Minimal success/failure carrier so the UI always receives either a value or a
 * [BackendError] and never a raw exception (spec 98).
 */
sealed class BackendResult<out T> {
    data class Success<T>(val value: T) : BackendResult<T>()
    data class Failure(val error: BackendError) : BackendResult<Nothing>()

    val isSuccess: Boolean get() = this is Success

    fun valueOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): BackendError? = (this as? Failure)?.error

    inline fun <R> map(transform: (T) -> R): BackendResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun onSuccess(block: (T) -> Unit): BackendResult<T> {
        if (this is Success) block(value)
        return this
    }

    inline fun onFailure(block: (BackendError) -> Unit): BackendResult<T> {
        if (this is Failure) block(error)
        return this
    }

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw com.palash.opencodeserver.backend.core.BackendException(error)
    }
}

inline fun <T> backendCatching(block: () -> T): BackendResult<T> = try {
    BackendResult.Success(block())
} catch (e: Throwable) {
    BackendResult.Failure(BackendError.wrap(e))
}