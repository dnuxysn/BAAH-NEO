// BAAH-AOS custom popup presentation, AGPL-3.0; no host or settings services imported.
package com.baah.nativehost.legacy
import com.baah.nativehost.NeoText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
@Composable fun QuickOptionsSurface(anchor: Rect?, dismiss: () -> Unit, content: @Composable () -> Unit) {
    if (anchor == null) {
        BaahDialog(onDismissRequest = dismiss, title = { Text("快捷选项") }, text = content,
            confirmButton = { TextButton(dismiss) { Text("关闭") } })
    } else Dialog(onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0.28f) }
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        val reveal by animateFloatAsState(if (visible) 1f else 0f, tween(180), label = "quick-options")
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxSize().clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null, onClick = dismiss)) {
            val width = minOf(330.dp, maxWidth - 34.dp)
            val left = with(density) { anchor.left.toDp() }.coerceIn(17.dp, maxWidth - width - 17.dp)
            val top = with(density) { anchor.bottom.toDp() } + 6.dp
            val height = (maxHeight - top - 40.dp).coerceAtLeast(120.dp)
            Surface(Modifier.offset { with(density) { IntOffset(left.roundToPx(), top.roundToPx()) } }
                .width(width).heightIn(max = height).graphicsLayer {
                    alpha = reveal
                    translationY = with(density) { (-8).dp.toPx() } * (1f - reveal)
                }.clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null, onClick = {}),
                shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
                shadowElevation = 12.dp,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
            }
        }
    }
}
