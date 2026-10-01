package com.palash.opencodeserver.backend.runtime

import com.palash.opencodeserver.backend.core.DependencyReport
import com.palash.opencodeserver.backend.core.DependencyState
import com.palash.opencodeserver.backend.core.EnvironmentReport
import com.palash.opencodeserver.backend.core.EnvironmentState
import com.palash.opencodeserver.backend.deps.DependencyCatalog
import com.palash.opencodeserver.backend.deps.DependencyId
import com.palash.opencodeserver.backend.deps.DependencyManager
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.opencode.OpenCodeManager
import com.palash.opencodeserver.backend.userland.PackageManagerDetector
import com.palash.opencodeserver.backend.userland.UserlandSelection
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Spec 56 - what was materialised on disk, so a later launch can verify rather than reinstall. */
data class RuntimeManifest(
    val installed: Boolean = false,
    val providerId: String? = null,
    val version: String? = null,
    val installedAt: Long = 0L,
    val abi: String? = null
)

/**
 * Spec 5 - is the local runtime ready?
 *
 * Answers all ten questions the spec lists:
 * ```
 * Runtime installed?          Linux userland available?   Shell available?
 * Package manager available?  Node available?             Git available?
 * OpenCode installed?         OpenCode version ok?        Project directory?
 * Required permissions?
 * ```
 *
 * The result is a structured [EnvironmentReport] with a state, never a bare boolean, so the UI can
 * distinguish "not set up" from "set up but degraded" from "broken" (spec 98).
 */
class EnvironmentManager(
    private val context: android.content.Context,
    private val layout: RuntimeLayout,
    private val architectureDetector: ArchitectureDetector,
    private val dependencies: DependencyManager,
    private val packageManagerDetector: PackageManagerDetector,
    private val openCode: OpenCodeManager,
    private val userlandRegistry: com.palash.opencodeserver.backend.userland.UserlandProviderRegistry,
    private val log: LogManager
) {

    private val stateMutex = Mutex()

    private val _state = MutableStateFlow(EnvironmentState.UNKNOWN)
    val state: StateFlow<EnvironmentState> = _state.asStateFlow()

    private val _report = MutableStateFlow<EnvironmentReport?>(null)
    val report: StateFlow<EnvironmentReport?> = _report.asStateFlow()

    /** Spec 5 - the full probe. Safe to call repeatedly; it never installs anything. */
    suspend fun evaluate(projectDirectory: String?): EnvironmentReport = stateMutex.withLock {
        _state.value = EnvironmentState.CHECKING

        val arch = architectureDetector.detect()
        val selection = userlandRegistry.select()

        layout.ensureDirectories()

        val runtimeInstalled = selection.isAvailable && layout.isRuntimeInstalled()
        val shell = resolveShell()
        val packageManager = runCatching { packageManagerDetector.detect() }.getOrNull()

        val reports = dependencies.checkAll()
        val git = reports.firstOrNull { it.id == DependencyId.GIT.name }
        val node = reports.firstOrNull { it.id == DependencyId.NODE.name }
        val openCodeVersion = runCatching { openCode.getInstalledVersion() }.getOrNull()

        val missingPermissions = checkPermissions()
        val blocking = firstBlockingIssue(selection, reports, missingPermissions)

        val nextState = when {
            blocking != null -> EnvironmentState.ERROR
            !runtimeInstalled -> EnvironmentState.NOT_INITIALIZED
            openCodeVersion == null -> EnvironmentState.NOT_INITIALIZED
            // Git and Node are useful but not required by the server, so their absence is DEGRADED
            // rather than blocking (spec 5).
            reports.any { it.state == DependencyState.MISSING && it.requiredForServer } ->
                EnvironmentState.NOT_INITIALIZED
            reports.any { it.state == DependencyState.MISSING } -> EnvironmentState.DEGRADED
            missingPermissions.isNotEmpty() -> EnvironmentState.DEGRADED
            else -> EnvironmentState.READY
        }

        val result = EnvironmentReport(
            state = nextState,
            architecture = arch,
            runtimeInstalled = runtimeInstalled,
            userlandName = selection.provider?.displayName,
            shell = shell?.first,
            packageManager = packageManager?.displayName,
            nodeVersion = node?.installedVersion,
            gitVersion = git?.installedVersion,
            openCodeVersion = openCodeVersion,
            projectDirectory = projectDirectory,
            storageFreeBytes = layout.freeBytes(),
            missingPermissions = missingPermissions,
            blockingReason = blocking
        )

        _report.value = result
        _state.value = nextState

        log.info(
            LogCategory.RUNTIME,
            "Environment evaluated: ${nextState.name}",
            "runtime=$runtimeInstalled shell=${shell?.first} pkg=${packageManager?.id} " +
                "git=${git?.installedVersion} node=${node?.installedVersion} opencode=$openCodeVersion"
        )
        result
    }

    /** Spec 5/12 - the compatibility summary used by setup step 2. */
    suspend fun describeDevice(): List<String> {
        val arch = architectureDetector.detect()
        return buildList {
            addAll(userlandRegistry.describe())
            add("Platform: ${architectureDetector.platformDescription(arch)}")
            add("OpenCode supported on this device: ${openCode.isArchitectureSupported()}")
            add("Free storage: ${layout.availableSpaceReport()}")
        }
    }

    /** Spec 74 - the storage precheck for the whole install. */
    suspend fun storagePrecheck(includeOptional: Boolean = false): Boolean {
        val required = DependencyCatalog.requiredBytes(includeOptional)
        val free = layout.freeBytes()
        val ok = free > required
        if (!ok) {
            log.warn(
                LogCategory.STORAGE,
                "Not enough storage for setup",
                "need=$required free=$free"
            )
        }
        return ok
    }

    /** Persist the manifest so a resumed setup knows what exists (spec 99/100). */
    fun writeManifest(manifest: RuntimeManifest) {
        runCatching {
            layout.runtimeManifestFile.writeText(
                buildString {
                    appendLine("installed=${manifest.installed}")
                    appendLine("providerId=${manifest.providerId.orEmpty()}")
                    appendLine("version=${manifest.version.orEmpty()}")
                    appendLine("installedAt=${manifest.installedAt}")
                    appendLine("abi=${manifest.abi.orEmpty()}")
                },
                Charsets.UTF_8
            )
        }
    }

    fun readManifest(): RuntimeManifest? = runCatching {
        val file = layout.runtimeManifestFile
        if (!file.exists()) return null
        val values = file.readLines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()
        RuntimeManifest(
            installed = values["installed"]?.toBooleanStrictOrNull() ?: false,
            providerId = values["providerId"]?.takeIf { it.isNotBlank() },
            version = values["version"]?.takeIf { it.isNotBlank() },
            installedAt = values["installedAt"]?.toLongOrNull() ?: 0L,
            abi = values["abi"]?.takeIf { it.isNotBlank() }
        )
    }.getOrNull()

    // ---- internals -----------------------------------------------------------------------

    private fun resolveShell(): Pair<String, List<String>>? =
        com.palash.opencodeserver.backend.runtime.pty.ShellLocator.preferredShell().takeIf { File(it.first).canExecute() }

    /** Spec 5 - permissions the runtime actually needs, not a blanket request. */
    private fun checkPermissions(): List<String> {
        val missing = mutableListOf<String>()
        // Notifications are needed for the foreground service notification (spec 36).
        val notificationsGranted = runCatching {
            val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager
            manager?.areNotificationsEnabled() != false
        }.getOrDefault(true)
        if (!notificationsGranted) {
            missing += "android.permission.POST_NOTIFICATIONS"
        }
        return missing
    }

    /**
     * The first genuinely blocking issue, phrased for a user.
     *
     * Order matters: architecture is reported before anything else because nothing else can succeed
     * if the device cannot host a compatible binary.
     */
    private fun firstBlockingIssue(
        selection: UserlandSelection,
        reports: List<DependencyReport>,
        missingPermissions: List<String>
    ): String? = when {
        !openCode.isArchitectureSupported() ->
            "OpenCode is not currently available for ${architectureDetector.detect().displayName}."

        !selection.isAvailable -> selection.reason

        reports.firstOrNull { it.id == DependencyId.RUNTIME.name }?.state == DependencyState.MISSING ->
            "The Linux runtime is not installed."

        reports.firstOrNull { it.id == DependencyId.OPENCODE.name }?.state == DependencyState.FAILED ->
            "OpenCode could not be installed."

        missingPermissions.contains("android.permission.POST_NOTIFICATIONS") ->
            "Notifications are disabled, so the background server cannot show its status."

        else -> null
    }
}