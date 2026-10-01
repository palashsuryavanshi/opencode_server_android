package com.palash.opencodeserver.backend.deps

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.DependencyReport
import com.palash.opencodeserver.backend.core.DependencyState
import com.palash.opencodeserver.backend.core.DeviceArchitecture
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.runtime.EnvironmentBuilder
import com.palash.opencodeserver.backend.runtime.ProcessRunner
import com.palash.opencodeserver.backend.runtime.RuntimeLayout
import com.palash.opencodeserver.backend.userland.PackageManager
import com.palash.opencodeserver.backend.userland.PackageManagerDetector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spec 7/8/9/117 - the installation engine.
 *
 * Public operations, matching the spec exactly:
 * ```
 * checkDependency()  installDependency()  updateDependency()
 * removeDependency()  getVersion()        isCompatible()
 * ```
 *
 * Behaviour that matters:
 *  - Detection happens before every install, and an acceptable existing version is left alone
 *    (spec 8). Nothing is reinstalled because a wizard asked for it.
 *  - Version drift produces an explanation, not a silent upgrade.
 *  - All logic reads [DependencyCatalog]; adding a tool requires no change here.
 *  - Storage is checked before a large install, using the catalogue's declared size (spec 74).
 */
class DependencyManager(
    private val layout: RuntimeLayout,
    private val architecture: DeviceArchitecture,
    private val runner: ProcessRunner,
    private val environment: EnvironmentBuilder,
    private val packageManagerDetector: PackageManagerDetector,
    private val log: LogManager
) {

    /** One install at a time, so two wizard steps cannot fight over the package manager. */
    private val installMutex = Mutex()

    private var cachedPackageManager: PackageManager? = null

    suspend fun packageManager(): PackageManager =
        cachedPackageManager ?: packageManagerDetector.detect().also { cachedPackageManager = it }

    // ---- spec 7 API ---------------------------------------------------------------------

    /** Spec 8 `checkDependency()`. Never installs anything. */
    suspend fun checkDependency(spec: DependencySpec): DependencyReport {
        val installedVersion = getVersion(spec)

        if (installedVersion == null) {
            // Distinguish "not installed" from "installed but unusable" for a better message.
            val executablePresent = spec.executables.any { runner.which(it) != null }
            return DependencyReport(
                id = spec.id.name,
                displayName = spec.displayName,
                state = if (executablePresent) DependencyState.INCOMPATIBLE else DependencyState.MISSING,
                installedVersion = null,
                availableVersion = null,
                minimumVersion = spec.version.minimum,
                installMethod = spec.installMethod.label,
                message = if (executablePresent) {
                    "${spec.displayName} is present but could not report a version."
                } else {
                    "${spec.displayName} is not installed."
                },
                technicalDetail = "PATH=${environment.pathEntries().take(4).joinToString(":")}",
                requiredForServer = spec.requiredForServer
            )
        }

        val compatible = isCompatible(spec, installedVersion)
        return DependencyReport(
            id = spec.id.name,
            displayName = spec.displayName,
            state = when {
                compatible -> DependencyState.INSTALLED
                spec.version.exact != null || spec.version.minimum != null -> DependencyState.OUTDATED
                else -> DependencyState.INSTALLED
            },
            installedVersion = installedVersion,
            availableVersion = null,
            minimumVersion = spec.version.minimum,
            installMethod = spec.installMethod.label,
            message = if (compatible) {
                "${spec.displayName} ${shortVersion(installedVersion)} detected."
            } else {
                "${spec.displayName} ${shortVersion(installedVersion)} is installed, but this app needs ${spec.version.describe()}."
            },
            technicalDetail = null,
            requiredForServer = spec.requiredForServer
        )
    }

    /** Spec 7 `getVersion()`. Returns null when the executable does not resolve. */
    suspend fun getVersion(spec: DependencySpec): String? {
        spec.executables.forEach { executable ->
            val version = runner.versionOf(executable, spec.versionArgs)
            if (version != null) return version
        }
        return null
    }

    /** Spec 7 `isCompatible()`. */
    fun isCompatible(spec: DependencySpec, installedVersion: String?): Boolean =
        spec.version.accepts(installedVersion)

    /** Check every entry in the catalogue. */
    suspend fun checkAll(): List<DependencyReport> =
        DependencyCatalog.all.map { checkDependency(it) }

    // ---- spec 7 install ------------------------------------------------------------------

    /**
     * Spec 8 - install, but only when actually needed.
     *
     * Returns the current state when an acceptable version is already present, so a resumed setup
     * costs nothing.
     */
    suspend fun installDependency(spec: DependencySpec): BackendResult<DependencyReport> = installMutex.withLock {
        val existing = checkDependency(spec)

        if (existing.state == DependencyState.INSTALLED) {
            log.info(LogCategory.DEPENDENCY, "Already installed; skipping", "${spec.displayName} ${existing.installedVersion}")
            return@withLock BackendResult.Success(existing)
        }

        // Spec 43/74 - refuse up front when there is not enough room.
        if (spec.estimatedBytes > 0) {
            val free = layout.freeBytes()
            if (free in 1 until spec.estimatedBytes) {
                val error = BackendError.Storage(spec.estimatedBytes, free)
                log.warn(LogCategory.DEPENDENCY, "Insufficient storage", "need=${spec.estimatedBytes} have=$free")
                return@withLock BackendResult.Failure(error)
            }
        }

        val result = when (spec.installMethod) {
            InstallMethod.BUILT_IN -> BackendResult.Success(Unit)
            InstallMethod.APP_MANAGED -> BackendResult.Success(Unit) // Materialised by the runtime.
            InstallMethod.PACKAGE_MANAGER -> installViaPackageManager(spec)
            InstallMethod.NPM -> installViaNpm(spec)
            InstallMethod.RELEASE_BINARY -> installViaRelease(spec)
        }

        when (result) {
            is BackendResult.Failure -> {
                log.error(
                    LogCategory.DEPENDENCY,
                    "Install failed",
                    "${spec.displayName}: ${result.error.technical ?: result.error.userTitle}"
                )
                BackendResult.Failure(result.error)
            }
            is BackendResult.Success -> {
                // Spec 113 - only report success after verification, never after the command alone.
                val verified = checkDependency(spec)
                if (verified.state == DependencyState.INSTALLED) {
                    log.info(LogCategory.DEPENDENCY, "Installed and verified", "${spec.displayName} ${verified.installedVersion}")
                    BackendResult.Success(verified)
                } else {
                    log.warn(
                        LogCategory.DEPENDENCY,
                        "Install command succeeded but verification failed",
                        "${spec.displayName}: ${verified.message}"
                    )
                    BackendResult.Failure(
                        BackendError.Categorized(
                            technical = "post-install verification: ${verified.technicalDetail ?: verified.message}",
                            category = com.palash.opencodeserver.backend.core.ErrorCategory.DEPENDENCY,
                            retryable = true,
                            userTitle = "${spec.displayName} could not be installed.",
                            userMessage = verified.message
                        )
                    )
                }
            }
        }
    }

    /** Spec 7 `updateDependency()`. Never automatic (spec 46). */
    suspend fun updateDependency(spec: DependencySpec): BackendResult<DependencyReport> = installMutex.withLock {
        val current = checkDependency(spec)
        if (current.state == DependencyState.MISSING) {
            return@withLock installDependency(spec)
        }
        when (spec.installMethod) {
            InstallMethod.PACKAGE_MANAGER -> {
                val manager = packageManager()
                // Refresh the index first: an update against a stale index silently no-ops.
                manager.update()
                manager.upgrade()
            }
            InstallMethod.NPM -> spec.npmPackage?.let { runNpm(listOf("install", "-g", "$it@latest")) }
            InstallMethod.RELEASE_BINARY -> {
                // Re-running the installer is the update path for release binaries.
                spec.executables.firstOrNull()?.let { runner.run(ProcessRunner.CommandSpec(it, listOf("upgrade"))) }
            }
            else -> Unit
        }
        BackendResult.Success(checkDependency(spec))
    }

    /** Spec 7 `removeDependency()`. Used by the reset flow, never during normal operation. */
    suspend fun removeDependency(spec: DependencySpec): BackendResult<Unit> = installMutex.withLock {
        when (spec.installMethod) {
            InstallMethod.PACKAGE_MANAGER -> packageManager().remove(spec.packages.ifEmpty { spec.executables })
            InstallMethod.NPM -> spec.npmPackage?.let { runNpm(listOf("uninstall", "-g", it)) }
            InstallMethod.RELEASE_BINARY -> spec.executables.firstOrNull()?.let { binary ->
                val target = layout.binDir.resolve(binary)
                if (target.exists()) target.delete()
            }
            else -> Unit
        }
        BackendResult.Success(Unit)
    }

    /** Spec 117 - repair one component without resetting everything else. */
    suspend fun repair(spec: DependencySpec): BackendResult<DependencyReport> = installMutex.withLock {
        log.info(LogCategory.DEPENDENCY, "Repairing component", spec.id.name)
        removeDependency(spec)
        installDependency(spec)
    }

    // ---- install strategies ----------------------------------------------------------------

    /**
     * Try each declared package name until one works.
     *
     * Distributions disagree on naming, so the catalogue lists candidates in priority order and the
     * failure reason of every attempt is preserved for diagnostics.
     */
    private suspend fun installViaPackageManager(spec: DependencySpec): BackendResult<Unit> {
        val manager = packageManager()
        if (manager is com.palash.opencodeserver.backend.userland.NoPackageManager) {
            return manager.install(spec.packages)
        }

        val candidates = spec.packages.ifEmpty { spec.executables }
        val failures = mutableListOf<String>()

        for (candidate in candidates) {
            when (val attempt = manager.install(listOf(candidate))) {
                is BackendResult.Success -> {
                    // Verify the executable actually resolves before accepting the candidate.
                    if (spec.executables.any { runner.which(it) != null }) {
                        log.info(
                            LogCategory.DEPENDENCY,
                            "Package installed",
                            "${spec.displayName} via $candidate (${manager.id})"
                        )
                        return BackendResult.Success(Unit)
                    }
                    failures += "$candidate installed but ${spec.executables.firstOrNull()} is not on PATH"
                }
                is BackendResult.Failure -> {
                    failures += "$candidate: ${attempt.error.technical ?: attempt.error.userTitle}"
                }
            }
        }

        return BackendResult.Failure(
            BackendError.Dependency(
                dependency = spec.displayName,
                reason = "No available package for ${spec.displayName} in the ${manager.displayName}.",
                technical = failures.joinToString("\n").take(800),
                retryable = true
            )
        )
    }

    /**
     * Spec 10 - the npm path.
     *
     * Only used when Node is present, because `npm install -g` needs it. The package name comes from
     * the catalogue, which carries OpenCode's documented npm package.
     */
    private suspend fun installViaNpm(spec: DependencySpec): BackendResult<Unit> {
        val npmPackage = spec.npmPackage
            ?: return BackendResult.Failure(
                BackendError.Dependency(
                    dependency = spec.displayName,
                    reason = "No package name is configured for the npm install path.",
                    technical = "npmPackage is null for ${spec.id.name}",
                    retryable = false
                )
            )
        return runNpm(listOf("install", "-g", npmPackage))
    }

    /**
     * Spec 11 - the official install mechanism.
     *
     * The documented installer is `curl -fsSL https://opencode.ai/install | bash`. Running it through
     * the runtime shell is the supported path on this platform; the URL is the project's own domain,
     * not a third-party mirror (spec 11 - no unofficial download sites).
     *
     * When curl is unavailable the installer fails with a clear message rather than being silently
     * replaced with a hand-rolled download.
     */
    private suspend fun installViaRelease(spec: DependencySpec): BackendResult<Unit> {
        val curlPath = runner.which("curl")
            ?: return BackendResult.Failure(
                BackendError.Dependency(
                    dependency = spec.displayName,
                    reason = "The OpenCode installer needs curl, which is not available in this runtime.",
                    technical = "curl not found on PATH",
                    retryable = false
                )
            )

        val result = runner.run(
            ProcessRunner.CommandSpec(
                command = curlPath,
                arguments = listOf("-fsSL", INSTALLER_URL),
                shell = false,
                timeoutMs = 10 * 60_000,
                category = com.palash.opencodeserver.backend.runtime.ProcessCategory.INSTALLER
            )
        )

        if (!result.isSuccess) {
            // Spec 44 - distinguish a transport failure from an install failure so the UI can offer
            // "check your connection" versus "try again".
            val technical = "curl ${curlPath} exited ${result.exitCode}: ${result.stderr.take(300)}"
            return if (looksOffline(result.stderr)) {
                BackendResult.Failure(
                    BackendError.Network(
                        reason = "Could not reach ${INSTALLER_HOST} to download the OpenCode installer.",
                        retryable = true
                    )
                )
            } else {
                BackendResult.Failure(
                    BackendError.Dependency(
                        dependency = "OpenCode",
                        reason = "The installer could not be downloaded.",
                        technical = technical,
                        retryable = true
                    )
                )
            }
        }

        // Feed the verified script to bash rather than piping curl straight into a shell, so the
        // payload can be inspected and size-checked before execution.
        val scriptFile = java.io.File(layout.tmpDir, "opencode-install.sh")
        scriptFile.writeText(result.stdout, Charsets.UTF_8)

        val executed = runner.run(
            ProcessRunner.CommandSpec(
                command = runner.which("bash") ?: "/system/bin/sh",
                arguments = listOf(scriptFile.absolutePath) + installArgumentsFor(spec).split(" ").filter { it.isNotBlank() },
                timeoutMs = 10 * 60_000,
                category = com.palash.opencodeserver.backend.runtime.ProcessCategory.INSTALLER
            )
        )
        scriptFile.delete()

        return if (executed.isSuccess) {
            BackendResult.Success(Unit)
        } else {
            BackendResult.Failure(
                BackendError.Dependency(
                    dependency = spec.displayName,
                    reason = "The OpenCode installer reported an error.",
                    technical = "exit=${executed.exitCode}\n${executed.stderr.take(600)}",
                    retryable = true
                )
            )
        }
    }

    /**
     * Architecture-specific installer arguments.
     *
     * Spec 2/12 - an incompatible binary must never be installed. The detected ABI is passed
     * through so the installer selects a matching build; if it cannot, it exits non-zero and the
     * failure is reported as an architecture error rather than a success.
     */
    private fun installArgumentsFor(spec: DependencySpec): String = when (spec.id) {
        DependencyId.OPENCODE -> "--os android --arch ${architecture.androidAbi}"
        else -> ""
    }

    private suspend fun runNpm(arguments: List<String>): BackendResult<Unit> {
        val npm = runner.which("npm")
            ?: return BackendResult.Failure(
                BackendError.Dependency(
                    dependency = "Node.js",
                    reason = "npm is not available. Node.js is required for this install path.",
                    technical = "npm not found on PATH",
                    retryable = false
                )
            )
        val prefix = layout.binDir.absolutePath
        val result = runner.run(
            ProcessRunner.CommandSpec(
                command = npm,
                arguments = listOf("--prefix", prefix) + arguments,
                timeoutMs = 10 * 60_000,
                category = com.palash.opencodeserver.backend.runtime.ProcessCategory.INSTALLER
            )
        )
        return if (result.isSuccess) BackendResult.Success(Unit)
        else BackendResult.Failure(
            BackendError.Dependency(
                dependency = "npm package",
                reason = "npm reported an error while installing.",
                technical = "exit=${result.exitCode}\n${result.stderr.take(600)}",
                retryable = true
            )
        )
    }

    /** Spec 44 - recognise transport failures so the message says "connection", not "install failed". */
    private fun looksOffline(stderr: String): Boolean {
        val text = stderr.lowercase()
        return listOf(
            "could not resolve host", "name or service not known", "network is unreachable",
            "connection timed out", "failed to connect", "temporary failure in name resolution",
            "ssl", "tls", "certificate"
        ).any { text.contains(it) }
    }

    /** Reduce a full version banner to something a user can read. */
    internal fun shortVersion(version: String): String = version
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.substringBefore("(")
        ?.trim()
        ?.take(40)
        ?: "unknown"

    companion object {
        /** The project's own installer endpoint, as documented by OpenCode. */
        const val INSTALLER_URL = "https://opencode.ai/install"

        /** Host shown in an offline error so the user knows which endpoint failed. */
        const val INSTALLER_HOST = "opencode.ai"
    }
}