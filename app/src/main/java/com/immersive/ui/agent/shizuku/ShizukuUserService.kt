package com.immersive.ui.agent.shizuku

import android.content.Context
import androidx.annotation.Keep
import com.immersive.shizuku.IShizukuUserService

/**
 * Runs inside the Shizuku-spawned privileged process (uid 2000 = ADB shell, or uid 0 =
 * root when Shizuku itself runs as root) and executes shell commands on the agent's
 * behalf — giving the app device-administrator power without the app process holding
 * root.
 *
 * Shizuku must be able to instantiate this class reflectively: keep BOTH the no-arg and
 * the Context constructors (v13 tries the Context one first), and keep them from R8
 * (see proguard-rules.pro). destroy() MUST exit the process (Shizuku does not kill it).
 */
class ShizukuUserService : IShizukuUserService.Stub {

    @Keep
    constructor() : super()

    @Keep
    constructor(context: Context) : super()

    override fun destroy() {
        // Called by Shizuku on unbind (reserved transaction). The privileged process
        // must terminate itself or it leaks.
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    override fun exec(command: String): String {
        return try {
            // redirectErrorStream merges stderr into stdout (avoids the two-pipe
            // deadlock). Read with a hard cap so huge output (e.g. dumpsys) neither
            // blocks forever nor exceeds the ~1 MB binder transaction limit when
            // returned over AIDL.
            val cap = 256 * 1024
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val reader = process.inputStream.bufferedReader()
            val sb = StringBuilder()
            val buf = CharArray(8192)
            var truncated = false
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                val room = cap - sb.length
                if (room <= 0) { truncated = true; break }
                sb.append(buf, 0, minOf(n, room))
                if (sb.length >= cap) { truncated = true; break }
            }
            // Stop a runaway producer so its pipe write side unblocks and it can exit.
            if (truncated) {
                try { process.destroy() } catch (_: Throwable) {}
            }
            val code = try { process.waitFor() } catch (_: Throwable) { -1 }
            buildString {
                append("exit=").append(code)
                val out = sb.toString().trimEnd()
                if (out.isNotEmpty()) append('\n').append(out)
                if (truncated) append("\n…(truncated)")
            }
        } catch (t: Throwable) {
            "exit=-1\n[error] ${t.message}"
        }
    }
}
