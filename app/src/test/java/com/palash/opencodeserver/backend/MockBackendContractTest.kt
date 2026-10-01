package com.palash.opencodeserver.backend

import com.palash.opencodeserver.backend.core.AppState
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.CreateProjectRequest
import com.palash.opencodeserver.backend.core.LocalCodingBackend
import com.palash.opencodeserver.backend.core.ServerProcessState
import com.palash.opencodeserver.backend.core.SetupCheckpoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 80/114 - the mock must honour the same contract as the real backend.
 *
 * If the mock is laxer, screens get built against behaviour that does not exist in production, which
 * is the failure this suite exists to prevent. Every assertion here is one that must also hold for
 * [RealLocalBackend].
 */
class MockBackendContractTest {

    private fun backend(): LocalCodingBackend = MockBackend()

    @Test
    fun startsInSetupRequiredRatherThanReportingReady() {
        // Spec 113: nothing may claim success before it has been verified.
        runTest {
            val backend = backend()
            assertEquals(AppState.SETUP_REQUIRED, backend.observeState().value)
        }
    }

    @Test
    fun setupReachesTheCompleteCheckpoint() {
        runTest {
            val backend = backend()
            val progress = backend.setup()

            assertEquals(SetupCheckpoint.COMPLETE, progress.checkpoint)
            assertTrue(progress.isTerminal)
            assertEquals(100, progress.percent)
        }
    }

    @Test
    fun setupProgressIsObservableWhileRunning() {
        runTest {
            val backend = backend()
            val progress = backend.observeSetupProgress()

            // Spec 6 - the flow must emit the current value rather than suspend with nothing, so a
            // wizard can render the last known step on re-entry.
            val initial = progress.first()
            assertEquals(null, initial)

            backend.setup()
            assertNotNull(progress.value)
            assertTrue(progress.value!!.isTerminal)
        }
    }

    @Test
    fun setupIsIdempotent() {
        runTest {
            val backend = backend()
            backend.setup()
            val second = backend.setup(force = false)

            assertEquals(SetupCheckpoint.COMPLETE, second.checkpoint)
        }
    }

    @Test
    fun serverStartsRunningOnlyAfterAHealthPass() {
        runTest {
            val backend = backend()
            val started = backend.startServer()

            assertTrue(started is BackendResult.Success)
            val info = (started as BackendResult.Success).value
            assertEquals(ServerProcessState.RUNNING, info.state)
            // Spec 113: running implies a health response was received.
            assertTrue(info.healthy)
            assertEquals("127.0.0.1", info.host)
        }
    }

    @Test
    fun serverEndpointIsNotHardCodedToPortFourZeroNineSix() {
        // Spec 48: the endpoint must be whatever was actually bound.
        runTest {
            val backend = backend()
            backend.startServer()
            val info = backend.getServerStatus()

            assertNotNull(info.baseUrl)
            assertEquals(info.port, info.baseUrl!!.substringAfterLast(':').toInt())
        }
    }

    @Test
    fun connectionCarriesAReferenceNotAPassword() {
        runTest {
            val backend = backend()
            backend.startServer()
            val connection = backend.observeConnection().value!!

            assertTrue(connection.hasCredentials)
            assertFalse(
                "The connection object must never carry a password",
                connection.toString().contains("password")
            )
        }
    }

    @Test
    fun stoppingTheServerTransitionsToStopped() {
        runTest {
            val backend = backend()
            backend.startServer()
            val stopped = backend.stopServer()

            assertEquals(ServerProcessState.STOPPED, stopped.valueOrNull()?.state)
            assertEquals(AppState.SERVER_STOPPED, backend.observeState().value)
        }
    }

    @Test
    fun restartReturnsToRunning() {
        runTest {
            val backend = backend()
            backend.startServer()
            val restarted = backend.restartServer()

            assertEquals(ServerProcessState.RUNNING, restarted.valueOrNull()?.state)
            assertTrue(restarted.valueOrNull()!!.healthy)
        }
    }

    @Test
    fun discoveringWithoutAServerReportsFailure() {
        runTest {
            val backend = backend()
            val discovered = backend.discoverRunningServer()

            assertTrue(discovered is BackendResult.Failure)
        }
    }

    @Test
    fun operationsWithoutAProjectFailWithAnActionableError() {
        runTest {
            val backend = MockBackend()
            // Remove the seeded project so there is genuinely no active project.
            val project = backend.getCurrentProject()!!
            backend.removeProject(project.id)

            val result = backend.readFile("src/index.js")

            assertTrue(result is BackendResult.Failure)
            val error = (result as BackendResult.Failure).error
            assertEquals("Select a project first.", error.userTitle)
        }
    }

    @Test
    fun pathTraversalIsRejectedByTheMockToo() {
        runTest {
            val backend = backend()
            val result = backend.writeFile("../../etc/passwd", "content")

            assertTrue(result is BackendResult.Failure)
            val technical = (result as BackendResult.Failure).error.technical!!
            // The mock routes through the production PathGuard, so the reason text is identical.
            assertTrue(technical.contains("may not contain '..'"))
        }
    }

    @Test
    fun largeFileReadRequiresConfirmation() {
        runTest {
            val backend = backend()
            val result = backend.readFile("assets/video.bin")

            assertTrue(result is BackendResult.Failure)
            val error = (result as BackendResult.Failure).error
            assertEquals("Large file", error.userTitle)
            assertTrue(error.retryable)
        }
    }

    @Test
    fun creatingAndSelectingAProjectSucceeds() {
        runTest {
            val backend = backend()
            val created = backend.createProject(CreateProjectRequest(name = "my-app"))

            assertTrue(created is BackendResult.Success)
            val project = (created as BackendResult.Success).value
            assertTrue(project.isActive)
            assertEquals("my-app", backend.getCurrentProject()?.name)
        }
    }

    @Test
    fun deletingProjectFilesRequiresConfirmation() {
        runTest {
            val backend = backend()
            val project = backend.getCurrentProject()!!

            val refused = backend.deleteProjectFiles(project.id, confirm = false)
            assertTrue(refused is BackendResult.Failure)

            val accepted = backend.deleteProjectFiles(project.id, confirm = true)
            assertTrue(accepted is BackendResult.Success)
        }
    }

    @Test
    fun resetRequiresConfirmation() {
        runTest {
            val backend = backend()
            assertTrue(backend.resetEnvironment(confirm = false) is BackendResult.Failure)
            assertTrue(backend.resetEnvironment(confirm = true) is BackendResult.Success)
        }
    }

    @Test
    fun sessionsCanBeCreatedAndMessagesExchanged() {
        runTest {
            val backend = backend()
            val created = backend.createSession("Test session")
            assertTrue(created is BackendResult.Success)

            val sessionId = (created as BackendResult.Success).value.id
            val sent = backend.sendMessage(sessionId, "hello")

            assertTrue(sent is BackendResult.Success)
            assertEquals(1, backend.getSessions().size)
        }
    }

    @Test
    fun sendingToAnUnknownSessionFails() {
        runTest {
            val backend = backend()
            val result = backend.sendMessage("no-such-session", "hello")

            assertTrue(result is BackendResult.Failure)
        }
    }

    @Test
    fun gitStatusIsReportedForAnActiveProject() {
        runTest {
            val backend = backend()
            val status = backend.getGitStatus()

            assertTrue(status is BackendResult.Success)
            val value = (status as BackendResult.Success).value
            assertTrue(value.isRepository)
            assertEquals("main", value.branch)
        }
    }

    @Test
    fun healthCheckCoversEveryRowTheSpecLists() {
        runTest {
            val backend = backend()
            val checks: Map<String, com.palash.opencodeserver.backend.core.HealthCheckResult> =
                backend.runHealthCheck().associateBy { it.name }

            listOf(
                "Runtime", "Shell", "Git", "Node", "OpenCode",
                "Server", "API", "Event stream", "Project", "Provider"
            ).forEach { name ->
                assertTrue("$name should be checked", checks.containsKey(name))
            }
        }
    }

    @Test
    fun failingHealthChecksCarryARemediation() {
        runTest {
            val backend = backend()
            val checks = backend.runHealthCheck()
            checks.filterNot { it.passed }.forEach { check ->
                assertNotNull("${check.name} should suggest a fix", check.remediation)
            }
        }
    }

    @Test
    fun diagnosticsReportRedactsAndOmitsSecrets() {
        runTest {
            val backend = backend()
            val report = backend.getDiagnostics()

            assertTrue(report.redactionApplied)
            assertTrue(report.recentLogs.none { it.contains("sk-") })
        }
    }

    @Test
    fun settingsRoundTrip() {
        runTest {
            val backend = backend()
            val updated = backend.updateSettings { it.copy(preferredPort = 5555) }

            assertEquals(5555, updated.valueOrNull()?.preferredPort)
            assertEquals(5555, backend.observeSettings().value.preferredPort)
        }
    }

    @Test
    fun terminalSessionsCanBeOpenedAndListed() {
        runTest {
            val backend = backend()
            val opened = backend.openTerminal()

            assertTrue(opened is BackendResult.Success)
            assertEquals(1, backend.observeTerminalSessions().value.size)
        }
    }

    @Test
    fun connectivityDistinguishesLocalServerFromInternet() {
        runTest {
            val backend = backend()
            val report = backend.getConnectivity()

            // Spec 76: the local server and the internet are separate signals.
            assertNotNull(report.connectivity)
            assertFalse(report.localServerRunning)
            assertTrue(report.terminalAvailable)
        }
    }

    @Test
    fun mockFlagParsingAcceptsTheUsualTruthyForms() {
        // Spec 114 - the decision is made at build time from USE_MOCK_BACKEND, so the parsing rules
        // are asserted here rather than through a Context a JVM test cannot provide.
        assertTrue(BackendFactory.parseBoolean("true"))
        assertTrue(BackendFactory.parseBoolean("1"))
        assertTrue(BackendFactory.parseBoolean("yes"))
    }

    @Test
    fun mockFlagParsingRejectsAnythingElse() {
        // Spec 114 - production defaults to the real backend, so an unrecognised value must mean false.
        assertFalse(BackendFactory.parseBoolean("false"))
        assertFalse(BackendFactory.parseBoolean("0"))
        assertFalse(BackendFactory.parseBoolean("no"))
        assertFalse(BackendFactory.parseBoolean(""))
        assertFalse(BackendFactory.parseBoolean("maybe"))
    }
}