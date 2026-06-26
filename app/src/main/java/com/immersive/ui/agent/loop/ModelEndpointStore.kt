package com.immersive.ui.agent.loop

import android.content.Context
import android.content.SharedPreferences

/**
 * A user-configured, OpenAI-compatible model endpoint, stored locally on the
 * device. When configured, the agent loop and goal understanding talk to this
 * endpoint directly — no project backend and no Gemini default — using the
 * user's own base URL + model + key.
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
 * Reads/writes [EndpointConfig]. The api key is a real secret, so it is kept in a
 * Keystore-backed [EncryptedSharedPreferences] store ("svate_secure_settings")
 * rather than the plaintext app prefs. Older builds wrote all three fields as
 * plaintext into "svate_settings"; [load] migrates that value into the encrypted
 * store once and scrubs the plaintext copy. If the encrypted store cannot be
 * opened on a device (a broken Keystore), we fall back to the legacy plaintext
 * store so the feature keeps working instead of crashing. The key is never logged.
 */
object ModelEndpointStore {
    // Legacy plaintext store (pre-encryption); still read for one-time migration
    // and used as a fallback when the encrypted store cannot be created.
    private const val LEGACY_PREFS = "svate_settings"
    private const val SECURE_PREFS = "svate_secure_settings"
    private const val KEY_BASE_URL = "model_endpoint_base_url"
    private const val KEY_MODEL = "model_endpoint_model"
    private const val KEY_API_KEY = "model_endpoint_api_key"

    /** Keystore-backed encrypted prefs, or null if they cannot be created here. */
    private fun securePrefs(context: Context): SharedPreferences? = SecurePrefs.open(context, SECURE_PREFS)

    private fun legacyPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): EndpointConfig {
        val secure = securePrefs(context)
            ?: return readFrom(legacyPrefs(context)) // secure store unavailable

        // One-time migration: secure store empty but a legacy plaintext value
        // exists -> copy it across and remove the plaintext copy.
        if (!secure.contains(KEY_API_KEY)) {
            val legacy = legacyPrefs(context)
            val legacyConfig = readFrom(legacy)
            if (legacyConfig.baseUrl.isNotEmpty() ||
                legacyConfig.model.isNotEmpty() ||
                legacyConfig.apiKey.isNotEmpty()
            ) {
                writeTo(secure, legacyConfig)
                legacy.edit()
                    .remove(KEY_BASE_URL)
                    .remove(KEY_MODEL)
                    .remove(KEY_API_KEY)
                    .apply()
                return legacyConfig
            }
        }

        return readFrom(secure)
    }

    fun save(context: Context, config: EndpointConfig) {
        writeTo(securePrefs(context) ?: legacyPrefs(context), config)
    }

    private fun readFrom(p: SharedPreferences): EndpointConfig = EndpointConfig(
        baseUrl = p.getString(KEY_BASE_URL, "").orEmpty(),
        model = p.getString(KEY_MODEL, "").orEmpty(),
        apiKey = p.getString(KEY_API_KEY, "").orEmpty(),
    )

    private fun writeTo(p: SharedPreferences, config: EndpointConfig) {
        p.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .apply()
    }
}
