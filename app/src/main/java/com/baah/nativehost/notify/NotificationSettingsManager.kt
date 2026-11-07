package com.baah.nativehost.notify

import android.content.Context
import com.baah.nativehost.UserData
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Properties
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import jakarta.mail.*
import jakarta.mail.internet.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Independent NEO transport. Legacy field names are a backup compatibility contract. */
class NotificationSettingsManager(private val context: Context) {
    private val data = UserData(context)
    fun json(): JSONObject {
        val result = JSONObject(DEFAULTS)
        data.loadSettings().optJSONObject("notifications")?.let { saved ->
            saved.keys().forEach { result.put(it, saved.get(it)) }
        }
        return result
    }
    fun update(key: String, value: String) {
        val settings = data.loadSettings()
        settings.put("notifications", (settings.optJSONObject("notifications") ?: JSONObject()).put(key, value))
        data.saveSettings(settings)
        if (key == "internalEnabled" && !value.toBoolean()) {
            val app = context.applicationContext as com.baah.nativehost.NeoApp
            if (!app.running.value && !app.busy.value && !app.gameAlive.value)
                context.getSystemService(android.app.NotificationManager::class.java).cancel(402)
        }
    }
    suspend fun send(title: String, content: String): List<String> = withContext(Dispatchers.IO) {
        val settings = json()
        settings.optString("enabledProviders").split(',').map(String::trim).filter(String::isNotEmpty).map { id ->
            try { deliver(id, settings, title, content); "$id：已发送" }
            catch (_: Exception) { "$id：发送失败，请检查网络、渠道参数和服务端状态" }
        }
    }
    private fun deliver(id: String, s: JSONObject, title: String, content: String) {
        fun required(key: String) = s.optString(key).also { require(it.isNotBlank()) { "缺少渠道参数" } }
        fun base(key: String) = required(key).trimEnd('/')
        fun obj(vararg entries: Pair<String, Any>) = JSONObject().apply { entries.forEach { put(it.first, it.second) } }
        fun post(url: String, body: JSONObject, headers: Map<String,String> = emptyMap()) = request(url, body.toString(), "application/json", headers)
        val message = "$title\n$content"
        when (id) {
            "Telegram" -> {
                val body = obj("chat_id" to required("telegramChatId"), "text" to message.take(4096))
                s.optString("telegramTopicId").takeIf(String::isNotBlank)?.let { body.put("message_thread_id", it.toLong()) }
                check(JSONObject(post("https://api.telegram.org/bot${required("telegramBotToken")}/sendMessage", body)).getBoolean("ok"))
            }
            "Discord Webhook" -> post(required("discordWebhookUrl"), obj("content" to message.take(2000)))
            "Discord" -> {
                val headers = mapOf("Authorization" to "Bot ${required("discordBotToken")}")
                val channel = JSONObject(post("https://discord.com/api/v10/users/@me/channels", obj("recipient_id" to required("discordUserId")), headers)).getString("id")
                post("https://discord.com/api/v10/channels/$channel/messages", obj("content" to message.take(2000)), headers)
            }
            "Bark" -> {
                val response = JSONObject(post("${base("barkServer")}/push", obj("device_key" to required("barkSendKey"), "title" to title, "body" to content, "group" to "BAAH NEO")))
                check(response.optInt("code") == 200)
            }
            "Gotify" -> post("${base("gotifyServer")}/message", obj("title" to title, "message" to content, "priority" to 5), mapOf("X-Gotify-Key" to required("gotifyToken")))
            "ServerChan" -> {
                val key = required("serverChanSendKey")
                val host = if (key.startsWith("sctp")) {
                    val number = Regex("^sctp(\\d+)t").find(key)?.groupValues?.get(1) ?: error("无效 SendKey")
                    "https://$number.push.ft07.com/send/$key.send"
                } else "https://sctapi.ftqq.com/$key.send"
                val response = request(host, "text=${encode(title.replace("\n", "").take(32))}&desp=${encode(content)}", "application/x-www-form-urlencoded")
                check(JSONObject(response).optInt("code", -1) == 0)
            }
            "DingTalk" -> {
                var url = "https://oapi.dingtalk.com/robot/send?access_token=${encode(required("dingTalkAccessToken"))}"
                s.optString("dingTalkSecret").takeIf(String::isNotBlank)?.let { secret ->
                    val timestamp = System.currentTimeMillis()
                    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
                    val sign = Base64.encodeToString(mac.doFinal("$timestamp\n$secret".toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
                    url += "&timestamp=$timestamp&sign=${encode(sign)}"
                }
                check(JSONObject(post(url, obj("msgtype" to "text", "text" to obj("content" to message)))).optInt("errcode", -1) == 0)
            }
            "KOOK" -> {
                val endpoint = if (s.optString("kookDirectMessage").toBoolean()) "direct-message" else "message"
                check(JSONObject(post("https://www.kookapp.cn/api/v3/$endpoint/create", obj("type" to 1, "target_id" to required("kookTargetId"), "content" to message), mapOf("Authorization" to "Bot ${required("kookBotToken")}"))).optInt("code", -1) == 0)
            }
            "Qmsg" -> check(JSONObject(post("${base("qmsgServer")}/jsend/${required("qmsgKey")}", obj("msg" to message, "qq" to s.optString("qmsgUser"), "bot" to s.optString("qmsgBot")))).optBoolean("success"))
            "CustomWebhook" -> {
                // Placeholders represent JSON string contents, including quotes/backslashes correctly.
                fun escaped(value: String) = JSONObject.quote(value).let { it.substring(1, it.length - 1) }
                val body = required("customWebhookBody").replace("{title}", escaped(title)).replace("{content}", escaped(content))
                    .replace("{time}", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                val rawHeaders = s.optString("customWebhookHeaders").trim()
                val headers = if (rawHeaders.startsWith("{")) {
                    val json = JSONObject(rawHeaders)
                    json.keys().asSequence().associateWith { json.getString(it) }
                } else rawHeaders.lineSequence().filter { ':' in it }.associate { line ->
                    // Older BAAH-AOS backups store one Header: Value pair per line.
                    line.substringBefore(':').trim() to line.substringAfter(':').trim()
                }
                request(required("customWebhookUrl"), body, "application/json", headers)
            }
            "SMTP" -> {
                val ssl = s.optString("smtpUseSsl").toBoolean()
                val auth = s.optString("smtpRequireAuthentication").toBoolean()
                val properties = Properties().apply {
                    setProperty("mail.smtp.host", required("smtpServer"))
                    setProperty("mail.smtp.port", required("smtpPort").toInt().toString())
                    setProperty("mail.smtp.ssl.enable", ssl.toString()); setProperty("mail.smtp.auth", auth.toString())
                    listOf("connectiontimeout", "timeout", "writetimeout").forEach { setProperty("mail.smtp.$it", "20000") }
                }
                val session = Session.getInstance(properties, if (!auth) null else object: Authenticator() {
                    override fun getPasswordAuthentication() = PasswordAuthentication(required("smtpUser"), required("smtpPassword"))
                })
                Transport.send(MimeMessage(session).apply {
                    setFrom(InternetAddress(required("smtpFrom")))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(required("smtpTo")))
                    setSubject(title.replace("\r", "").replace("\n", ""), "UTF-8"); setText(content, "UTF-8")
                })
            }
            else -> error("未知渠道")
        }
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun request(url: String, body: String, type: String, headers: Map<String,String> = emptyMap()): String {
        val uri = URI(url)
        require(uri.scheme in setOf("https", "http"))
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 20000; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "$type; charset=utf-8")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode in 200..299) { "推送服务器拒绝请求" }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { connection.disconnect() }
    }
    companion object { private const val DEFAULTS = """{"sendOnComplete":"false","sendOnError":"true","sendOnServiceDied":"false","includeLogDetails":"false","enabledProviders":"","serverChanSendKey":"","discordBotToken":"","discordUserId":"","discordWebhookUrl":"","smtpServer":"","smtpPort":"","smtpUser":"","smtpPassword":"","smtpUseSsl":"false","smtpRequireAuthentication":"false","smtpFrom":"","smtpTo":"","barkServer":"https://api.day.app","barkSendKey":"","telegramBotToken":"","telegramChatId":"","telegramTopicId":"","dingTalkAccessToken":"","dingTalkSecret":"","kookBotToken":"","kookTargetId":"","kookDirectMessage":"false","qmsgServer":"","qmsgKey":"","qmsgUser":"","qmsgBot":"","gotifyServer":"","gotifyToken":"","customWebhookUrl":"","customWebhookHeaders":"","customWebhookBody":""}""" }
}
