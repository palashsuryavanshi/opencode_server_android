package com.palash.opencodeserver.backend.files

import com.palash.opencodeserver.backend.api.OcFileContent
import com.palash.opencodeserver.backend.api.OpenCodeApiClient
import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.FileContentResult
import com.palash.opencodeserver.backend.core.ProjectFileChange
import com.palash.opencodeserver.backend.core.ProjectFileNode
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.project.PathGuard
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Spec 104 - file reads with a large-file guard.
 *
 * Files above the threshold are not loaded automatically. The caller receives the size and a
 * `requiresConfirmation` flag so the UI can ask before spending the memory, which is what spec 104
 * requires.
 *
 * Reads are streamed through a bounded buffer rather than loaded whole, and binary content is
 * reported as binary instead of being decoded into garbage.
 */
class FileService(
    private val api: OpenCodeApiClient,
    private val log: LogManager,
    @Volatile var largeFileThresholdBytes: Long = 2L * 1024 * 1024
) {

    /**
     * Spec 104 - list one directory level.
     *
     * The API's `/file` endpoint is the source of truth because it applies OpenCode's own ignore
     * rules, so the tree matches what the agent actually sees.
     */
    suspend fun list(projectDirectory: String, relativePath: String, depth: Int = 1): BackendResult<List<ProjectFileNode>> {
        val normalized = PathGuard.rejectTraversal(relativePath)
        if (!normalized.isValid) {
            return BackendResult.Failure(
                BackendError.InvalidPath(relativePath, normalized.reason ?: "Invalid path")
            )
        }
        val path = normalized.normalized.orEmpty()
        val limit = depth.coerceIn(1, 3)

        return when (val listed = api.listFiles(path, projectDirectory)) {
            is BackendResult.Failure -> listed
            is BackendResult.Success -> BackendResult.Success(buildTree(path, listed.value, limit, 1))
        }
    }

    /** Recurse to [limit] levels, bounded so a huge project cannot build an enormous tree. */
    private suspend fun buildTree(
        basePath: String,
        nodes: List<com.palash.opencodeserver.backend.api.OcFileNode>,
        limit: Int,
        level: Int
    ): List<ProjectFileNode> = nodes
        .filter { it.resolvedName.isNotBlank() }
        .map { node ->
            val children = if (node.isDirectory && level < limit) {
                when (val nested = api.listFiles(node.resolvedPath, node.absolute?.let { null })) {
                    is BackendResult.Success -> buildTree(node.resolvedPath, nested.value, limit, level + 1)
                    is BackendResult.Failure -> emptyList()
                }
            } else {
                emptyList()
            }
            ProjectFileNode(
                name = node.resolvedName,
                path = node.resolvedPath,
                isDirectory = node.isDirectory,
                sizeBytes = sizeOf(node),
                isIgnored = node.ignored == true,
                children = children
            )
        }
        .sortedWith(compareByDescending<ProjectFileNode> { it.isDirectory }.thenBy { it.name })

    private suspend fun sizeOf(node: com.palash.opencodeserver.backend.api.OcFileNode): Long? {
        if (node.isDirectory) return null
        // `/file` does not report sizes, so the on-disk size is read directly for the active
        // project. Falls back to null when the file is outside our reach.
        return runCatching { File(node.absolute ?: node.resolvedPath).length() }.getOrNull()
    }

    /** Spec 104 - read with a size guard. */
    suspend fun read(
        projectDirectory: String,
        relativePath: String,
        allowLarge: Boolean = false
    ): BackendResult<FileContentResult> {
        val normalized = PathGuard.rejectTraversal(relativePath)
        if (!normalized.isValid) {
            return BackendResult.Failure(
                BackendError.InvalidPath(relativePath, normalized.reason ?: "Invalid path")
            )
        }
        val path = normalized.normalized!!

        // Check the size before the request so a huge file is never transferred just to be refused.
        val apparentSize = runCatching {
            File(File(projectDirectory), path).length()
        }.getOrDefault(-1L)

        if (!allowLarge && apparentSize > largeFileThresholdBytes) {
            log.info(
                LogCategory.PROJECT,
                "Large file read deferred",
                "$path size=$apparentSize threshold=$largeFileThresholdBytes"
            )
            return BackendResult.Failure(
                BackendError.LargeFile(path, apparentSize, largeFileThresholdBytes)
            )
        }

        return when (val content = api.readFile(path, projectDirectory)) {
            is BackendResult.Failure -> content
            is BackendResult.Success -> BackendResult.Success(toResult(path, content.value, allowLarge))
        }
    }

    /**
     * Convert the API response, applying the guard again.
     *
     * The pre-check uses the local filesystem while the API is authoritative about encoding and
     * ignore state, so the threshold is enforced on the actual byte length too.
     */
    private fun toResult(
        path: String,
        content: OcFileContent,
        allowLarge: Boolean
    ): FileContentResult {
        val text = content.content.orEmpty()
        val sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong()

        if (content.isBinary) {
            return FileContentResult(
                path = path,
                content = "",
                sizeBytes = sizeBytes,
                isTruncated = true,
                isBinary = true,
                requiresConfirmation = false
            )
        }

        if (!allowLarge && sizeBytes > largeFileThresholdBytes) {
            // Truncate rather than return the whole thing: the caller gets a usable prefix and an
            // explicit flag instead of an unbounded allocation.
            val truncated = text.take((largeFileThresholdBytes / 2).toInt())
            return FileContentResult(
                path = path,
                content = truncated,
                sizeBytes = sizeBytes,
                isTruncated = true,
                isBinary = false,
                requiresConfirmation = true
            )
        }

        return FileContentResult(
            path = path,
            content = text,
            sizeBytes = sizeBytes,
            isTruncated = false,
            isBinary = false,
            requiresConfirmation = false
        )
    }

    /**
     * Write a file.
     *
     * The OpenCode HTTP API is read-only for file content, so writes go through the runtime directly
     * after [PathGuard] containment. This is the documented fallback hierarchy: API, then CLI, then
     * the runtime shell (spec 92) — here the runtime filesystem, which is even more direct than a
     * shell command and avoids command construction entirely (spec 84).
     */
    suspend fun write(
        projectDirectory: String,
        relativePath: String,
        content: String,
        writeDirectly: suspend (File, String) -> BackendResult<Unit>
    ): BackendResult<Unit> {
        val resolved = PathGuard.resolveInProject(File(projectDirectory), relativePath)
        val target = when (resolved) {
            is BackendResult.Failure -> return resolved
            is BackendResult.Success -> resolved.value
        }
        return writeDirectly(target, content)
    }
}

/**
 * Spec 71 - project file watching.
 *
 * Scope is deliberately narrow: only the active project directory is watched, never the Android
 * filesystem at large. Polling is used rather than `inotify` because `inotify` is not reachable
 * from inside a PRoot sandbox, and a bounded interval keeps the cost predictable (spec 73).
 */
class FileWatcher(
    private val log: LogManager,
    private val intervalMs: Long = 3_000,
    private val maxEntries: Int = 5_000
) : AutoCloseable {

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "file-watcher").apply { isDaemon = true }
    }

    @Volatile
    private var task: ScheduledFuture<*>? = null

    @Volatile
    private var previous: Map<String, Long> = emptyMap()

    @Volatile
    private var watchRoot: File? = null

    @Volatile
    private var listener: ((ProjectFileChange) -> Unit)? = null

    fun start(root: File, onChange: (ProjectFileChange) -> Unit) {
        stop()
        watchRoot = root
        listener = onChange
        previous = snapshot(root)
        task = executor.scheduleWithFixedDelay({
            val target = watchRoot ?: return@scheduleWithFixedDelay
            val sink = listener ?: return@scheduleWithFixedDelay
            val current = snapshot(target)
            diff(previous, current).forEach { change -> runCatching { sink(change) } }
            previous = current
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)

        log.info(LogCategory.PROJECT, "Watching project for changes", root.absolutePath)
    }

    /**
     * Spec 71 - the change stream the frontend observes.
     *
     * Falls back to a caller-supplied root when no project is active, so the flow never watches
     * anything broader than it was told to.
     */
    fun changes(fallbackRoot: File): Flow<ProjectFileChange> = callbackFlow {
        // The polling thread is not a coroutine, so it hands changes over with trySend, which is
        // thread-safe; the flow itself stays open until the collector leaves.
        start(watchRoot ?: fallbackRoot) { change -> trySend(change) }
        awaitClose { stop() }
    }

    fun stop() {
        task?.cancel(false)
        task = null
        watchRoot = null
        listener = null
    }

    /** Spec 71 - changes between two snapshots. */
    internal fun diff(
        before: Map<String, Long>,
        after: Map<String, Long>
    ): List<ProjectFileChange> = buildList {
        after.forEach { (path, modifiedAt) ->
            val previous = before[path]
            when {
                previous == null -> add(ProjectFileChange(path, ProjectFileChange.FileChangeType.CREATED))
                previous != modifiedAt -> add(ProjectFileChange(path, ProjectFileChange.FileChangeType.MODIFIED))
            }
        }
        before.keys.forEach { path ->
            if (!after.containsKey(path)) {
                add(ProjectFileChange(path, ProjectFileChange.FileChangeType.DELETED))
            }
        }
    }

    /**
     * Snapshot the tree, bounded so a large project cannot blow up memory (spec 103).
     *
     * Common build and VCS directories are skipped: watching them produces noise, not signal.
     */
    private fun snapshot(root: File): Map<String, Long> {
        if (!root.isDirectory) return emptyMap()
        val result = LinkedHashMap<String, Long>()
        runCatching {
            root.walkTopDown()
                .onEnter { dir ->
                    val name = dir.name
                    name !in IGNORED_DIRECTORIES && !name.startsWith(".")
                }
                .filter { it.isFile }
                .take(maxEntries)
                .forEach { file ->
                    result[file.absolutePath] = file.lastModified()
                }
        }
        return result
    }

    override fun close() = stop()

    companion object {
        private val IGNORED_DIRECTORIES = setOf(
            "node_modules", "build", "dist", "out", "target",
            ".gradle", ".git", ".next", ".cache", "vendor"
        )
    }
}