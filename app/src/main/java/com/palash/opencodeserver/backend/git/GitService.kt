package com.palash.opencodeserver.backend.git

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.GitStatus
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.runtime.ProcessRunner
import java.io.File

/**
 * Spec 72 - real Git state.
 *
 * The spec says "do not invent Git state", so every field here comes from parsing Git's own
 * machine-readable output:
 *
 *  - `git status --porcelain=v1 -b` for branch, staged, modified, untracked, conflicted
 *  - `git rev-list --left-right --count HEAD...@{upstream}` for ahead/behind
 *
 * A non-repository yields [GitStatus.notARepository] rather than an exception, because "this folder
 * is not under version control" is normal, not an error.
 *
 * All commands are argument arrays; no user input is ever concatenated into a shell string (spec 84).
 */
class GitService(
    private val runner: ProcessRunner,
    private val log: LogManager
) {

    suspend fun status(projectDirectory: String): BackendResult<GitStatus> {
        val dir = File(projectDirectory)
        if (!dir.isDirectory) {
            return BackendResult.Failure(
                BackendError.Project(
                    projectName = dir.name,
                    technical = "Directory does not exist: $projectDirectory",
                    requiresSelection = false
                )
            )
        }

        val inside = runGit(dir, listOf("rev-parse", "--is-inside-work-tree"))
        if (inside.exitCode != 0) {
            log.debug(LogCategory.PROJECT, "Not a git repository", projectDirectory)
            return BackendResult.Success(GitStatus.notARepository())
        }

        val porcelain = runGit(dir, listOf("status", "--porcelain=v1", "-b"))
        if (porcelain.exitCode != 0) {
            return BackendResult.Failure(
                BackendError.Categorized(
                    technical = "git status exited ${porcelain.exitCode}: ${porcelain.stderr.take(300)}",
                    category = com.palash.opencodeserver.backend.core.ErrorCategory.PROJECT,
                    retryable = true,
                    userTitle = "Git status could not be read.",
                    userMessage = "Try again, or repair Git from Settings."
                )
            )
        }

        val parsed = parsePorcelain(porcelain.stdout)
        val aheadBehind = runGit(dir, listOf("rev-list", "--left-right", "--count", "HEAD...@{upstream}"))
            .stdout
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }
            .takeIf { it.size == 2 }

        return BackendResult.Success(
            parsed.copy(
                ahead = aheadBehind?.getOrNull(0) ?: 0,
                behind = aheadBehind?.getOrNull(1) ?: 0
            )
        )
    }

    /** Spec 72 - stage one file. */
    suspend fun stageFile(projectDirectory: String, relativePath: String): BackendResult<Unit> =
        git(dir = projectDirectory, args = listOf("add", "--", relativePath), label = "git add")

    /** Spec 72 - stage everything. */
    suspend fun stageAll(projectDirectory: String): BackendResult<Unit> =
        git(dir = projectDirectory, args = listOf("add", "-A"), label = "git add -A")

    /** Spec 72 - commit. */
    suspend fun commit(projectDirectory: String, message: String): BackendResult<Unit> =
        git(dir = projectDirectory, args = listOf("commit", "-m", message), label = "git commit")

    /** Spec 72 - create a repository in a managed project. */
    suspend fun initRepository(projectDirectory: String): BackendResult<Unit> =
        git(dir = projectDirectory, args = listOf("init"), label = "git init")

    /** Spec 72 - current branch name. */
    suspend fun currentBranch(projectDirectory: String): String? =
        runGit(File(projectDirectory), listOf("rev-parse", "--abbrev-ref", "HEAD"))
            .stdout
            .trim()
            .takeIf { it.isNotEmpty() && it != "HEAD" }

    private suspend fun git(dir: String, args: List<String>, label: String): BackendResult<Unit> {
        val result = runGit(File(dir), args)
        return if (result.exitCode == 0) {
            BackendResult.Success(Unit)
        } else {
            log.warn(LogCategory.PROJECT, "Git command failed", "$label exit=${result.exitCode}")
            BackendResult.Failure(
                BackendError.Categorized(
                    technical = "$label exit=${result.exitCode}: ${result.stderr.take(300)}",
                    category = com.palash.opencodeserver.backend.core.ErrorCategory.PROJECT,
                    // A rejected commit (nothing staged, missing identity) must not be auto-retried.
                    retryable = false,
                    userTitle = "That Git command did not succeed.",
                    userMessage = "Review the change in the Git panel and try again."
                )
            )
        }
    }

    private suspend fun runGit(dir: File, args: List<String>): ProcessRunner.CommandResult =
        runner.run(
            ProcessRunner.CommandSpec(
                command = "git",
                arguments = args,
                workingDirectory = dir.absolutePath,
                timeoutMs = 20_000
            )
        )

    /**
     * Parse `git status --porcelain=v1 -b`.
     *
     * Format: two status characters then a space and the path. `XY` where X is the index state and Y
     * is the worktree state. A branch header line starts with `##`.
     *
     * This lives in the companion so it can be tested without spawning git; it is pure string work.
     */
    internal fun parsePorcelain(output: String): GitStatus = PorcelainParser.parse(output)
}

/** Spec 72 - the porcelain parser, isolated because it is pure and security-relevant. */
internal object PorcelainParser {

    fun parse(output: String): GitStatus {
        var branch: String? = null
        var ahead = 0
        var behind = 0
        val staged = mutableListOf<String>()
        val modified = mutableListOf<String>()
        val untracked = mutableListOf<String>()
        val conflicted = mutableListOf<String>()

        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.startsWith("##") -> {
                    val header = line.removePrefix("##").trim()
                    // "main...origin/main [ahead 2, behind 1]" or "main"
                    branch = header.substringBefore("...").trim().takeIf { it.isNotEmpty() }
                    Regex("ahead (\\d+)").find(header)?.let { ahead = it.groupValues[1].toIntOrNull() ?: 0 }
                    Regex("behind (\\d+)").find(header)?.let { behind = it.groupValues[1].toIntOrNull() ?: 0 }
                }
                line.length < 3 -> Unit
                else -> {
                    val index = line[0]
                    val worktree = line[1]
                    val path = line.substring(3).trim().removePrefix("\"")
                    when {
                        index == '?' && worktree == '?' -> untracked += path
                        index == 'U' || worktree == 'U' || (index == 'A' && worktree == 'A') ||
                            (index == 'D' && worktree == 'D') -> conflicted += path
                        // Renames arrive as "old -> new".
                        index == 'R' -> staged += path.substringAfter(" -> ")
                        index != ' ' && index != '?' -> staged += path
                        worktree != ' ' -> modified += path
                        else -> Unit
                    }
                }
            }
        }

        return GitStatus(
            isRepository = true,
            branch = branch,
            ahead = ahead,
            behind = behind,
            modified = modified,
            staged = staged,
            untracked = untracked,
            conflicted = conflicted,
            rawPorcelain = output.take(2_000)
        )
    }
}