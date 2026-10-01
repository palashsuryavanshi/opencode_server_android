package com.palash.opencodeserver.backend.core

/**
 * Structured lifecycle states reported to the frontend.
 *
 * Every state here is observable through [LocalCodingBackend.state]; the UI is expected to render
 * from these values rather than inferring progress from side effects.
 */

/** Spec 5 - readiness of the local Linux/runtime environment. */
enum class EnvironmentState {
    UNKNOWN,
    CHECKING,
    NOT_INITIALIZED,
    INSTALLING,
    READY,
    DEGRADED,
    ERROR
}

/** Spec 16 - lifecycle of the `opencode serve` child process. */
enum class ServerProcessState {
    UNKNOWN,
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    CRASHED,
    ERROR
}

/** Spec 49 - transport level connectivity to the local OpenCode server. */
enum class ApiConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/** Spec 112 - the exact state vocabulary the frontend is allowed to depend on. */
enum class AppState {
    INITIALIZING,
    SETUP_REQUIRED,
    INSTALLING,
    READY,
    SERVER_STARTING,
    SERVER_RUNNING,
    SERVER_STOPPING,
    SERVER_STOPPED,
    SERVER_ERROR,
    API_CONNECTING,
    API_CONNECTED,
    API_ERROR,
    OFFLINE,
    DEGRADED;

    val isBusy: Boolean
        get() = this == INITIALIZING || this == INSTALLING || this == SERVER_STARTING ||
            this == SERVER_STOPPING || this == API_CONNECTING
}

/** Spec 7/8 - per dependency status produced by the [com.palash.opencodeserver.backend.deps.DependencyManager]. */
enum class DependencyState {
    UNKNOWN,
    MISSING,
    INSTALLING,
    INSTALLED,
    OUTDATED,
    INCOMPATIBLE,
    FAILED
}

/** Spec 60 - first-run wizard stages. Kept flat so progress can be reported as an ordinal. */
enum class SetupStep(
    val title: String,
    val userDetail: String
) {
    WELCOME("Welcome", "Your device will be prepared for local coding."),
    CHECKING_DEVICE("Checking device", "Architecture, storage and runtime compatibility."),
    RUNTIME("Linux runtime", "Installing the terminal runtime."),
    PACKAGE_MANAGER("Package manager", "Preparing the package manager."),
    CORE_TOOLS("Core utilities", "Installing core command line utilities."),
    GIT("Git", "Installing version control."),
    NODE("Node.js", "Installing the JavaScript runtime."),
    OPENCODE("OpenCode", "Installing the OpenCode coding agent."),
    CONFIG("OpenCode configuration", "Creating the managed configuration directory."),
    SERVER("Local server", "Starting the local OpenCode server."),
    PROVIDER("AI provider", "Connect an AI provider to start coding."),
    DONE("Ready", "Your local coding environment is ready.");

    val isComplete: Boolean get() = this == DONE
}

/** Spec 100 - resumable setup checkpoints. Setup resumes at the first checkpoint not reached. */
enum class SetupCheckpoint {
    STARTED,
    RUNTIME_READY,
    PACKAGE_MANAGER_READY,
    GIT_READY,
    NODE_READY,
    OPENCODE_READY,
    CONFIG_READY,
    SERVER_READY,
    COMPLETE;

    val stageLabel: String
        get() = when (this) {
            STARTED -> "Setup started"
            RUNTIME_READY -> "Runtime ready"
            PACKAGE_MANAGER_READY -> "Package manager ready"
            GIT_READY -> "Git ready"
            NODE_READY -> "Node ready"
            OPENCODE_READY -> "OpenCode ready"
            CONFIG_READY -> "Configuration ready"
            SERVER_READY -> "Server ready"
            COMPLETE -> "Setup complete"
        }
}

/** Spec 63 - independent per-tab terminal lifecycle. */
enum class TerminalStatus {
    STARTING,
    RUNNING,
    EXITED,
    CLOSING,
    CLOSED,
    CRASHED
}

/** Spec 37 - how eagerly the server is started. */
enum class StartPolicy(val label: String) {
    NEVER("Never"),
    WHEN_APP_OPENS("When app opens"),
    KEEP_RUNNING("Keep running")
}

/** Spec 67 - bounded scrollback so terminal RAM usage stays predictable. */
enum class ScrollbackLimit(val lines: Int, val label: String) {
    LINES_1000(1_000, "1,000 lines"),
    LINES_5000(5_000, "5,000 lines"),
    LINES_10000(10_000, "10,000 lines"),
    UNLIMITED(Int.MAX_VALUE, "Unlimited");

    val isUnbounded: Boolean get() = this == UNLIMITED
}

/** Spec 81 - fault injection switches used to exercise the frontend without a real runtime. */
enum class TestScenario(val label: String) {
    NONE("Healthy"),
    SLOW_STARTUP("Slow server startup"),
    SERVER_CRASH("Server crash after start"),
    CONNECTION_FAILURE("API connection failure"),
    DEPENDENCY_FAILURE("Dependency installation failure"),
    OPENCODE_UNAVAILABLE("OpenCode unavailable for this architecture"),
    INVALID_API_KEY("Invalid API key"),
    SSE_DISCONNECT("Event stream disconnects"),
    TERMINAL_CRASH("Terminal process crash"),
    LOW_STORAGE("Low storage"),
    OFFLINE("Offline mode");

    val isActive: Boolean get() = this != NONE
}

/** Spec 76/77 - connectivity layers are reported independently, never conflated. */
enum class ConnectivityState { ONLINE, OFFLINE, UNKNOWN }

/** Spec 78 - provider reachability as understood through OpenCode, not by direct provider calls. */
enum class ProviderHealth {
    UNKNOWN,
    AUTHENTICATED,
    NOT_CONFIGURED,
    INVALID_CREDENTIALS,
    UNREACHABLE
}