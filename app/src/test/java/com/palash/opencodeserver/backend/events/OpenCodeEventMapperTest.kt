package com.palash.opencodeserver.backend.events

import com.palash.opencodeserver.backend.api.OcEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 70 - event translation.
 *
 * The critical path is streaming text: a `message.part.updated` frame with a `delta` must produce a
 * [BackendEvent.MessageDelta], and one without must produce a full-text replacement. Getting this
 * wrong is what makes a UI either drop deltas or duplicate text.
 */
class OpenCodeEventMapperTest {

    @Test
    fun connectedEventMarksTheStreamAsUp() {
        val events = OpenCodeEventMapper.map(OcEvent(type = "server.connected", properties = emptyMap()))

        assertEquals(1, events.size)
        assertTrue(events.first() is BackendEvent.EventStreamConnected)
    }

    @Test
    fun textPartWithDeltaBecomesMessageDelta() {
        val events = OpenCodeEventMapper.map(
            OcEvent(
                type = "message.part.updated",
                properties = mapOf(
                    "delta" to "Hello",
                    "part" to mapOf(
                        "id" to "prt_1",
                        "sessionID" to "ses_1",
                        "messageID" to "msg_1",
                        "type" to "text",
                        "text" to ""
                    )
                )
            )
        )

        assertEquals(1, events.size)
        val delta = events.first() as BackendEvent.MessageDelta
        assertEquals("ses_1", delta.sessionId)
        assertEquals("msg_1", delta.messageId)
        assertEquals("prt_1", delta.partId)
        assertEquals("Hello", delta.delta)
    }

    @Test
    fun textPartWithoutDeltaBecomesFullText() {
        val events = OpenCodeEventMapper.map(
            OcEvent(
                type = "message.part.updated",
                properties = mapOf(
                    "part" to mapOf(
                        "id" to "prt_1",
                        "sessionID" to "ses_1",
                        "messageID" to "msg_1",
                        "type" to "text",
                        "text" to "Complete answer"
                    )
                )
            )
        )

        val text = events.first() as BackendEvent.MessageText
        assertEquals("Complete answer", text.text)
    }

    @Test
    fun toolPartPendingBecomesToolStarted() {
        val events = OpenCodeEventMapper.map(toolEvent(status = "pending"))

        val started = events.first() as BackendEvent.ToolStarted
        assertEquals("read", started.tool)
        assertEquals("Reading files", started.title)
    }

    @Test
    fun toolPartRunningBecomesToolStarted() {
        val events = OpenCodeEventMapper.map(toolEvent(status = "running"))

        assertTrue(events.first() is BackendEvent.ToolStarted)
    }

    @Test
    fun toolPartCompletedBecomesToolOutput() {
        val events = OpenCodeEventMapper.map(
            toolEvent(status = "completed", output = "3 files read", error = null)
        )

        val output = events.first() as BackendEvent.ToolOutput
        assertEquals("completed", output.status)
        assertEquals("3 files read", output.output)
    }

    @Test
    fun toolPartErrorBecomesToolOutputWithError() {
        val events = OpenCodeEventMapper.map(
            toolEvent(status = "error", output = null, error = "permission denied")
        )

        val output = events.first() as BackendEvent.ToolOutput
        assertEquals("error", output.status)
        assertEquals("permission denied", output.error)
    }

    @Test
    fun reasoningPartBecomesReasoning() {
        val events = OpenCodeEventMapper.map(
            OcEvent(
                type = "message.part.updated",
                properties = mapOf(
                    "part" to mapOf(
                        "sessionID" to "ses_1",
                        "messageID" to "msg_1",
                        "type" to "reasoning",
                        "text" to "Considering the options"
                    )
                )
            )
        )

        val reasoning = events.first() as BackendEvent.Reasoning
        assertEquals("Considering the options", reasoning.text)
    }

    @Test
    fun patchPartEmitsFileChangedForEachFile() {
        val events = OpenCodeEventMapper.map(
            OcEvent(
                type = "message.part.updated",
                properties = mapOf(
                    "part" to mapOf(
                        "sessionID" to "ses_1",
                        "messageID" to "msg_1",
                        "type" to "patch",
                        "files" to listOf("src/a.ts", "src/b.ts")
                    )
                )
            )
        )

        assertEquals(2, events.size)
        assertTrue(events.all { it is BackendEvent.FileChanged })
    }

    @Test
    fun fileWatcherUpdateMapsToCreatedModifiedDeleted() {
        val created = OpenCodeEventMapper.map(
            OcEvent(type = "file.watcher.updated", properties = mapOf("file" to "a.ts", "event" to "add"))
        ).first() as BackendEvent.WatcherChanged
        assertEquals(
            com.palash.opencodeserver.backend.core.ProjectFileChange.FileChangeType.CREATED,
            created.change.changeType
        )

        val modified = OpenCodeEventMapper.map(
            OcEvent(type = "file.watcher.updated", properties = mapOf("file" to "a.ts", "event" to "change"))
        ).first() as BackendEvent.WatcherChanged
        assertEquals(
            com.palash.opencodeserver.backend.core.ProjectFileChange.FileChangeType.MODIFIED,
            modified.change.changeType
        )

        val deleted = OpenCodeEventMapper.map(
            OcEvent(type = "file.watcher.updated", properties = mapOf("file" to "a.ts", "event" to "unlink"))
        ).first() as BackendEvent.WatcherChanged
        assertEquals(
            com.palash.opencodeserver.backend.core.ProjectFileChange.FileChangeType.DELETED,
            deleted.change.changeType
        )
    }

    @Test
    fun fileEditedEventCarriesThePath() {
        val events = OpenCodeEventMapper.map(
            OcEvent(type = "file.edited", properties = mapOf("file" to "src/index.js"))
        )

        assertEquals("src/index.js", (events.first() as BackendEvent.FileChanged).path)
    }

    @Test
    fun sessionIdleIsSurfaced() {
        val events = OpenCodeEventMapper.map(
            OcEvent(type = "session.idle", properties = mapOf("sessionID" to "ses_9"))
        )

        assertEquals("ses_9", (events.first() as BackendEvent.SessionIdle).sessionId)
    }

    @Test
    fun sessionStatusIsPassedThrough() {
        val events = OpenCodeEventMapper.map(
            OcEvent(
                type = "session.status",
                properties = mapOf(
                    "sessionID" to "ses_1",
                    "status" to mapOf("type" to "retry", "message" to "rate limited", "attempt" to 2)
                )
            )
        )

        val status = events.first() as BackendEvent.SessionStatusChanged
        assertEquals("retry", status.status)
        assertEquals("rate limited", status.detail)
    }

    @Test
    fun sessionErrorSurfacesTheMessage() {
        val events = OpenCodeEventMapper.map(
            OcEvent(
                type = "session.error",
                properties = mapOf(
                    "sessionID" to "ses_1",
                    "error" to mapOf("name" to "APIError", "data" to mapOf("message" to "Provider rejected"))
                )
            )
        )

        val error = events.first() as BackendEvent.Error
        assertEquals("session", error.scope)
        assertEquals("Provider rejected", error.message)
    }

    @Test
    fun retryPartBecomesRetryableError() {
        val events = OpenCodeEventMapper.map(
            OcEvent(
                type = "message.part.updated",
                properties = mapOf(
                    "part" to mapOf("sessionID" to "ses_1", "messageID" to "msg_1", "type" to "retry")
                )
            )
        )

        val error = events.first() as BackendEvent.Error
        assertEquals("provider", error.scope)
        assertTrue(error.retryable)
    }

    @Test
    fun installationUpdateAvailableIsSurfaced() {
        val events = OpenCodeEventMapper.map(
            OcEvent(
                type = "installation.update-available",
                properties = mapOf("version" to mapOf("version" to "1.2.3"))
            )
        )

        assertEquals("1.2.3", (events.first() as BackendEvent.ServerUpdateAvailable).version)
    }

    @Test
    fun deliberatelyIgnoredEventsProduceNothing() {
        // These are listed explicitly so the choice is auditable rather than accidental.
        listOf("lsp.updated", "todo.updated", "command.executed", "tui.toast.show", "session.diff")
            .forEach { type ->
                assertTrue(
                    "$type should not reach the UI",
                    OpenCodeEventMapper.map(OcEvent(type = type, properties = emptyMap())).isEmpty()
                )
            }
    }

    @Test
    fun unknownEventTypeIsSurfacedRatherThanDropped() {
        // Spec 70: a server-side addition must be visible in diagnostics, not vanish.
        val events = OpenCodeEventMapper.map(
            OcEvent(type = "brand.new.event", properties = emptyMap())
        )

        val error = events.first() as BackendEvent.Error
        assertEquals("event", error.scope)
        assertTrue(error.message.contains("brand.new.event"))
    }

    @Test
    fun emptyTypeProducesNothing() {
        assertTrue(OpenCodeEventMapper.map(OcEvent(type = null, properties = emptyMap())).isEmpty())
    }

    @Test
    fun partWithoutIdentifiersIsIgnored() {
        val events = OpenCodeEventMapper.map(
            OcEvent(
                type = "message.part.updated",
                properties = mapOf("part" to mapOf("type" to "text", "text" to "orphan"))
            )
        )

        assertTrue(events.isEmpty())
    }

    private fun toolEvent(status: String, output: String? = null, error: String? = null) = OcEvent(
        type = "message.part.updated",
        properties = mapOf(
            "part" to mapOf(
                "id" to "prt_2",
                "sessionID" to "ses_1",
                "messageID" to "msg_1",
                "type" to "tool",
                "callID" to "call_1",
                "tool" to "read",
                "state" to buildMap<String, Any?> {
                    put("status", status)
                    put("title", toolTitle)
                    output?.let { put("output", it) }
                    error?.let { put("error", it) }
                }
            )
        )
    )

    /** The title every tool event carries unless a test overrides it. */
    private val toolTitle = "Reading files"
}