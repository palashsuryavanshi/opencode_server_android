package com.palash.opencodeserver.backend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 98 - error wording.
 *
 * The rule under test: a user-facing message must never lead with a raw errno. These are the exact
 * shapes spec 52 and spec 98 ask for.
 */
class BackendErrorsTest {

    @Test
    fun connectionError_leadsWithFriendlyText_notErrno() {
        val error = BackendError.Connection(
            host = "127.0.0.1",
            port = 4096,
            technical = "ECONNREFUSED 127.0.0.1:4096"
        )

        assertEquals("OpenCode is not running.", error.userTitle)
        assertTrue(error.userMessage.contains("Start the local server"))
        // The raw errno survives only in the technical layer.
        assertTrue(error.technical!!.contains("ECONNREFUSED"))
        assertFalse(error.userMessage.contains("ECONNREFUSED"))
    }

    @Test
    fun display_combinesBothLayers() {
        val error = BackendError.Runtime("EACCES: permission denied")
        val display = error.display()

        assertTrue(display.contains("terminal runtime could not start"))
        assertTrue(display.contains("Technical details:"))
        assertTrue(display.contains("EACCES"))
    }

    @Test
    fun authenticationError_isNotAutoRetryable() {
        val error = BackendError.Authentication("HTTP 401")
        assertFalse(error.retryable)
        assertFalse(error.category.isAutoRetryable)
    }

    @Test
    fun timeoutError_isRetryable() {
        val error = BackendError.Timeout("opencode health check", 5_000)
        assertTrue(error.retryable)
        assertTrue(error.category.isAutoRetryable)
    }

    @Test
    fun streamingTimeout_isNotRetryable() {
        // Spec 106: an SSE stream is meant to stay open, so a stalled read is not a request to retry.
        val error = BackendError.Timeout("event stream", 0, isStreaming = true)
        assertFalse(error.retryable)
    }

    @Test
    fun projectError_requestsSelectionWhenMissing() {
        val error = BackendError.Project("project", "no active project", requiresSelection = true)
        assertEquals("Select a project first.", error.userTitle)
        assertTrue(error.userMessage.contains("Choose or create a project"))
    }

    @Test
    fun storageError_statesBothRequirementAndAvailability() {
        val error = BackendError.Storage(500L * 1024 * 1024, 180L * 1024 * 1024)
        assertTrue(error.userMessage.contains("500.0 MB"))
        assertTrue(error.userMessage.contains("180.0 MB"))
        assertTrue(error.userMessage.contains("Free up storage"))
    }

    @Test
    fun wrap_returnsOriginalErrorFromBackendException() {
        val original = BackendError.Connection("127.0.0.1", 4096, "refused")
        val wrapped = BackendError.wrap(BackendException(original))
        assertEquals(original, wrapped)
    }

    @Test
    fun categorized_errorUsesSuppliedCategory() {
        val error = BackendError.wrap(
            IllegalStateException("boom"),
            category = ErrorCategory.AUTHENTICATION,
            userTitle = "Rejected.",
            userMessage = "Try again with a different key."
        )
        assertEquals(ErrorCategory.AUTHENTICATION, error.category)
        assertFalse(error.retryable)
        assertEquals("Rejected.", error.userTitle)
    }
}