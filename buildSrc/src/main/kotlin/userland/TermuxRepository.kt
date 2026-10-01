package userland

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

/**
 * Resolves and downloads a Linux userland from the Termux package repository.
 *
 * Why Termux's repository rather than a Debian or Alpine rootfs:
 *
 *  - Termux publishes packages built **for Android**, linked against Bionic. They run directly in the
 *    app sandbox, so no PRoot, no chroot and no instruction emulation is involved (spec 2).
 *  - It is a real package index per ABI, which is exactly what spec 2 needs: pick the payload to
 *    match the detected architecture, never the other way round.
 *  - It is the same source a developer would otherwise install Termux to use, so reusing it avoids
 *    making users maintain two Linux environments.
 *
 * Licence note: Termux packages are GPL-3.0. Redistribution obligations are recorded in
 * THIRD_PARTY_NOTICES.md and must be satisfied before a release build ships a bundled payload.
 */
object TermuxRepository {

    const val BASE_URL = "https://packages.termux.dev/apt/termux-main"

    /** One entry from the repository index. */
    data class Package(
        val name: String,
        val version: String,
        val architecture: String,
        val filename: String,
        val size: Long,
        val depends: List<DependencyGroup>,
        val provides: List<String>,
        val installedSize: Long
    ) {
        fun downloadUrl(): String = "$BASE_URL/$filename"
    }

    /**
     * One `Depends:` clause.
     *
     * Kept as a group of alternatives rather than a flat list because `a | b` means *one of these*,
     * not both. That distinction matters here: `npm` depends on `nodejs | nodejs-lts`, and resolving
     * both would install two Node runtimes that fight over `bin/node`.
     */
    data class DependencyGroup(val alternatives: List<String>) {
        val preferred: String get() = alternatives.first()
    }

    /** Maps an Android ABI to the repository's architecture directory. */
    fun architectureFor(abi: String): String? = when {
        abi.startsWith("arm64") || abi.startsWith("aarch64") -> "aarch64"
        abi.startsWith("armeabi") -> "arm"
        abi.startsWith("x86_64") -> "x86_64"
        abi.startsWith("x86") -> "i686"
        else -> null
    }

    /** Downloads and parses `Packages.gz` for one architecture. */
    fun fetchIndex(architecture: String, cacheDir: File): Map<String, Package> {
        val target = File(cacheDir, "index-$architecture.txt")
        if (target.isFile && target.length() > 0) {
            return parseIndex(target)
        }

        val url = "$BASE_URL/dists/stable/main/binary-$architecture/Packages.gz"
        val bytes = download(url)
        // The .NET/JDK gzip stream is used directly; the index is a plain gzip stream, not a tarball.
        val text = GZIPInputStream(bytes.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        target.parentFile?.mkdirs()
        target.writeText(text, Charsets.UTF_8)
        return parseIndex(target)
    }

    /**
     * Parses the RFC822-style index.
     *
     * Stanzas are separated by a blank line; fields are `Key: value` with continuation lines
     * indented, which is how the long `Depends` field is wrapped.
     */
    fun parseIndex(file: File): Map<String, Package> {
        val packages = LinkedHashMap<String, Package>()

        for (stanza in file.readText(Charsets.UTF_8).split("\n\n")) {
            if (stanza.isBlank()) continue

            val fields = LinkedHashMap<String, String>()
            var currentKey: String? = null
            for (line in stanza.split("\n")) {
                when {
                    line.startsWith(" ") || line.startsWith("\t") -> {
                        currentKey?.let { fields[it] = fields.getOrDefault(it, "") + " " + line.trim() }
                    }
                    line.contains(':') -> {
                        val key = line.substringBefore(':').trim()
                        val value = line.substringAfter(':').trim()
                        fields[key] = value
                        currentKey = key
                    }
                }
            }

            val name = fields["Package"] ?: continue
            packages[name] = Package(
                name = name,
                version = fields["Version"].orEmpty(),
                architecture = fields["Architecture"].orEmpty(),
                filename = fields["Filename"].orEmpty(),
                size = fields["Size"]?.toLongOrNull() ?: 0L,
                depends = parseDependencies(fields["Depends"].orEmpty()),
                provides = parseDependencies(fields["Provides"].orEmpty()).flatMap { it.alternatives },
                // Installed-Size is expressed in kibibytes in the repository index.
                installedSize = fields["Installed-Size"]?.toLongOrNull()?.times(1024L) ?: 0L
            )
        }

        return packages
    }

    /**
     * Parses a `Depends:` or `Provides:` field into groups.
     *
     * Clauses are comma-separated; within a clause `a | b` separates alternatives, and a trailing
     * `(>= 1.2)` or `:any` constraint is stripped. The version constraint is deliberately not
     * enforced: this task resolves against a single index, so a name either resolves to the right
     * package for this architecture or it does not exist, and failing hard on a marginal version
     * would be more brittle than useful.
     */
    private fun parseDependencies(raw: String): List<DependencyGroup> = raw
        .split(',')
        .mapNotNull { clause ->
            val alternatives = clause
                .split('|')
                .map { it.substringBefore('(').substringBefore(':').trim() }
                .filter { it.isNotEmpty() }
            if (alternatives.isEmpty()) null else DependencyGroup(alternatives)
        }

    /**
     * Resolves the transitive closure of [seedPackages].
     *
     * A breadth-first walk over `Depends`, because a userland is only usable if its libraries came
     * with it. Two details make the result correct rather than merely large:
     *
     *  - `Provides` is honoured, because some packages satisfy a dependency through a virtual name.
     *  - OR-groups take the first alternative that is already resolved. Without this, seeding
     *    `nodejs-lts` *and* `npm` would also pull `nodejs` in and install two conflicting runtimes.
     */
    fun resolveClosure(
        index: Map<String, Package>,
        seedPackages: List<String>
    ): Resolution {
        val byProvides = HashMap<String, String>()
        index.values.forEach { pkg ->
            byProvides.putIfAbsent(pkg.name, pkg.name)
            pkg.provides.forEach { byProvides.putIfAbsent(it, pkg.name) }
        }

        val resolved = LinkedHashMap<String, Package>()

        fun isSatisfied(name: String): Boolean {
            val actual = byProvides[name] ?: name
            return actual in resolved
        }

        // Seeds are themselves OR-groups, so `seedPackages` is folded through the same rule below.
        val queue = ArrayDeque<List<String>>()
        seedPackages.forEach { queue.addLast(listOf(it)) }
        val missing = mutableListOf<String>()

        while (queue.isNotEmpty()) {
            val alternatives = queue.removeFirst()
            if (alternatives.any { isSatisfied(it) }) continue

            val chosen = alternatives.firstOrNull { (byProvides[it] ?: it) in index }
            if (chosen == null) {
                // Record the whole clause: "libfoo | libbar" is more useful to a human than "libfoo".
                missing += alternatives.joinToString(" | ")
                continue
            }

            val actualName = byProvides[chosen] ?: chosen
            if (actualName in resolved) continue

            val pkg = index.getValue(actualName)
            resolved[pkg.name] = pkg
            pkg.depends.forEach { queue.addLast(it.alternatives) }
        }

        return Resolution(
            packages = resolved.values.toList(),
            missing = missing
        )
    }

    /** The transitive closure of a seed set, plus anything that could not be resolved. */
    data class Resolution(val packages: List<Package>, val missing: List<String>) {
        val downloadBytes: Long get() = packages.sumOf { it.size }
        val installedBytes: Long get() = packages.sumOf { it.installedSize }
    }

    fun download(url: String): ByteArray {
        val connection = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 300_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "opencode-android-bootstrap")

        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("GET $url returned HTTP $code")
        }
        return connection.inputStream.use { it.readBytes() }
    }

    /**
     * Extracts a package's `data.tar.*` payload into [destination].
     *
     * Symlinks are recorded into [symlinks] rather than created. A Windows build host cannot create
     * them without elevated privileges, and a link target like `/system/bin/sh` is not a valid
     * Windows path at all. Recording them lets the tar writer emit proper link entries, which the app
     * recreates on device where they work (see TarWriter for why).
     *
     * Returns the number of file entries written, so the caller can assert something was actually
     * installed rather than trusting a zero exit code (spec 113).
     */
    fun extractPackage(
        debFile: File,
        destination: File,
        symlinks: MutableMap<String, String>
    ): Int {
        val dataMember = ArArchive.entries(debFile).firstOrNull { it.name.startsWith("data.tar") }
            ?: error("${debFile.name} has no data.tar member")

        val payload = ArArchive.run {
            val target = File(debFile.parentFile, dataMember.name)
            extractMember(debFile, "data.tar", target)
            target
        }

        return when {
            payload.name.endsWith(".xz") ->
                payload.inputStream().use { raw -> XZInputStream(raw).use { extractTar(it, destination, symlinks) } }
            payload.name.endsWith(".gz") ->
                GZIPInputStream(payload.inputStream()).use { extractTar(it, destination, symlinks) }
            payload.name.endsWith(".zst") -> throw IllegalStateException(
                "${debFile.name} uses zstd, which this build cannot decode. " +
                    "Termux stable ships .xz; a zstd index means the repository changed format."
            )
            else -> payload.inputStream().use { extractTar(it, destination, symlinks) }
        }
    }

    /**
     * Unpack a tar stream into [destination], recording symlinks instead of creating them.
     *
     * Uses `java.nio.file` throughout rather than `java.io.File`. On Windows, `FileOutputStream`
     * rejects any path longer than MAX_PATH with "Invalid file path", and a Termux userland
     * contains paths that exceed it once the build directory prefix is added. NIO transparently
     * uses extended-length paths on Windows, so this works regardless of where the project lives.
     */
    private fun extractTar(
        input: InputStream,
        destination: File,
        symlinks: MutableMap<String, String>
    ): Int {
        val root = destination.toPath()
        var written = 0
        var skipped = 0
        var firstFailure: String? = null

        TarReader(input).use { tar ->
            tar.forEachEntry { entry, payload ->
                val relative = userlandRelative(entry.name) ?: return@forEachEntry

                try {
                    val target = root.resolve(relative)

                    when {
                        entry.isDirectory -> {
                            // A package may install a file where another installed a directory.
                            // Removing the conflicting regular file is correct here because the
                            // archive order is the install order, so the later package wins.
                            if (Files.exists(target) && !Files.isDirectory(target)) {
                                Files.delete(target)
                            }
                            Files.createDirectories(target)
                        }

                        entry.isSymlink -> {
                            target.parent?.let { Files.createDirectories(it) }
                            // A symlink entry may replace a file unpacked by an earlier package.
                            if (Files.exists(target) && !Files.isSymbolicLink(target)) {
                                runCatching { Files.delete(target) }
                            }
                            symlinks[relative] = entry.linkTarget
                        }

                        else -> {
                            target.parent?.let { Files.createDirectories(it) }
                            Files.newOutputStream(target).use { out -> payload.copyTo(out) }
                            // Windows has no POSIX executable bit, so the archive's mode is applied
                            // here instead. 0111 is the "any execute" mask.
                            if (entry.mode and 0b001_001_001 != 0) {
                                runCatching { Files.setPosixFilePermissions(target, executablePermissions) }
                            }
                            written++
                        }
                    }
                } catch (e: Exception) {
                    // One unusable entry must not abandon the rest of a 50 MB package. The count is
                    // carried back to the caller so a payload that is quietly missing files is still
                    // visible, rather than being swallowed here.
                    skipped++
                    if (skipped == 1) firstFailure = "$relative (${e::class.java.simpleName})"
                }
            }
        }

        if (skipped > 0) {
            unusableEntries += skipped
            if (firstFailure != null) firstUnusableEntry = firstFailure
        }
        return written
    }

    /** Entries that could not be written during the most recent [extractPackage] call. */
    var unusableEntries: Int = 0
        private set

    /** The first path that could not be written, for the task log. */
    var firstUnusableEntry: String? = null
        private set

    /** Clears the per-call counters so the next package's skips are reported on their own. */
    fun resetUnusable() {
        unusableEntries = 0
        firstUnusableEntry = null
    }

    /** 0755, expressed as POSIX permissions. */
    private val executablePermissions: Set<java.nio.file.attribute.PosixFilePermission> =
        java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")

    /**
     * The path an archive entry takes inside the userland, or null if it belongs there at all.
     *
     * A Termux package lays its files out as:
     *
     * ```text
     * ./data/data/com.termux/files/usr/bin/bash     <- the $PREFIX that Termux itself uses
     * ./data/data/com.termux/files/home/.profile    <- a user's dotfile
     * ```
     *
     * The payload root *is* that `$PREFIX`, so the archive path becomes `bin/bash`, `lib/…`,
     * `share/…`. That is what makes `PATH=<root>/bin` resolve the same binaries Termux would run,
     * and it is what `EnvironmentBuilder` expects to find.
     *
     * Returns null rather than a guessed path for anything outside the prefix, which keeps a
     * crafted archive from writing anywhere it likes (spec 83).
     */
    fun userlandRelative(archivePath: String): String? {
        val cleaned = archivePath.removePrefix("./").trimEnd('/')
        if (cleaned.isEmpty()) return null

        val relative = when {
            cleaned.startsWith(TERMUX_PREFIX) ->
                cleaned.removePrefix(TERMUX_PREFIX).ifEmpty { return null }
            cleaned.startsWith(TERMUX_HOME) ->
                cleaned.removePrefix(TERMUX_HOME).ifEmpty { return null }
            else -> return null
        }

        // A leading slash, a drive letter or a `..` segment would escape the userland root.
        if (relative.startsWith("/") || relative.contains(":")) return null
        if (relative.split('/').any { it == ".." }) return null

        return relative.ifEmpty { null }
    }

    /** Termux's install prefix, relative to a package archive root. */
    private const val TERMUX_PREFIX = "data/data/com.termux/files/usr/"

    /** Termux's default home, kept separate because it must not shadow the app's own HOME. */
    private const val TERMUX_HOME = "data/data/com.termux/files/home/"

    /**
     * Makes the tree executable.
     *
     * Applied after extraction because a Windows checkout does not carry POSIX modes, and without it
     * nothing in the userland would be runnable once it reached Android. On a POSIX host the archive's
     * own modes are already correct, so this is a no-op there.
     */
    fun markExecutables(root: File): Int {
        var count = 0
        root.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val relative = file.relativeTo(root).invariantSeparatorsPath
            // Everything under bin/ or sbin/ is an executable, as are the shared libraries' loaders.
            val shouldBeExecutable = relative.startsWith("bin/") || relative.startsWith("sbin/") ||
                relative.contains("/bin/") || relative.endsWith("/bash") ||
                relative.startsWith("libexec/") || relative.startsWith("etc/profile.d/")
            if (shouldBeExecutable) {
                file.setExecutable(true, false)
                count++
            }
        }
        return count
    }

    /**
     * The executable bit for a host checkout, which is what the tar writer records.
     *
     * A Windows host has no such bit, so the archive's mode comes from [modeFor] instead: anything
     * the tar marked executable, plus everything the tar writer infers from the path.
     */
    fun isExecutableHostFile(file: File, root: File): Boolean =
        file.canExecute() || modeFor(file.relativeTo(root).invariantSeparatorsPath) != null

    /** 0755 for anything that is a program, 0644 otherwise. Null means "infer from mode". */
    fun modeFor(relativePath: String): Int? = when {
        relativePath.startsWith("bin/") || relativePath.startsWith("sbin/") -> 0b111_101_101
        relativePath.contains("/bin/") -> 0b111_101_101
        relativePath.startsWith("libexec/") -> 0b111_101_101
        relativePath.endsWith("/bash") -> 0b111_101_101
        relativePath.startsWith("etc/profile.d/") -> 0b111_101_101
        else -> null
    }

    fun architectureLabel(architecture: String): String = architecture.lowercase(Locale.US)
}