// Adapted from BAAH-AOS (AGPL-3.0); display and process backend belongs to NEO.
package com.baah.nativehost.legacy
import com.baah.nativehost.NeoText as Text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.baah.nativehost.*



/** BAAH hangar layout bound to NEO's independent MaaFramework preview. */
@Composable
fun BaahHangarScreen(
    session: NeoApp,
    selectedConfig: String,
    onConfigSelected: (String) -> Unit,
    onQuickOptions: (Rect) -> Unit,
    onTools: () -> Unit,
    onExplore: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit = {},
    nextSchedule: NextSchedule? = null,
    showBusy: Boolean,
) {
    val running by session.running.collectAsState()
    val busy by session.busy.collectAsState()
    val logText by session.logs.collectAsState()
    val message by session.message.collectAsState()
    val fps by session.fps.collectAsState()
    val alive by session.gameAlive.collectAsState()
    val display by session.display.collectAsState()
    val game = GameUi(if (alive) GameState.Running else if (running && display > 0) GameState.Starting else GameState.Stopped, fps)
    val run = RunUi(logText.lines(), message.takeIf { it.startsWith("失败") })
    val provision = ProvisionUi(showBusy, message)
    val configNames = remember(busy, selectedConfig) { session.data.names() }
    val resolution = remember { DisplayResolution(1280, 720) }
    var quickBounds by remember { mutableStateOf(Rect.Zero) }
    val active = showBusy || running
    BaahPreviewHost(resolution, onFullscreenChanged, captureFrame = { session.lastPreviewFrame }, preview = { expanded ->
        Preview(session, Modifier.fillMaxSize(), interactive = false)
        if (game.game != GameState.Running) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        if (!expanded) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (game.game == GameState.Running) Text("%.0f FPS".format(game.fps ?: 0f), color = Color(0xFFEDEDED), fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp))
            else Spacer(Modifier.weight(1f))
            Text(when (game.game) {
                GameState.Stopped -> "未运行"
                GameState.Starting -> "启动中"
                GameState.Running -> "运行中"
            }, color = when (game.game) {
                GameState.Running -> Color(0xFF7CF08A)
                GameState.Starting -> Color(0xFF64C9F2)
                GameState.Stopped -> Color(0xFFB0B0B0)
            }, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }) { previewModifier ->
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("BAAH NEO", fontSize = 24.sp, modifier = Modifier.padding(top = 12.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
            .background(Color.Black).then(previewModifier))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BaahCard(Modifier.weight(1f).fillMaxHeight()) {
                Text("运行配置", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                BaahDropdown(selectedConfig, configNames.ifEmpty { listOf(selectedConfig) }.map { it to it }, translateLabels = false, onSelect = onConfigSelected)
                Button({
                    if (active) session.requestStop()
                    else if (!session.busy.value) session.action { session.start(selectedConfig) }
                },
                    Modifier.fillMaxWidth().height(36.dp), shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(0.dp)) { Text(if (active) "停止挂机" else "开始挂机", fontSize = 13.sp) }
                Spacer(Modifier.height(2.dp))
                OutlinedButton({ onQuickOptions(quickBounds) }, Modifier.fillMaxWidth().height(36.dp)
                    .onGloballyPositioned { quickBounds = it.boundsInWindow() },
                    shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(0.dp)) {
                    Text("⋮  快捷选项", fontSize = 13.sp)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BaahCard(Modifier.weight(1f).fillMaxWidth().clickable(onClick = onTools)) {
                    Text("小工具", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("快速调用任务", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BaahCard(Modifier.weight(1f).fillMaxWidth().clickable(onClick = onExplore)) {
                    Text("推图设置", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("扫荡关卡与队伍强度", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val next = nextSchedule?.nextTriggerAt?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        }
        Text("挂机：${if (active) "运行中" else "未运行"} · 下次: ${next?.let { "$it（${nextSchedule.strategy.name}）" } ?: "未设置"}",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (provision.busy || run.error != null) {
            Text(if (provision.busy) provision.message else run.error.orEmpty(),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BaahCard(Modifier.fillMaxWidth().height(200.dp)) {
            val scroll = rememberScrollState()
            val visibleLogs = run.logs.filterNot { "[BAAH-AOS-PROGRESS]" in it }
            LaunchedEffect(run.logs.lastOrNull()) { scroll.animateScrollTo(scroll.maxValue) }
            androidx.compose.material3.Text(if (visibleLogs.isEmpty()) (if (projectLanguage() == "en") "No logs yet.\n\nStart a task to see its BAAH log here." else "还没有本轮日志\n\n点「开始挂机」后，这里实时显示这一轮的 BAAH 任务日志。") else visibleLogs.joinToString("\n"), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll))
        }
    }
    }
}

@Composable
internal fun BaahCard(modifier: Modifier = Modifier, verticalPadding: androidx.compose.ui.unit.Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = verticalPadding), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}
