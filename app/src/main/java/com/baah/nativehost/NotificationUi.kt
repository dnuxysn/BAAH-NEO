// BAAH-AOS notification presentation only, AGPL-3.0. NEO owns the foreground service.
package com.baah.nativehost

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews

enum class BaahPhase { Idle, Preparing, Starting, Running, Stopped, Completed, Failed }
data class BaahProgress(val phase: BaahPhase, val title: String, val detail: String, val percent: Int?)
enum class EventNotificationLevel { OFF, DEFAULT, HIGH }

internal fun buildProgressNotification(
    context: Context,
    state: BaahProgress,
    gameAlive: Boolean,
    eventLevel: EventNotificationLevel = EventNotificationLevel.DEFAULT,
    alert: Boolean = false,
): Notification {
    val active = state.phase in listOf(BaahPhase.Preparing, BaahPhase.Starting, BaahPhase.Running)
    val launch = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val channel = if (state.phase in listOf(BaahPhase.Completed, BaahPhase.Failed) &&
        eventLevel == EventNotificationLevel.HIGH) RunService.RESULT_CHANNEL else RunService.CHANNEL
    val builder = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(state.title)
        .setContentText(state.detail)
        .setContentIntent(launch)
        .setOngoing(active || gameAlive)
        .setOnlyAlertOnce(!alert)
    val percent = state.percent?.coerceIn(0, 100)
    if (percent != null) {
        val view = RemoteViews(context.packageName, R.layout.baah_notification_progress).apply {
            setTextViewText(R.id.baah_notification_detail, "$percent% · ${state.detail}")
            setProgressBar(R.id.baah_notification_track, 100, percent, false)
        }
        builder.setStyle(Notification.DecoratedCustomViewStyle()).setCustomContentView(view).setCustomBigContentView(view)
    } else {
        builder.setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.neo_icon))
    }
    return builder.build()
}
