package com.baah.nativehost.legacy

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import com.baah.nativehost.NeoApp
import com.baah.nativehost.Preview

/** BAAH-AOS window behavior, with NEO's MaaFramework capture and input backend. */
class BaahFullscreenActivity : ComponentActivity() {
    private val app get() = application as NeoApp
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = if (intent.getIntExtra("entryRotation", 1) == 3) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply { rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            val fps by app.fps.collectAsState(); val game by app.gameAlive.collectAsState()
            BackHandler { closePreview() }
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val previewModifier = if (maxWidth / maxHeight > 16f / 9f) Modifier.fillMaxHeight().aspectRatio(16f / 9f) else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    Preview(app, previewModifier)
                }
                if (game) Text("%.0f FPS".format(fps), fontSize = 12.sp, color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp))
                Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(44.dp).clip(CircleShape).background(Color(0x99000000)).clickable(onClick = ::closePreview), contentAlignment = Alignment.Center) {
                    Text("✕", color = Color.White, fontSize = 20.sp)
                }
            }
        }
        window.decorView.doOnPreDraw { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
    }
    private fun closePreview() {
        window.attributes = window.attributes.apply { rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT }
        BaahFullscreenFrame.returnImage = app.lastPreviewFrame
        BaahFullscreenFrame.returnRotation = windowManager.defaultDisplay.rotation
        setResult(Activity.RESULT_OK, Intent().putExtra("rotation", windowManager.defaultDisplay.rotation))
        finish(); overridePendingTransition(0, 0)
    }
}
internal object BaahFullscreenFrame {
    var image: Bitmap? = null
    var returnImage: Bitmap? by mutableStateOf(null)
    var returnRotation: Int? by mutableStateOf(null)
}
