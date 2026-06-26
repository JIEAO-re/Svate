package com.immersive.ui.agent.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.immersive.shizuku.IShizukuUserService
import com.immersive.ui.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** Outcome of a privileged shell execution. */
data class ShellResult(val ok: Boolean, val output: String)

/**
 * Bridges the app to Shizuku's privileged (ADB/root) process. Lazily binds the
 * [ShizukuUserService] and runs shell commands through it.
 *
 * Every public call is safe to make even when Shizuku is absent or unauthorized — it
 * returns a clear, actionable [ShellResult] rather than throwing — so the agent's tools
 * degrade gracefully and tell the user how to enable it.
 */
object ShizukuManager {
    private const val PERMISSION_CODE = 4471

    @Volatile private var userService: IShizukuUserService? = null
    @Volatile private var bindDeferred: CompletableDeferred<IShizukuUserService?>? = null
    @Volatile private var permissionListenerRegistered = false

    /** Serializes bind attempts so concurrent exec() calls can't race on bindDeferred. */
    private val bindMutex = Mutex()

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShizukuUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            // Stable identity so R8 renaming the class cannot spawn a duplicate service.
            .tag("svate-shizuku-shell")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = if (binder != null && binder.pingBinder()) {
                IShizukuUserService.Stub.asInterface(binder)
            } else {
                null
            }
            userService = svc
            bindDeferred?.complete(svc)
            bindDeferred = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
        }
    }

    /** True when Shizuku is installed and its server is reachable. */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** True when the user has granted this app Shizuku access. */
    fun hasPermission(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Show the Shizuku permission dialog (async; the user grants, then retries the action). */
    fun requestPermission() {
        try {
            if (!permissionListenerRegistered) {
                // The result is observed by re-checking hasPermission() on the next call;
                // a no-op listener is enough to satisfy the API and avoid leaks.
                Shizuku.addRequestPermissionResultListener { _, _ -> }
                permissionListenerRegistered = true
            }
            if (!Shizuku.isPreV11() && !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(PERMISSION_CODE)
            }
        } catch (_: Throwable) {
        }
    }

    /** One-line human status for the settings UI / diagnostics. */
    fun status(): String = when {
        !isAvailable() -> "未连接（请安装并激活 Shizuku）"
        !hasPermission() -> "已连接，待授权"
        else -> "已就绪（uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)}）"
    }

    /** Run a shell command at Shizuku privilege. Never throws. */
    suspend fun exec(command: String): ShellResult {
        if (!isAvailable()) {
            return ShellResult(false, "Shizuku 未运行：请先安装 Shizuku 并用无线调试激活，然后重试。")
        }
        if (!hasPermission()) {
            requestPermission()
            return ShellResult(false, "需要 Shizuku 授权：请在弹出的 Shizuku 对话框点「允许」后重试。")
        }
        val svc = bindAndGet()
            ?: return ShellResult(false, "无法绑定 Shizuku 特权服务，请确认 Shizuku 正在运行后重试。")
        return try {
            // The binder call blocks; bound it so a hung privileged process cannot
            // pin a thread / freeze the loop indefinitely.
            val out = withContext(Dispatchers.IO) {
                withTimeoutOrNull(60_000L) { svc.exec(command) }
            }
            if (out == null) {
                ShellResult(false, "命令执行超时（>60s）。")
            } else {
                // exec() returns "exit=<code>\n..."; exit=0 is success.
                val ok = out.lineSequence().firstOrNull()?.trim() == "exit=0"
                ShellResult(ok, out)
            }
        } catch (t: Throwable) {
            // A dead binder: drop the cached service so the next call rebinds cleanly.
            userService = null
            ShellResult(false, "特权命令执行失败：${t.message}")
        }
    }

    /** Bind the privileged UserService (serialized; cached once bound). */
    private suspend fun bindAndGet(): IShizukuUserService? = bindMutex.withLock {
        userService?.let { return@withLock it }
        val deferred = CompletableDeferred<IShizukuUserService?>()
        bindDeferred = deferred
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (t: Throwable) {
            bindDeferred = null
            return@withLock null
        }
        val svc = withTimeoutOrNull(12_000L) { deferred.await() }
        bindDeferred = null
        svc
    }
}
