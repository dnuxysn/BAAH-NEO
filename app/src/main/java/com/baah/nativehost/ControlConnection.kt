package com.baah.nativehost

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import kotlinx.coroutines.flow.MutableStateFlow

class ControlConnection(private val context: Context) {
    val permission = MutableStateFlow<Boolean?>(null)
    @Volatile var remote: IControlService? = null
        private set
    private val owner = Binder()
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ControlService::class.java))
        .processNameSuffix("neo_control").daemon(false).debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    private var pending: CompletableDeferred<IControlService>? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val api = IControlService.Stub.asInterface(service)
            try { api.watch(owner); remote = api; pending?.complete(api) }
            catch (e: Exception) { pending?.completeExceptionally(e) }
        }
        override fun onServiceDisconnected(name: ComponentName) { remote = null }
    }
    init {
        Shizuku.addBinderReceivedListenerSticky { permission.value = granted() }
        Shizuku.addBinderDeadListener { remote = null; permission.value = false }
        Shizuku.addRequestPermissionResultListener { _, result -> permission.value = result == PackageManager.PERMISSION_GRANTED }
        // Binder delivery is asynchronous; no callback arrives when Shizuku is stopped.
        Handler(Looper.getMainLooper()).postDelayed({
            if (permission.value == null) permission.value = granted()
        }, 2_000)
    }
    fun granted() = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    fun requestPermission() {
        check(Shizuku.pingBinder()) { "请先在 Shizuku 中启动服务" }
        if (!granted()) Shizuku.requestPermission(402)
    }
    suspend fun connect(): IControlService {
        remote?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        check(granted()) { "请先授予 BAAH NEO Shizuku 权限" }
        return withContext(Dispatchers.Main) {
            val waiter = pending?.takeIf { it.isActive } ?: CompletableDeferred<IControlService>().also {
                pending = it; Shizuku.bindUserService(args, connection)
            }
            try { withTimeout(20_000) { waiter.await() } }
            finally { if (waiter.isCompleted) pending = null }
        }
    }
    suspend fun release() = withContext(Dispatchers.IO) { remote?.releaseDisplay() }
}
