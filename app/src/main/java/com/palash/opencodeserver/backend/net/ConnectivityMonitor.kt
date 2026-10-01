package com.palash.opencodeserver.backend.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.palash.opencodeserver.backend.core.ConnectivityReport
import com.palash.opencodeserver.backend.core.ConnectivityState
import com.palash.opencodeserver.backend.core.ProviderHealth
import com.palash.opencodeserver.backend.core.ServerProcessState
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Spec 45/76/77 - connectivity, reported per layer.
 *
 * The spec is explicit about a mistake this class exists to prevent: do not report "OpenCode offline"
 * merely because the internet is unavailable. The local server keeps working without a network, so
 * these are three independent signals:
 *
 * ```
 * Local OpenCode server:  ✓ Running
 * Terminal:               ✓ Available
 * Internet:               ✗ Offline
 * Provider:               ⚠ Cannot reach provider
 * ```
 */
class ConnectivityMonitor(
    private val context: Context,
    private val log: LogManager
) {

    private val _state = MutableStateFlow(currentState())

    /** Spec 45 - the observable connectivity state. */
    val state: StateFlow<ConnectivityState> = _state.asStateFlow()

    /**
     * Spec 45 - emit on change.
     *
     * Android's own callback is the source, not a polling loop, so this costs nothing when idle.
     */
    fun observe(): Flow<ConnectivityState> = callbackFlow {
        trySend(currentState())
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                update()
            }

            override fun onLost(network: Network) {
                update()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                update()
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        runCatching {
            manager.registerNetworkCallback(request, callback)
            manager.registerDefaultNetworkCallback(callback)
        }.onFailure {
            log.warn(LogCategory.NETWORK, "Could not register network callback", it.message)
        }

        awaitClose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    private fun update() {
        val next = currentState()
        if (next != _state.value) {
            log.info(LogCategory.NETWORK, "Connectivity changed", "${_state.value} -> $next")
            _state.value = next
        }
    }

    /**
     * Spec 76 - internet reachability.
     *
     * `NET_CAPABILITY_VALIDATED` is required so a captive portal is not mistaken for a working
     * connection.
     */
    private fun currentState(): ConnectivityState {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ConnectivityState.UNKNOWN
        return try {
            val network = manager.activeNetwork ?: return ConnectivityState.OFFLINE
            val caps = manager.getNetworkCapabilities(network) ?: return ConnectivityState.OFFLINE
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (hasInternet && validated) ConnectivityState.ONLINE else ConnectivityState.OFFLINE
        } catch (e: Exception) {
            ConnectivityState.UNKNOWN
        }
    }

    /**
     * Spec 76/77 - the layered report.
     *
     * The local server and terminal states are supplied by their owners so this class stays free of
     * dependencies on the server or terminal managers.
     */
    fun buildReport(
        serverState: ServerProcessState,
        terminalAvailable: Boolean,
        providerHealth: ProviderHealth
    ): ConnectivityReport {
        val internet = _state.value
        val detail = when {
            serverState != ServerProcessState.RUNNING && internet == ConnectivityState.ONLINE ->
                "The local server is not running. Internet access is available."
            serverState == ServerProcessState.RUNNING && internet == ConnectivityState.OFFLINE ->
                "Local tools work offline. Prompts need an internet connection."
            serverState == ServerProcessState.RUNNING -> "Everything is connected."
            else -> "Local server is stopped."
        }

        return ConnectivityReport(
            connectivity = internet,
            localServerRunning = serverState == ServerProcessState.RUNNING,
            terminalAvailable = terminalAvailable,
            providerHealth = if (internet == ConnectivityState.OFFLINE && providerHealth != ProviderHealth.UNKNOWN) {
                // Spec 77 - be specific about why the provider is unreachable.
                ProviderHealth.UNREACHABLE
            } else {
                providerHealth
            },
            detail = detail
        )
    }
}