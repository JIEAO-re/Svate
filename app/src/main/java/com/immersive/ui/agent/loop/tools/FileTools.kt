package com.immersive.ui.agent.loop.tools

import android.os.Build
import android.os.Environment
import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Direct file-system tools for the agent: list / read / write / move / delete files in
 * the device's shared storage.
 *
 * These are real filesystem operations — they do NOT need accessibility or screen
 * recording — but they require the "All files access" (MANAGE_EXTERNAL_STORAGE)
 * permission, which the user grants once in system settings. Without it, the tools
 * return a clear, actionable error instead of failing opaquely.
 *
 * Risk: reads are SAFE; write/move are NORMAL; delete is HIGH (always prompts, even in
 * AUTO mode) because it is destructive.
 */
internal object FileToolSupport {

    /** Largest text payload read_file will return inline, to bound the model's context. */
    const val MAX_READ_BYTES = 128 * 1024

    /** Cap directory listings so a huge folder cannot flood the turn. */
    const val MAX_LIST_ENTRIES = 300

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /** Shared-storage root, e.g. /storage/emulated/0. */
    fun storageRoot(): File = Environment.getExternalStorageDirectory()

    /** Whether the app holds "All files access" so the file tools can operate. */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Standard "permission missing" result with actionable guidance for the model. */
    fun noAccessResult(): ToolResult = ToolResult(
        ok = false,
        text = "No file access yet: the \"All files access\" permission is not granted. Ask the " +
            "user to open Svate's 设置 → 授予文件访问权限 (or system Settings → Apps → Svate → " +
            "All files access) and enable it, then retry.",
    )

    /**
     * Resolve a model-supplied path to a File. Absolute paths are used as-is; relative
     * paths (and "~", "/") resolve under the shared-storage root, which is the friendly
     * default the model should think in (Download, DCIM, Pictures, Documents, ...).
     */
    fun resolve(path: String): File {
        val p = path.trim()
        return when {
            p.isEmpty() || p == "~" || p == "/" -> storageRoot()
            p.startsWith("~/") -> File(storageRoot(), p.removePrefix("~/"))
            p.startsWith("/") -> File(p)
            else -> File(storageRoot(), p)
        }
    }

    /**
     * True if [f] resolves to the shared-storage root, comparing CANONICAL paths so
     * aliases/symlinks (e.g. /sdcard → /storage/emulated/0) cannot slip past the
     * delete-root guard. Falls back to absolutePath if canonicalization fails.
     */
    fun isStorageRoot(f: File): Boolean {
        val target = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        val root = runCatching { storageRoot().canonicalPath }.getOrDefault(storageRoot().absolutePath)
        return target == root
    }

    /** A short, model-friendly path: relative to storage root when it lives under it. */
    fun displayPath(f: File): String {
        val root = storageRoot().absolutePath
        val abs = f.absolutePath
        return if (abs == root) "/" else if (abs.startsWith("$root/")) abs.removePrefix("$root/") else abs
    }

    /** One listing line: "name/  (dir)" or "name  (1.2 KB, 2026-06-13 23:31)". */
    fun listingLine(f: File): String {
        return if (f.isDirectory) {
            "${f.name}/  (dir)"
        } else {
            "${f.name}  (${humanSize(f.length())}, ${timeFmt.format(Date(f.lastModified()))})"
        }
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    /** Heuristic: treat a byte slice as binary if it has a NUL or many non-text bytes. */
    fun looksBinary(bytes: ByteArray): Boolean {
        var suspicious = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == 0) return true
            // Allow tab/newline/carriage-return; flag other control chars.
            if (v < 0x09 || (v in 0x0E..0x1F)) suspicious++
        }
        return suspicious > bytes.size / 16
    }
}

/** list_files: enumerate a directory (or describe a single file). Read-only. */
class ListFilesTool : PhoneTool {
    override val name: String = "list_files"
    override val description: String =
        "List files and folders at a path in shared storage (e.g. \"Download\", \"DCIM/Camera\", " +
            "or \"/\" for the storage root). Read-only."
    override val isReadOnly: Boolean = true
    override val riskClass: RiskClass = RiskClass.SAFE

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("path", ToolSupport.prop("string", "Folder path relative to shared storage, or \"/\" for the root."))
        return ToolSupport.objectSchema(props)
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        if (!FileToolSupport.hasAllFilesAccess()) return FileToolSupport.noAccessResult()
        val target = FileToolSupport.resolve(args.optString("path", "/"))
        if (!target.exists()) return ToolResult(ok = false, text = "No such path: ${FileToolSupport.displayPath(target)}")
        if (!target.isDirectory) {
            return ToolResult(ok = true, text = "${FileToolSupport.displayPath(target)} — ${FileToolSupport.listingLine(target)}")
        }
        val children = target.listFiles()
            ?: return ToolResult(ok = false, text = "Cannot read ${FileToolSupport.displayPath(target)} (permission denied or not a directory).")
        if (children.isEmpty()) return ToolResult(ok = true, text = "${FileToolSupport.displayPath(target)} is empty.")
        val sorted = children.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        val shown = sorted.take(FileToolSupport.MAX_LIST_ENTRIES)
        val body = shown.joinToString("\n") { FileToolSupport.listingLine(it) }
        val more = if (sorted.size > shown.size) "\n… and ${sorted.size - shown.size} more" else ""
        return ToolResult(ok = true, text = "${FileToolSupport.displayPath(target)} (${children.size} items):\n$body$more")
    }
}

/** read_file: return a text file's content (bounded). Read-only. */
class ReadFileTool : PhoneTool {
    override val name: String = "read_file"
    override val description: String =
        "Read a text file's content from shared storage. Returns up to 128 KB; binary files are " +
            "described, not dumped. Read-only."
    override val isReadOnly: Boolean = true
    override val riskClass: RiskClass = RiskClass.SAFE

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("path", ToolSupport.prop("string", "File path relative to shared storage, or an absolute path."))
        return ToolSupport.objectSchema(props, required = listOf("path"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        if (!FileToolSupport.hasAllFilesAccess()) return FileToolSupport.noAccessResult()
        val path = args.optString("path", "").trim()
        if (path.isEmpty()) return ToolResult(ok = false, text = "read_file requires a path.")
        val target = FileToolSupport.resolve(path)
        if (!target.exists() || !target.isFile) return ToolResult(ok = false, text = "No such file: ${FileToolSupport.displayPath(target)}")
        return try {
            val size = target.length()
            val raw = target.inputStream().use { stream ->
                val buf = ByteArray(FileToolSupport.MAX_READ_BYTES)
                val n = stream.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
            if (FileToolSupport.looksBinary(raw)) {
                ToolResult(ok = true, text = "Binary file ${FileToolSupport.displayPath(target)} (${FileToolSupport.humanSize(size)}); content not shown.")
            } else {
                val text = String(raw, Charsets.UTF_8)
                val truncated = if (size > FileToolSupport.MAX_READ_BYTES) "\n…(truncated; file is ${FileToolSupport.humanSize(size)})" else ""
                ToolResult(ok = true, text = "${FileToolSupport.displayPath(target)} (${FileToolSupport.humanSize(size)}):\n$text$truncated")
            }
        } catch (t: Throwable) {
            ToolResult(ok = false, text = "Failed to read ${FileToolSupport.displayPath(target)}: ${t.message}")
        }
    }
}

/** write_file: create or overwrite/append a text file. */
class WriteFileTool : PhoneTool {
    override val name: String = "write_file"
    override val description: String =
        "Write text to a file in shared storage, creating parent folders as needed. Overwrites by " +
            "default; set append=true to append."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.NORMAL

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("path", ToolSupport.prop("string", "Destination file path relative to shared storage, or absolute."))
        props.put("content", ToolSupport.prop("string", "Text content to write."))
        props.put("append", ToolSupport.prop("boolean", "Append instead of overwrite (default false)."))
        return ToolSupport.objectSchema(props, required = listOf("path", "content"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        if (!FileToolSupport.hasAllFilesAccess()) return FileToolSupport.noAccessResult()
        val path = args.optString("path", "").trim()
        if (path.isEmpty()) return ToolResult(ok = false, text = "write_file requires a path.")
        val content = args.optString("content", "")
        val append = args.optBoolean("append", false)
        val target = FileToolSupport.resolve(path)
        if (target.isDirectory) return ToolResult(ok = false, text = "${FileToolSupport.displayPath(target)} is a directory.")
        return try {
            target.parentFile?.mkdirs()
            val bytes = content.toByteArray(Charsets.UTF_8)
            java.io.FileOutputStream(target, append).use { it.write(bytes) }
            val verb = if (append) "Appended" else "Wrote"
            ToolResult(ok = true, text = "$verb ${FileToolSupport.humanSize(bytes.size.toLong())} to ${FileToolSupport.displayPath(target)}.")
        } catch (t: Throwable) {
            ToolResult(ok = false, text = "Failed to write ${FileToolSupport.displayPath(target)}: ${t.message}")
        }
    }
}

/** move_file: rename or move a file/folder. */
class MoveFileTool : PhoneTool {
    override val name: String = "move_file"
    override val description: String =
        "Move or rename a file or folder within shared storage."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.NORMAL

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("from", ToolSupport.prop("string", "Source path."))
        props.put("to", ToolSupport.prop("string", "Destination path."))
        return ToolSupport.objectSchema(props, required = listOf("from", "to"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        if (!FileToolSupport.hasAllFilesAccess()) return FileToolSupport.noAccessResult()
        val from = FileToolSupport.resolve(args.optString("from", ""))
        val to = FileToolSupport.resolve(args.optString("to", ""))
        if (!from.exists()) return ToolResult(ok = false, text = "No such source: ${FileToolSupport.displayPath(from)}")
        if (to.exists()) return ToolResult(ok = false, text = "Destination already exists: ${FileToolSupport.displayPath(to)}")
        return try {
            to.parentFile?.mkdirs()
            if (from.renameTo(to)) {
                ToolResult(ok = true, text = "Moved ${FileToolSupport.displayPath(from)} → ${FileToolSupport.displayPath(to)}.")
            } else {
                // renameTo fails across mount points; fall back to copy + delete for files.
                if (from.isFile) {
                    from.copyTo(to, overwrite = false)
                    from.delete()
                    ToolResult(ok = true, text = "Moved ${FileToolSupport.displayPath(from)} → ${FileToolSupport.displayPath(to)}.")
                } else {
                    ToolResult(ok = false, text = "Could not move folder ${FileToolSupport.displayPath(from)} (cross-volume move unsupported).")
                }
            }
        } catch (t: Throwable) {
            ToolResult(ok = false, text = "Failed to move: ${t.message}")
        }
    }
}

/** delete_file: delete a file or folder. HIGH risk — always prompts. */
class DeleteFileTool : PhoneTool {
    override val name: String = "delete_file"
    override val description: String =
        "Delete a file or folder from shared storage. For a non-empty folder, pass recursive=true. " +
            "Destructive — always asks the user first."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.HIGH

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("path", ToolSupport.prop("string", "Path to delete."))
        props.put("recursive", ToolSupport.prop("boolean", "Delete a non-empty folder and its contents (default false)."))
        return ToolSupport.objectSchema(props, required = listOf("path"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        if (!FileToolSupport.hasAllFilesAccess()) return FileToolSupport.noAccessResult()
        val target = FileToolSupport.resolve(args.optString("path", ""))
        val recursive = args.optBoolean("recursive", false)
        if (!target.exists()) return ToolResult(ok = false, text = "No such path: ${FileToolSupport.displayPath(target)}")
        // Guard against wiping the entire storage root (canonical compare so /sdcard and
        // other symlinks to the root cannot bypass this).
        if (FileToolSupport.isStorageRoot(target)) {
            return ToolResult(ok = false, text = "Refusing to delete the storage root.")
        }
        return try {
            val ok = if (target.isDirectory) {
                val empty = target.listFiles()?.isEmpty() ?: true
                if (!empty && !recursive) {
                    return ToolResult(ok = false, text = "${FileToolSupport.displayPath(target)} is a non-empty folder; pass recursive=true to delete it and its contents.")
                }
                if (recursive) target.deleteRecursively() else target.delete()
            } else {
                target.delete()
            }
            if (ok) ToolResult(ok = true, text = "Deleted ${FileToolSupport.displayPath(target)}.")
            else ToolResult(ok = false, text = "Failed to delete ${FileToolSupport.displayPath(target)}.")
        } catch (t: Throwable) {
            ToolResult(ok = false, text = "Failed to delete ${FileToolSupport.displayPath(target)}: ${t.message}")
        }
    }
}
