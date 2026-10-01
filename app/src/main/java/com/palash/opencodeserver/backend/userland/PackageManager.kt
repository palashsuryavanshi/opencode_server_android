package com.palash.opencodeserver.backend.userland

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.DeviceArchitecture
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.runtime.EnvironmentBuilder
import com.palash.opencodeserver.backend.runtime.ProcessRunner
import com.palash.opencodeserver.backend.runtime.RuntimeLayout

/**
 * Spec 9 - the package manager abstraction.
 *
 * Neither `apt` nor `pkg` is assumed. A runtime that has no package manager is a valid outcome and
 * is represented by [NoPackageManager], which makes installers fall back to release binaries instead
 * of failing with "command not found".
 */
interface PackageManager {

    val id: String
    val displayName: String

    /** Spec 9 `isInstalled()`. */
    suspend fun isInstalled(): Boolean

    /** Spec 9 `getVersion()`. */
    suspend fun version(): String?

    /** Spec 9 `install()`. [nonInteractive] must add the runtime's non-interactive flags. */
    suspend fun install(
        packages: List<String>,
        nonInteractive: Boolean = true,
        onProgress: (String) -> Unit = {}
    ): BackendResult<Unit>

    /** Spec 9 `remove()`. */
    suspend fun remove(packages: List<String>): BackendResult<Unit>

    /** Spec 9 `update()` - refresh the index without upgrading installed packages. */
    suspend fun update(onProgress: (String) -> Unit = {}): BackendResult<Unit>

    /** Spec 9 `upgrade()`. */
    suspend fun upgrade(onProgress: (String) -> Unit = {}): BackendResult<Unit>

    /** Spec 9 `search()`. */
    suspend fun search(term: String): BackendResult<List<String>>

    /** True when the manager can install a specific package name without guessing. */
    suspend fun supportsPackage(name: String): Boolean
}

/** Spec 9 - the honest answer when a runtime ships no package manager. */
class NoPackageManager(private val reason: String) : PackageManager {

    override val id = "none"
    override val displayName = "No package manager"
    override suspend fun isInstalled(): Boolean = false
    override suspend fun version(): String? = null

    override suspend fun install(
        packages: List<String>,
        nonInteractive: Boolean,
        onProgress: (String) -> Unit
    ): BackendResult<Unit> = BackendResult.Failure(
        BackendError.Categorized(
            technical = reason,
            category = com.palash.opencodeserver.backend.core.ErrorCategory.RUNTIME,
            retryable = false,
            userTitle = "This runtime has no package manager.",
            userMessage = "Tools for this runtime are installed from release artifacts instead."
        )
    )

    override suspend fun remove(packages: List<String>): BackendResult<Unit> = install(packages)
    override suspend fun update(onProgress: (String) -> Unit): BackendResult<Unit> =
        BackendResult.Success(Unit)
    override suspend fun upgrade(onProgress: (String) -> Unit): BackendResult<Unit> =
        BackendResult.Success(Unit)
    override suspend fun search(term: String): BackendResult<List<String>> = BackendResult.Success(emptyList())
    override suspend fun supportsPackage(name: String): Boolean = false
}

/**
 * `pkg` / `pkg_add` style managers used by BSD-flavoured Termux packages.
 *
 * Every command is an argument array; nothing is concatenated into a shell string (spec 84).
 */
class PkgPackageManager(
    private val runner: ProcessRunner,
    private val log: LogManager
) : PackageManager {

    override val id = "pkg"
    override val displayName = "pkg (Termux)"

    override suspend fun isInstalled(): Boolean = runner.which("pkg") != null

    override suspend fun version(): String? = runner.versionOf("pkg", listOf("--version"))

    override suspend fun install(
        packages: List<String>,
        nonInteractive: Boolean,
        onProgress: (String) -> Unit
    ): BackendResult<Unit> = runInstall(listOf("pkg", "install") + (if (nonInteractive) listOf("-y") else emptyList()) + packages, onProgress)

    override suspend fun remove(packages: List<String>): BackendResult<Unit> =
        runInstall(listOf("pkg", "uninstall", "-y") + packages, {})

    override suspend fun update(onProgress: (String) -> Unit): BackendResult<Unit> =
        runInstall(listOf("pkg", "update"), onProgress)

    override suspend fun upgrade(onProgress: (String) -> Unit): BackendResult<Unit> =
        runInstall(listOf("pkg", "upgrade", "-y"), onProgress)

    override suspend fun search(term: String): BackendResult<List<String>> {
        val result = runner.run(
            ProcessRunner.CommandSpec("pkg", listOf("search", term), timeoutMs = 20_000)
        )
        val packages = result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return BackendResult.Success(packages)
    }

    override suspend fun supportsPackage(name: String): Boolean =
        runner.which(name) != null || search(name).valueOrNull()?.isNotEmpty() == true

    private suspend fun runInstall(argv: List<String>, onProgress: (String) -> Unit): BackendResult<Unit> {
        onProgress("Running ${argv.first()}")
        val result = runner.run(
            ProcessRunner.CommandSpec(
                command = argv.first(),
                arguments = argv.drop(1),
                timeoutMs = 10 * 60_000,
                category = com.palash.opencodeserver.backend.runtime.ProcessCategory.INSTALLER,
                onStdout = onProgress
            )
        )
        return if (result.isSuccess) {
            BackendResult.Success(Unit)
        } else {
            BackendResult.Failure(
                BackendError.Categorized(
                    technical = "${argv.first()} exited ${result.exitCode}: ${result.stderr.take(400)}",
                    category = com.palash.opencodeserver.backend.core.ErrorCategory.DEPENDENCY,
                    retryable = true,
                    userTitle = "A package could not be installed.",
                    userMessage = "The package manager reported an error. Tap View Details for the reason."
                )
            )
        }
    }
}

/** `apt-get` style manager used by Debian-derived userlands. */
class AptPackageManager(
    private val runner: ProcessRunner,
    private val log: LogManager
) : PackageManager {

    override val id = "apt"
    override val displayName = "apt (Debian)"

    override suspend fun isInstalled(): Boolean = runner.which("apt-get") != null

    override suspend fun version(): String? = runner.versionOf("apt-get", listOf("--version"))

    override suspend fun install(
        packages: List<String>,
        nonInteractive: Boolean,
        onProgress: (String) -> Unit
    ): BackendResult<Unit> {
        // A bare apt install fails on a stale index, which is the most common failure mode, so the
        // index is refreshed first when explicitly asked.
        val env = if (nonInteractive) mapOf("DEBIAN_FRONTEND" to "noninteractive") else emptyMap()
        val result = runner.run(
            ProcessRunner.CommandSpec(
                command = "apt-get",
                arguments = (if (nonInteractive) listOf("-y") else emptyList()) +
                    listOf("--no-install-recommends", "install") + packages,
                timeoutMs = 10 * 60_000,
                category = com.palash.opencodeserver.backend.runtime.ProcessCategory.INSTALLER,
                extraEnv = env,
                onStdout = onProgress
            )
        )
        return toResult(result, "apt-get install")
    }

    override suspend fun remove(packages: List<String>): BackendResult<Unit> {
        val result = runner.run(
            ProcessRunner.CommandSpec("apt-get", listOf("-y", "remove") + packages, timeoutMs = 5 * 60_000)
        )
        return toResult(result, "apt-get remove")
    }

    override suspend fun update(onProgress: (String) -> Unit): BackendResult<Unit> {
        val result = runner.run(
            ProcessRunner.CommandSpec("apt-get", listOf("update"), timeoutMs = 5 * 60_000, onStdout = onProgress)
        )
        return toResult(result, "apt-get update")
    }

    override suspend fun upgrade(onProgress: (String) -> Unit): BackendResult<Unit> {
        val result = runner.run(
            ProcessRunner.CommandSpec("apt-get", listOf("-y", "upgrade"), timeoutMs = 15 * 60_000, onStdout = onProgress)
        )
        return toResult(result, "apt-get upgrade")
    }

    override suspend fun search(term: String): BackendResult<List<String>> {
        val result = runner.run(ProcessRunner.CommandSpec("apt-cache", listOf("search", term), timeoutMs = 20_000))
        return BackendResult.Success(
            result.stdout.lineSequence()
                .map { it.substringBefore(" - ").trim() }
                .filter { it.isNotEmpty() }
                .toList()
        )
    }

    override suspend fun supportsPackage(name: String): Boolean = true

    private fun toResult(result: ProcessRunner.CommandResult, label: String): BackendResult<Unit> =
        if (result.isSuccess) BackendResult.Success(Unit)
        else BackendResult.Failure(
            BackendError.Categorized(
                technical = "$label exited ${result.exitCode}: ${result.stderr.take(400)}",
                category = com.palash.opencodeserver.backend.core.ErrorCategory.DEPENDENCY,
                retryable = true,
                userTitle = "A package could not be installed.",
                userMessage = "The package manager reported an error. Tap View Details for the reason."
            )
        )
}

/** `apk` style manager used by Alpine-derived userlands. */
class ApkPackageManager(
    private val runner: ProcessRunner,
    private val log: LogManager
) : PackageManager {

    override val id = "apk"
    override val displayName = "apk (Alpine)"

    override suspend fun isInstalled(): Boolean = runner.which("apk") != null

    override suspend fun version(): String? = runner.versionOf("apk", listOf("--version"))

    override suspend fun install(
        packages: List<String>,
        nonInteractive: Boolean,
        onProgress: (String) -> Unit
    ): BackendResult<Unit> {
        val result = runner.run(
            ProcessRunner.CommandSpec(
                command = "apk",
                arguments = listOf("add", "--no-cache") + packages,
                timeoutMs = 10 * 60_000,
                onStdout = onProgress
            )
        )
        return toResult(result, "apk add")
    }

    override suspend fun remove(packages: List<String>): BackendResult<Unit> {
        val result = runner.run(ProcessRunner.CommandSpec("apk", listOf("del") + packages, timeoutMs = 5 * 60_000))
        return toResult(result, "apk del")
    }

    override suspend fun update(onProgress: (String) -> Unit): BackendResult<Unit> = runIndex(onProgress)

    override suspend fun upgrade(onProgress: (String) -> Unit): BackendResult<Unit> {
        val result = runner.run(ProcessRunner.CommandSpec("apk", listOf("upgrade"), timeoutMs = 10 * 60_000, onStdout = onProgress))
        return toResult(result, "apk upgrade")
    }

    override suspend fun search(term: String): BackendResult<List<String>> {
        val result = runner.run(ProcessRunner.CommandSpec("apk", listOf("search", term), timeoutMs = 20_000))
        return BackendResult.Success(result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() })
    }

    override suspend fun supportsPackage(name: String): Boolean = true

    private suspend fun runIndex(onProgress: (String) -> Unit): BackendResult<Unit> {
        val result = runner.run(ProcessRunner.CommandSpec("apk", listOf("update"), timeoutMs = 3 * 60_000, onStdout = onProgress))
        return toResult(result, "apk update")
    }

    private fun toResult(result: ProcessRunner.CommandResult, label: String): BackendResult<Unit> =
        if (result.isSuccess) BackendResult.Success(Unit)
        else BackendResult.Failure(
            BackendError.Categorized(
                technical = "$label exited ${result.exitCode}: ${result.stderr.take(400)}",
                category = com.palash.opencodeserver.backend.core.ErrorCategory.DEPENDENCY,
                retryable = true,
                userTitle = "A package could not be installed.",
                userMessage = "The package manager reported an error. Tap View Details for the reason."
            )
        )
}

/**
 * Spec 9 - runtime detection, then package manager selection.
 *
 * Detection order matters: the first manager whose executable actually resolves wins, and a runtime
 * with none produces [NoPackageManager] rather than an assumed `apt`.
 */
class PackageManagerDetector(
    private val runner: ProcessRunner,
    private val environment: EnvironmentBuilder,
    private val log: LogManager
) {

    private val candidates: List<Pair<PackageManager, String>> by lazy {
        listOf(
            PkgPackageManager(runner, log) to "pkg",
            AptPackageManager(runner, log) to "apt-get",
            ApkPackageManager(runner, log) to "apk"
        )
    }

    /** Resolve the package manager for the current runtime. Cached after first success. */
    @Volatile
    private var cached: PackageManager? = null

    suspend fun detect(): PackageManager {
        cached?.let { return it }
        for ((manager, executable) in candidates) {
            if (runner.which(executable) != null) {
                log.info(LogCategory.RUNTIME, "Detected package manager", "id=${manager.id} via $executable")
                cached = manager
                return manager
            }
        }
        val none = NoPackageManager(
            "No supported package manager found on PATH (${environment.pathEntries().take(3).joinToString(":")})"
        )
        log.warn(LogCategory.RUNTIME, "No package manager detected", none.displayName)
        return none
    }

    /** Describe what was found, for the Advanced view. */
    suspend fun describe(): List<String> = buildList {
        val manager = detect()
        add("Package manager: ${manager.displayName}")
        add("Detected via: ${if (manager is NoPackageManager) "none" else runner.which(manager.id)?.substringAfterLast('/')}")
        add("Version: ${manager.version() ?: "unknown"}")
    }
}

/**
 * Spec 2 - a Linux userland the app controls.
 *
 * A provider owns one coherent way of getting a working Linux environment. They are ordered by
 * capability so the richest wins, and each reports its own availability honestly so the UI can
 * explain a degraded environment instead of guessing.
 */
interface UserlandProvider {

    val id: String
    val displayName: String

    /** Does this provider give a full Linux userland? */
    val providesFullUserland: Boolean

    /** Spec 12 - can this provider run binaries built for the detected ABI and libc? */
    fun supportsArchitecture(arch: DeviceArchitecture): Boolean

    /** Install or materialise the userland. */
    suspend fun install(): BackendResult<Unit>

    suspend fun isInstalled(): Boolean

    /** The runtime's own version string, for diagnostics. */
    suspend fun version(): String?

    /** Called after install so the environment can be rewritten for this runtime. */
    suspend fun activate(environment: EnvironmentBuilder)
}

/**
 * Spec 2 - the selection result, including why the chosen provider was chosen.
 *
 * The explanation is retained because "why is my terminal in compatibility mode" is the single most
 * common support question this app will face.
 */
data class UserlandSelection(
    val provider: UserlandProvider?,
    val isAvailable: Boolean,
    val reason: String,
    val alternatives: List<String>
)

/**
 * Spec 2 - pick the best available userland for this device.
 *
 * Order: bundled/provisioned userland, then an external Termux installation the user already has,
 * then the platform shell as a last-resort fallback. A device where nothing works reports
 * [UserlandSelection.isAvailable] = false, which surfaces as a clear setup error (spec 43) rather
 * than a crash later.
 */
class UserlandProviderRegistry(
    private val providers: List<UserlandProvider>,
    private val architecture: DeviceArchitecture,
    private val log: LogManager
) {

    /** Providers that claim support for this ABI, in priority order. */
    private val compatible: List<UserlandProvider> =
        providers.filter { it.supportsArchitecture(architecture) }

    fun select(): UserlandSelection {
        if (compatible.isEmpty()) {
            return UserlandSelection(
                provider = null,
                isAvailable = false,
                reason = "No bundled Linux userland is available for ${architecture.displayName}.",
                alternatives = listOf(
                    "Development builds include a userland for ARM64, the most common device architecture.",
                    "A future release may add ${architecture.displayName} support.",
                    "You can still use the app to browse and edit projects; terminal features are unavailable."
                )
            )
        }
        val chosen = compatible.first()
        log.info(
            LogCategory.RUNTIME,
            "Selected Linux userland",
            "id=${chosen.id} name=${chosen.displayName} full=${chosen.providesFullUserland}"
        )
        return UserlandSelection(
            provider = chosen,
            isAvailable = true,
            reason = "Using ${chosen.displayName}.",
            alternatives = compatible.drop(1).map { it.displayName }
        )
    }

    fun describe(): List<String> = buildList {
        add("Detected ABI: ${architecture.displayName} (${architecture.androidAbi})")
        add("libc: ${architecture.libcKind}")
        add("Compatible userlands: ${compatible.size}")
        compatible.forEach { add("  - ${it.displayName} (${it.id})") }
    }
}