package com.palash.opencodeserver.backend.project

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.runtime.RuntimeLayout
import java.io.File

/**
 * Spec 28/29/30/53 - project registry.
 *
 * Projects live in two places and the difference is surfaced, not hidden:
 *  - **managed**: created by the app under `~/projects`, fully under app control
 *  - **external**: a directory the user picked through the Android file picker, which may be on
 *    shared storage the app can only reach through a SAF grant
 *
 * Removing a project only unregisters it. Deleting files requires a separate, explicitly confirmed
 * call (spec 28/58).
 */
class ProjectManager(
    private val layout: RuntimeLayout,
    private val log: LogManager
) {

    private val store = ProjectStore(layout, log)

    /** Spec 28 - list, with the active project flagged. */
    suspend fun listProjects(): BackendResult<List<ProjectRecordInternal>> =
        store.read().map { records -> records }

    suspend fun activeProject(): ProjectRecordInternal? = store.read().valueOrNull()?.firstOrNull { it.isActive }

    /**
     * Spec 28 - create a managed project.
     *
     * A managed project also gets a Git repository so the Git panel has something real to show, and
     * optionally the built-in test project (spec 109).
     */
    suspend fun createManagedProject(
        name: String,
        initializeGit: Boolean,
        seedTestProject: Boolean,
        runGit: suspend (File, List<String>) -> com.palash.opencodeserver.backend.core.BackendResult<Unit>
    ): BackendResult<ProjectRecordInternal> {
        // A rejected name must come back as a typed failure rather than a cast, so the UI can show
        // the specific reason.
        val safeName = when (val validation = validateProjectName(name)) {
            is BackendResult.Success -> validation.value
            is BackendResult.Failure -> return validation
        }

        val dir = File(layout.projects, safeName)

        if (dir.exists()) {
            return BackendResult.Failure(
                BackendError.Project(
                    projectName = safeName,
                    technical = "Directory already exists: ${dir.absolutePath}",
                    requiresSelection = false
                )
            )
        }

        return try {
            if (!dir.mkdirs()) {
                return BackendResult.Failure(
                    BackendError.Project(
                        projectName = safeName,
                        technical = "mkdirs failed for ${dir.absolutePath}",
                        requiresSelection = false
                    )
                )
            }
            if (seedTestProject) seedHelloProject(dir)
            if (initializeGit) runGit(dir, listOf("init"))

            val record = ProjectRecordInternal(
                id = "proj-${System.currentTimeMillis()}",
                name = safeName,
                path = dir.absolutePath,
                displayPath = layout.toUserPath(dir),
                isManaged = true,
                createdAt = System.currentTimeMillis()
            )
            store.upsert(record, makeActive = true)
            log.info(LogCategory.PROJECT, "Created managed project", record.displayPath)
            BackendResult.Success(record)
        } catch (e: Exception) {
            BackendResult.Failure(BackendError.wrap(e))
        }
    }

    /**
     * Spec 28/29 - register a directory the user selected outside the sandbox.
     *
     * The path is validated as readable, and the record is flagged non-managed so the UI can explain
     * that deleting it is not the app's decision.
     */
    suspend fun addExternalProject(path: String): BackendResult<ProjectRecordInternal> {
        val validation = PathGuard.normalizeAbsolute(layout.resolveUserPath(path).absolutePath)
        if (!validation.isValid) {
            return BackendResult.Failure(
                BackendError.InvalidPath(path, validation.reason ?: "Not a usable folder")
            )
        }
        val dir = File(validation.normalized!!)
        val record = ProjectRecordInternal(
            id = "proj-${System.currentTimeMillis()}",
            name = dir.name.ifBlank { "project" },
            path = dir.absolutePath,
            displayPath = dir.absolutePath,
            isManaged = false,
            createdAt = System.currentTimeMillis()
        )
        store.upsert(record, makeActive = false)
        log.info(LogCategory.PROJECT, "Registered external project", record.displayPath)
        return BackendResult.Success(record)
    }

    /** Spec 28 - make one project active. Only one is active at a time. */
    suspend fun selectProject(projectId: String): BackendResult<ProjectRecordInternal> =
        store.setActive(projectId)

    suspend fun renameProject(projectId: String, newName: String): BackendResult<ProjectRecordInternal> {
        val safeName = when (val validation = validateProjectName(newName)) {
            is BackendResult.Success -> validation.value
            is BackendResult.Failure -> return validation
        }

        val records = store.read().valueOrNull().orEmpty()
        val target = records.firstOrNull { it.id == projectId }
            ?: return BackendResult.Failure(
                BackendError.Project(projectId, "No project with id $projectId")
            )
        val renamed = target.copy(name = safeName)
        store.replace(renamed)
        log.info(LogCategory.PROJECT, "Renamed project", "${target.name} -> ${renamed.name}")
        return BackendResult.Success(renamed)
    }

    /**
     * Spec 28 - remove from the app.
     *
     * Files are untouched. This is the only safe default, so the parameter does not exist.
     */
    suspend fun removeProject(projectId: String): BackendResult<Unit> =
        store.remove(projectId).also {
            log.info(LogCategory.PROJECT, "Unregistered project (files kept)", projectId)
        }

    /**
     * Spec 28/58 - delete the files. Requires explicit confirmation.
     *
     * Managed projects only: deleting a user's own external directory is refused, because the app
     * should not be the thing that removes user data outside its own sandbox.
     */
    suspend fun deleteProjectFiles(projectId: String, confirm: Boolean): BackendResult<Unit> {
        if (!confirm) {
            return BackendResult.Failure(
                BackendError.Unknown(
                    technical = "deleteProjectFiles refused without confirmation",
                    userTitle = "Confirmation required.",
                    userMessage = "Deleting project files cannot be undone."
                )
            )
        }
        val record = store.read().valueOrNull()?.firstOrNull { it.id == projectId }
            ?: return BackendResult.Failure(
                BackendError.Project(projectId, "No project with id $projectId")
            )
        if (!record.isManaged) {
            return BackendResult.Failure(
                BackendError.Project(
                    projectName = record.name,
                    technical = "Refusing to delete a directory outside the app sandbox: ${record.path}",
                    requiresSelection = false
                )
            )
        }
        return try {
            val dir = File(record.path)
            if (dir.exists()) dir.deleteRecursively()
            store.remove(projectId)
            log.info(LogCategory.PROJECT, "Deleted managed project files", record.displayPath)
            BackendResult.Success(Unit)
        } catch (e: Exception) {
            BackendResult.Failure(BackendError.wrap(e))
        }
    }

    /** Spec 109 - the optional built-in test project. */
    private fun seedHelloProject(dir: File) {
        val src = File(dir, "src")
        src.mkdirs()
        File(dir, "README.md").writeText(
            """
            # hello-opencode

            A minimal project for verifying the local development environment.

            Checks this project enables:
            - Terminal works
            - Node works
            - Git works
            - OpenCode works
            - The local API works
            """.trimIndent(),
            Charsets.UTF_8
        )
        File(dir, "package.json").writeText(
            """{
              "name": "hello-opencode",
              "version": "1.0.0",
              "private": true,
              "type": "module",
              "scripts": { "start": "node src/index.js" }
            }""",
            Charsets.UTF_8
        )
        File(src, "index.js").writeText(
            """
            console.log("hello from opencode");
            console.log("node", process.version);
            """.trimIndent(),
            Charsets.UTF_8
        )
    }

    /** Spec 83/98 - reject names that would escape the projects directory or confuse the shell. */
    private fun validateProjectName(name: String): BackendResult<String> {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> BackendResult.Failure(
                BackendError.Unknown(
                    technical = "empty project name",
                    userTitle = "A project name is required.",
                    userMessage = "Enter a name for the project."
                )
            )
            trimmed.contains('/') || trimmed.contains('\\') -> BackendResult.Failure(
                BackendError.Unknown(
                    technical = "name contains a path separator: $trimmed",
                    userTitle = "That name cannot be used.",
                    userMessage = "Use a simple name without slashes or backslashes."
                )
            )
            trimmed == "." || trimmed == ".." -> BackendResult.Failure(
                BackendError.Unknown(
                    technical = "name is a relative marker: $trimmed",
                    userTitle = "That name cannot be used.",
                    userMessage = "Choose a different project name."
                )
            )
            trimmed.length > 64 -> BackendResult.Failure(
                BackendError.Unknown(
                    technical = "name too long: ${trimmed.length} chars",
                    userTitle = "That name is too long.",
                    userMessage = "Use 64 characters or fewer."
                )
            )
            else -> BackendResult.Success(trimmed)
        }
    }
}
