// Adapted from BAAH-AOS (AGPL-3.0); display and process backend belongs to NEO.
package com.baah.nativehost.legacy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.Surface
import android.graphics.Bitmap
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Animate the inline preview before handing it to the separately oriented fullscreen window. */
@Composable
internal fun BaahPreviewHost(
    resolution: DisplayResolution,
    onFullscreenChanged: (Boolean) -> Unit,
    captureFrame: () -> Bitmap?,
    preview: @Composable (Boolean) -> Unit,
    page: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val activity = context.previewActivity()
    val scope = rememberCoroutineScope()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val coordinates = remember { PreviewCoordinates() }
    var inline by remember { mutableStateOf<Rect?>(null) }
    var active by remember { mutableStateOf(false) }
    var fullscreenWindow by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }
    var landscapeDirection by remember { mutableIntStateOf(if (activity?.windowManager?.defaultDisplay?.rotation == Surface.ROTATION_270) 270 else 90) }
    var originalRotation by remember { mutableIntStateOf(0) }
    var transitionFrame by remember { mutableStateOf<Bitmap?>(null) }
    var transition by remember { mutableStateOf<Job?>(null) }
    var originalOrientation by remember { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }
    var originalLandscape by remember { mutableStateOf(false) }
    val move = remember { Animatable(0f) }
    val turn = remember { Animatable(0f) }
    val dim = remember { Animatable(0f) }
    val heading = remember { Animatable(90f) }
    fun updateInlineBounds() {
        val root = coordinates.host
        val card = coordinates.card
        if (!active && root?.isAttached == true && card?.isAttached == true) {
            val bounds = root.localBoundingBoxOf(card, clipBounds = false)
            if (bounds.width > 0f && bounds.height > 0f) inline = bounds
        }
    }
    val changed by rememberUpdatedState(onFullscreenChanged)
    LaunchedEffect(active) {
        changed(active)
        if (!active) activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(Unit) { onDispose { changed(false) } }
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                when (orientation) {
                    // Sensor angles and Display rotation have opposite signs.
                    in 60..120 -> landscapeDirection = 270
                    in 240..300 -> landscapeDirection = 90
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    DisposableEffect(active, activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val visible = window?.decorView?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it) }
            ?.isVisible(WindowInsetsCompat.Type.systemBars()) != false
        val oldRotationAnimation = window?.attributes?.rotationAnimation
        if (active) {
            window?.attributes = window?.attributes?.apply {
                rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
            }
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            if (active && oldRotationAnimation != null) window?.attributes = window?.attributes?.apply {
                rotationAnimation = oldRotationAnimation
            }
            if (active && visible) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    fun exit() {
        if (!active || exiting) return
        transition?.cancel()
        exiting = true
        transition = scope.launch {
            coroutineScope {
                launch { turn.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) }
                launch { move.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) }
                dim.animateTo(0f, tween(280))
            }
            active = false
            val autoRotate = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0
            if (autoRotate || originalOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                activity?.requestedOrientation = originalOrientation
            }
            exiting = false
            fullscreenWindow = false
            BaahFullscreenFrame.returnRotation = null
            transitionFrame = null
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        transitionFrame = BaahFullscreenFrame.returnImage ?: transitionFrame
        BaahFullscreenFrame.returnImage = null
        scope.launch {
            val rotation = result.data?.getIntExtra("rotation", Surface.ROTATION_90) ?: Surface.ROTATION_90
            // The retained viewport can already have its old size while the display is still
            // landscape. Wait for the actual display and two layout frames before unwinding.
            var settledFrames = 0
            while (settledFrames < 2) {
                withFrameNanos { }
                val settled = activity?.windowManager?.defaultDisplay?.rotation == originalRotation &&
                    viewport.width > 0 && (viewport.width > viewport.height) == originalLandscape
                settledFrames = if (settled) settledFrames + 1 else 0
            }
            heading.snapTo(((rotation * 90 - originalRotation * 90 + 540) % 360 - 180).toFloat())
            exit()
        }
    }
    fun enter() {
        if (active || inline == null) return
        originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        originalLandscape = viewport.width > viewport.height
        originalRotation = activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val entryDirection = landscapeDirection
        BaahFullscreenFrame.returnRotation = null
        // An unspecified Activity inherits the last display direction when system rotation is locked.
        // Keep this underlying window in its existing direction while the fullscreen window rotates.
        activity?.requestedOrientation = when (activity?.windowManager?.defaultDisplay?.rotation) {
            Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        active = true
        exiting = false
        transitionFrame = captureFrame()
        transition = scope.launch {
            heading.snapTo(((entryDirection - originalRotation * 90 + 540) % 360 - 180).toFloat())
            coroutineScope {
                launch { dim.animateTo(1f, tween(200)) }
                move.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
            }
            turn.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
            BaahFullscreenFrame.image = transitionFrame
            fullscreenWindow = true
            launcher.launch(Intent(context, BaahFullscreenActivity::class.java).putExtra("entryRotation", entryDirection / 90))
            @Suppress("DEPRECATION")
            activity?.overridePendingTransition(0, 0)
        }
    }
    BackHandler(active && !fullscreenWindow, ::exit)
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }
        .onGloballyPositioned { coordinates.host = it; updateInlineBounds() }) {
        page(Modifier.onGloballyPositioned { coordinates.card = it; updateInlineBounds() }.clickable(onClick = ::enter))
        if (active) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim.value)).clickable(onClick = {}))
        inline?.let { source ->
            val center = source.center + (Offset(viewport.width / 2f, viewport.height / 2f) - source.center) * move.value
            val targetWidth = if (viewport.height > viewport.width) minOf(viewport.height.toFloat(), viewport.width * resolution.aspectRatio)
                else minOf(viewport.width.toFloat(), viewport.height * resolution.aspectRatio)
            val scale = 1f + (targetWidth / source.width - 1f) * turn.value
            val size = with(density) { source.width.toDp() to source.height.toDp() }
            Box(Modifier.offset { IntOffset((center.x - source.width / 2).roundToInt(), (center.y - source.height / 2).roundToInt()) }
                .size(size.first, size.second).graphicsLayer {
                    val returned = BaahFullscreenFrame.returnRotation
                    val angle = if (fullscreenWindow && returned != null && !exiting)
                        ((returned * 90 - originalRotation * 90 + 540) % 360 - 180).toFloat() else heading.value
                    scaleX = scale; scaleY = scale; rotationZ = angle * turn.value
                    shape = RoundedCornerShape(12.dp * (1f - turn.value)); clip = true
                }.then(if (!active) Modifier.clickable(onClick = ::enter) else Modifier).background(Color.Black)) {
                preview(active)
                if (active) (BaahFullscreenFrame.returnImage ?: transitionFrame)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
        }
    }
}

private class PreviewCoordinates(var host: LayoutCoordinates? = null, var card: LayoutCoordinates? = null)
private tailrec fun Context.previewActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.previewActivity()
    else -> null
}
