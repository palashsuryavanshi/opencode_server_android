package com.palash.opencodeserver.backend.server

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Spec 13 - automatic port selection.
 *
 * The documented default is 4096, but assuming it is free is how a second server ends up racing the
 * first. The order is:
 *
 * ```
 * try the configured port
 *   -> occupied? ask the OS for an ephemeral port (IANA dynamic range, never privileged)
 *   -> record the endpoint the server actually bound to
 * ```
 *
 * Two details that matter:
 *  - a port is only *reserved* for the moment it takes to spawn the server, so there is an inherent
 *    race. That is why [verify] exists: the recorded endpoint is confirmed against the health
 *    endpoint rather than assumed.
 *  - when the user has pinned a port, a conflict is reported rather than silently moved, because a
 *    silent port change breaks any external client the user configured.
 */
class PortSelector(
    private val log: LogManager,
    private val lowPort: Int = 49152,
    private val highPort: Int = 65535
) {

    data class Selection(
        val port: Int,
        val wasDefaulted: Boolean,
        val reason: String
    )

    /**
     * Pick a port.
     *
     * @param preferred the user's configured port (4096 by default)
     * @param strict when true, a busy [preferred] is an error rather than a fallback. Used when the
     * user explicitly pinned a port in settings.
     */
    fun select(preferred: Int, strict: Boolean = false): BackendResult<Selection> {
        if (isAvailable(preferred)) {
            return BackendResult.Success(
                Selection(preferred, wasDefaulted = false, reason = "Configured port is available.")
            )
        }

        if (strict) {
            log.warn(LogCategory.SERVER, "Configured port is occupied", "port=$preferred")
            return BackendResult.Failure(
                BackendError.Categorized(
                    technical = "Port $preferred is already in use on 127.0.0.1",
                    category = com.palash.opencodeserver.backend.core.ErrorCategory.CONNECTION,
                    retryable = false,
                    userTitle = "Port $preferred is already in use.",
                    userMessage =
                        "Another app is using that port. Choose a different port in Server settings."
                )
            )
        }

        val fallback = findAvailablePort()
            ?: return BackendResult.Failure(
                BackendError.Categorized(
                    technical = "No free port in $lowPort..$highPort",
                    category = com.palash.opencodeserver.backend.core.ErrorCategory.CONNECTION,
                    retryable = true,
                    userTitle = "No free port for the local server.",
                    userMessage = "Close other apps using local network ports and try again."
                )
            )

        log.info(
            LogCategory.SERVER,
            "Falling back to an available port",
            "requested=$preferred selected=$fallback"
        )
        return BackendResult.Success(
            Selection(
                port = fallback,
                wasDefaulted = true,
                reason = "Port $preferred was occupied, so port $fallback was used instead."
            )
        )
    }

    /** Ask the OS for a free port from the dynamic range. */
    fun findAvailablePort(): Int? {
        repeat(24) {
            val candidate = (lowPort..highPort).random()
            if (isAvailable(candidate)) return candidate
        }
        // Fall back to letting the kernel choose, which is guaranteed to succeed if anything can.
        return runCatching {
            ServerSocket(0).use { it.localPort }
        }.getOrNull()
    }

    /**
     * True when nothing is already bound.
     *
     * Binding to loopback only is intentional: a port occupied on the LAN interface but free on
     * loopback would still block a loopback bind, and the reverse would too, so a plain bind attempt
     * is the accurate test.
     */
    fun isAvailable(port: Int): Boolean = runCatching {
        ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress("127.0.0.1", port))
        }.use { true }
    }.getOrElse { false }

    /**
     * Spec 17/113 - confirm the port is actually listening after start.
     *
     * This is the check that prevents reporting a server as running when the process started but
     * never bound.
     */
    fun isListening(host: String, port: Int, timeoutMs: Int = 1_500): Boolean = try {
        java.net.Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (e: IOException) {
        false
    }
}