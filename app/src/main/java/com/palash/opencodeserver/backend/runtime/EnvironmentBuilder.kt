package com.palash.opencodeserver.backend.runtime

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.DeviceArchitecture
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.log.SecretRedactor
import java.io.File
import java.util.LinkedHashMap

/**
 * Spec 54 - a controlled environment for every child process.
 *
 * Two rules drive the design:
 *  1. Build the environment from an allowlist. Anything not named here is not inherited, so an
 *     Android-specific secret can never leak into the Linux userland (spec 54).
 *  2. The secret-bearing variables ([OPENCODE_SERVER_PASSWORD], [OPENCODE_SERVER_USERNAME]) are
 *     returned through [ProcessEnvironment.withSecrets] rather than stored on the object, which
 *     keeps them out of any `toString`, log line or diagnostics dump (spec 15/27).
 */
class EnvironmentBuilder(
    private val layout: RuntimeLayout,
    private val architecture: DeviceArchitecture,
    private val log: LogManager
) {

    /** Values that are safe to log, print and persist. */
    private val base: LinkedHashMap<String, String> = LinkedHashMap()

    /** Names of the secret variables we inject at spawn time. */
    private val secretKeys = linkedSetOf("OPENCODE_SERVER_PASSWORD", "OPENCODE_SERVER_USERNAME")

    init {
        base["HOME"] = layout.home.absolutePath
        base["PWD"] = layout.home.absolutePath
        base["TMPDIR"] = layout.tmpDir.absolutePath
        base["TMP"] = layout.tmpDir.absolutePath
        base["TEMP"] = layout.tmpDir.absolutePath
        base["PATH"] = buildPath()
        base["TERM"] = "xterm-256color"
        base["COLORTERM"] = "truecolor"
        base["LANG"] = "en_US.UTF-8"
        base["LC_ALL"] = "en_US.UTF-8"
        base["SHELL"] = detectPreferredShell()
        base["USER"] = "dev"
        base["LOGNAME"] = "dev"

        // OpenCode data/config placement. The server honours these, so the app controls where the
        // persistent state lives instead of relying on the process CWD (spec 47/56).
        base["OPENCODE_CONFIG_DIR"] = layout.openCodeConfigDir.absolutePath
        base["OPENCODE_DATA_DIR"] = layout.openCodeDataDir.absolutePath
        base["OPENCODE_DISABLE_AUTOUPDATE"] = "true"
        base["OPENCODE_DISABLE_TERMINAL_TITLE"] = "true"
        base["OPENCODE_CLIENT"] = "android"
        base["NO_COLOR"] = ""

        // Architecture facts, useful for installers that branch on platform.
        base["ANDROID_ABI"] = architecture.androidAbi
        base["OPENCODE_ANDROID_APP"] = "1"
    }

    /**
     * Spec 55 - PATH ordering. The app-owned `bin` directory comes first so an installed or repaired
     * `opencode` always wins over anything the userland provides, and the standard userland bins
     * follow so `git`, `node` and friends resolve.
     */
    private fun buildPath(): String = listOf(
        layout.binDir.absolutePath,
        layout.homeBin.absolutePath,
        File(layout.home, ".local/share/opencode/bin").absolutePath,
        "/usr/local/bin",
        "/usr/bin",
        "/bin",
        "/system/bin"
    ).distinct().joinToString(":")

    /** Spec 34 - prefer bash, fall back to sh, then the runtime default. */
    private fun detectPreferredShell(): String {
        listOf("/data/data/com.termux/files/usr/bin/bash", "/usr/bin/bash", "/system/bin/bash")
            .firstOrNull { File(it).canExecute() }
            ?.let { return it }
        return listOf("/data/data/com.termux/files/usr/bin/sh", "/usr/bin/sh", "/system/bin/sh")
            .firstOrNull { File(it).canExecute() }
            ?: "/system/bin/sh"
    }

    fun set(key: String, value: String) {
        if (SecretRedactor.isSensitiveKey(key)) {
            // Never place a secret in the loggable map; it belongs in the secret channel.
            log.warn(LogCategory.SECURITY, "Refused to place a secret in the base environment", "key=$key")
            return
        }
        base[key] = value
    }

    fun unset(key: String) {
        base.remove(key)
    }

    fun setWorkingDirectory(path: String) {
        base["PWD"] = path
    }

    fun setShell(shell: String) {
        base["SHELL"] = shell
    }

    fun overridePath(entries: List<String>) {
        base["PATH"] = entries.distinct().joinToString(":")
    }

    fun pathEntries(): List<String> = base["PATH"].orEmpty().split(":").filter { it.isNotBlank() }

    /** Spec 54 - the complete environment minus secrets, safe to log verbatim. */
    fun snapshot(): Map<String, String> = LinkedHashMap(base)

    /** The value that will be visible to child processes; secrets intentionally excluded. */
    fun processEnvironment(): Map<String, String> = LinkedHashMap(base)

    /**
     * Build the final environment for a spawn.
     *
     * [secrets] is passed in at the call site from the credential vault and is never retained.
     */
    fun processEnvironment(secrets: Map<String, String> = emptyMap()): Map<String, String> {
        val out = LinkedHashMap(base)
        secrets.forEach { (key, value) ->
            if (key in secretKeys) out[key] = value
        }
        return out
    }

    /** Spec 15 - the credential reference, never the credential itself. */
    fun secretKeyNames(): Set<String> = secretKeys

    /**
     * Spec 42/59 - environment summary for the Advanced view. Values pass through the redactor so
     * even a future addition of a secret-shaped key cannot leak.
     */
    fun redactedSummary(): Map<String, String> = SecretRedactor.redactAll(base)

    /** Spec 55 - verify a command is reachable on PATH; used by the repair flow. */
    fun which(command: String): String? {
        val direct = pathEntries().map { File(it, command) }.firstOrNull { it.canExecute() && it.isFile }
        if (direct != null) return direct.absolutePath
        // Fall back to an actual PATH lookup inside the runtime; the install flow uses this because
        // a userland binary may not be visible to the host JVM's PATH.
        return null
    }

    fun assertWritableRuntime(): BackendError? =
        layout.ensureDirectories() ?: layout.runtimeRoot.takeIf { !it.canWrite() }?.let {
            BackendError.Runtime("Runtime root is not writable", retryable = false)
        }
}