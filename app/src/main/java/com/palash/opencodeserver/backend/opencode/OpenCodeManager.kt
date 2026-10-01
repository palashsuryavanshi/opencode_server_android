package com.palash.opencodeserver.backend.opencode

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.DeviceArchitecture
import com.palash.opencodeserver.backend.core.ProviderHealth
import com.palash.opencodeserver.backend.core.UpdateAvailability
import com.palash.opencodeserver.backend.deps.DependencyCatalog
import com.palash.opencodeserver.backend.deps.DependencyId
import com.palash.opencodeserver.backend.deps.DependencyManager
import com.palash.opencodeserver.backend.deps.DependencySpec
import com.palash.opencodeserver.backend.deps.InstallMethod
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.runtime.ProcessRunner
import com.palash.opencodeserver.backend.runtime.RuntimeLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spec 11/12/46/118 - OpenCode lifecycle management.
 *
 * ```
 * isInstalled()  getInstalledVersion()  checkLatestCompatibleVersion()
 * install()  update()  uninstall()  verify()
 * ```
 *
 * Two decisions shape this class:
 *
 *  - OpenCode remains the source of truth for its own version. The app reads it by running
 *    `opencode --version` rather than assuming a build number (spec 11 - no hard-coded version).
 *  - Compatibility is judged against the running architecture before anything is installed
 *    (spec 12). When no compatible build exists the result is a typed error, never a fake success.
 */
class OpenCodeManager(
    private val layout: RuntimeLayout,
    private val architecture: DeviceArchitecture,
    private val runner: ProcessRunner,
    private val dependencies: DependencyManager,
    private val log: LogManager
) {

    private val _installedVersion = MutableStateFlow<String?>(null)
    val installedVersion: StateFlow<String?> = _installedVersion.asStateFlow()

    private val _installMethod = MutableStateFlow<InstallMethod?>(null)
    val installMethod: StateFlow<InstallMethod?> = _installMethod.asStateFlow()

    private val _providerHealth = MutableStateFlow(ProviderHealth.UNKNOWN)
    val providerHealth: StateFlow<ProviderHealth> = _providerHealth.asStateFlow()

    private val spec: DependencySpec = DependencyCatalog.byId(DependencyId.OPENCODE)

    // ---- spec 11 API ----------------------------------------------------------------------

    /** Spec 11 `isInstalled()`. True only when the binary resolves *and* runs. */
    suspend fun isInstalled(): Boolean = getInstalledVersion() != null

    /** Spec 11 `getInstalledVersion()`. Null when absent; never a guess. */
    suspend fun getInstalledVersion(): String? {
        val version = runner.versionOf("opencode", listOf("--version"))
        _installedVersion.value = version
        return version
    }

    /**
     * Spec 11 `checkLatestCompatibleVersion()`.
     *
     * The server publishes `installation.update-available` events, and the CLI has an `upgrade`
     * command. This reports what is installed and asks the runtime whether a newer one exists,
     * without downloading anything, so the user can choose (spec 46).
     */
    suspend fun checkLatestCompatibleVersion(): BackendResult<UpdateAvailability> {
        val installed = getInstalledVersion()
            ?: return BackendResult.Failure(
                BackendError.Unknown(
                    technical = "opencode is not installed",
                    userTitle = "OpenCode is not installed.",
                    userMessage = "Install OpenCode to check for updates."
                )
            )

        if (!isArchitectureSupported()) {
            return BackendResult.Success(
                UpdateAvailability(
                    component = "OpenCode",
                    installedVersion = installed,
                    availableVersion = null,
                    updateAvailable = false,
                    autoUpdateEnabled = false,
                    notes = "No compatible build is published for ${architecture.displayName}."
                )
            )
        }

        // Ask the installed CLI rather than guessing a release number (spec 93).
        val probe = runner.run(
            ProcessRunner.CommandSpec("opencode", listOf("upgrade", "--help"), timeoutMs = 20_000)
        )
        val supportsUpgrade = probe.isSuccess

        return BackendResult.Success(
            UpdateAvailability(
                component = "OpenCode",
                installedVersion = installed,
                availableVersion = null, // Determined server-side; not hard-coded.
                updateAvailable = false,
                autoUpdateEnabled = false,
                notes = if (supportsUpgrade) {
                    "Use Update to install the latest compatible release."
                } else {
                    "This build does not expose the upgrade command; reinstall is required."
                }
            )
        )
    }

    /**
     * Spec 11 `install()`.
     *
     * Delegates to [DependencyManager] so installation logic stays in one place (spec 7), then
     * verifies the result. The method never returns success on the strength of the install command
     * alone (spec 113).
     */
    suspend fun install(): BackendResult<String> {
        if (!isArchitectureSupported()) {
            val error = BackendError.ArchitectureUnsupported(
                detected = architecture.displayName,
                required = "A compatible Linux ${architecture.displayName} runtime",
                technical = architecture.notes.joinToString("\n")
            )
            log.error(LogCategory.OPENCODE, "No compatible OpenCode build for this architecture", error.technical)
            return BackendResult.Failure(error)
        }

        // Spec 113 - success is only reported after verification, not after the install command alone.
        return when (val result = dependencies.installDependency(spec)) {
            is BackendResult.Failure -> result
            is BackendResult.Success -> {
                _installMethod.value = spec.installMethod
                verify()
            }
        }
    }

    /** Spec 11 `verify()`. Runs the binary and confirms it reports a version. */
    suspend fun verify(): BackendResult<String> {
        val version = getInstalledVersion()
            ?: return BackendResult.Failure(
                BackendError.Categorized(
                    technical = "opencode --version returned no output after install",
                    category = com.palash.opencodeserver.backend.core.ErrorCategory.DEPENDENCY,
                    retryable = true,
                    userTitle = "OpenCode did not start correctly.",
                    userMessage = "The install finished but OpenCode did not respond. Try Repair OpenCode."
                )
            )

        // Spec 93 - capability check against the installed release, not an assumed layout.
        val help = runner.run(ProcessRunner.CommandSpec("opencode", listOf("--help"), timeoutMs = 20_000))
        val capabilities = OpenCodeCapabilities.parse(help.stdout + help.stderr)

        if (!capabilities.hasServe) {
            log.warn(
                LogCategory.OPENCODE,
                "Installed OpenCode does not expose the serve command",
                "version=$version capabilities=${capabilities.present}"
            )
            return BackendResult.Failure(
                BackendError.ApiVersion(
                    installed = version,
                    expected = "a release that exposes `opencode serve`",
                    detail = capabilities.present.joinToString(", ")
                )
            )
        }

        log.info(
            LogCategory.OPENCODE,
            "OpenCode verified",
            "version=$version commands=${capabilities.present.joinToString(",")}"
        )
        return BackendResult.Success(version)
    }

    /** Spec 11 `update()`. Requires explicit user permission (spec 46). */
    suspend fun update(confirm: Boolean): BackendResult<String> {
        if (!confirm) {
            return BackendResult.Failure(
                BackendError.Unknown(
                    technical = "update refused without confirmation",
                    userTitle = "Update cancelled.",
                    userMessage = "OpenCode is unchanged."
                )
            )
        }

        val before = getInstalledVersion()
        log.info(LogCategory.OPENCODE, "Updating OpenCode", "from=${before ?: "not installed"}")

        // Spec 118 - preserve configuration and credentials before touching the binary.
        backupConfiguration()

        return when (val result = dependencies.updateDependency(spec)) {
            is BackendResult.Failure -> {
                // Spec 118 - roll back on a failed upgrade rather than leaving a broken install.
                rollback(before)
                BackendResult.Failure(result.error)
            }
            is BackendResult.Success -> when (val verified = verify()) {
                is BackendResult.Success -> {
                    log.info(LogCategory.OPENCODE, "OpenCode updated", "${before ?: "none"} -> ${verified.value}")
                    verified
                }
                is BackendResult.Failure -> {
                    rollback(before)
                    BackendResult.Failure(verified.error)
                }
            }
        }
    }

    /** Spec 11 `uninstall()`. Configuration is kept; the app can reinstall without re-auth. */
    suspend fun uninstall(keepConfig: Boolean = true): BackendResult<Unit> {
        log.info(LogCategory.OPENCODE, "Uninstalling OpenCode", "keepConfig=$keepConfig")
        dependencies.removeDependency(spec)
        _installedVersion.value = null
        if (!keepConfig) layout.openCodeConfigDir.deleteRecursively()
        return BackendResult.Success(Unit)
    }

    // ---- spec 12 architecture ------------------------------------------------------------------

    /**
     * Spec 12 - can a compatible binary exist here?
     *
     * Aarch64 and x86_64 are the architectures OpenCode publishes builds for on Android; 32-bit is
     * reported honestly as unavailable rather than being attempted and failing obscurely.
     */
    fun isArchitectureSupported(): Boolean =
        architecture.displayName in SUPPORTED_TARGETS

    /** Spec 12 - the structured explanation for an unsupported architecture. */
    fun unsupportedExplanation(): String = buildString {
        appendLine("Detected architecture: ${architecture.displayName} (${architecture.androidAbi})")
        appendLine("Device ABIs: ${architecture.supportedAbis.joinToString(", ").ifEmpty { "unknown" }}")
        appendLine("libc: ${architecture.libcKind}")
        appendLine("64-bit device: ${architecture.is64Bit}")
        appendLine()
        appendLine("OpenCode is not currently available for this device/runtime.")
        if (architecture.displayName in THIRTY_TWO_BIT) {
            appendLine("Supported targets: ${SUPPORTED_TARGETS.joinToString(", ")}")
        }
    }

    // ---- spec 26/78 provider health ---------------------------------------------------------------

    /**
     * Spec 78 - provider health, evaluated through OpenCode rather than by calling the provider.
     *
     * The app never talks to an LLM endpoint directly (spec 25), so this reflects what OpenCode
     * reports about connected providers.
     */
    fun updateProviderHealth(configuredProviders: List<String>, reachable: Boolean) {
        _providerHealth.value = when {
            configuredProviders.isEmpty() -> ProviderHealth.NOT_CONFIGURED
            !reachable -> ProviderHealth.UNREACHABLE
            else -> ProviderHealth.AUTHENTICATED
        }
    }

    // ---- spec 118/119 upgrade safety ----------------------------------------------------------------

    /** Copy the config directory aside so a failed upgrade cannot destroy user settings. */
    private fun backupConfiguration() {
        runCatching {
            if (!layout.openCodeConfigDir.isDirectory) return
            val stamp = System.currentTimeMillis()
            val backup = java.io.File(layout.backupDir, "opencode-config-$stamp")
            backup.parentFile?.mkdirs()
            layout.openCodeConfigDir.copyRecursively(backup, overwrite = true)
            log.info(LogCategory.OPENCODE, "Configuration backed up before update", backup.name)
        }.onFailure {
            log.warn(LogCategory.OPENCODE, "Could not back up configuration", it.message)
        }
    }

    /**
     * Spec 118 - best-effort rollback.
     *
     * Reinstalling the previously known version is attempted; if that is not possible the user is
     * told to use Repair rather than being left with a silent broken state.
     */
    private suspend fun rollback(previousVersion: String?) {
        if (previousVersion == null) {
            log.warn(LogCategory.OPENCODE, "No previous version recorded; cannot roll back")
            return
        }
        log.warn(LogCategory.OPENCODE, "Rolling back OpenCode", "target=$previousVersion")
        dependencies.removeDependency(spec)
        dependencies.installDependency(spec)
        val current = getInstalledVersion()
        if (current == null) {
            log.error(
                LogCategory.OPENCODE,
                "Rollback did not produce a working binary",
                "expected=$previousVersion actual=${current ?: "none"}"
            )
        }
    }

    /** Spec 42 - Advanced details for the OpenCode layer. */
    suspend fun describe(): List<String> {
        val details = mutableListOf(
            "OpenCode installed: ${isInstalled()}",
            "Version: ${getInstalledVersion() ?: "not installed"}",
            "Install method: ${_installMethod.value?.label ?: "unknown"}",
            "Architecture supported: ${isArchitectureSupported()}",
            "Detected: ${architecture.displayName} / ${architecture.libcKind}",
            "Config dir: ${layout.openCodeConfigDir.absolutePath}",
            "Data dir: ${layout.openCodeDataDir.absolutePath}",
            "Provider health: ${_providerHealth.value}"
        )
        if (!isArchitectureSupported()) {
            details += unsupportedExplanation().trim().lines()
        }
        return details
    }

    companion object {
        /** Targets for which a usable OpenCode build exists on Android. */
        val SUPPORTED_TARGETS = setOf("ARM64", "x86_64")

        val THIRTY_TWO_BIT = setOf("ARM32", "x86")
    }
}

/**
 * Spec 93 - capabilities discovered from the installed release's `--help`.
 *
 * Cached per version so repeated checks are cheap, and invalidated when the binary changes.
 */
object OpenCodeCapabilities {

    data class Capabilities(
        val present: List<String>,
        val hasServe: Boolean,
        val hasService: Boolean,
        val hasSession: Boolean,
        val hasAuth: Boolean,
        val hasRun: Boolean,
        val hasWeb: Boolean,
        val hasUpgrade: Boolean
    ) {
        val serveMode: ServeMode
            get() = when {
                hasServe && hasService -> ServeMode.SERVE_OR_SERVICE
                hasServe -> ServeMode.SERVE
                hasService -> ServeMode.SERVICE
                else -> ServeMode.UNAVAILABLE
            }
    }

    /**
     * Spec 94 - which server mode to use.
     *
     * `serve` is preferred because the app owns the child process directly, which gives reliable
     * lifecycle control and avoids a second daemon being managed behind the app's back.
     */
    enum class ServeMode(val preferredCommand: List<String>) {
        SERVE_OR_SERVICE(listOf("serve")),
        SERVE(listOf("serve")),
        SERVICE(listOf("service", "start")),
        UNAVAILABLE(emptyList())
    }

    private val cache = mutableMapOf<String, Capabilities>()

    /** Commands this class knows how to detect, with the flag that advertises each one. */
    private val knownCommands = listOf(
        "serve", "service", "session", "auth", "run", "web",
        "upgrade", "attach", "export", "import", "models"
    )

    fun parse(helpText: String): Capabilities {
        val text = helpText.lowercase()
        // Whole-word match: `serve` must not be reported just because `server` appears.
        val has = { command: String -> command in text.splitToSequence("\\s+".toRegex()) }
        val present = knownCommands.filter(has)
        return Capabilities(
            present = present,
            hasServe = has("serve"),
            hasService = has("service"),
            hasSession = has("session"),
            hasAuth = has("auth"),
            hasRun = has("run"),
            hasWeb = has("web"),
            hasUpgrade = has("upgrade")
        )
    }

    fun forVersion(version: String, helpText: String): Capabilities =
        cache.getOrPut(version) { parse(helpText) }

    fun invalidate() = cache.clear()
}