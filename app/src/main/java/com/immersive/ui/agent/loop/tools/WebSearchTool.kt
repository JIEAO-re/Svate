package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.SearchConfig
import com.immersive.ui.agent.loop.SearchEndpointStore
import com.immersive.ui.agent.loop.SearchProvider
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A single normalized search result, provider-agnostic. */
internal data class SearchHit(val title: String, val url: String, val snippet: String)

/**
 * Search the web for up-to-date information and return the top results. Read-only
 * (SAFE) — it never touches the device, only the configured search provider over
 * HTTPS. Lets the model look things up directly instead of driving a browser app.
 *
 * The provider (Tavily / Brave / self-hosted SearXNG), api key, and optional
 * endpoint override come from [SearchEndpointStore]. Like all loop tools it runs
 * on-device, so it works in both the device-direct and server-proxy turn modes.
 */
class WebSearchTool : PhoneTool {
    override val name: String = "web_search"
    override val description: String =
        "Search the web and return the top results (title, URL, snippet). Use it to look up " +
            "current facts, prices, docs, or anything not on the current screen — instead of " +
            "navigating a browser app. Read-only. Requires a search provider configured in settings."
    override val isReadOnly: Boolean = true
    override val riskClass: RiskClass = RiskClass.SAFE

    private val timeoutMs = 15_000

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("query", ToolSupport.prop("string", "The search query."))
        props.put("max_results", ToolSupport.prop("integer", "How many results to return (default 5, max 10)."))
        return ToolSupport.objectSchema(props, required = listOf("query"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) return ToolResult(ok = false, text = "web_search requires a non-empty query.")
        val maxResults = args.optInt("max_results", 5).coerceIn(1, 10)

        val config = SearchEndpointStore.load(ctx.appContext)
        if (!config.isConfigured()) {
            return ToolResult(
                ok = false,
                text = "Web search is not configured. Set a search provider and key (or a SearXNG " +
                    "endpoint) in Svate settings.",
            )
        }
        // The query is model-generated; redact any secret-shaped tokens before it
        // leaves the device to the search provider.
        val safeQuery = ToolSupport.redactSecrets(query)

        return try {
            val body = withTimeout(timeoutMs.toLong() + 5_000L) {
                runInterruptible(Dispatchers.IO) { fetch(config, safeQuery, maxResults) }
            }
            val hits = parseResults(config.provider, body, maxResults)
            if (hits.isEmpty()) {
                ToolResult(ok = true, text = "No web results for \"$safeQuery\".")
            } else {
                ToolResult(ok = true, text = formatResults(safeQuery, hits))
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            ToolResult(ok = false, text = "web_search failed: ${t.message}")
        }
    }

    // ===== Per-provider request construction =====

    private fun fetch(config: SearchConfig, query: String, maxResults: Int): String = when (config.provider) {
        SearchProvider.TAVILY -> tavily(config, query, maxResults)
        SearchProvider.BRAVE -> brave(config, query, maxResults)
        SearchProvider.SEARXNG -> searxng(config, query)
    }

    private fun tavily(config: SearchConfig, query: String, maxResults: Int): String {
        val base = config.endpoint.ifBlank { "https://api.tavily.com" }.trimEnd('/')
        val url = URL("$base/search")
        requireSecureUrl(url)
        val payload = JSONObject()
            .put("api_key", config.apiKey)
            .put("query", query)
            .put("max_results", maxResults)
            .put("search_depth", "basic")
            .toString()
        return postJson(url, payload, headers = emptyMap())
    }

    private fun brave(config: SearchConfig, query: String, maxResults: Int): String {
        val base = config.endpoint.ifBlank { "https://api.search.brave.com" }.trimEnd('/')
        val q = URLEncoder.encode(query, "UTF-8")
        val url = URL("$base/res/v1/web/search?q=$q&count=$maxResults")
        requireSecureUrl(url)
        return httpGet(
            url,
            headers = mapOf(
                "Accept" to "application/json",
                "X-Subscription-Token" to config.apiKey,
            ),
        )
    }

    private fun searxng(config: SearchConfig, query: String): String {
        val base = config.endpoint.trimEnd('/')
        val q = URLEncoder.encode(query, "UTF-8")
        val url = URL("$base/search?q=$q&format=json")
        requireSecureUrl(url)
        val headers = if (config.apiKey.isNotBlank()) mapOf("Authorization" to "Bearer ${config.apiKey}") else emptyMap()
        return httpGet(url, headers)
    }

    // ===== Networking =====

    private fun httpGet(url: URL, headers: Map<String, String>): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return readResponse(connection)
    }

    private fun postJson(url: URL, payload: String, headers: Map<String, String>): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload) }
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): String {
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("search failed: HTTP $code | ${text.take(200)}")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun requireSecureUrl(url: URL) {
        if (url.protocol.equals("https", ignoreCase = true)) return
        val host = url.host.orEmpty().lowercase()
        val isLoopback = host == "10.0.2.2" || host == "localhost" || host == "127.0.0.1"
        if (!isLoopback) {
            throw IllegalStateException(
                "Search endpoint must use https; plain http is only allowed for loopback hosts " +
                    "(10.0.2.2/localhost/127.0.0.1) — e.g. a local SearXNG instance.",
            )
        }
    }

    companion object {
        /**
         * Parse a provider response body into normalized [SearchHit]s. Pure (org.json
         * only, no android.* / network) so it is JVM-unit-testable.
         */
        internal fun parseResults(provider: SearchProvider, body: String, maxResults: Int): List<SearchHit> {
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
            val arr: JSONArray = when (provider) {
                SearchProvider.TAVILY -> root.optJSONArray("results")
                SearchProvider.BRAVE -> root.optJSONObject("web")?.optJSONArray("results")
                SearchProvider.SEARXNG -> root.optJSONArray("results")
            } ?: JSONArray()

            val out = mutableListOf<SearchHit>()
            for (i in 0 until arr.length()) {
                if (out.size >= maxResults) break
                val o = arr.optJSONObject(i) ?: continue
                val link = o.optString("url").trim()
                if (link.isBlank()) continue
                val title = o.optString("title").trim().ifBlank { link }
                val snippet = when (provider) {
                    SearchProvider.BRAVE -> o.optString("description")
                    else -> o.optString("content")
                }.trim()
                out += SearchHit(title = title, url = link, snippet = snippet)
            }
            return out
        }

        /** Render hits into a compact, model-facing block. Pure / JVM-unit-testable. */
        internal fun formatResults(query: String, hits: List<SearchHit>): String {
            val sb = StringBuilder()
            sb.append("Web search results for \"").append(query).append("\":\n")
            hits.forEachIndexed { i, h ->
                sb.append(i + 1).append(". ").append(h.title).append('\n')
                sb.append("   ").append(h.url).append('\n')
                if (h.snippet.isNotBlank()) {
                    sb.append("   ").append(h.snippet.take(300)).append('\n')
                }
            }
            return sb.toString().trimEnd()
        }
    }
}
