package com.palash.opencodeserver.backend.project

import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.runtime.RuntimeLayout
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File

/**
 * Spec 57 - persisted project metadata.
 *
 * Deliberately a plain JSON file inside app-private storage rather than Room: the schema is tiny,
 * the access pattern is "read all, write all", and keeping it dependency-free means project
 * recovery does not depend on a database migration succeeding.
 */
class ProjectStore(
    private val layout: RuntimeLayout,
    private val log: LogManager
) {

    private val moshi = Moshi.Builder().build()

    private val listAdapter by lazy {
        val type = Types.newParameterizedType(List::class.java, ProjectRecordInternal::class.java)
        moshi.adapter<List<ProjectRecordInternal>>(type)
    }

    /** Read all records. A corrupt file is preserved and reported rather than silently discarded. */
    suspend fun read(): BackendResult<List<ProjectRecordInternal>> = try {
        val file = layout.projectsFile
        if (!file.exists()) {
            BackendResult.Success(emptyList())
        } else {
            val text = file.readText(Charsets.UTF_8)
            BackendResult.Success(runCatching { listAdapter.fromJson(text).orEmpty() }.getOrElse {
                log.error(LogCategory.PROJECT, "Project metadata is corrupt; starting empty", file.name, it)
                preserveCorrupt(file)
                emptyList()
            })
        }
    } catch (e: Exception) {
        BackendResult.Failure(com.palash.opencodeserver.backend.core.BackendError.wrap(e))
    }

    suspend fun upsert(record: ProjectRecordInternal, makeActive: Boolean) {
        val current = read().valueOrNull().orEmpty()
            .filterNot { it.id == record.id }
            .map { if (makeActive) it.copy(isActive = false) else it }
        val next = current + record.copy(isActive = makeActive || current.none { it.isActive })
        write(next)
    }

    suspend fun replace(record: ProjectRecordInternal) {
        val current = read().valueOrNull().orEmpty()
        write(current.map { if (it.id == record.id) record else it })
    }

    suspend fun setActive(projectId: String): BackendResult<ProjectRecordInternal> {
        val current = read().valueOrNull().orEmpty()
        val target = current.firstOrNull { it.id == projectId }
            ?: return BackendResult.Failure(
                com.palash.opencodeserver.backend.core.BackendError.Project(
                    projectName = projectId,
                    technical = "No project with id $projectId"
                )
            )
        val next = current.map { it.copy(isActive = it.id == projectId) }
        write(next)
        return BackendResult.Success(target.copy(isActive = true))
    }

    suspend fun remove(projectId: String): BackendResult<Unit> {
        val current = read().valueOrNull().orEmpty()
        write(current.filterNot { it.id == projectId })
        return BackendResult.Success(Unit)
    }

    suspend fun clear() = write(emptyList())

    private suspend fun write(records: List<ProjectRecordInternal>) {
        try {
            layout.projectsFile.parentFile?.mkdirs()
            val temp = File(layout.projectsFile.parentFile, layout.projectsFile.name + ".tmp")
            temp.writeText(listAdapter.toJson(records), Charsets.UTF_8)
            if (!temp.renameTo(layout.projectsFile)) {
                layout.projectsFile.writeText(listAdapter.toJson(records), Charsets.UTF_8)
                temp.delete()
            }
        } catch (e: Exception) {
            log.error(LogCategory.PROJECT, "Failed to persist project metadata", null, e)
        }
    }

    private fun preserveCorrupt(file: File) {
        runCatching {
            file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"), overwrite = true)
        }
    }
}

/** The persisted shape. Isomorphic to the UI model, but owned by the backend. */
data class ProjectRecordInternal(
    val id: String,
    val name: String,
    val path: String,
    val displayPath: String,
    val isManaged: Boolean,
    val createdAt: Long,
    val lastOpenedAt: Long? = null,
    val isActive: Boolean = false,
    val languageHint: String? = null
)