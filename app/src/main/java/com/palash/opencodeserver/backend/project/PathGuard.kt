package com.palash.opencodeserver.backend.project

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.PathValidation
import java.io.File

/**
 * Spec 83 - path normalisation and traversal containment.
 *
 * The nuance the spec calls out: traversal must be blocked when an operation is meant to be
 * sandboxed, but legitimate developer workflows where the user intentionally picks a directory
 * outside the app sandbox must keep working.
 *
 * Two distinct intents are therefore modelled:
 *  - [normalizeWithin] - the operation is sandboxed to a root, so `../..` must not escape.
 *  - [normalizeAbsolute] - the user explicitly granted access to a directory; the path is validated
 *    for sanity but not confined.
 */
object PathGuard {

    /**
     * Resolve [input] and confirm it stays inside [root].
     *
     * Canonical paths are used so a symlink pointing outside the root is caught, which a naive
     * `startsWith` on the raw string would miss (spec 82, symlink handling).
     */
    fun normalizeWithin(root: File, input: String): PathValidation {
        if (input.isBlank()) return PathValidation(false, null, "Path is empty")

        val rootCanonical = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        val candidateRaw = File(input).let { if (it.isAbsolute) it else File(rootCanonical, input) }
        val candidate = runCatching { candidateRaw.canonicalFile }.getOrElse { candidateRaw.absoluteFile }

        val rootPath = rootCanonical.path
        val candidatePath = candidate.path

        val inside = candidatePath == rootPath || candidatePath.startsWith("$rootPath${File.separator}")
        if (!inside) {
            return PathValidation(
                isValid = false,
                normalized = null,
                reason = "Path escapes the allowed root",
                escapedSandbox = true
            )
        }
        return PathValidation(true, candidatePath)
    }

    /**
     * Validate a user-selected external directory.
     *
     * Checks that it exists, is a directory, is readable, and contains no NUL byte. Traversal
     * segments are allowed here because the user deliberately chose this location.
     */
    fun normalizeAbsolute(input: String): PathValidation {
        if (input.isBlank()) return PathValidation(false, null, "Path is empty")
        if (input.contains('\u0000')) return PathValidation(false, null, "Path contains an invalid character")

        val file = File(input)
        if (!file.exists()) return PathValidation(false, null, "Path does not exist")
        if (!file.isDirectory) return PathValidation(false, null, "Path is not a folder")
        if (!file.canRead()) return PathValidation(false, null, "Path is not readable")

        val canonical = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
        return PathValidation(true, canonical.path)
    }

    /**
     * Spec 83 - reject a project-relative path that tries to climb out.
     *
     * A reason is returned rather than silently cleaning, so the caller can tell the user what was
     * wrong with their input.
     */
    fun rejectTraversal(relativePath: String): PathValidation {
        if (relativePath.isBlank()) return PathValidation(false, null, "Path is empty")
        // A NUL byte truncates the path inside the C library, so "a.tsx\0.txt" could be read as a
        // different file than it appears to be. Reject it before anything touches the filesystem.
        if (relativePath.contains('\u0000')) {
            return PathValidation(false, null, "Path contains an invalid character")
        }
        val segments = relativePath.replace('\\', '/').split('/')
        if (segments.count { it == ".." } > 0) {
            return PathValidation(
                isValid = false,
                normalized = null,
                reason = "Relative paths may not contain '..'",
                escapedSandbox = true
            )
        }
        return PathValidation(true, relativePath.trim('/'))
    }

    /**
     * Resolve a project-relative path against a project root, refusing escapes.
     *
     * This is the guard every file read/write goes through, so `../../etc/passwd` in a UI field can
     * never reach outside the active project.
     */
    fun resolveInProject(projectRoot: File, relativePath: String): BackendResult<File> {
        val traversal = rejectTraversal(relativePath)
        if (!traversal.isValid) {
            return BackendResult.Failure(
                BackendError.InvalidPath(relativePath, traversal.reason ?: "Invalid path")
            )
        }
        val validation = normalizeWithin(projectRoot, traversal.normalized!!)
        if (!validation.isValid) {
            return BackendResult.Failure(
                BackendError.InvalidPath(relativePath, validation.reason ?: "Invalid path")
            )
        }
        return BackendResult.Success(File(validation.normalized!!))
    }
}