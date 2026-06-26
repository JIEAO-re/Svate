package com.immersive.ui.agent.loop

import android.content.Context
import android.content.SharedPreferences

/** Supported web-search backends for the `web_search` tool. */
enum class SearchProvider {
    /** Tavily (LLM-agent search; POST /search with api_key + query). Default. */
    TAVILY,

    /** Brave Search API (GET /res/v1/web/search with X-Subscription-Token). */
    BRAVE,

    /** Self-hosted SearXNG instance (GET {endpoint}/search?format=json). */
    SEARXNG,
    ;

    companion object {
        fun fromKey(value: String?): SearchProvider =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: TAVILY
    }
}

/**
 * Web-search configuration. [endpoint] is an optional base-URL override (blank =
 * provider default); SearXNG has no public default so it requires one. The api key
 * is a secret and is kept in the Keystore-backed store.
 */
data class SearchConfig(
    val provider: SearchProvider,
    val apiKey: String,
    val endpoint: String,
) {
    fun isConfigured(): Boolean = when (provider) {
        // SearXNG is keyless but needs a self-hosted instance URL.
        SearchProvider.SEARXNG -> endpoint.isNotBlank()
        // Tavily / Brave are hosted; they only need the user's api key.
        else -> apiKey.isNotBlank()
    }
}

/**
 * Reads/writes [SearchConfig]. The api key lives in the same Keystore-backed
 * encrypted store as the model endpoint ("svate_secure_settings"), under distinct
 * `search_*` keys, so secrets are never plaintext on disk. Falls back to the
 * legacy plaintext prefs only if the encrypted store cannot be opened.
 */
object SearchEndpointStore {
    private const val SECURE_PREFS = "svate_secure_settings"
    private const val LEGACY_PREFS = "svate_settings"
    private const val KEY_PROVIDER = "search_provider"
    private const val KEY_API_KEY = "search_api_key"
    private const val KEY_ENDPOINT = "search_endpoint"

    fun load(context: Context): SearchConfig {
        val p = prefs(context)
        return SearchConfig(
            provider = SearchProvider.fromKey(p.getString(KEY_PROVIDER, null)),
            apiKey = p.getString(KEY_API_KEY, "").orEmpty(),
            endpoint = p.getString(KEY_ENDPOINT, "").orEmpty(),
        )
    }

    fun save(context: Context, config: SearchConfig) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, config.provider.name)
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_ENDPOINT, config.endpoint.trim())
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        SecurePrefs.open(context, SECURE_PREFS)
            ?: context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
}
