package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.SearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [WebSearchTool] response parsing/formatting across providers.
 * The per-provider JSON shapes differ (Tavily/SearXNG use `content`, Brave nests
 * under `web.results` with `description`), so the adapter must normalize each.
 */
class WebSearchToolTest {

    @Test
    fun parses_tavilyResults() {
        val body = """
            {"query":"q","results":[
              {"title":"Kotlin","url":"https://kotlinlang.org","content":"A modern language","score":0.9},
              {"title":"Docs","url":"https://kotlinlang.org/docs","content":"Reference"}
            ]}
        """.trimIndent()
        val hits = WebSearchTool.parseResults(SearchProvider.TAVILY, body, 5)
        assertEquals(2, hits.size)
        assertEquals("Kotlin", hits[0].title)
        assertEquals("https://kotlinlang.org", hits[0].url)
        assertEquals("A modern language", hits[0].snippet)
    }

    @Test
    fun parses_braveResults_fromNestedWebObject() {
        val body = """
            {"web":{"results":[
              {"title":"Brave","url":"https://brave.com","description":"Search engine"}
            ]}}
        """.trimIndent()
        val hits = WebSearchTool.parseResults(SearchProvider.BRAVE, body, 5)
        assertEquals(1, hits.size)
        assertEquals("Brave", hits[0].title)
        assertEquals("https://brave.com", hits[0].url)
        assertEquals("Search engine", hits[0].snippet) // brave uses `description`
    }

    @Test
    fun parses_searxngResults() {
        val body = """
            {"results":[
              {"title":"SearXNG","url":"https://example.org","content":"Metasearch"}
            ]}
        """.trimIndent()
        val hits = WebSearchTool.parseResults(SearchProvider.SEARXNG, body, 5)
        assertEquals(1, hits.size)
        assertEquals("Metasearch", hits[0].snippet)
    }

    @Test
    fun respects_maxResultsCap() {
        val items = (1..8).joinToString(",") { """{"title":"T$it","url":"https://x/$it","content":"c$it"}""" }
        val body = """{"results":[$items]}"""
        val hits = WebSearchTool.parseResults(SearchProvider.TAVILY, body, 3)
        assertEquals(3, hits.size)
    }

    @Test
    fun skips_entriesWithoutUrl_andFallsBackTitleToUrl() {
        val body = """
            {"results":[
              {"title":"no link","url":"","content":"x"},
              {"title":"","url":"https://only-url","content":"y"}
            ]}
        """.trimIndent()
        val hits = WebSearchTool.parseResults(SearchProvider.TAVILY, body, 5)
        assertEquals(1, hits.size)
        assertEquals("https://only-url", hits[0].url)
        assertEquals("https://only-url", hits[0].title) // blank title falls back to url
    }

    @Test
    fun malformedBody_yieldsNoResults_notThrow() {
        assertTrue(WebSearchTool.parseResults(SearchProvider.TAVILY, "not json", 5).isEmpty())
        assertTrue(WebSearchTool.parseResults(SearchProvider.BRAVE, "{}", 5).isEmpty())
    }

    @Test
    fun formatResults_isNumbered_andEchoesQuery_andTruncatesSnippet() {
        val hits = listOf(
            SearchHit("Title A", "https://a", "snippet a"),
            SearchHit("Title B", "https://b", "x".repeat(500)),
        )
        val out = WebSearchTool.formatResults("my query", hits)
        assertTrue(out.contains("\"my query\""))
        assertTrue(out.contains("1. Title A"))
        assertTrue(out.contains("https://a"))
        assertTrue(out.contains("2. Title B"))
        // Snippet capped at 300 chars, so the 500-char one is truncated.
        assertFalse(out.contains("x".repeat(301)))
    }
}
