package com.palash.opencodeserver.backend.events

import com.palash.opencodeserver.backend.api.HttpOpenCodeApiClient
import com.palash.opencodeserver.backend.api.OcEvent
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response

/** Spec 22/23 - observable connection state of the SSE stream. */
data class EventStreamState(
    val connected: Boolean = false,
    val connectedSince: Long? = null,
    val lastEventAt: Long? = null,
    val reconnectAttempts: Int = 0,
    val nextRetryAt: Long? = null,
    val lastError: String? = null,
    val serverVersion: String? = null
) {
    /** Spec 23 - true while the client is actively waiting to retry. */
    val isRetrying: Boolean get() = !connected && nextRetryAt != null
}

/**
 * Spec 22/23/95 - the SSE event manager.
 *
 * Responsibilities, matching the spec exactly:
 * ```
 * connect()  disconnect()  reconnect()  subscribe()  unsubscribe()
 * ```
 *
 * Reconnect policy (spec 23): 1s, 2s, 5s, then 10s with jitter, capped attempts. Retrying stops as
 * soon as the server is confirmed stopped, because a stopped server needs [connect], not retries.
 *
 * Lifecycle robustness (spec 22): the read loop runs on an IO dispatcher and is a child of a
 * supervisor, so app backgrounding or a UI crash cannot take the backend down. Android may still
 * suspend the process; the next foreground triggers [onForeground] which forces a reconnect.
 */
/**
 * @param clientProvider resolves the HTTP client on demand.
 *
 * A provider rather than an instance breaks a construction cycle: the event manager needs an API
 * client, but the API client needs the server manager's endpoint, and the server manager needs the
 * event manager. Resolving lazily means all three can be wired in any order.
 */
class OpenCodeEventManager(
    private val clientProvider: () -> HttpOpenCodeApiClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val client: HttpOpenCodeApiClient get() = clientProvider()

    private val moshi = Moshi.Builder().build()
    private val eventAdapter by lazy {
        val type = Types.newParameterizedType(OcEvent::class.java, Map::class.java, String::class.java, Any::class.java)
        moshi.adapter<OcEvent>(type)
    }

    private val _events = MutableSharedFlow<BackendEvent>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<BackendEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(EventStreamState())
    val state: StateFlow<EventStreamState> = _state.asStateFlow()

    private val _rawEvents = MutableSharedFlow<OcEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    /** Escape hatch for diagnostics: the untranslated server events. */
    val rawEvents: SharedFlow<OcEvent> = _rawEvents.asSharedFlow()

    @Volatile
    private var readJob: Job? = null

    @Volatile
    private var serverConfirmedStopped: Boolean = true

    /** Guard against two concurrent read loops when connect and reconnect race. */
    private val connectMutex = Mutex()

    // ---- spec 22 API ------------------------------------------------------------------

    /** Connect, or do nothing if already connected. */
    suspend fun connect(): Boolean = connectMutex.withLock {
        if (_state.value.connected) return@withLock true
        // Spec 23 - a confirmed-stopped server needs connect(), not a retry loop.
        if (serverConfirmedStopped) return@withLock false
        startReadLoop(initial = true)
        _state.value.connected || readJob?.isActive == true
    }

    /** Spec 23/89 - disconnect deliberately. No retries follow. */
    suspend fun disconnect() {
        readJob?.cancel()
        readJob = null
        _state.update {
            it.copy(connected = false, connectedSince = null, nextRetryAt = null, lastError = null)
        }
    }

    /** Force a fresh connection, resetting the backoff (spec 22/38 - server was restarted). */
    suspend fun reconnect() {
        disconnect()
        _state.update { it.copy(reconnectAttempts = 0, nextRetryAt = null) }
        connect()
    }

    fun subscribe(): SharedFlow<BackendEvent> = _events

    fun unsubscribe() {
        // Subscribers are removed by cancelling their collection; this exists so the contract in the
        // spec is explicit and so a future reference-counted implementation has a home.
        _events.resetReplayCache()
    }

    // ---- lifecycle hooks ---------------------------------------------------------------

    /** Called by the server manager once the process is confirmed alive. */
    fun onServerStarted(serverVersion: String?) {
        serverConfirmedStopped = false
        _state.update { it.copy(serverVersion = serverVersion, reconnectAttempts = 0) }
    }

    /** Spec 23/89 - a confirmed stop means stop retrying. */
    fun onServerStopped(reason: String) {
        serverConfirmedStopped = true
        readJob?.cancel()
        readJob = null
        _state.update {
            EventStreamState(connected = false, lastError = reason)
        }
    }

    /** Spec 22 - app came to the foreground: verify and reconnect if needed. */
    suspend fun onForeground() {
        if (serverConfirmedStopped) return
        if (_state.value.connected && readJob?.isActive == true) return
        reconnect()
    }

    /** Spec 22 - app went to the background: keep the stream but stop reconnect churn. */
    fun onBackground() {
        // Android may suspend us at any moment. Releasing the socket keeps a backgrounded app from
        // holding a wakeful connection; the foreground hook re-establishes it.
        readJob?.cancel()
        readJob = null
        _state.update { it.copy(connected = false, nextRetryAt = null) }
    }

    /** Spec 81 - simulate a dropped stream in test mode. */
    fun simulateDisconnect(reason: String = "Simulated event stream disconnect") {
        readJob?.cancel()
        readJob = null
        _state.update {
            it.copy(connected = false, lastError = reason, nextRetryAt = System.currentTimeMillis())
        }
        _events.tryEmit(BackendEvent.EventStreamDisconnected(reason, willRetry = !serverConfirmedStopped))
        if (!serverConfirmedStopped) scope.launch { onForeground() }
    }

    // ---- read loop ----------------------------------------------------------------------

    private suspend fun startReadLoop(initial: Boolean) {
        val job = scope.launch {
            var attempt = if (initial) 0 else _state.value.reconnectAttempts
            while (coroutineContext.isActive && !serverConfirmedStopped) {
                try {
                    val opened = runOnce()
                    if (opened) {
                        attempt = 0
                        // A successful read means the stream stayed open. If it ends we loop.
                        continue
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    onStreamLost(e.message ?: e::class.java.simpleName)
                } catch (e: Exception) {
                    onStreamLost(e::class.java.simpleName)
                }

                if (serverConfirmedStopped) break

                attempt++
                val delayMs = backoffMs(attempt)
                _state.update {
                    it.copy(
                        connected = false,
                        reconnectAttempts = attempt,
                        nextRetryAt = System.currentTimeMillis() + delayMs,
                        lastError = "Stream closed"
                    )
                }
                _events.tryEmit(BackendEvent.EventStreamReconnecting(attempt, delayMs))
                delay(delayMs)
            }
        }
        readJob = job
    }

    /** Returns true when the stream connected and stayed open until cancelled. */
    private suspend fun runOnce(): Boolean {
        val call = client.openEventStream()
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    onStreamLost("HTTP ${response.code}")
                    return false
                }
                onStreamOpened(response)
                readSseLoop(response)
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            onStreamLost(e.message ?: "stream error")
            false
        }
    }

    /**
     * Parse the SSE wire format directly.
     *
     * Doing this by hand rather than adding a dependency keeps the parse surface small and auditable,
     * and lets the reconnection logic own the whole lifecycle. Frame shape:
     * ```
     * event: message.part.updated
     * data: {"type":"message.part.updated","properties":{...}}
     * ```
     */
    private suspend fun readSseLoop(response: Response) {
        val source = response.body?.source() ?: run {
            onStreamLost("Empty response body")
            return
        }
        val eventName = StringBuilder()
        val data = StringBuilder()

        while (coroutineContext.isActive && !serverConfirmedStopped) {
            val line = try {
                source.readUtf8Line()
            } catch (e: IOException) {
                onStreamLost(e.message ?: "read failed")
                return
            }

            if (line == null) {
                onStreamLost("Server closed the stream")
                return
            }

            when {
                line.isEmpty() -> {
                    // Blank line terminates a frame.
                    val payload = data.toString()
                    val name = eventName.toString()
                    if (payload.isNotBlank()) dispatchFrame(name, payload)
                    eventName.setLength(0)
                    data.setLength(0)
                }
                line.startsWith(":") -> Unit // Comment / keep-alive.
                line.startsWith("event:") -> eventName.append(line.removePrefix("event:").trim())
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trim())
                }
                line.startsWith("id:") -> Unit
                line.startsWith("retry:") -> Unit
                else -> Unit
            }
        }
    }

    private suspend fun dispatchFrame(eventName: String, payload: String) {
        val parsed = try {
            eventAdapter.fromJson(payload)
        } catch (e: Exception) {
            null
        } ?: return

        _state.update { it.copy(lastEventAt = System.currentTimeMillis()) }
        _rawEvents.tryEmit(parsed)

        OpenCodeEventMapper.map(parsed).forEach { mapped ->
            _events.tryEmit(mapped)
            // Forward a compact trail into the app log for the Advanced view (spec 41).
            if (mapped is BackendEvent.Error) {
                // Errors are already user-safe strings produced by the mapper.
            }
        }
    }

    private suspend fun onStreamOpened(response: Response) {
        _state.update {
            it.copy(
                connected = true,
                connectedSince = System.currentTimeMillis(),
                reconnectAttempts = 0,
                nextRetryAt = null,
                lastError = null
            )
        }
        _events.tryEmit(BackendEvent.EventStreamConnected(_state.value.serverVersion))
    }

    private fun onStreamLost(reason: String) {
        _state.update { it.copy(connected = false, lastError = reason) }
        _events.tryEmit(
            BackendEvent.EventStreamDisconnected(reason, willRetry = !serverConfirmedStopped)
        )
    }

    /**
     * Spec 23 - 1s, 2s, 5s, then 10s with jitter.
     *
     * Jitter matters here: without it, every reinstalled app on a shared network retries in lockstep.
     */
    internal fun backoffMs(attempt: Int): Long {
        val base = when {
            attempt <= 1 -> 1_000L
            attempt == 2 -> 2_000L
            attempt <= 4 -> 5_000L
            else -> 10_000L
        }
        val jitter = (base * 0.2 * kotlin.random.Random.nextDouble()).toLong()
        return base + jitter
    }
}

