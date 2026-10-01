package com.palash.opencodeserver.backend.project

import com.palash.opencodeserver.backend.core.BackendResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Spec 82/83 - path security.
 *
 * The spec's own example is `../../something`, so that exact input is the primary case here, along
 * with the symlink variant that a naive string-prefix check would miss.
 */
class PathGuardTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun projectRoot(): File {
        val root = temp.newFolder("project")
        File(root, "src").mkdirs()
        File(root, "src/App.tsx").writeText("content")
        return root
    }

    // ---- sandboxed containment ------------------------------------------------------------

    @Test
    fun relativePathInsideRootIsAccepted() {
        val validation = PathGuard.normalizeWithin(projectRoot(), "src/App.tsx")
        assertTrue(validation.isValid)
        assertFalse(validation.escapedSandbox)
    }

    @Test
    fun traversalOutOfRootIsRejected() {
        val validation = PathGuard.normalizeWithin(projectRoot(), "../../etc/passwd")
        assertFalse(validation.isValid)
        assertTrue(validation.escapedSandbox)
        assertTrue(validation.reason!!.contains("escapes"))
    }

    @Test
    fun traversalInsideRootIsRejectedEvenIfItResolvesInside() {
        // A path that climbs out and back in is still rejected: intent, not outcome, is what matters
        // for a UI-supplied relative path.
        val root = projectRoot()
        val validation = PathGuard.rejectTraversal("src/../../${root.name}/src/App.tsx")
        assertFalse(validation.isValid)
        assertTrue(validation.escapedSandbox)
    }

    @Test
    fun traversalUsingBackslashesIsRejected() {
        val validation = PathGuard.rejectTraversal("..\\..\\etc\\passwd")
        assertFalse(validation.isValid)
        assertTrue(validation.escapedSandbox)
    }

    @Test
    fun rejectTraversal_normalisesSeparators() {
        val validation = PathGuard.rejectTraversal("/src/App.tsx")
        assertTrue(validation.isValid)
        assertEquals("src/App.tsx", validation.normalized)
    }

    @Test
    fun symlinkPointingOutsideRootIsRejected() {
        // A canonical-path check is what catches this; a string prefix on the raw path would pass.
        val root = projectRoot()
        val outside = temp.newFolder("outside")
        File(outside, "secret.txt").writeText("secret")
        val link = File(root, "escape")
        val linked = runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) }
            .getOrNull()

        if (linked == null) {
            // Symlinks are unavailable on this filesystem; the canonical check is still exercised by
            // the traversal cases above.
            return
        }

        val validation = PathGuard.normalizeWithin(root, "escape/secret.txt")
        assertFalse(validation.isValid)
        assertTrue(validation.escapedSandbox)
    }

    @Test
    fun absolutePathInsideRootIsAccepted() {
        val root = projectRoot()
        val target = File(root, "src/App.tsx")
        val validation = PathGuard.normalizeWithin(root, target.absolutePath)
        assertTrue(validation.isValid)
    }

    @Test
    fun emptyPathIsRejected() {
        assertFalse(PathGuard.normalizeWithin(projectRoot(), "").isValid)
        assertFalse(PathGuard.rejectTraversal("").isValid)
    }

    // ---- external selection ------------------------------------------------------------------

    @Test
    fun existingDirectoryIsAcceptedAsExternal() {
        val dir = temp.newFolder("external")
        val validation = PathGuard.normalizeAbsolute(dir.absolutePath)
        assertTrue(validation.isValid)
    }

    @Test
    fun missingDirectoryIsRejectedWithReason() {
        val validation = PathGuard.normalizeAbsolute(File(temp.root, "nope").absolutePath)
        assertFalse(validation.isValid)
        assertTrue(validation.reason!!.contains("does not exist"))
    }

    @Test
    fun fileIsRejectedBecauseItIsNotAFolder() {
        val file = temp.newFile("plain.txt")
        val validation = PathGuard.normalizeAbsolute(file.absolutePath)
        assertFalse(validation.isValid)
        assertTrue(validation.reason!!.contains("not a folder"))
    }

    @Test
    fun nullByteIsRejected() {
        val validation = PathGuard.normalizeAbsolute("/tmp/evil\u0000.txt")
        assertFalse(validation.isValid)
        assertTrue(validation.reason!!.contains("invalid character"))
    }

    // ---- resolution through the API ----------------------------------------------------------------

    @Test
    fun resolveInProject_returnsFileInsideRoot() {
        val root = projectRoot()
        val resolved = PathGuard.resolveInProject(root, "src/App.tsx")
        assertTrue(resolved is BackendResult.Success)
        assertEquals(File(root, "src/App.tsx").canonicalPath, (resolved as BackendResult.Success).value.canonicalPath)
    }

    @Test
    fun resolveInProject_rejectsTraversal() {
        val resolved = PathGuard.resolveInProject(projectRoot(), "../../etc/passwd")
        assertTrue(resolved is BackendResult.Failure)
    }

    @Test
    fun resolveInProject_rejectsNulByte() {
        val resolved = PathGuard.resolveInProject(projectRoot(), "src/App\u0000.tsx")
        assertTrue(resolved is BackendResult.Failure)
    }
}