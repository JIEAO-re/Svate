package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import com.immersive.ui.agent.shizuku.ShizukuManager
import org.json.JSONObject

/**
 * Privileged "device administrator" tools backed by Shizuku (ADB/root). The `shell`
 * tool is the master capability — it can run anything; the rest are safe-typed
 * convenience wrappers so the model does not have to hand-write shell for common admin
 * actions. ALL are HIGH risk, so the permission gate always prompts the user (with the
 * exact command/args) before they run, in both ASK and AUTO modes.
 */
private const val MAX_SHELL_OUTPUT = 8000

private fun boundOutput(s: String): String =
    if (s.length <= MAX_SHELL_OUTPUT) s else s.take(MAX_SHELL_OUTPUT) + "\n…(truncated)"

/** A simple shell-safe token (package id, permission name): letters, digits, . _ - only. */
internal fun isSafeToken(s: String): Boolean =
    s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

/** Single-quote a value for safe inclusion in a shell command line. */
internal fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * Run an arbitrary shell command at ADB/root privilege via Shizuku. The master
 * tool. Its `command` is passed verbatim to `sh -c` (no token validation — that
 * is the point of a raw shell), so it is declared [RiskClass.HIGH] and the
 * permission gate prompts the user with the exact command before running in
 * SAFE/ASK/AUTO modes. The ONLY mode that runs it without confirmation is
 * EXPERIMENTAL, the user's explicit "no confirmation, ever" opt-in (the toggle
 * shows a one-time warning naming uninstall/delete/system-setting changes). If a
 * non-raw tool ever needs to embed model-controlled text in a command, it must
 * use the typed wrappers below (isSafeToken + shellQuote), never this tool.
 */
class ShellTool : PhoneTool {
    override val name: String = "shell"
    override val description: String =
        "Run a shell command at ADB/root privilege via Shizuku — pm, am, settings, cmd, dumpsys, " +
            "svc, input, wm, etc. Very powerful: it can change or break the device, so use it only " +
            "for tasks that genuinely need privileged control, and prefer the typed tools " +
            "(force_stop_app/uninstall_package/grant_permission/set_setting) when they fit. " +
            "Requires Shizuku to be running and authorized."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.HIGH

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("command", ToolSupport.prop("string", "The full shell command line, e.g. \"pm list packages -3\"."))
        return ToolSupport.objectSchema(props, required = listOf("command"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val cmd = args.optString("command", "").trim()
        if (cmd.isEmpty()) return ToolResult(ok = false, text = "shell requires a command.")
        val r = ShizukuManager.exec(cmd)
        return ToolResult(ok = r.ok, text = boundOutput(r.output))
    }
}

/** Force-stop an app by package (am force-stop). */
class ForceStopAppTool : PhoneTool {
    override val name: String = "force_stop_app"
    override val description: String =
        "Force-stop an app by its package id (kills it, like the system's Force Stop). Requires Shizuku."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.HIGH

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("package", ToolSupport.prop("string", "Package id, e.g. \"com.tencent.mm\"."))
        return ToolSupport.objectSchema(props, required = listOf("package"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val pkg = args.optString("package", "").trim()
        if (!isSafeToken(pkg)) return ToolResult(ok = false, text = "Invalid package id.")
        val r = ShizukuManager.exec("am force-stop $pkg")
        return ToolResult(ok = r.ok, text = boundOutput(r.output).ifBlank { "Force-stopped $pkg." })
    }
}

/** Silently uninstall a package (pm uninstall). */
class UninstallPackageTool : PhoneTool {
    override val name: String = "uninstall_package"
    override val description: String =
        "Uninstall an app by package id silently via Shizuku (no confirmation dialog). Requires Shizuku."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.HIGH

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("package", ToolSupport.prop("string", "Package id to uninstall."))
        return ToolSupport.objectSchema(props, required = listOf("package"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val pkg = args.optString("package", "").trim()
        if (!isSafeToken(pkg)) return ToolResult(ok = false, text = "Invalid package id.")
        val r = ShizukuManager.exec("pm uninstall $pkg")
        return ToolResult(ok = r.ok, text = boundOutput(r.output))
    }
}

/** Grant a runtime permission to an app (pm grant). */
class GrantPermissionTool : PhoneTool {
    override val name: String = "grant_permission"
    override val description: String =
        "Grant a runtime permission to an app via Shizuku (pm grant). Requires Shizuku."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.HIGH

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("package", ToolSupport.prop("string", "Target package id."))
        props.put("permission", ToolSupport.prop("string", "Permission, e.g. \"android.permission.CAMERA\"."))
        return ToolSupport.objectSchema(props, required = listOf("package", "permission"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val pkg = args.optString("package", "").trim()
        val perm = args.optString("permission", "").trim()
        if (!isSafeToken(pkg) || !isSafeToken(perm)) return ToolResult(ok = false, text = "Invalid package or permission.")
        val r = ShizukuManager.exec("pm grant $pkg $perm")
        return ToolResult(ok = r.ok, text = boundOutput(r.output).ifBlank { "Granted $perm to $pkg." })
    }
}

/** Revoke a runtime permission from an app (pm revoke). */
class RevokePermissionTool : PhoneTool {
    override val name: String = "revoke_permission"
    override val description: String =
        "Revoke a runtime permission from an app via Shizuku (pm revoke). Requires Shizuku."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.HIGH

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("package", ToolSupport.prop("string", "Target package id."))
        props.put("permission", ToolSupport.prop("string", "Permission to revoke."))
        return ToolSupport.objectSchema(props, required = listOf("package", "permission"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val pkg = args.optString("package", "").trim()
        val perm = args.optString("permission", "").trim()
        if (!isSafeToken(pkg) || !isSafeToken(perm)) return ToolResult(ok = false, text = "Invalid package or permission.")
        val r = ShizukuManager.exec("pm revoke $pkg $perm")
        return ToolResult(ok = r.ok, text = boundOutput(r.output).ifBlank { "Revoked $perm from $pkg." })
    }
}

/** Write a protected system setting (settings put). */
class SetSettingTool : PhoneTool {
    override val name: String = "set_setting"
    override val description: String =
        "Change a protected Android setting via Shizuku (settings put). namespace is one of " +
            "system / secure / global. Example: namespace=system key=screen_brightness value=128. " +
            "Requires Shizuku."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.HIGH

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("namespace", ToolSupport.prop("string", "system | secure | global"))
        props.put("key", ToolSupport.prop("string", "Setting key, e.g. \"screen_brightness\"."))
        props.put("value", ToolSupport.prop("string", "New value."))
        return ToolSupport.objectSchema(props, required = listOf("namespace", "key", "value"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ns = args.optString("namespace", "").trim()
        val key = args.optString("key", "").trim()
        val value = args.optString("value", "")
        if (ns !in setOf("system", "secure", "global")) return ToolResult(ok = false, text = "namespace must be system, secure, or global.")
        if (!isSafeToken(key)) return ToolResult(ok = false, text = "Invalid setting key.")
        val r = ShizukuManager.exec("settings put $ns $key ${shellQuote(value)}")
        return ToolResult(ok = r.ok, text = boundOutput(r.output).ifBlank { "Set $ns/$key = $value." })
    }
}
