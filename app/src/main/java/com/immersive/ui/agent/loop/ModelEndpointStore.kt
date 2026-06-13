package com.immersive.ui.agent.loop

import android.content.Context

/**
 * A user-configured, OpenAI-compatible model endpoint, stored locally on the
 * device (SharedPreferences). When configured, the agent loop and goal
 * understanding talk to this endpoint directly — no project backend and no
 * Gemini default — using the user's own base URL + model + key.
 */
data class EndpointConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    /** All three fields are required for the device to drive the model directly. */
    fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    /** Base URL with trailing slashes trimmed; clients append `/chat/completions`. */
    fun normalizedBaseUrl(): String = baseUrl.trim().trimEnd('/')
}

/**
 * Reads/writes [EndpointConfig] in the shared "svate_settings" preferences. The
 * key is kept on-device only and never logged.
 */
object ModelEndpointStore {
    private const val PREFS = "svate_settings"
    private const val KEY_BASE_URL = "model_endpoint_base_url"
    private const val KEY_MODEL = "model_endpoint_model"
    private const val KEY_API_KEY = "model_endpoint_api_key"

    fun load(context: Context): EndpointConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return EndpointConfig(
            baseUrl = p.getString(KEY_BASE_URL, "").orEmpty(),
            model = p.getString(KEY_MODEL, "").orEmpty(),
            apiKey = p.getString(KEY_API_KEY, "").orEmpty(),
        )
    }

    fun save(context: Context, config: EndpointConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .apply()
    }
}
