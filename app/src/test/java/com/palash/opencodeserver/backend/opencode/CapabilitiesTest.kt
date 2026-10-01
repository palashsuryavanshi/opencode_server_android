package com.palash.opencodeserver.backend.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 93/94 - version-aware command discovery.
 *
 * The spec is explicit that the CLI layout must not be assumed. These tests assert that capabilities
 * are *discovered* from the installed build's help output, and that the serve-mode choice follows the
 * server manager's stated preference for `serve`.
 */
class OpenCodeCapabilitiesTest {

    @Test
    fun detectsDocumentedCommandsFromHelpOutput() {
        val help = """
            Commands:
              tui        Start the terminal user interface
              agent      Manage agents for opencode
              attach     Attach a terminal to an already running OpenCode backend
              auth       Manage credentials and login for providers
              github     Manage the GitHub agent
              mcp        Model Context Protocol servers
              models     List all models
              run        Run opencode in non-interactive mode
              serve      Start a headless OpenCode server
              service    Manage opencode as a background service
              session    Manage OpenCode sessions
              stats      Token usage statistics
              export     Export session data
              import     Import session data
              web        Start a server with a web interface
              acp        Start an ACP server
              upgrade    Updates opencode
              db         Database tools
              uninstall  Uninstall opencode
        """.trimIndent()

        val capabilities = OpenCodeCapabilities.parse(help)

        assertTrue(capabilities.hasServe)
        assertTrue(capabilities.hasSession)
        assertTrue(capabilities.hasAuth)
        assertTrue(capabilities.hasRun)
        assertTrue(capabilities.hasWeb)
        assertTrue(capabilities.hasUpgrade)
        assertTrue(capabilities.hasService)
    }

    @Test
    fun reportsAbsentCommands() {
        val help = "Commands:\n  serve   Start a headless OpenCode server\n  run     Run opencode"

        val capabilities = OpenCodeCapabilities.parse(help)

        assertTrue(capabilities.hasServe)
        assertFalse(capabilities.hasWeb)
        assertFalse(capabilities.hasUpgrade)
    }

    @Test
    fun serveIsPreferredOverService() {
        // Spec 94: for Android, `serve` gives the app reliable lifecycle control because it owns the
        // child process directly.
        val help = "Commands:\n  serve   Start a server\n  service Manage services"

        val capabilities = OpenCodeCapabilities.parse(help)
        val mode = capabilities.serveMode

        assertEquals(OpenCodeCapabilities.ServeMode.SERVE_OR_SERVICE, mode)
        assertEquals(listOf("serve"), mode.preferredCommand)
    }

    @Test
    fun serviceOnlyFallsBackToServiceStart() {
        val capabilities = OpenCodeCapabilities.parse("Commands:\n  service Manage services")

        assertEquals(OpenCodeCapabilities.ServeMode.SERVICE, capabilities.serveMode)
        assertEquals(listOf("service", "start"), capabilities.serveMode.preferredCommand)
    }

    @Test
    fun noServerCommandIsReportedAsUnavailable() {
        val capabilities = OpenCodeCapabilities.parse("Commands:\n  run  Run opencode")

        assertEquals(OpenCodeCapabilities.ServeMode.UNAVAILABLE, capabilities.serveMode)
        assertTrue(capabilities.serveMode.preferredCommand.isEmpty())
    }

    @Test
    fun emptyHelpYieldsNoCapabilities() {
        val capabilities = OpenCodeCapabilities.parse("")

        assertFalse(capabilities.hasServe)
        assertTrue(capabilities.present.isEmpty())
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val capabilities = OpenCodeCapabilities.parse("COMMANDS:\n  SERVE  Start a server")

        assertTrue(capabilities.hasServe)
    }

    @Test
    fun resultsAreCachedPerVersion() {
        val first = OpenCodeCapabilities.forVersion("1.0.0", "Commands:\n  serve  Start")
        val second = OpenCodeCapabilities.forVersion("1.0.0", "Commands:\n  serve  Start")

        assertTrue(first === second)
    }

    @Test
    fun invalidateClearsTheCache() {
        OpenCodeCapabilities.forVersion("2.0.0", "Commands:\n  serve  Start")
        OpenCodeCapabilities.invalidate()

        val fresh = OpenCodeCapabilities.forVersion("2.0.0", "Commands:\n  serve  Start")
        assertTrue(fresh.hasServe)
    }
}