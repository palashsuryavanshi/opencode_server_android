package com.palash.opencodeserver.backend.runtime

import android.content.Context
import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.Format
import java.io.File

/**
 * Spec 56 - a stable, application-owned Linux home.
 *
 * Layout (all under `filesDir`, so it is private to the app and survives process death):
 *
 * ```
 * runtime/
 *   home/            <- $HOME for every process
 *     .config/       <- OpenCode config dir (OPENCODE_CONFIG_DIR)
 *     .local/share/  <- OpenCode data (auth.json, sessions, db)
 *     .cache/
 *     projects/      <- managed project roots
 *   bin/             <- $PATH prefix, installed tool links and shims
 *   tmp/             <- $TMPDIR
 *   logs/            <- structured backend logs
 *   dl/              <- partial downloads (resume support)
 *   userland/        <- extracted PRoot/distro trees, keyed by ABI
 *   state/           <- checkpoints, server registration, settings
 * ```
 *
 * Nothing persistent lives in the cache directory; Android may delete it at any time.
 */
class RuntimeLayout(private val context: Context) {

    val runtimeRoot: File = File(context.filesDir, "runtime")

    val home: File = File(runtimeRoot, "home")
    val homeConfig: File = File(home, ".config")
    val homeLocalShare: File = File(home, ".local/share")
    val homeCache: File = File(home, ".cache")
    val homeBin: File = File(home, ".local/bin")
    val projects: File = File(home, "projects")

    val binDir: File = File(runtimeRoot, "bin")
    val tmpDir: File = File(runtimeRoot, "tmp")
    val logDir: File = File(runtimeRoot, "logs")
    val downloadDir: File = File(runtimeRoot, "dl")
    val userlandDir: File = File(runtimeRoot, "userland")
    val stateDir: File = File(runtimeRoot, "state")
    val backupDir: File = File(runtimeRoot, "backups")

    /** OpenCode's own config directory; matches `~/.config/opencode` under $HOME. */
    val openCodeConfigDir: File = File(homeConfig, "opencode")

    /** OpenCode's data directory; matches `~/.local/share/opencode` under $HOME. */
    val openCodeDataDir: File = File(homeLocalShare, "opencode")

    val checkpointFile: File = File(stateDir, "setup_checkpoint")
    val serverRegistrationFile: File = File(stateDir, "server.json")
    val settingsFile: File = File(stateDir, "settings.json")
    val projectsFile: File = File(stateDir, "projects.json")
    val runtimeManifestFile: File = File(stateDir, "runtime.json")

    /** Every directory the app must be able to write to. */
    val managedDirectories: List<File> = listOf(
        runtimeRoot, home, homeConfig, homeLocalShare, homeCache, homeBin, projects,
        binDir, tmpDir, logDir, downloadDir, userlandDir, stateDir, backupDir,
        openCodeConfigDir, openCodeDataDir
    )

    /**
     * Spec 74 - actual requirement, computed from a declared estimate rather than hard-coded in the
     * installer. [pendingDownloadsBytes] is the sum of the planned payloads plus headroom for
     * extraction (rootfs images expand roughly 3x).
     */
    fun ensureDirectories(): BackendError? {
        managedDirectories.forEach { dir ->
            if (!dir.exists() && !dir.mkdirs()) {
                return BackendError.Storage(
                    requiredBytes = 0,
                    availableBytes = freeBytes(context.filesDir)
                )
            }
            if (!dir.canWrite()) {
                return BackendError.Runtime("Directory is not writable: ${dir.name}", retryable = false)
            }
        }
        return null
    }

    /** True when [home] exists and looks like a usable Linux home. */
    fun isRuntimeInstalled(): Boolean =
        home.isDirectory && homeConfig.isDirectory && homeLocalShare.isDirectory

    /** Spec 74 - free space on the volume backing [filesDir]. */
    fun freeBytes(): Long = freeBytes(context.filesDir)

    fun totalBytes(): Long = totalBytes(context.filesDir)

    fun availableSpaceReport(): String =
        "${Format.bytes(freeBytes())} free of ${Format.bytes(totalBytes())}"

    /** Map a runtime-absolute path (what the user sees, `~/...`) to a real path. */
    fun resolveUserPath(path: String): File {
        val cleaned = path.trim()
        return when {
            cleaned == "~" || cleaned == "" -> home
            cleaned.startsWith("~/") -> File(home, cleaned.removePrefix("~/"))
            cleaned.startsWith("/") -> File(cleaned)
            else -> File(home, cleaned)
        }
    }

    /** Render a real path the way the user should see it. */
    fun toUserPath(file: File): String = when {
        file.absolutePath == home.absolutePath -> "~"
        file.absolutePath.startsWith(home.absolutePath + File.separator) ->
            "~" + file.absolutePath.removePrefix(home.absolutePath)
        else -> file.absolutePath
    }

    companion object {
        fun freeBytes(dir: File): Long = runCatching {
            val stat = android.os.StatFs(dir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        }.getOrElse {
            runCatching { dir.usableSpace }.getOrDefault(0L)
        }

        fun totalBytes(dir: File): Long = runCatching {
            val stat = android.os.StatFs(dir.absolutePath)
            stat.blockCountLong * stat.blockSizeLong
        }.getOrElse {
            runCatching { dir.totalSpace }.getOrDefault(0L)
        }
    }
}