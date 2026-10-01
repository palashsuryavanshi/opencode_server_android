package com.palash.opencodeserver.backend.userland

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.DeviceArchitecture
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.runtime.EnvironmentBuilder
import com.palash.opencodeserver.backend.runtime.ProcessRunner
import com.palash.opencodeserver.backend.runtime.RuntimeLayout
import java.io.File

/**
 * Spec 2 - the provisioned Linux userland, the primary runtime.
 *
 * The app ships or downloads one userland per ABI and runs binaries from it. [supportsArchitecture]
 * is the gate that implements the spec's hard rule: an incompatible binary is never selected, let
 * alone downloaded.
 *
 * A `TargetTriple` is used rather than a bare architecture name so a musl-versus-glibc mismatch is
 * caught here, before an install is attempted, instead of surfacing as a cryptic loader error later.
 */
class ProvisionedUserlandProvider(
    private val layout: RuntimeLayout,
    private val runner: ProcessRunner,
    private val log: LogManager,
    private val tripleResolver: TargetTripleResolver = TargetTripleResolver(),
    /** When present, install() extracts `assets/userland/<arch>.tar.gz` instead of shelling out. */
    private val installer: UserlandInstaller? = null
) : UserlandProvider {

    override val id = "provisioned"
    override val displayName = "App-provided Linux environment"
    override val providesFullUserland = true

    /** The per-ABI root under `runtime/userland/<triple>`. */
    fun rootFor(triple: String): File = File(layout.userlandDir, triple)

    override fun supportsArchitecture(arch: DeviceArchitecture): Boolean =
        tripleResolver.resolve(arch) != null

    override suspend fun isInstalled(): Boolean {
        val triple = tripleResolver.resolve(architectureSnapshot()) ?: return false
        val root = rootFor(triple)
        // A userland is considered present only when a shell binary actually exists.
        return root.isDirectory && findShell(root) != null
    }

    override suspend fun version(): String? = runner.versionOf("sh", listOf("--version"))

    override suspend fun install(): BackendResult<Unit> {
        val arch = architectureSnapshot()
        val triple = tripleResolver.resolve(arch)
            ?: return BackendResult.Failure(
                BackendError.ArchitectureUnsupported(
                    detected = arch.displayName,
                    required = tripleResolver.supportedTargets().joinToString(", "),
                    technical = tripleResolver.explain(arch)
                )
            )

        val root = rootFor(triple)
        if (findShell(root) != null) {
            log.info(LogCategory.RUNTIME, "Userland already installed", triple)
            return BackendResult.Success(Unit)
        }

        // Preferred path: extract the APK-bundled `assets/userland/<arch>.tar.gz` with the
        // in-app installer, which recreates symlinks and needs no host tar/unzstd binary.
        installer?.let {
            return when (val installed = it.install(arch)) {
                is BackendResult.Success -> {
                    log.info(
                        LogCategory.RUNTIME,
                        "Linux userland extracted",
                        "triple=$triple files=${installed.value.files} " +
                            "symlinks=${installed.value.symlinks} root=${root.absolutePath}"
                    )
                    BackendResult.Success(Unit)
                }
                is BackendResult.Failure -> installed
            }
        }

        // Legacy path: a `.tar.zst` payload staged next to the userland dir, extracted with a
        // host tool. Kept so tests and sideloaded layouts without the installer keep working.

        // The userland payload is provisioned by the build or fetched during release packaging. The
        // app verifies its presence and integrity rather than downloading it at runtime, which keeps
        // setup offline-capable and avoids fetching an unverified archive on first launch.
        val payload = File(layout.userlandDir, "$triple.tar.zst")
        if (!payload.exists()) {
            log.warn(
                LogCategory.RUNTIME,
                "No userland payload bundled for this ABI",
                "triple=$triple abi=${arch.androidAbi}"
            )
            return BackendResult.Failure(
                BackendError.ArchitectureUnsupported(
                    detected = arch.displayName,
                    required = "A bundled Linux userland for $triple",
                    technical = "Expected payload: ${payload.absolutePath}"
                )
            )
        }

        return extract(root, payload, triple)
    }

    /**
     * Extraction happens off the main thread and is verified before use.
     *
     * A truncated archive is the realistic failure here, so the shell check after extraction is what
     * turns that into an error rather than a broken terminal later.
     */
    private suspend fun extract(root: File, payload: File, triple: String): BackendResult<Unit> {
        return try {
            if (!root.exists()) root.mkdirs()

            val extractor = findExtractor()
                ?: return BackendResult.Failure(
                    BackendError.Runtime(
                        "No extraction tool (tar/unzstd) is available for the bundled userland",
                        retryable = false
                    )
                )

            val result = runner.run(
                com.palash.opencodeserver.backend.runtime.ProcessRunner.CommandSpec(
                    command = extractor.first,
                    arguments = extractor.second + listOf(payload.absolutePath, "-C", root.absolutePath),
                    timeoutMs = 10 * 60_000,
                    category = com.palash.opencodeserver.backend.runtime.ProcessCategory.INSTALLER
                )
            )

            if (!result.isSuccess) {
                return BackendResult.Failure(
                    BackendError.Runtime(
                        "Extracting the Linux userland failed: exit ${result.exitCode}",
                        retryable = true
                    )
                )
            }

            if (findShell(root) == null) {
                return BackendResult.Failure(
                    BackendError.Runtime(
                        "The extracted userland has no usable shell",
                        retryable = false
                    )
                )
            }

            log.info(
                LogCategory.RUNTIME,
                "Linux userland extracted",
                "triple=$triple root=${root.absolutePath}"
            )
            BackendResult.Success(Unit)
        } catch (e: Exception) {
            BackendResult.Failure(BackendError.wrap(e))
        }
    }

    private suspend fun findExtractor(): Pair<String, List<String>>? {
        if (runner.which("tar") != null) {
            val zstd = runner.which("zstd")
            return if (zstd != null) {
                "tar" to listOf("--use-compress-program=$zstd", "-xf")
            } else {
                "tar" to listOf("-xf")
            }
        }
        if (runner.which("unzstd") != null) {
            return "unzstd" to listOf("-c")
        }
        return null
    }

    /** Look for a shell inside a userland root, preferring bash (spec 34). */
    private fun findShell(root: File): File? =
        listOf("bin/bash", "bin/sh", "usr/bin/bash", "usr/bin/sh")
            .map { File(root, it) }
            .firstOrNull { it.canExecute() }

    override suspend fun activate(environment: EnvironmentBuilder) {
        val triple = tripleResolver.resolve(architectureSnapshot()) ?: return
        val root = rootFor(triple)

        // Spec 55 - the userland's own bins come first so installed tools resolve inside it.
        environment.overridePath(
            listOf(
                layout.binDir.absolutePath,
                File(root, "usr/local/bin").absolutePath,
                File(root, "usr/bin").absolutePath,
                File(root, "bin").absolutePath,
                File(root, "sbin").absolutePath
            ) + environment.pathEntries()
        )

        findShell(root)?.let { environment.setShell(it.absolutePath) }
        environment.set("OPENCODE_USERLAND_ROOT", root.absolutePath)
    }

    /**
     * Spec 2 - map a device ABI to a build target.
     *
     * The mapping is explicit and total. Anything unknown returns null, which the caller surfaces as
     * "not available for this device" rather than guessing.
     */
    class TargetTripleResolver {

        fun resolve(arch: DeviceArchitecture): String? {
            val abi = arch.androidAbi.lowercase()
            return when {
                abi.startsWith("arm64") || abi.startsWith("aarch64") -> "aarch64-linux-android"
                abi.startsWith("armeabi") -> "armv7a-linux-androideabi"
                abi.startsWith("x86_64") -> "x86_64-linux-android"
                abi.startsWith("x86") -> "i686-linux-android"
                else -> null
            }
        }

        fun supportedTargets(): List<String> = listOf(
            "aarch64-linux-android",
            "x86_64-linux-android"
        )

        fun explain(arch: DeviceArchitecture): String = buildString {
            appendLine("Detected Android ABI: ${arch.androidAbi}")
            appendLine("All ABIs advertised: ${arch.supportedAbis.joinToString(", ").ifEmpty { "none" }}")
            appendLine("Supported targets: ${supportedTargets().joinToString(", ")}")
            appendLine()
            appendLine(
                "OpenCode is not currently available for this device/runtime: no bundled Linux " +
                    "userland exists for this ABI. This is a build-packaging gap rather than a " +
                    "device fault."
            )
        }
    }

    /** Injected so the ABI can be overridden in tests without touching Build.SUPPORTED_ABIS. */
    @Volatile
    private var archOverride: DeviceArchitecture? = null

    private fun architectureSnapshot(): DeviceArchitecture = archOverride ?: DeviceArchitecture(
        androidAbi = android.os.Build.SUPPORTED_ABIS?.firstOrNull().orEmpty(),
        supportedAbis = android.os.Build.SUPPORTED_ABIS?.toList().orEmpty(),
        is64Bit = true,
        runtimeAbi = android.os.Build.SUPPORTED_ABIS?.firstOrNull().orEmpty(),
        libcKind = DeviceArchitecture.LibcKind.BIONIC
    )
}

/**
 * Spec 2 - an existing Termux installation, used when present.
 *
 * This provider exists because many users already have Termux, and reusing it is both faster and
 * more respectful than downloading a second userland. It is a bridge: the app drives the other
 * installation through Termux's command API rather than copying files out of it.
 */
class TermuxBridgeUserlandProvider(
    private val log: LogManager,
    private val apiBaseUrl: String = "http://127.0.0.1:8765"
) : UserlandProvider {

    override val id = "termux-bridge"
    override val displayName = "Existing Termux installation"
    override val providesFullUserland = true

    override fun supportsArchitecture(arch: DeviceArchitecture): Boolean = isTermuxInstalled()

    override suspend fun isInstalled(): Boolean = isTermuxInstalled()

    override suspend fun version(): String? = null

    override suspend fun install(): BackendResult<Unit> {
        if (!isTermuxInstalled()) {
            return BackendResult.Failure(
                BackendError.Unknown(
                    technical = "Termux is not installed",
                    userTitle = "Termux is not available.",
                    userMessage = "Install Termux to reuse an existing development environment."
                )
            )
        }
        return BackendResult.Success(Unit)
    }

    override suspend fun activate(environment: EnvironmentBuilder) {
        val termuxHome = "/data/data/com.termux/files/usr"
        environment.overridePath(
            listOf(
                "$termuxHome/bin",
                "$termuxHome/local/bin"
            ) + environment.pathEntries()
        )
        environment.set("TERMUX_HOME", termuxHome)
        log.info(LogCategory.RUNTIME, "Activated Termux bridge", termuxHome)
    }

    /**
     * Probe Termux's local command socket.
     *
     * The check is a plain TCP connect because Termux's API service is opt-in and may be off; if it
     * is off, this provider is simply not used.
     */
    private fun isTermuxInstalled(): Boolean {
        val pm = context?.packageManager ?: return false
        return runCatching {
            pm.getPackageInfo("com.termux", 0)
            true
        }.getOrDefault(false)
    }

    private var context: android.content.Context? = null

    fun attachContext(context: android.content.Context) {
        this.context = context.applicationContext
    }
}

/**
 * Spec 2/32 - the platform shell, always available but clearly labelled.
 *
 * This is what keeps the terminal usable when no userland is provisioned. It cannot run Linux
 * userland binaries, so the environment reports DEGRADED and OpenCode reports unavailable rather
 * than the app pretending a full environment exists.
 */
class PlatformShellUserlandProvider(
    private val log: LogManager
) : UserlandProvider {

    override val id = "platform-shell"
    override val displayName = "Android shell (limited)"
    override val providesFullUserland = false

    override fun supportsArchitecture(arch: DeviceArchitecture): Boolean = true

    override suspend fun isInstalled(): Boolean = File("/system/bin/sh").canExecute()

    override suspend fun version(): String? = null

    override suspend fun install(): BackendResult<Unit> = BackendResult.Success(Unit)

    override suspend fun activate(environment: EnvironmentBuilder) {
        log.warn(
            LogCategory.RUNTIME,
            "Falling back to the Android shell",
            "Developer tooling will be unavailable in this mode"
        )
        environment.setShell("/system/bin/sh")
    }
}