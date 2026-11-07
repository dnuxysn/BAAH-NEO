package com.baah.nativehost

import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import kotlinx.coroutines.flow.MutableStateFlow

class ControlConnection(private val context: Context) {
    enum class PermissionState { Checking, Granted, Request, OpenManager, Install }

    private companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        const val DOWNLOAD_URL = "https://github.com/RikkaApps/Shizuku/releases/latest"
        const val REQUEST_CODE = 402
    }

    val permission = MutableStateFlow(PermissionState.Checking)
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
        Shizuku.addBinderReceivedListenerSticky { permission.value = currentPermission() }
        Shizuku.addBinderDeadListener {
            remote = null
            permission.value = currentPermission()
        }
        Shizuku.addRequestPermissionResultListener { requestCode, result ->
            if (requestCode == REQUEST_CODE) {
                permission.value = if (result == PackageManager.PERMISSION_GRANTED) {
                    PermissionState.Granted
                } else {
                    currentPermission()
                }
            }
        }
        // Give the provider time to deliver the binder before showing setup instructions.
        Handler(Looper.getMainLooper()).postDelayed({
            if (permission.value == PermissionState.Checking) permission.value = currentPermission()
        }, 2_000)
    }

    fun granted() = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun refreshPermission() {
        if (permission.value != PermissionState.Checking || Shizuku.pingBinder()) {
            permission.value = currentPermission()
        }
    }

    private fun currentPermission(): PermissionState {
        if (granted()) return PermissionState.Granted
        val canRequest = runCatching {
            Shizuku.pingBinder() && !Shizuku.isPreV11() && !Shizuku.shouldShowRequestPermissionRationale()
        }.getOrDefault(false)
        if (canRequest) return PermissionState.Request
        return try {
            context.packageManager.getApplicationInfo(MANAGER_PACKAGE, 0)
            PermissionState.OpenManager
        } catch (_: PackageManager.NameNotFoundException) {
            PermissionState.Install
        }
    }

    fun requestPermission() {
        val state = currentPermission()
        permission.value = state
        when (state) {
            PermissionState.Request -> Shizuku.requestPermission(REQUEST_CODE)
            PermissionState.OpenManager -> {
                val intent = context.packageManager.getLaunchIntentForPackage(MANAGER_PACKAGE)
                    ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", MANAGER_PACKAGE, null))
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PermissionState.Install -> context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            else -> Unit
        }
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
