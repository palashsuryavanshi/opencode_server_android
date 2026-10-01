package com.palash.opencodeserver.backend

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.TestScenario
import com.palash.opencodeserver.backend.events.OpenCodeEventManager
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.server.OpenCodeServerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Spec 81 - the developer test mode.
 *
 * The point is to let the frontend be exercised against every failure the real system can produce,
 * without needing a device that actually fails in that way. Each scenario maps to a real failure the
 * production code already handles, so the UI states being tested here are the same ones users hit.
 *
 * Scenarios are opt-in and never active in a release build by default.
 */
class TestModeController(
    private val events: OpenCodeEventManager,
    private val server: OpenCodeServerManager,
    private val log: LogManager
) {

    @Volatile
    var scenario: TestScenario = TestScenario.NONE
        private set

    @Volatile
    private var repeatJob: kotlinx.coroutines.Job? = null

    private var scope: kotlinx.coroutines.CoroutineScope? = null

    /**
     * Apply a scenario.
     *
     * [TestScenario.NONE] tears everything down, including the repeating fault injector, so a test
     * mode can never leak into later behaviour.
     */
    fun apply(newScenario: TestScenario) {
        if (scenario == newScenario) return
        scenario = newScenario
        server.scenario = newScenario

        if (newScenario == TestScenario.NONE) {
            repeatJob?.cancel()
            repeatJob = null
            log.info(LogCategory.RUNTIME, "Test mode disabled")
            return
        }

        log.warn(
            LogCategory.RUNTIME,
            "Test mode enabled",
            "scenario=${newScenario.name} label=${newScenario.label}"
        )

        if (newScenario == TestScenario.SSE_DISCONNECT) {
            events.simulateDisconnect("Test mode: SSE disconnect")
        }
    }

    /** Start the repeating injector. Called once by the backend when a scenario needs a loop. */
    fun bindScope(scope: kotlinx.coroutines.CoroutineScope) {
        this.scope = scope
    }

    /**
     * Drive a repeating fault, for scenarios that must fire more than once.
     *
     * Slower than the real failure rate on purpose: the UI has to be readable.
     */
    fun startRepeatingFault(intervalMs: Long = 20_000) {
        if (!scenario.isActive) return
        val target = scope ?: return
        repeatJob?.cancel()
        repeatJob = target.launch {
            while (isActive) {
                delay(intervalMs)
                when (scenario) {
                    TestScenario.SSE_DISCONNECT -> events.simulateDisconnect("Test mode: periodic disconnect")
                    TestScenario.LOW_STORAGE -> log.warn(
                        LogCategory.STORAGE,
                        "Test mode: simulating low storage",
                        "reported free space is artificially reduced"
                    )
                    TestScenario.SERVER_CRASH -> log.warn(
                        LogCategory.SERVER,
                        "Test mode: simulating a server crash",
                        "the real path is exercised by killing the process"
                    )
                    TestScenario.TERMINAL_CRASH -> log.warn(
                        LogCategory.TERMINAL,
                        "Test mode: simulating a terminal crash"
                    )
                    else -> Unit
                }
            }
        }
    }

    /**
     * Spec 81 - explain what the current scenario will do.
     *
     * Surfaced in the UI so a tester knows they are not looking at a genuine failure.
     */
    fun describe(): String = when (scenario) {
        TestScenario.NONE -> "Test mode is off."
        TestScenario.SLOW_STARTUP -> "Server startup is delayed by 3 seconds."
        TestScenario.SERVER_CRASH -> "The server exits after starting."
        TestScenario.CONNECTION_FAILURE -> "API requests fail as if the server were unreachable."
        TestScenario.DEPENDENCY_FAILURE -> "Dependency installation fails."
        TestScenario.OPENCODE_UNAVAILABLE ->
            "Setup reports OpenCode as unavailable for this architecture."
        TestScenario.INVALID_API_KEY -> "The provider rejects the stored key."
        TestScenario.SSE_DISCONNECT -> "The event stream drops periodically."
        TestScenario.TERMINAL_CRASH -> "Terminal sessions terminate unexpectedly."
        TestScenario.LOW_STORAGE -> "Available storage is reported as critically low."
        TestScenario.OFFLINE -> "The device reports no internet connection."
    }

    /** The error a scenario should present, so the UI shows a real message rather than a stub. */
    fun errorForCurrentScenario(): BackendError? = when (scenario) {
        TestScenario.NONE -> null
        TestScenario.CONNECTION_FAILURE -> BackendError.Connection(
            host = "127.0.0.1",
            port = server.info.value.port ?: 4096,
            technical = "simulated ECONNREFUSED"
        )
        TestScenario.INVALID_API_KEY -> BackendError.Categorized(
            technical = "provider returned 401",
            category = com.palash.opencodeserver.backend.core.ErrorCategory.AUTHENTICATION,
            retryable = false,
            userTitle = "The provider rejected the API key.",
            userMessage = "Check the key in Providers and save it again."
        )
        TestScenario.OFFLINE -> BackendError.Network("simulated offline", retryable = true)
        TestScenario.LOW_STORAGE -> BackendError.Storage(500L * 1024 * 1024, 180L * 1024 * 1024)
        else -> null
    }
}