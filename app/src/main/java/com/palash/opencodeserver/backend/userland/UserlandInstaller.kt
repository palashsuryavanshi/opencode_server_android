package com.palash.opencodeserver.backend.userland

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.DeviceArchitecture
import com.palash.opencodeserver.backend.core.ErrorCategory
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spec 2/48/83/113 - installs the bundled Linux userland on device.
 *
 * The build ([BootstrapUserlandTask]) packs one `assets/userland/<arch>.tar.gz` per ABI plus a
 * `<arch>.manifest` carrying its SHA-256. This installer is the other half: it resolves the ABI to
 * the same architecture string, verifies the archive against the manifest, and extracts it into
 * `userland/<triple>` — the directory [ProvisionedUserlandProvider.rootFor] serves from.
 *
 * Why a tar and not loose assets: APK assets cannot represent symlinks, and a Linux userland
 * depends on them (`sh` is `bash`, versioned `.so` names). The archive records links; Android
 * recreates them here, where symlinks work (the Windows build host could not).
 *
 * Pure file I/O below [install] takes an [AssetSource] rather than a Context so the whole flow is
 * JVM-testable with fake assets.
 */
class UserlandInstaller(
    private val userlandDir: File,
    private val log: LogManager? = null,
    private val assets: AssetSource
) {

    /** Where APK assets are read from. */
    interface AssetSource {
        /** Open [assetPath] (e.g. `userland/aarch64.tar.gz`), or null when it is not packaged. */
        fun open(assetPath: String): InputStream?

        /** Read a small text asset, or null when it is not packaged. */
        fun readText(assetPath: String): String? =
            open(assetPath)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** [AssetSource] backed by the APK. */
    class AndroidAssets(private val context: android.content.Context) : AssetSource {
        override fun open(assetPath: String): InputStream? =
            runCatching { context.assets.open(assetPath) }.getOrNull()
    }

    data class InstallReport(
        val triple: String,
        val architecture: String,
        val files: Int,
        val symlinks: Int,
        val skipped: Int,
        val archiveBytes: Long,
        val alreadyInstalled: Boolean = false
    )

    /**
     * Spec 2 - the ABI-to-architecture map, identical to the build's `TermuxRepository`.
     * Returns null for anything unmapped; callers surface that as "not available", never a guess.
     */
    fun architectureFor(abi: String): String? = Companion.architectureFor(abi)

    /** The install root for [deviceArch], or null when the ABI is unmapped. */
    fun rootFor(deviceArch: DeviceArchitecture): File? {
        val architecture = architectureFor(deviceArch.androidAbi) ?: return null
        val triple = tripleForArchitecture(architecture) ?: return null
        return File(userlandDir, triple)
    }

    suspend fun install(
        deviceArch: DeviceArchitecture,
        onProgress: (String) -> Unit = {}
    ): BackendResult<InstallReport> = withContext(Dispatchers.IO) {
        val abi = deviceArch.androidAbi
        val architecture = architectureFor(abi)
            ?: return@withContext BackendResult.Failure(
                BackendError.ArchitectureUnsupported(
                    detected = deviceArch.displayName.ifEmpty { abi.ifEmpty { "unknown" } },
                    required = "aarch64, arm, x86_64 or i686 Linux userland",
                    technical = "No userland architecture maps to Android ABI '$abi'. Refusing to guess (spec 2)."
                )
            )
        val triple = tripleForArchitecture(architecture)
            ?: return@withContext BackendResult.Failure(
                BackendError.ArchitectureUnsupported(
                    detected = architecture,
                    required = "a known install triple",
                    technical = "architectureFor('$abi') = '$architecture' has no target triple."
                )
            )

        val root = File(userlandDir, triple)
        if (findShell(root) != null) {
            log?.info(LogCategory.RUNTIME, "Userland already installed", triple)
            return@withContext BackendResult.Success(
                InstallReport(triple, architecture, 0, 0, 0, 0, alreadyInstalled = true)
            )
        }

        val assetPath = "userland/$architecture.tar.gz"
        val manifestPath = "userland/$architecture.manifest"
        val manifest = assets.readText(manifestPath)?.let { UserlandArchive.parseManifest(it) }
        if (manifest == null) {
            log?.warn(LogCategory.RUNTIME, "Userland manifest not packaged", manifestPath)
        }

        // Stage to a temp file first: asset streams cannot be rewound, and the SHA check must run
        // before a single byte is trusted.
        val staging = File(userlandDir, ".$architecture.tar.gz.part")
        try {
            val source = assets.open(assetPath)
            if (source == null) {
                return@withContext BackendResult.Failure(
                    BackendError.ArchitectureUnsupported(
                        detected = deviceArch.displayName,
                        required = "A bundled Linux userland for $triple",
                        technical = "Expected asset: $assetPath"
                    )
                )
            }
            source.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            val archiveBytes = staging.length()

            val expectedSha = manifest?.get("archiveSha256")
            if (!expectedSha.isNullOrBlank()) {
                onProgress("Verifying download…")
                val actualSha = UserlandArchive.sha256Hex(staging)
                if (!actualSha.equals(expectedSha.trim(), ignoreCase = true)) {
                    log?.error(
                        LogCategory.SECURITY,
                        "Userland payload failed its integrity check",
                        "arch=$architecture expected=${expectedSha.take(16)}… actual=${actualSha.take(16)}…"
                    )
                    return@withContext BackendResult.Failure(
                        BackendError.Runtime(
                            "The bundled Linux environment failed its integrity check and was not installed.",
                            retryable = false
                        )
                    )
                }
            }

            onProgress("Extracting Linux environment…")
            val extracted = extract(staging, root, onProgress)

            val shell = findShell(root)
            if (shell == null) {
                return@withContext BackendResult.Failure(
                    BackendError.Runtime(
                        "The extracted Linux environment has no usable shell.",
                        retryable = false
                    )
                )
            }
            log?.info(
                LogCategory.RUNTIME,
                "Linux userland installed",
                "triple=$triple files=${extracted.files} symlinks=${extracted.symlinks} " +
                    "skipped=${extracted.skipped} shell=${shell.name}"
            )
            BackendResult.Success(
                InstallReport(
                    triple = triple,
                    architecture = architecture,
                    files = extracted.files,
                    symlinks = extracted.symlinks,
                    skipped = extracted.skipped,
                    archiveBytes = archiveBytes
                )
            )
        } catch (e: Exception) {
            BackendResult.Failure(BackendError.wrap(e, category = ErrorCategory.RUNTIME))
        } finally {
            runCatching { if (staging.exists()) staging.delete() }
        }
    }

    private class ExtractCounts(var files: Int = 0, var symlinks: Int = 0, var skipped: Int = 0)

    private fun extract(archive: File, root: File, onProgress: (String) -> Unit): ExtractCounts {
        val counts = ExtractCounts()
        val rootCanonical = root.canonicalPath
        var seen = 0

        GZIPInputStream(archive.inputStream()).use { gzip ->
            UserlandArchive.forEachEntry(gzip) { entry, payload ->
                val relative = safeRelative(entry.name)
                if (relative == null) {
                    counts.skipped++
                    return@forEachEntry
                }
                // Spec 83 - containment is checked on the canonical path, so `a/../../b` and
                // symlink-adjacent spellings cannot escape the userland root.
                val target = File(root, relative)
                val canonical = runCatching { target.canonicalPath }.getOrNull()
                if (canonical == null || (canonical != rootCanonical && !canonical.startsWith(rootCanonical + File.separator))) {
                    counts.skipped++
                    return@forEachEntry
                }
                try {
                    when (entry.type) {
                        UserlandArchive.EntryType.DIRECTORY -> {
                            if (target.exists() && !target.isDirectory) runCatching { target.delete() }
                            target.mkdirs()
                        }
                        UserlandArchive.EntryType.SYMLINK -> {
                            target.parentFile?.mkdirs()
                            runCatching { if (target.exists() || Files.isSymbolicLink(target.toPath())) target.delete() }
                            Files.createSymbolicLink(target.toPath(), Paths.get(entry.linkTarget))
                            counts.symlinks++
                        }
                        UserlandArchive.EntryType.FILE -> {
                            target.parentFile?.mkdirs()
                            if (Files.isSymbolicLink(target.toPath())) runCatching { target.delete() }
                            target.outputStream().use { out -> payload.copyTo(out) }
                            if (shouldBeExecutable(relative, entry.mode)) {
                                runCatching { target.setExecutable(true, false) }
                            }
                            counts.files++
                        }
                        UserlandArchive.EntryType.OTHER -> counts.skipped++
                    }
                } catch (e: Exception) {
                    counts.skipped++
                }
                if (++seen % 1000 == 0) onProgress("Extracted $seen entries…")
            }
        }
        return counts
    }

    /**
     * Archive modes from early payloads are unreliable (decimal/octal mix-up in the first writer),
     * so executability is decided by path as well as by mode: anything the writer meant to be a
     * program matches the same rule the build's `markExecutables` used.
     */
    private fun shouldBeExecutable(relative: String, mode: Int): Boolean {
        if (mode and 0b001_001_001 != 0) return true
        val path = relative.removePrefix("./")
        return path.startsWith("bin/") || path.startsWith("sbin/") ||
            path.contains("/bin/") || path.endsWith("/bash") ||
            path.startsWith("libexec/") || path.startsWith("etc/profile.d/")
    }

    /** A shell counts as present when it exists, following symlinks (spec 113). */
    private fun findShell(root: File): File? =
        listOf("bin/bash", "bin/sh")
            .map { File(root, it) }
            .firstOrNull { it.exists() }

    companion object {
        fun architectureFor(abi: String): String? = when {
            abi.startsWith("arm64") || abi.startsWith("aarch64") -> "aarch64"
            abi.startsWith("armeabi") -> "arm"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "i686"
            else -> null
        }

        fun tripleForArchitecture(architecture: String): String? = when (architecture) {
            "aarch64" -> "aarch64-linux-android"
            "arm" -> "armv7a-linux-androideabi"
            "x86_64" -> "x86_64-linux-android"
            "i686" -> "i686-linux-android"
            else -> null
        }

        /**
         * Spec 83 - map an archive entry to a path inside the userland, or null when it must not
         * be written. Absolute paths, drive letters, backslashes and `..` segments are rejected
         * before any filesystem access.
         */
        fun safeRelative(rawName: String): String? {
            var cleaned = rawName.trim().removePrefix("./")
            while (cleaned.startsWith("./")) cleaned = cleaned.removePrefix("./")
            cleaned = cleaned.trimEnd('/')
            if (cleaned.isEmpty()) return null
            if (cleaned.startsWith("/")) return null
            if (cleaned.contains(":") || cleaned.contains("\\")) return null
            val segments = cleaned.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
            return segments.joinToString("/")
        }
    }
}
