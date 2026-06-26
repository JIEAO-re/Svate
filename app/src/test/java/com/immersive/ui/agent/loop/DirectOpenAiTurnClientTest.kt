package com.immersive.ui.agent.loop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [DirectOpenAiTurnClient.buildMessages]: the Gemini-style
 * Content[] -> OpenAI chat messages transform, focused on the fragile positional
 * function_call <-> function_response (tool_call id) pairing.
 */
class DirectOpenAiTurnClientTest {

    private val client = DirectOpenAiTurnClient(EndpointConfig("https://x/v1", "m", "k"))

    private fun textPart(t: String) = JSONObject().put("text", t)
    private fun fnCall(name: String, args: JSONObject = JSONObject()) =
        JSONObject().put("function_call", JSONObject().put("name", name).put("args", args))
    private fun fnResp(name: String, resp: JSONObject = JSONObject()) =
        JSONObject().put("function_response", JSONObject().put("name", name).put("response", resp))
    private fun content(role: String, vararg parts: JSONObject) =
        JSONObject().put("role", role).put("parts", JSONArray().apply { parts.forEach { put(it) } })

    @Test
    fun systemInstruction_isFirstMessage_whenPresent() {
        val msgs = client.buildMessages("be helpful", JSONArray().put(content("user", textPart("hi"))))
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("be helpful", msgs.getJSONObject(0).getString("content"))
        assertEquals("user", msgs.getJSONObject(1).getString("role"))
        assertEquals("hi", msgs.getJSONObject(1).getString("content"))
    }

    @Test
    fun blankSystemInstruction_isOmitted() {
        val msgs = client.buildMessages("", JSONArray().put(content("user", textPart("hi"))))
        assertEquals(1, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
    }

    @Test
    fun modelFunctionCall_becomesAssistantToolCall_withPositionalId() {
        val contents = JSONArray().put(content("model", fnCall("tap", JSONObject().put("x", 1))))
        val msgs = client.buildMessages("", contents)
        val assistant = msgs.getJSONObject(0)
        assertEquals("assistant", assistant.getString("role"))
        val calls = assistant.getJSONArray("tool_calls")
        assertEquals(1, calls.length())
        assertEquals("call_0", calls.getJSONObject(0).getString("id"))
        assertEquals("tap", calls.getJSONObject(0).getJSONObject("function").getString("name"))
        assertTrue(calls.getJSONObject(0).getJSONObject("function").getString("arguments").contains("\"x\""))
        // No text alongside the call -> content is JSON null, not "".
        assertEquals(JSONObject.NULL, assistant.get("content"))
    }

    @Test
    fun functionResponse_pairsToPrecedingCallId_fifo() {
        val contents = JSONArray()
            .put(content("model", fnCall("tap")))
            .put(content("function", fnResp("tap", JSONObject().put("ok", true))))
        val msgs = client.buildMessages("", contents)
        // [0] assistant tool_calls (call_0), [1] tool message echoing call_0.
        val toolMsg = msgs.getJSONObject(1)
        assertEquals("tool", toolMsg.getString("role"))
        assertEquals("call_0", toolMsg.getString("tool_call_id"))
        assertTrue(toolMsg.getString("content").contains("ok"))
    }

    @Test
    fun multipleCallsInOneTurn_pairInOrder_toResponses() {
        val contents = JSONArray()
            .put(content("model", fnCall("a"), fnCall("b")))
            .put(content("function", fnResp("a"), fnResp("b")))
        val msgs = client.buildMessages("", contents)
        val calls = msgs.getJSONObject(0).getJSONArray("tool_calls")
        assertEquals("call_0", calls.getJSONObject(0).getString("id"))
        assertEquals("call_1", calls.getJSONObject(1).getString("id"))
        // Two tool messages follow, paired FIFO: call_0 then call_1.
        assertEquals("call_0", msgs.getJSONObject(1).getString("tool_call_id"))
        assertEquals("call_1", msgs.getJSONObject(2).getString("tool_call_id"))
    }

    @Test
    fun userInlineImage_becomesImageUrlDataUri() {
        val imgPart = JSONObject().put("inline_image_base64", "QUJD").put("mime_type", "image/png")
        val msgs = client.buildMessages("", JSONArray().put(content("user", imgPart)))
        val contentArr = msgs.getJSONObject(0).getJSONArray("content")
        val img = contentArr.getJSONObject(0)
        assertEquals("image_url", img.getString("type"))
        assertEquals("data:image/png;base64,QUJD", img.getJSONObject("image_url").getString("url"))
    }

    @Test
    fun modelTextOnly_becomesPlainAssistantContent() {
        val msgs = client.buildMessages("", JSONArray().put(content("model", textPart("done"))))
        val assistant = msgs.getJSONObject(0)
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("done", assistant.getString("content"))
        assertFalse(assistant.has("tool_calls"))
    }
}
