package com.baah.nativehost

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import org.json.*
import java.util.UUID

class MainActivity : ComponentActivity() {
    fun applySystemBars(dark: Boolean) {
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark })
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }
    private val app get() = application as NeoApp
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        app.mainActivityOnTop.value = isTopResumedActivity
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT < 29) app.mainActivityOnTop.value = true
    }

    override fun onPause() {
        if (android.os.Build.VERSION.SDK_INT < 29) app.mainActivityOnTop.value = false
        super.onPause()
    }

    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { app.action { check(!app.running.value); app.data.export(it); app.message.value = "完整备份已导出，请妥善保管" } }
    }
    private var pendingBackup by mutableStateOf<BackupPreview?>(null)
    private val restore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { app.action { check(!app.running.value); pendingBackup = BackupPreview.inspect(this, it) } }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 31 && savedInstanceState == null) {
            splashScreen.setOnExitAnimationListener { splash ->
                splash.animate().alpha(0f).setDuration(220L).withEndAction { splash.remove() }.start()
            }
        }
        lifecycleScope.launch {
            app.keepScheduleScreenOn.collect { keepOn ->
                val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                if (keepOn) window.addFlags(flag) else window.clearFlags(flag)
            }
        }
        applySystemBars(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        if (android.os.Build.VERSION.SDK_INT >= 33) notifications.launch("android.permission.POST_NOTIFICATIONS")
        setContent { MaterialTheme { NeoRoot(app, { export.launch("BAAH-NEO-backup.zip") }, { restore.launch(arrayOf("application/zip", "application/octet-stream")) }, pendingBackup,
            { pendingBackup?.file?.delete(); pendingBackup = null },
            { pendingBackup?.let { backup -> pendingBackup = null; app.action {
                try { check(!app.running.value); app.closeSession(); app.data.restore(android.net.Uri.fromFile(backup.file)); app.dataRevision.value++; ScheduleReceiver.arm(this); app.message.value = "备份已恢复" }
                finally { backup.file.delete() }
            } } }) } }
    }
}

@Composable fun Preview(app: NeoApp, modifier: Modifier, interactive: Boolean = true) {
    val display by app.display.collectAsState()
    val trace by app.touchTrace.collectAsState()
    val settingsRevision by UserData.settingsRevision.collectAsState()
    val dataRevision by app.dataRevision.collectAsState()
    val showTouches = remember(settingsRevision, dataRevision) { app.data.loadSettings().optBoolean("touchPreview", true) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(trace?.at) { if (trace != null) { visible = true; delay(350); visible = false } }
    val markerAlpha by androidx.compose.animation.core.animateFloatAsState(if (visible) 1f else 0f, androidx.compose.animation.core.tween(350), label = "touch-preview")
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val covered by app.screenCover.visible.collectAsState()
    val blanked by app.screenBlanked.collectAsState()
    LaunchedEffect(display, lifecycleState, covered, blanked) {
        if (!lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) || display < 0 || covered || blanked) return@LaunchedEffect
        while (isActive) {
            withContext(Dispatchers.IO) { runCatching {
                app.controller?.capture()?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.also { bitmap -> app.lastPreviewFrame = bitmap }?.asImageBitmap() }
            }.getOrNull() }?.let { frame = it }
            delay(250)
        }
    }
    var begin = androidx.compose.ui.geometry.Offset.Zero
    var end = androidx.compose.ui.geometry.Offset.Zero
    var start = 0L
    Box(modifier.background(androidx.compose.ui.graphics.Color.Black).pointerInput(interactive) {
        if (!interactive) return@pointerInput
        detectTapGestures { pos -> scope.launch(Dispatchers.IO) { runCatching {
            val controller = app.controller ?: error("控制器未连接")
            controller.click((pos.x / size.width * 1280).toInt().coerceIn(0, 1279), (pos.y / size.height * 720).toInt().coerceIn(0, 719))
        }.onFailure { app.message.value = "失败：${it.message}" } } }
    }.pointerInput(interactive) {
        if (!interactive) return@pointerInput
        detectDragGestures(onDragStart = { begin = it; end = it; start = System.currentTimeMillis() }, onDragEnd = {
            if (!interactive) return@detectDragGestures; val from = begin; val to = end; val ms = (System.currentTimeMillis() - start).toInt().coerceIn(80, 5000)
            scope.launch(Dispatchers.IO) { runCatching { val controller = app.controller ?: error("控制器未连接"); controller.swipe((from.x / size.width * 1280).toInt().coerceIn(0, 1279), (from.y / size.height * 720).toInt().coerceIn(0, 719), (to.x / size.width * 1280).toInt().coerceIn(0, 1279), (to.y / size.height * 720).toInt().coerceIn(0, 719), ms) }.onFailure { app.message.value = "失败：${it.message}" } }
        }) { change, _ -> end = change.position; change.consume() }
    }) {
        frame?.let { Image(it, "游戏预览", Modifier.fillMaxSize()) }
        if (showTouches && markerAlpha > 0f) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            trace?.let { point ->
                val center = androidx.compose.ui.geometry.Offset(point.x / 1280 * size.width, point.y / 720 * size.height)
                drawCircle(androidx.compose.ui.graphics.Color(0xFF64C9F2).copy(alpha = 0.35f * markerAlpha), 13.dp.toPx(), center)
                drawCircle(androidx.compose.ui.graphics.Color(0xFF64C9F2).copy(alpha = markerAlpha), 13.dp.toPx(), center, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        }
    }
}

