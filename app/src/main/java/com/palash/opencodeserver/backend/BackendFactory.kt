package com.palash.opencodeserver.backend

import android.content.Context
import com.palash.opencodeserver.backend.core.LocalCodingBackend
import com.squareup.moshi.Moshi

/**
 * Spec 80/114 - choose between the mock and the real backend.
 *
 * Resolution order:
 *  1. a `USE_MOCK_BACKEND` value in the app's `.env` (the convention this project already uses)
 *  2. the persisted user setting, which is what the Settings screen writes
 *  3. `BuildConfig.DEBUG`, so a development build defaults to mock only when nothing else says so
 *
 * Production builds therefore default to the real backend (spec 114).
 */
object BackendFactory {

    private const val ENV_KEY = "USE_MOCK_BACKEND"
    private val moshi = Moshi.Builder().build()

    @Volatile
    private var cached: LocalCodingBackend? = null

    /** The singleton the whole app shares. Server state must not be duplicated per screen. */
    fun create(context: Context): LocalCodingBackend = cached ?: synchronized(this) {
        cached ?: build(context.applicationContext).also { cached = it }
    }

    /** Explicit selection, used by the Settings toggle and by tests. */
    fun override(backend: LocalCodingBackend?) {
        cached = backend
    }

    fun reset() {
        cached = null
    }

    private fun build(context: Context): LocalCodingBackend =
        if (shouldUseMock(context)) MockBackend() else RealLocalBackend(context)

    /**
     * Read `USE_MOCK_BACKEND` from the packaged resources.
     *
     * The value comes from the Secrets Gradle plugin's generated resources, so it follows the same
     * `.env` convention the rest of this project already uses.
     */
    fun shouldUseMock(context: Context): Boolean {
    envValue(context)?.let { return parseBoolean(it) }

    // The persisted user preference, written by the Settings screen.
    val preference = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    preference.getString(KEY_USE_MOCK, null)?.let { return parseBoolean(it) }

    // Spec 114 - the build type flag is the last resort, and a release build never selects mock.
    return try {
        val clazz = Class.forName("${context.packageName}.BuildConfig")
        val debug = clazz.getField("DEBUG").getBoolean(null)
        val configured = clazz.getField("USE_MOCK_BACKEND").getBoolean(null)
        configured && debug
    } catch (e: Exception) {
        false
    }
}

    /** Persist the user's choice so it survives a restart. */
    fun setMockPreference(context: Context, useMock: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USE_MOCK, useMock.toString())
            .apply()
        // The choice changes which backend instance exists, so the cache must be dropped.
        reset()
    }

    private fun envValue(context: Context): String? = runCatching {
    val id = context.resources.getIdentifier(ENV_KEY, "string", context.packageName)
    if (id == 0) null else context.resources.getString(id)
}.getOrNull()

/** Internal so the resolution rules can be asserted without an Android Context. */
internal fun parseBoolean(value: String): Boolean = when (value.trim().lowercase()) {
    "true", "1", "yes", "on" -> true
    else -> false
}

    private const val PREFS = "opencode_backend_prefs"
    private const val KEY_USE_MOCK = "use_mock_backend"
}