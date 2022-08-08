package app.gaborbiro.permutator.uptime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.gaborbiro.permutator.R

const val EXTRA_COMMAND = "EXTRA_COMMAND"
const val EXTRA_CLEAR_BO_NOTIFICATION = "EXTRA_CLEAR_NOTIFICATION"
const val COMMAND_START = "start"
const val COMMAND_STOP = "stop"

private const val CHANNEL_ID_FOREGROUND = "UptimeServiceForeground"
private const val CHANNEL_ID_UPDATE = "UptimeServiceUpdate"
private const val NOTIFICATION_ID_FOREGROUND = 1001
private const val NOTIFICATION_ID_BACK_ONLINE = 1002

fun Context.createNotificationChannels() {
    val importance = NotificationManager.IMPORTANCE_DEFAULT
    val foregroundChannel = NotificationChannel(
        CHANNEL_ID_FOREGROUND,
        "Uptime Service Foreground",
        importance
    ).apply {
        description = "Triggers a notification when internet is back"
    }

    val onlineChannel =
        NotificationChannel(CHANNEL_ID_UPDATE, "Uptime Service Update", importance).apply {
            description = "Triggers a notification when internet is back"
        }

    val notificationManager: NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannels(
        listOf(
            foregroundChannel,
            onlineChannel,
        )
    )
}

fun Service.setBackgroundNotificationEnabled(enabled: Boolean) {
    if (enabled) {
        val stopIntent = PendingIntent.getService(
            applicationContext,
            1,
            getScanStopIntent(clearBackOnlineNotification = false),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle("Checking internet connection...")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(R.drawable.ic_close, "Stop", stopIntent)
        startForeground(NOTIFICATION_ID_FOREGROUND, builder.build())
    } else {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_FOREGROUND)
    }
}

fun Context.showBackOnlineNotification() {
    val stopIntent = PendingIntent.getService(
        applicationContext,
        1,
        getScanStopIntent(clearBackOnlineNotification = true),
        0
    )
    val builder = NotificationCompat.Builder(this, CHANNEL_ID_UPDATE)
        .setSmallIcon(R.drawable.ic_cloud_filled)
        .setContentTitle("Internet is back")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setOngoing(false)
        .setAutoCancel(true)
        .addAction(R.drawable.ic_close, "Stop", stopIntent)
    applicationContext.getSystemService(NotificationManager::class.java)
        .notify(NOTIFICATION_ID_BACK_ONLINE, builder.build())
}

fun Context.clearBackOnlineNotification() {
    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_BACK_ONLINE)
}

fun Context.getScanStopIntent(clearBackOnlineNotification: Boolean) =
    Intent(this, UptimeService::class.java).also {
        it.putExtra(EXTRA_COMMAND, COMMAND_STOP)
        it.putExtra(EXTRA_CLEAR_BO_NOTIFICATION, clearBackOnlineNotification)
    }