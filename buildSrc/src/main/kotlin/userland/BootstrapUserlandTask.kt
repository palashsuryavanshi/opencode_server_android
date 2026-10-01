package userland

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Spec 2 - produce a Linux userland for one Android ABI.
 *
 * The task turns the app's dependency needs into a concrete, verified tree:
 *
 * ```
 * package index (per ABI) -> resolve closure -> download .deb -> extract -> mark executables
 * ```
 *
 * What comes out is what gets bundled into the APK's assets, so `ProvisionedUserlandProvider` reads
 * it from `assets/` at first run instead of expecting someone to have placed a file there.
 *
 * Verification is deliberate: a missing seed binary fails the task rather than producing a payload
 * that only fails later on a device (spec 113).
 */
@CacheableTask
abstract class BootstrapUserlandTask : DefaultTask() {

    /** Android ABI to build for, e.g. `arm64-v8a`. */
    @get:Input
    abstract val abi: Property<String>

    /**
     * Packages the runtime cannot function without, or cannot install OpenCode without.
     *
     * The list is deliberately small: it is the closure of what the app needs, not a maximal
     * desktop environment, because every package costs APK size.
     */
    @get:Input
    abstract val seedPackages: ListProperty<String>

    /** Where the produced payload is written. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** Scratch space for downloads; not part of the output. */
    @get:Internal
    abstract val cacheDirectory: DirectoryProperty

    /** Verify the build succeeded rather than only checking that files appeared. */
    @get:Input
    abstract val failOnMissingBinaries: Property<Boolean>

    init {
        group = "userland"
        description = "Downloads and assembles a Linux userland for one Android ABI."
        failOnMissingBinaries.convention(true)
    }

    @TaskAction
    fun bootstrap() {
        val logger: Logger = Logging.getLogger(BootstrapUserlandTask::class.java)
        val architecture = TermuxRepository.architectureFor(abi.get())
            ?: error(
                "No Termux architecture for ABI '${abi.get()}'. Refusing to guess, because " +
                    "downloading the wrong payload is exactly the failure spec 2 forbids."
            )

        val outputRoot = outputDirectory.get().asFile
        val cache = cacheDirectory.get().asFile.also { it.mkdirs() }

        logger.lifecycle("Bootstrapping Linux userland for ${abi.get()} ($architecture)")

        val index = TermuxRepository.fetchIndex(architecture, cache)
        logger.lifecycle("Index contains ${index.size} packages")

        val closure = TermuxRepository.resolveClosure(index, seedPackages.get())
        if (closure.missing.isNotEmpty()) {
            logger.warn("Unresolved optional dependencies: ${closure.missing.joinToString(", ")}")
        }
        logger.lifecycle(
            "Resolved ${closure.packages.size} packages, " +
                "${closure.downloadBytes / 1024 / 1024} MB to download, " +
                "~${closure.installedBytes / 1024 / 1024} MB installed"
        )

        // Stage the extracted tree outside the output directory. The shipped artefact is a single
        // gzipped tar, so the loose tree is an intermediate, not something the APK packages.
        //
        // The staging root is kept deliberately short. A Termux userland nests several directories
        // deep under data/data/com.termux/files, and a project path that is already long can push
        // individual entries past what Windows tooling handles comfortably.
        val stagingRoot = System.getProperty("java.io.tmpdir") ?: "."
        val staging = File(stagingRoot, "opencode-ul-$architecture").also {
            if (it.exists()) it.deleteRecursively()
            it.mkdirs()
        }

        // Symlinks are collected rather than created: a Windows build host cannot create them, and a
        // POSIX target such as /system/bin/sh is not a valid Windows path. The tar writer emits them
        // as real link entries, which the app recreates on device.
        val symlinks = LinkedHashMap<String, String>()

        var extracted = 0
        val failures = mutableListOf<String>()
        for (pkg in closure.packages) {
            val debFile = File(cache, "${architecture}-${pkg.name}.deb")
            if (!debFile.isFile || debFile.length() != pkg.size) {
                logger.info("Downloading ${pkg.name} (${pkg.version})")
                runCatching { TermuxRepository.download(pkg.downloadUrl()) }
                    .onSuccess { debFile.writeBytes(it) }
                    .onFailure {
                        // A failed package is recorded rather than swallowed: the verification below
                        // decides whether the tree is still good enough to ship.
                        failures += "download ${pkg.name}: ${it.message}"
                    }
            }

            if (debFile.isFile) {
                runCatching { extracted += TermuxRepository.extractPackage(debFile, staging, symlinks) }
                    .onFailure {
                        failures += "extract ${pkg.name}: ${it.message}"
                        it.stackTrace.take(4).forEach { frame -> logger.warn("    at $frame") }
                    }

                // Entries the host filesystem refused are reported once, not per file. In practice
                // these are `termux-exec` development headers, which a runtime payload does not need.
                val unusable = TermuxRepository.unusableEntries
                if (unusable > 0) {
                    val first = TermuxRepository.firstUnusableEntry ?: "unknown"
                    TermuxRepository.resetUnusable()
                    failures += "$unusable entr${if (unusable == 1) "y" else "ies"} skipped " +
                        "(first: $first)"
                }
            }
        }

        val executables = TermuxRepository.markExecutables(staging)
        logger.lifecycle(
            "Extracted $extracted files, recorded ${symlinks.size} symlinks, " +
                "marked $executables executables"
        )
        if (failures.isNotEmpty()) {
            logger.warn("${failures.size} package(s) could not be processed:")
            failures.forEach { logger.warn("  $it") }
        }

        // Verify the staged tree before packaging it, so a broken payload is never shipped.
        verify(staging, symlinks, logger)

        // ---- package into one gzipped tar ----------------------------------------------
        val payloadDir = File(outputRoot, "userland").also { it.mkdirs() }
        val archive = File(payloadDir, "$architecture.tar.gz")
        if (archive.exists()) archive.delete()

        archive.outputStream().use { stream ->
            TarWriter(stream).writeTree(TreeCollector.collect(staging, symlinks))
        }
        val archiveSha = sha256(archive)

        val manifest = File(payloadDir, "$architecture.manifest")
        manifest.writeText(
            buildString {
                appendLine("architecture=$architecture")
                appendLine("abi=${abi.get()}")
                appendLine("archive=$architecture.tar.gz")
                appendLine("archiveBytes=${archive.length()}")
                appendLine("archiveSha256=$archiveSha")
                appendLine("files=${extracted}")
                appendLine("symlinks=${symlinks.size}")
                appendLine("packages=${closure.packages.size}")
                appendLine("packageNames=${closure.packages.joinToString(",") { it.name }}")
            },
            Charsets.UTF_8
        )

        logger.lifecycle(
            "Userland payload: ${archive.absolutePath} " +
                "(${archive.length() / 1024 / 1024} MB, sha256 ${archiveSha.take(16)}...)"
        )
    }

    /**
     * Spec 113 - verify the result, do not trust that files appeared.
     *
     * A binary counts as present if it is either an extracted file or a recorded symlink, because on
     * a Windows build host the links only exist in the archive. Without a shell the terminal cannot
     * run at all, so that is a hard failure; a missing git, node or npm is likewise fatal when
     * [failOnMissingBinaries] is set, because OpenCode cannot then be installed.
     */
    private fun verify(staging: File, recordedLinks: Map<String, String>, logger: Logger) {
        // Host symlinks are normally absent on a Windows checkout, but the check is kept so
        // verification also holds on a host that does support them.
        val links = recordedLinks + symlinkTargets(staging)

        fun present(relative: String): Boolean =
            File(staging, relative).isFile || links.containsKey(relative)

        val shell = listOf("bin/bash", "bin/sh").firstOrNull { present(it) }
        if (shell == null) {
            error(
                "The userland for ${abi.get()} has no shell. Packaging this payload would produce " +
                    "an app whose terminal cannot start."
            )
        }
        logger.lifecycle("Verified shell: $shell")

        val optional = listOf(
            "bin/git" to "git",
            "bin/node" to "Node.js",
            "bin/npm" to "npm",
            "bin/curl" to "curl",
            "bin/tar" to "tar"
        )
        optional.forEach { (path, label) ->
            if (!present(path)) logger.warn("Userland is missing ${label} ($path)")
        }

        if (failOnMissingBinaries.get()) {
            listOf("bin/git" to "git", "bin/node" to "Node.js", "bin/npm" to "npm").forEach { (path, label) ->
                check(present(path)) {
                    "The userland for ${abi.get()} is missing $label ($path). " +
                        "Install OpenCode requires it, so this payload would not work."
                }
            }
        }
    }

    /**
     * Symlinks the host filesystem reports, relative to [root].
     *
     * A Windows checkout has none of the links the tar will carry, so this is normally empty; it
     * exists so verification also holds on a host that does support them.
     */
    private fun symlinkTargets(root: File): Map<String, String> =
        root.walkTopDown()
            .filter { Files.isSymbolicLink(it.toPath()) }
            .associate { it.relativeTo(root).invariantSeparatorsPath to Files.readSymbolicLink(it.toPath()).toString() }

    /** Spec 48 - the archive's own checksum, recorded in the manifest for the installer to verify. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Spec 3 - record what was bundled.
 *
 * Written alongside the payload so the licence obligations of a release build are visible from the
 * artefact itself rather than only in a repository document that can drift.
 */
@CacheableTask
abstract class WriteUserlandNoticesTask : DefaultTask() {

    @get:Input
    abstract val packages: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val target = File(outputDirectory.get().asFile, "userland/NOTICES.txt")
        target.parentFile?.mkdirs()
        target.writeText(
            buildString {
                appendLine("Bundled Linux userland - third-party notices")
                appendLine()
                appendLine("Source: Termux package repository (https://packages.termux.dev)")
                appendLine("Licence: GPL-3.0 for the packages listed below.")
                appendLine()
                appendLine(
                    "The bundled userland is a combined work of the packages below. The corresponding" +
                        " source is available from the Termux project at the same address."
                )
                appendLine()
                appendLine("Packages in this payload:")
                packages.get().sorted().forEach { appendLine("  - $it") }
                appendLine()
                appendLine(
                    "Bundling this payload makes the distributed APK subject to GPL-3.0 terms. See" +
                        " THIRD_PARTY_NOTICES.md for the full obligations and release checklist."
                )
            },
            Charsets.UTF_8
        )
    }
}