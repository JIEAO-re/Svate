package com.immersive.ui.agent.loop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the v1 tool declarations are complete and JSON-legal. Uses only
 * org.json and pure Kotlin types so it runs on the JVM.
 */
class ToolRegistryTest {

    private val expectedTools = listOf(
        "take_screenshot",
        "read_ui_tree",
        "tap",
        "type_text",
        "swipe",
        "scroll",
        "press_back",
        "press_home",
        "open_app",
        "launch_intent",
        "wait",
        "web_search",
        "list_files",
        "read_file",
        "write_file",
        "move_file",
        "delete_file",
        "shell",
        "force_stop_app",
        "uninstall_package",
        "grant_permission",
        "revoke_permission",
        "set_setting",
        "finish",
        "ask_user",
    )

    @Test
    fun registry_containsAllV1Tools_inOrder() {
        val registry = ToolRegistry.createDefault()
        val names = registry.tools().map { it.name }
        assertEquals(expectedTools, names)
    }

    @Test
    fun declarations_haveNameDescriptionAndSchema() {
        val registry = ToolRegistry.createDefault()
        val declarations = registry.toDeclarations()
        assertEquals(expectedTools.size, declarations.length())

        for (i in 0 until declarations.length()) {
            val decl = declarations.getJSONObject(i)
            val name = decl.getString("name")
            assertTrue("unexpected tool name: $name", name in expectedTools)
            assertTrue("blank description for $name", decl.getString("description").isNotBlank())

            // parameters_json_schema must embed as a real JSON object, not a string.
            val schema = decl.getJSONObject("parameters_json_schema")
            assertEquals("object", schema.getString("type"))
            assertNotNull("missing properties for $name", schema.getJSONObject("properties"))
        }
    }

    @Test
    fun eachSchema_isLegalJson_andReparsable() {
        val registry = ToolRegistry.createDefault()
        for (tool in registry.tools()) {
            // Must parse without throwing; a malformed schema would raise here.
            val schema = JSONObject(tool.parametersJsonSchema())
            assertEquals("object", schema.optString("type"))
            assertTrue(schema.has("properties"))
        }
    }

    @Test
    fun requiredParams_areDeclaredForKeyTools() {
        val registry = ToolRegistry.createDefault()

        val typeSchema = JSONObject(registry.byName("type_text")!!.parametersJsonSchema())
        assertTrue(requiredList(typeSchema).contains("text"))

        val launchSchema = JSONObject(registry.byName("launch_intent")!!.parametersJsonSchema())
        assertTrue(requiredList(launchSchema).contains("action"))

        val finishSchema = JSONObject(registry.byName("finish")!!.parametersJsonSchema())
        val finishRequired = requiredList(finishSchema)
        assertTrue(finishRequired.contains("summary"))
        assertTrue(finishRequired.contains("success"))

        val askSchema = JSONObject(registry.byName("ask_user")!!.parametersJsonSchema())
        assertTrue(requiredList(askSchema).contains("question"))
    }

    /** Read the "required" array of a schema into a Kotlin list of strings. */
    private fun requiredList(schema: JSONObject): List<String> {
        val arr: JSONArray = schema.optJSONArray("required") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    @Test
    fun riskClasses_matchContract() {
        val registry = ToolRegistry.createDefault()
        assertEquals(RiskClass.SAFE, registry.byName("take_screenshot")!!.riskClass)
        assertEquals(RiskClass.SAFE, registry.byName("read_ui_tree")!!.riskClass)
        assertEquals(RiskClass.NORMAL, registry.byName("tap")!!.riskClass)
        assertEquals(RiskClass.NORMAL, registry.byName("type_text")!!.riskClass)
        assertEquals(RiskClass.LOW, registry.byName("swipe")!!.riskClass)
        assertEquals(RiskClass.LOW, registry.byName("scroll")!!.riskClass)
        assertEquals(RiskClass.LOW, registry.byName("press_back")!!.riskClass)
        assertEquals(RiskClass.LOW, registry.byName("press_home")!!.riskClass)
        assertEquals(RiskClass.NORMAL, registry.byName("open_app")!!.riskClass)
        assertEquals(RiskClass.HIGH, registry.byName("launch_intent")!!.riskClass)
        assertEquals(RiskClass.SAFE, registry.byName("wait")!!.riskClass)
        assertEquals(RiskClass.SAFE, registry.byName("finish")!!.riskClass)
        assertEquals(RiskClass.SAFE, registry.byName("ask_user")!!.riskClass)
        assertEquals(RiskClass.SAFE, registry.byName("list_files")!!.riskClass)
        assertEquals(RiskClass.SAFE, registry.byName("read_file")!!.riskClass)
        assertEquals(RiskClass.NORMAL, registry.byName("write_file")!!.riskClass)
        assertEquals(RiskClass.NORMAL, registry.byName("move_file")!!.riskClass)
        assertEquals(RiskClass.HIGH, registry.byName("delete_file")!!.riskClass)
        // Privileged Shizuku tools are all HIGH (always prompt, even in AUTO).
        assertEquals(RiskClass.HIGH, registry.byName("shell")!!.riskClass)
        assertEquals(RiskClass.HIGH, registry.byName("force_stop_app")!!.riskClass)
        assertEquals(RiskClass.HIGH, registry.byName("uninstall_package")!!.riskClass)
        assertEquals(RiskClass.HIGH, registry.byName("grant_permission")!!.riskClass)
        assertEquals(RiskClass.HIGH, registry.byName("revoke_permission")!!.riskClass)
        assertEquals(RiskClass.HIGH, registry.byName("set_setting")!!.riskClass)
    }

    @Test
    fun readOnlyFlags_matchContract() {
        val registry = ToolRegistry.createDefault()
        val readOnly = setOf(
            "take_screenshot", "read_ui_tree", "wait", "web_search", "finish", "ask_user",
            "list_files", "read_file",
        )
        for (tool in registry.tools()) {
            assertEquals(
                "isReadOnly mismatch for ${tool.name}",
                tool.name in readOnly,
                tool.isReadOnly,
            )
        }
    }

    @Test
    fun byName_returnsNullForUnknown() {
        val registry = ToolRegistry.createDefault()
        assertFalse(registry.tools().isEmpty())
        assertEquals(null, registry.byName("does_not_exist"))
    }
}
