package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.AgentAccessibilityService
import com.immersive.ui.agent.UiNode
import com.immersive.ui.agent.UiNodePruner
import com.immersive.ui.agent.UiTreeParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Shared helpers for the v1 tools: callback->suspend bridging, JSON schema
 * building, and a compact text rendering of the UI tree.
 *
 * Kept internal to the tools package so each tool file stays focused on one
 * capability.
 */
internal object ToolSupport {

    /** Default timeout for a single gesture dispatch, mirroring AccessibilityMotor. */
    const val GESTURE_TIMEOUT_MS = 3_000L

    /**
     * Bridge a callback-style gesture (`fun(x, cb)`) into a suspend boolean.
     * Returns false on timeout so a stuck dispatch never hangs the loop.
     */
    suspend fun awaitGesture(
        timeoutMs: Long = GESTURE_TIMEOUT_MS,
        register: ((Boolean) -> Unit) -> Unit,
    ): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                register { result ->
                    if (cont.isActive) cont.resume(result)
                }
            }
        } ?: false
    }

    /** Build a JSON Schema object string with the given properties and required keys. */
    fun objectSchema(
        properties: JSONObject,
        required: List<String> = emptyList(),
    ): String {
        val schema = JSONObject()
        schema.put("type", "object")
        schema.put("properties", properties)
        if (required.isNotEmpty()) {
            schema.put("required", JSONArray(required))
        }
        // The model must not invent extra fields it cannot honor.
        schema.put("additionalProperties", false)
        return schema.toString()
    }

    /** A single property descriptor, e.g. prop("string", "the text to type"). */
    fun prop(type: String, description: String): JSONObject {
        return JSONObject().apply {
            put("type", type)
            put("description", description)
        }
    }

    /** An empty object schema for parameterless tools. */
    fun emptySchema(): String = objectSchema(JSONObject())

    /**
     * Read and prune the current UI tree from the running service.
     * Returns an empty list when the service is unavailable.
     */
    fun readPrunedNodes(service: AgentAccessibilityService?): List<UiNode> {
        if (service == null) return emptyList()
        val raw = UiTreeParser.parse(service.getRootNode())
        return UiNodePruner.prune(raw).nodes
    }

    /**
     * Render pruned nodes into a compact, token-bounded text block for the model.
     * Uses Rect field arithmetic only (no Rect helper methods) so the same render
     * path is safe to exercise from JVM unit tests.
     */
    fun renderNodes(nodes: List<UiNode>, maxNodes: Int = 40): String {
        if (nodes.isEmpty()) return "(no readable UI nodes)"
        val sb = StringBuilder()
        sb.append("UI nodes (").append(minOf(nodes.size, maxNodes)).append("):\n")
        for (node in nodes.take(maxNodes)) {
            val type = node.className.substringAfterLast('.')
            val label = buildString {
                if (node.text.isNotBlank()) append('"').append(node.text).append('"')
                if (node.contentDesc.isNotBlank()) {
                    if (isNotEmpty()) append(' ')
                    append("desc=\"").append(node.contentDesc).append('"')
                }
            }.ifBlank { "(no text)" }
            val attrs = buildList {
                if (node.isClickable) add("clickable")
                if (node.isEditable) add("editable")
                if (node.isScrollable) add("scrollable")
            }.joinToString(",")
            sb.append('[').append(node.index).append("] ")
                .append(type).append(' ').append(label)
                .append(" bounds=[")
                .append(node.bounds.left).append(',').append(node.bounds.top).append(',')
                .append(node.bounds.right).append(',').append(node.bounds.bottom).append("] ")
                .append(attrs)
            if (node.packageName.isNotBlank()) {
                sb.append(" pkg=").append(node.packageName)
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}
