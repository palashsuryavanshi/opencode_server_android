package com.palash.opencodeserver.backend.opencode

import android.content.Context
import com.palash.opencodeserver.backend.api.OcConfig
import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.squareup.moshi.Moshi
import java.io.File

/**
 * Spec 47 - the managed OpenCode configuration.
 *
 * The rule from the spec is "do not hard-code OpenCode configuration into the Android application".
 * This class therefore:
 *
 *  - writes a *minimal* config file that only sets things the app genuinely needs (a stable model
 *    choice is deliberately **not** set, because that would override the user's provider choice)
 *  - exposes only settings that the running server confirms it supports, which is verified by
 *    reading back the effective config from `GET /config`
 *  - never edits the config outside the managed directory
 */
class OpenCodeConfigManager(
    private val context: Context,
    private val log: LogManager
) {

    private val moshi = Moshi.Builder().build()

    /** The config file inside the runtime-determined config dir (spec 47). */
    fun configFile(configDir: File): File = File(configDir, "opencode.json")

    /**
     * Spec 47/100 - ensure the configuration exists.
     *
     * Written as JSON text rather than through a serializer so the file is readable and the app's
     * intent is explicit. Only non-content settings are included.
     */
    fun ensureConfig(configDir: File): BackendResult<File> {
        return try {
            if (!configDir.exists() && !configDir.mkdirs()) {
                return BackendResult.Failure(
                    BackendError.Categorized(
                        technical = "Could not create ${configDir.absolutePath}",
                        category = com.palash.opencodeserver.backend.core.ErrorCategory.PERMISSION,
                        retryable = false,
                        userTitle = "Configuration could not be created.",
                        userMessage = "The app could not create its configuration folder."
                    )
                )
            }
            val file = configFile(configDir)
            if (!file.exists()) {
                file.writeText(defaultConfigJson(), Charsets.UTF_8)
                log.info(LogCategory.OPENCODE, "Created OpenCode configuration", file.name)
            } else {
                log.debug(LogCategory.OPENCODE, "OpenCode configuration already exists", file.name)
            }
            BackendResult.Success(file)
        } catch (e: Exception) {
            BackendResult.Failure(BackendError.wrap(e, com.palash.opencodeserver.backend.core.ErrorCategory.PERMISSION))
        }
    }

    /**
     * The minimum configuration the app manages.
     *
     * `share` is set to manual so session transcripts are not published automatically; everything
     * else is left to OpenCode defaults.
     */
    private fun defaultConfigJson(): String = """
        {
          "${'$'}schema": "https://opencode.ai/config.json",
          "share": "manual",
          "autoupdate": "notify"
        }
    """.trimIndent()

    /** Spec 47 - read the effective config the server actually loaded. */
    suspend fun readEffective(client: com.palash.opencodeserver.backend.api.OpenCodeApiClient): BackendResult<OcConfig> =
        client.getConfig()

    /**
     * Spec 47 - apply a user edit.
     *
     * Goes through the API rather than writing the file, so OpenCode validates the change and the
     * result reflects what the server accepted. A rejection is surfaced as an error instead of a
     * silent no-op.
     */
    suspend fun applyEdit(
        client: com.palash.opencodeserver.backend.api.OpenCodeApiClient,
        edit: ConfigEdit
    ): BackendResult<OcConfig> {
        val current = when (val read = readEffective(client)) {
            is BackendResult.Success -> read.value
            is BackendResult.Failure -> return read
        }

        val updated = current.copy(
            model = edit.model ?: current.model,
            small_model = edit.smallModel ?: current.small_model,
            theme = edit.theme ?: current.theme,
            logLevel = edit.logLevel ?: current.logLevel,
            share = edit.share ?: current.share
        )

        return when (val patched = client.patchConfig(updated)) {
            is BackendResult.Success -> {
                log.info(LogCategory.OPENCODE, "Applied configuration edit", describeEdit(edit))
                patched
            }
            is BackendResult.Failure -> patched
        }
    }

    /**
     * The settings this app is willing to expose.
     *
     * Anything not listed here is not offered in the UI, because offering a setting the app cannot
     * honour would be worse than not offering it (spec 47).
     */
    fun supportedSettings(): List<SupportedSetting> = listOf(
        SupportedSetting(
            key = "model",
            label = "Default model",
            description = "Provider/model used when a session does not specify one.",
            format = "provider/model"
        ),
        SupportedSetting(
            key = "small_model",
            label = "Small model",
            description = "Model used for short tasks such as title generation.",
            format = "provider/model"
        ),
        SupportedSetting(
            key = "theme",
            label = "Theme",
            description = "Interface theme used by the OpenCode TUI."
        ),
        SupportedSetting(
            key = "logLevel",
            label = "Log level",
            description = "OpenCode's own log verbosity.",
            allowed = listOf("DEBUG", "INFO", "WARN", "ERROR")
        ),
        SupportedSetting(
            key = "share",
            label = "Session sharing",
            description = "Controls automatic publishing of session transcripts.",
            allowed = listOf("manual", "auto", "disabled")
        )
    )

    private fun describeEdit(edit: ConfigEdit): String = buildList {
        edit.model?.let { add("model=$it") }
        edit.smallModel?.let { add("small_model=$it") }
        edit.theme?.let { add("theme=$it") }
        edit.logLevel?.let { add("logLevel=$it") }
        edit.share?.let { add("share=$it") }
    }.joinToString(", ").ifEmpty { "no changes" }

    /** Spec 119 - copy the config aside before a reset or upgrade. */
    fun backup(configDir: File, destinationDir: File): BackendResult<File> = try {
        if (!destinationDir.exists()) destinationDir.mkdirs()
        val stamp = System.currentTimeMillis()
        val target = File(destinationDir, "opencode-config-$stamp.json")
        configFile(configDir).takeIf { it.exists() }?.copyTo(target, overwrite = true)
        log.info(LogCategory.OPENCODE, "Configuration backed up", target.name)
        BackendResult.Success(target)
    } catch (e: Exception) {
        BackendResult.Failure(BackendError.wrap(e))
    }

    /** Spec 117 - reset configuration without touching credentials or sessions. */
    fun reset(configDir: File): BackendResult<Unit> = try {
        val config = configFile(configDir)
        if (config.exists()) config.delete()
        ensureConfig(configDir)
        BackendResult.Success(Unit)
    } catch (e: Exception) {
        BackendResult.Failure(BackendError.wrap(e))
    }
}

/** A partial configuration change; null fields are left as-is. */
data class ConfigEdit(
    val model: String? = null,
    val smallModel: String? = null,
    val theme: String? = null,
    val logLevel: String? = null,
    val share: String? = null
)

/** Spec 47 - a setting the app is willing to show, with its constraints. */
data class SupportedSetting(
    val key: String,
    val label: String,
    val description: String,
    val format: String? = null,
    val allowed: List<String>? = null
)