package com.palash.opencodeserver.backend.git

import com.palash.opencodeserver.backend.core.GitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 72 - Git state must be real, never invented.
 *
 * These tests assert the porcelain parser against Git's documented `status --porcelain=v1 -b`
 * output, because that parser is where fabricated state would otherwise creep in.
 *
 * The parser is pure, so nothing here spawns a process.
 */
class GitPorcelainParsingTest {

    private fun parse(output: String) = PorcelainParser.parse(output)

    @Test
    fun parsesBranchAndUpstreamDivergence() {
        val status = parse("## main...origin/main [ahead 2, behind 1]")

        assertTrue(status.isRepository)
        assertEquals("main", status.branch)
        assertEquals(2, status.ahead)
        assertEquals(1, status.behind)
    }

    @Test
    fun parsesBranchWithNoUpstream() {
        val status = parse("## feature/new-branch")

        assertEquals("feature/new-branch", status.branch)
        assertEquals(0, status.ahead)
        assertEquals(0, status.behind)
    }

    @Test
    fun distinguishesStagedFromModified() {
        // "M " (index) is staged; " M" (worktree) is an unstaged modification.
        val status = parse(
            """
            ## main
            M  staged.tsx
             M unstaged.tsx
            """.trimIndent()
        )

        assertEquals(listOf("staged.tsx"), status.staged)
        assertEquals(listOf("unstaged.tsx"), status.modified)
        assertTrue(status.hasChanges)
    }

    @Test
    fun parsesUntrackedFiles() {
        val status = parse(
            """
            ## main
            ?? notes.md
            """.trimIndent()
        )

        assertEquals(listOf("notes.md"), status.untracked)
        assertTrue(status.staged.isEmpty())
        assertTrue(status.modified.isEmpty())
    }

    @Test
    fun parsesDeletedAndAdded() {
        val status = parse(
            """
            ## main
            D  removed.tsx
            A  added.tsx
            """.trimIndent()
        )

        assertTrue(status.staged.containsAll(listOf("removed.tsx", "added.tsx")))
    }

    @Test
    fun parsesConflictedFiles() {
        val status = parse(
            """
            ## main
            UU conflicted.tsx
            """.trimIndent()
        )

        assertEquals(listOf("conflicted.tsx"), status.conflicted)
    }

    @Test
    fun renameUsesTheNewPath() {
        val status = parse(
            """
            ## main
            R  old.tsx -> new.tsx
            """.trimIndent()
        )

        assertEquals(listOf("new.tsx"), status.staged)
    }

    @Test
    fun cleanTreeReportsNoChanges() {
        val status = parse("## main...origin/main")

        assertTrue(status.isRepository)
        assertFalse(status.hasChanges)
    }

    @Test
    fun emptyOutputYieldsNoBranchAndNoChanges() {
        val status = parse("")

        assertNull(status.branch)
        assertFalse(status.hasChanges)
        assertTrue(status.staged.isEmpty())
    }

    @Test
    fun notARepositoryFactoryHasSentinelValues() {
        val status = GitStatus.notARepository()

        assertFalse(status.isRepository)
        assertNull(status.branch)
        assertFalse(status.hasChanges)
    }
}