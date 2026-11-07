// BAAH-AOS notification presentation only, AGPL-3.0. NEO owns the foreground service.
package com.baah.nativehost
import android.app.*
import android.content.*
import android.graphics.*
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat

enum class BaahPhase { Idle, Preparing, Starting, Running, Stopped, Completed, Failed }
data class BaahProgress(val phase: BaahPhase, val title: String, val detail: String, val done: Int, val tasks: List<String>)
enum class EventNotificationLevel { OFF, DEFAULT, HIGH }
internal fun loadBaahProgressTracker(context: Context): Bitmap =
    context.assets.open("baah-aos/notification_tracker.png").use { stream ->
            val source = BitmapFactory.decodeStream(stream)
            var left = source.width
            var right = -1
            var top = source.height
            var bottom = -1
            for (y in 0 until source.height) for (x in 0 until source.width) {
                if ((source.getPixel(x, y) ushr 24) > 8) {
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            if (right < left) source else Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1)
                .also { if (it !== source) source.recycle() }
    }

internal fun buildProgressNotification(context: Context, state: BaahProgress, tracker: Bitmap?, gameAlive: Boolean,
    eventLevel: EventNotificationLevel = EventNotificationLevel.DEFAULT,
    alert: Boolean = false, preferNativeProgress: Boolean = true): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        val total = state.tasks.size.coerceAtLeast(1)
        val active = state.phase in listOf(BaahPhase.Preparing, BaahPhase.Starting, BaahPhase.Running)
        val style = NotificationCompat.ProgressStyle()
            .setProgress(state.done.coerceIn(0, total))
            .setStyledByProgress(true)
            .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(total).setColor(0xFF64C9F2.toInt())))
        tracker?.let { style.setProgressTrackerIcon(IconCompat.createWithBitmap(it)) }
        val launch = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val channel = if (state.phase in listOf(BaahPhase.Completed, BaahPhase.Failed) &&
            eventLevel == EventNotificationLevel.HIGH) RunService.RESULT_CHANNEL else RunService.CHANNEL
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.title)
            .setContentText("${state.done}/${state.tasks.size} · ${state.detail}")
            .setContentIntent(launch)
            .setOngoing(active || gameAlive)
            .setOnlyAlertOnce(!alert)
            .setRequestPromotedOngoing(false)
        if (Build.VERSION.SDK_INT >= 36 && preferNativeProgress) {
            builder.setProgress(total, state.done, active && state.tasks.isEmpty()).setStyle(style)
        } else {
            val expanded = RemoteViews(context.packageName, R.layout.baah_notification_progress).apply {
                setTextViewText(R.id.baah_notification_title, state.title)
                setTextViewText(R.id.baah_notification_detail, "${state.done}/${state.tasks.size} · ${state.detail}")
                setImageViewBitmap(R.id.baah_notification_track, renderBaahProgressTrack(state.done, total, tracker))
            }
            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle()).setCustomBigContentView(expanded)
        }
        return builder.build()
}

/** The same crystal boundary and cyan completed segment on Android versions without ProgressStyle. */
internal fun renderBaahProgressTrack(done: Int, total: Int, tracker: Bitmap?): Bitmap {
    val bitmap = Bitmap.createBitmap(960, 132, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64C9F2.toInt()
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val left = 54f
    val right = 906f
    val boundary = left + (right - left) * (done.coerceIn(0, total.coerceAtLeast(1)).toFloat() / total.coerceAtLeast(1))
    paint.strokeWidth = 6f
    canvas.drawLine(left, 66f, right, 66f, paint)
    paint.strokeWidth = 21f
    if (boundary > left) canvas.drawLine(left, 66f, boundary, 66f, paint)
    tracker?.let {
        val scale = minOf(102f / it.width, 114f / it.height)
        val width = it.width * scale
        val height = it.height * scale
        canvas.drawBitmap(it, null, android.graphics.RectF(boundary - width / 2, 66f - height / 2,
            boundary + width / 2, 66f + height / 2), paint)
    }
    return bitmap
}
