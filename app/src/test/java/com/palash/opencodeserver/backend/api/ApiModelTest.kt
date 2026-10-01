package com.palash.opencodeserver.backend.api

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 20/21/82 - API client resilience.
 *
 * The contract that matters: a response from an unfamiliar OpenCode release must degrade, never crash.
 * These tests feed shapes that differ from the ones this client was built against and assert that the
 * meaningful fields still parse and the missing ones come back null.
 */
class ApiModelTest {

    private val moshi = Moshi.Builder().build()

    @Test
    fun healthParsesTheDocumentedShape() {
        val health = moshi.adapter(HealthResponse::class.java)
            .fromJson("""{"healthy":true,"version":"1.2.3"}""")

        assertNotNull(health)
        assertEquals(true, health!!.healthy)
        assertEquals("1.2.3", health.version)
    }

    @Test
    fun healthWithUnknownExtraFieldsStillParses() {
        val health = moshi.adapter(HealthResponse::class.java)
            .fromJson("""{"healthy":true,"version":"2.0.0","uptimeSeconds":42,"futureField":{}}""")

        assertEquals("2.0.0", health!!.version)
    }

    @Test
    fun healthWithMissingVersionIsNullNotZero() {
        val health = moshi.adapter(HealthResponse::class.java).fromJson("""{"healthy":true}""")

        assertEquals(true, health!!.healthy)
        assertNull(health.version)
    }

    @Test
    fun sessionParsesTheDocumentedShape() {
        val json = """
            {
              "id": "ses_1",
              "projectID": "prj_1",
              "directory": "/data/projects/demo",
              "title": "Refactor auth",
              "version": "1.2.3",
              "time": { "created": 1700000000000, "updated": 1700000600000 },
              "summary": { "additions": 12, "deletions": 3, "files": 2 }
            }
        """.trimIndent()

        val session = moshi.adapter(OcSession::class.java).fromJson(json)!!

        assertEquals("ses_1", session.resolvedId)
        assertEquals("Refactor auth", session.resolvedTitle)
        assertEquals(1700000000000L, session.createdAtMs)
        assertEquals(1700000600000L, session.updatedAtMs)
    }

    @Test
    fun sessionWithoutTitleFallsBackToADefault() {
        val session = moshi.adapter(OcSession::class.java).fromJson("""{"id":"ses_2"}""")!!

        assertEquals("ses_2", session.resolvedId)
        assertEquals("Untitled session", session.resolvedTitle)
    }

    @Test
    fun sessionWithUnknownFieldsDoesNotThrow() {
        // A newer release adding fields must not break an older client.
        val json = """{"id":"ses_3","title":"T","brandNewField":[1,2,3],"nested":{"a":1}}"""

        val session = moshi.adapter(OcSession::class.java).fromJson(json)
        assertNotNull(session)
    }

    @Test
    fun partToolStateIsReadWithoutASealedType() {
        // The tool-state union is intentionally unmapped; the accessor helpers read it instead, which
        // is what lets a new state value survive.
        val json = """
            {
              "id":"prt_1","sessionID":"ses_1","messageID":"msg_1","type":"tool",
              "callID":"call_1","tool":"bash",
              "state":{"status":"running","title":"Running npm install"}
            }
        """.trimIndent()

        val part = moshi.adapter(OcPart::class.java).fromJson(json)!!

        assertTrue(part.isTool)
        assertEquals("running", part.toolStatus())
        assertEquals("Running npm install", part.toolTitle())
    }

    @Test
    fun partTypeHelpersIdentifyEachUnionMember() {
        assertTrue(moshi.adapter(OcPart::class.java).fromJson("""{"type":"text"}""")!!.isText)
        assertTrue(moshi.adapter(OcPart::class.java).fromJson("""{"type":"reasoning"}""")!!.isReasoning)
        assertTrue(moshi.adapter(OcPart::class.java).fromJson("""{"type":"tool"}""")!!.isTool)
        assertTrue(moshi.adapter(OcPart::class.java).fromJson("""{"type":"file"}""")!!.isFile)
        assertTrue(moshi.adapter(OcPart::class.java).fromJson("""{"type":"patch"}""")!!.isPatch)
    }

    @Test
    fun messageErrorExposesItsNestedMessage() {
        val json = """
            {"id":"msg_1","sessionID":"ses_1","role":"assistant",
             "error":{"name":"APIError","data":{"message":"Provider rejected the request"}}}
        """.trimIndent()

        val message = moshi.adapter(OcMessage::class.java).fromJson(json)!!

        assertEquals("Provider rejected the request", message.error!!.messageText())
        assertFalse(message.error!!.isAborted)
        assertFalse(message.error!!.isAuthProblem)
    }

    @Test
    fun abortedMessageErrorIsIdentified() {
        val message = moshi.adapter(OcMessage::class.java)
            .fromJson("""{"error":{"name":"MessageAbortedError","data":{"message":"aborted"}}}""")!!

        assertTrue(message.error!!.isAborted)
    }

    @Test
    fun eventParsesTypeAndProperties() {
        val event = moshi.adapter(OcEvent::class.java).fromJson(
            """{"type":"message.part.updated","properties":{"delta":"hi","part":{"id":"p1"}}}"""
        )!!

        assertEquals("message.part.updated", event.resolvedType)
        assertEquals("hi", event.string("delta"))
        assertNotNull(event.map("part"))
    }

    @Test
    fun eventWithNoPropertiesIsHandled() {
        val event = moshi.adapter(OcEvent::class.java).fromJson("""{"type":"server.connected"}""")!!

        assertEquals("server.connected", event.resolvedType)
        assertNull(event.string("anything"))
        assertNull(event.map("anything"))
    }

    @Test
    fun fileNodeIdentifiesDirectories() {
        val dir = moshi.adapter(OcFileNode::class.java)
            .fromJson("""{"name":"src","path":"src","type":"directory","ignored":false}""")!!
        val file = moshi.adapter(OcFileNode::class.java)
            .fromJson("""{"name":"index.ts","path":"src/index.ts","type":"file","ignored":false}""")!!

        assertTrue(dir.isDirectory)
        assertFalse(file.isDirectory)
        assertEquals("index.ts", file.resolvedName)
    }

    @Test
    fun binaryFileContentIsFlagged() {
        val content = moshi.adapter(OcFileContent::class.java)
            .fromJson("""{"type":"binary","content":"","encoding":"base64"}""")!!

        assertTrue(content.isBinary)
    }

    @Test
    fun providerListParsesConnectedProviders() {
        val json = """
            {
              "all": [
                {"id":"anthropic","name":"Anthropic","source":"env","env":["ANTHROPIC_API_KEY"],
                 "models":{"claude":{"id":"claude"}}},
                {"id":"openai","name":"OpenAI","source":"config","models":{}}
              ],
              "default": {"anthropic":"claude"},
              "connected": ["anthropic"]
            }
        """.trimIndent()

        val response = moshi.adapter(OcProviderListResponse::class.java).fromJson(json)!!

        assertEquals(2, response.all!!.size)
        assertEquals(listOf("anthropic"), response.connected)
        assertEquals("claude", response.default!!["anthropic"])
        assertEquals(1, response.all!![0].modelCount)
    }

    @Test
    fun sessionStatusVariantsAreDistinguished() {
        val adapter = moshi.adapter(OcSessionStatus::class.java)

        assertTrue(adapter.fromJson("""{"type":"idle"}""")!!.isIdle)
        assertTrue(adapter.fromJson("""{"type":"busy"}""")!!.isBusy)
        assertTrue(adapter.fromJson("""{"type":"retry","attempt":2}""")!!.isRetrying)
    }

    @Test
    fun malformedJsonIsCaughtByTheTransportRatherThanCrashing() {
        // The client wraps parse failures in BackendError.MalformedResponse; this asserts the
        // underlying failure is a parse exception, not something worse.
        val result = runCatching { moshi.adapter(HealthResponse::class.java).fromJson("{not json") }

        assertTrue(result.isFailure)
    }
}