package com.baah.nativehost
import com.baah.nativehost.NeoText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baah.nativehost.legacy.*
import com.baah.nativehost.notify.NotificationSettingsManager

@Composable fun NotificationPage(app: NeoApp, back: () -> Unit) {
    val manager = remember { NotificationSettingsManager(app) }
    var revision by remember { mutableIntStateOf(0) }; val settings = remember(revision) { manager.json() }
    var message by remember { mutableStateOf<String?>(null) }
    fun update(key: String, value: String) { manager.update(key, value); revision++ }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row { TextButton(back) { Text("‹") }; Text("通知设置", fontSize = 20.sp) }
        Text("实况通知", fontSize = 12.sp)
        BaahCard(Modifier.fillMaxWidth()) {
            Text("展示方式", fontSize = 16.sp); Text("实时更新", fontSize = 12.sp)
            Button(onClick = { app.action(showBusy = false) { RunService.update(app, "实况通知测试 · 进入游戏 → 执行任务 → 任务收尾", done = 1, total = 3) } }, Modifier.fillMaxWidth()) { Text("发送实况测试") }
        }
        Text("内部通知", fontSize = 12.sp)
        BaahCard(Modifier.fillMaxWidth()) {
            Toggle("通知提醒", settings.optString("internalEnabled", "true").toBoolean()) { update("internalEnabled", it.toString()) }
            Toggle("弹出提示", settings.optString("popup", "false").toBoolean()) { update("popup", it.toString()) }
            Button(onClick = { RunService.update(app, "BAAH NEO 内部通知渠道正常", active = false) }, Modifier.fillMaxWidth()) { Text("发送测试通知") }
        }
        Text("外部通知", fontSize = 12.sp)
        BaahCard(Modifier.fillMaxWidth()) {
            listOf("sendOnComplete" to "任务完成时通知", "sendOnError" to "任务出错时通知", "sendOnServiceDied" to "服务异常终止时通知", "includeLogDetails" to "附带日志详情").forEach { (key, title) ->
                Toggle(title, settings.optString(key).toBoolean()) { update(key, it.toString()) }
            }
        }
        Text("通知渠道", fontSize = 12.sp)
        channelFields.forEach { (id, fields) ->
            val enabled = id in settings.optString("enabledProviders").split(',')
            BaahCard(Modifier.fillMaxWidth()) {
                Toggle(id, enabled) { checked ->
                    val ids = settings.optString("enabledProviders").split(',').filter { it.isNotBlank() }.toMutableSet()
                    if (checked) ids.add(id) else ids.remove(id)
                    update("enabledProviders", ids.joinToString(","))
                }
                if (enabled) fields.forEach { (key, label) ->
                    val value = settings.optString(key)
                    if (key in setOf("smtpUseSsl", "smtpRequireAuthentication", "kookDirectMessage")) Toggle(label, value.toBoolean()) { update(key, it.toString()) }
                    else {
                        var draft by remember(key) { mutableStateOf(value) }
                        OutlinedTextField(draft, { draft = it; update(key, it) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (key.contains("token", true) || key.contains("key", true) || key.contains("password", true) || key.contains("secret", true)) PasswordVisualTransformation() else VisualTransformation.None)
                    }
                }
            }
        }
        Button(onClick = { app.action { message = manager.send("通知测试", "BAAH NEO 外部通知渠道测试").joinToString("\n").ifBlank { "请先启用通知渠道" } } }, Modifier.fillMaxWidth()) { Text("发送外部测试通知") }
    }
    message?.let { BaahDialog(onDismissRequest = { message = null }, text = { Text(it) }, confirmButton = { TextButton({ message = null }) { Text("确定") } }) }
}

private val channelFields = linkedMapOf(
    "ServerChan" to listOf("serverChanSendKey" to "SendKey"),
    "Telegram" to listOf("telegramBotToken" to "Bot Token", "telegramChatId" to "Chat ID", "telegramTopicId" to "Topic ID"),
    "Discord" to listOf("discordBotToken" to "Bot Token", "discordUserId" to "User ID"),
    "DingTalk" to listOf("dingTalkAccessToken" to "Access Token", "dingTalkSecret" to "Secret"),
    "KOOK" to listOf("kookBotToken" to "Bot Token", "kookTargetId" to "Target ID", "kookDirectMessage" to "私信"),
    "Discord Webhook" to listOf("discordWebhookUrl" to "Webhook URL"),
    "SMTP" to listOf("smtpServer" to "服务器", "smtpPort" to "端口", "smtpUser" to "用户名", "smtpPassword" to "密码", "smtpUseSsl" to "SSL", "smtpRequireAuthentication" to "需要认证", "smtpFrom" to "发件人", "smtpTo" to "收件人"),
    "Bark" to listOf("barkServer" to "服务器", "barkSendKey" to "SendKey"),
    "Qmsg" to listOf("qmsgServer" to "服务器", "qmsgKey" to "Key", "qmsgUser" to "用户", "qmsgBot" to "机器人"),
    "Gotify" to listOf("gotifyServer" to "服务器", "gotifyToken" to "Token"),
    "CustomWebhook" to listOf("customWebhookUrl" to "URL", "customWebhookHeaders" to "请求头 JSON", "customWebhookBody" to "请求体模板")
)
