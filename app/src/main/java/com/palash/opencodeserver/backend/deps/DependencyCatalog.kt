package com.palash.opencodeserver.backend.deps

/**
 * Spec 7 - the single declaration point for every managed dependency.
 *
 * Adding a tool means adding one entry here. No install logic is hard-coded anywhere else in the
 * app: [com.palash.opencodeserver.backend.deps.DependencyManager] reads this catalogue, and the first-run wizard,
 * repair flow and diagnostics all derive their steps from it.
 *
 * [installMethod] is symbolic rather than a command, so the concrete command is chosen by whichever
 * [com.palash.opencodeserver.backend.userland.PackageManager] the detected runtime provides (spec 9).
 */
enum class DependencyId(val displayName: String, val purpose: String) {
    RUNTIME("Terminal runtime", "A Linux userland able to run developer binaries"),
    SHELL("Shell", "Interactive command line"),
    CORE_UTILITIES("Core utilities", "ls, cat, grep and friends"),
    PACKAGE_MANAGER("Package manager", "Installs and updates tools"),
    GIT("Git", "Version control"),
    NODE("Node.js", "JavaScript runtime, required by the npm install path"),
    OPENCODE("OpenCode", "The coding agent"),
    TEST_PROJECT("Test project", "Optional sample project for verification")
}

/** How a dependency is obtained. The runtime decides the concrete package manager. */
enum class InstallMethod(val label: String) {
    /** Nothing to install; the platform provides it. */
    BUILT_IN("Built in"),

    /** Installed by the detected package manager. */
    PACKAGE_MANAGER("Package manager"),

    /** Installed from a published npm package inside the userland. */
    NPM("npm package"),

    /** Extracted from the project's own release artifacts. */
    RELEASE_BINARY("Release binary"),

    /** Created by the app itself. */
    APP_MANAGED("Managed by the app")
}

/** Version constraint. `null` means "any version is acceptable". */
data class VersionConstraint(
    val minimum: String? = null,
    val exact: String? = null
) {
    fun accepts(version: String?): Boolean {
        val actual = version?.let { normalize(it) } ?: return false
        if (exact != null) return actual == normalize(exact)
        val floor = minimum?.let { normalize(it) } ?: return true
        return compare(actual, floor) >= 0
    }

    fun describe(): String = when {
        exact != null -> "exactly $exact"
        minimum != null -> "$minimum or newer"
        else -> "any version"
    }

    companion object {
        fun normalize(version: String): String =
            version.trim().removePrefix("v").lineSequence().first().trim().lowercase()

        /**
         * Compare dotted numeric versions, tolerating suffixes like `-arm64` or `-rc1`.
         *
         * A pre-release suffix sorts **below** the same version without one, which is the semantic
         * versioning rule. An architecture suffix such as `-arm64` is not ordered at all, because
         * it does not make a version newer or older.
         */
        fun compare(a: String, b: String): Int {
            val numericA = numericParts(a)
            val numericB = numericParts(b)

            for (i in 0 until maxOf(numericA.size, numericB.size)) {
                val left = numericA.getOrNull(i) ?: 0
                val right = numericB.getOrNull(i) ?: 0
                if (left != right) return left.compareTo(right)
            }

            return when {
                isPreRelease(a) && !isPreRelease(b) -> -1
                !isPreRelease(a) && isPreRelease(b) -> 1
                else -> 0
            }
        }

        private fun numericParts(version: String): List<Int> =
            version.substringBefore('-').split('.').map { part -> part.toIntOrNull() ?: 0 }

        /** Recognises `-rc1`, `-alpha`, `-beta`, `-pre` and `-dev`, but not `-arm64`. */
        private fun isPreRelease(version: String): Boolean {
            val suffix = version.substringAfter('-', "").lowercase()
            if (suffix.isEmpty()) return false
            return PRE_RELEASE_MARKERS.any { suffix.startsWith(it) }
        }

        private val PRE_RELEASE_MARKERS =
            listOf("rc", "alpha", "beta", "pre", "dev", "snapshot", "nightly")
    }
}

/**
 * One catalogue entry.
 *
 * [packages] are the names tried against the detected package manager, in order. Several are listed
 * because distributions disagree on naming (`nodejs` on Debian, `nodejs` on Arch, `node` elsewhere).
 * A name that is not available in the current distribution simply fails and the next is tried.
 */
data class DependencySpec(
    val id: DependencyId,
    val version: VersionConstraint = VersionConstraint(),
    val installMethod: InstallMethod,
    /** Executables that must resolve on PATH for this dependency to count as installed. */
    val executables: List<String> = emptyList(),
    /** Package names to try against the package manager. */
    val packages: List<String> = emptyList(),
    /** The flag used to read a version string. */
    val versionArgs: List<String> = listOf("--version"),
    /** npm package name, when [installMethod] is [InstallMethod.NPM]. */
    val npmPackage: String? = null,
    /** Whether the server cannot start without this. */
    val requiredForServer: Boolean = true,
    /** Approximate download requirement, used for the storage precheck (spec 74). */
    val estimatedBytes: Long = 0L,
    val optional: Boolean = false
) {
    /** Convenience accessor so callers never have to reach through [id] for a label. */
    val displayName: String get() = id.displayName

    fun detectCommands(): List<List<String>> = executables.map { listOf(it) + versionArgs }
}

/**
 * Spec 7 - every dependency the app manages, declared once.
 *
 * Order matters: it is the install order, because later entries depend on earlier ones (OpenCode's
 * npm path needs Node, which needs the runtime).
 */
object DependencyCatalog {

    val all: List<DependencySpec> = listOf(
        DependencySpec(
            id = DependencyId.RUNTIME,
            installMethod = InstallMethod.APP_MANAGED,
            executables = listOf("sh"),
            requiredForServer = true,
            estimatedBytes = 120L * 1024 * 1024
        ),
        DependencySpec(
            id = DependencyId.SHELL,
            installMethod = InstallMethod.PACKAGE_MANAGER,
            executables = listOf("bash"),
            packages = listOf("bash"),
            versionArgs = listOf("--version"),
            // A missing bash is not fatal: the runtime falls back to sh (spec 34).
            requiredForServer = false
        ),
        DependencySpec(
            id = DependencyId.CORE_UTILITIES,
            installMethod = InstallMethod.PACKAGE_MANAGER,
            executables = listOf("ls", "cat", "grep"),
            packages = listOf("coreutils", "grep"),
            requiredForServer = true
        ),
        DependencySpec(
            id = DependencyId.PACKAGE_MANAGER,
            installMethod = InstallMethod.BUILT_IN,
            executables = listOf("sh"),
            requiredForServer = false
        ),
        DependencySpec(
            id = DependencyId.GIT,
            version = VersionConstraint(minimum = "2.20"),
            installMethod = InstallMethod.PACKAGE_MANAGER,
            executables = listOf("git"),
            packages = listOf("git"),
            requiredForServer = false, // Useful, but the server runs without it.
            estimatedBytes = 20L * 1024 * 1024
        ),
        DependencySpec(
            id = DependencyId.NODE,
            version = VersionConstraint(minimum = "18"),
            installMethod = InstallMethod.PACKAGE_MANAGER,
            executables = listOf("node"),
            packages = listOf("nodejs", "node", "nodejs-lts"),
            versionArgs = listOf("--version"),
            requiredForServer = false, // Only the npm install path needs it.
            estimatedBytes = 90L * 1024 * 1024
        ),
        DependencySpec(
            id = DependencyId.OPENCODE,
            installMethod = InstallMethod.RELEASE_BINARY,
            executables = listOf("opencode"),
            versionArgs = listOf("--version"),
            // The npm package name documented by OpenCode for the npm install path.
            npmPackage = "opencode-ai",
            requiredForServer = true,
            estimatedBytes = 80L * 1024 * 1024
        ),
        DependencySpec(
            id = DependencyId.TEST_PROJECT,
            installMethod = InstallMethod.APP_MANAGED,
            optional = true,
            requiredForServer = false
        )
    )

    fun byId(id: DependencyId): DependencySpec = all.first { it.id == id }

    /** Spec 100 - the checkpoints that correspond to catalogue entries. */
    fun checkpointFor(id: DependencyId): com.palash.opencodeserver.backend.core.SetupCheckpoint =
        when (id) {
            DependencyId.RUNTIME -> com.palash.opencodeserver.backend.core.SetupCheckpoint.RUNTIME_READY
            DependencyId.PACKAGE_MANAGER -> com.palash.opencodeserver.backend.core.SetupCheckpoint.PACKAGE_MANAGER_READY
            DependencyId.GIT -> com.palash.opencodeserver.backend.core.SetupCheckpoint.GIT_READY
            DependencyId.NODE -> com.palash.opencodeserver.backend.core.SetupCheckpoint.NODE_READY
            DependencyId.OPENCODE -> com.palash.opencodeserver.backend.core.SetupCheckpoint.OPENCODE_READY
            else -> com.palash.opencodeserver.backend.core.SetupCheckpoint.CONFIG_READY
        }

    /** Total estimated download for a storage precheck (spec 74). */
    fun requiredBytes(includeOptional: Boolean = false): Long =
        all.filter { includeOptional || !it.optional }.sumOf { it.estimatedBytes }
}
