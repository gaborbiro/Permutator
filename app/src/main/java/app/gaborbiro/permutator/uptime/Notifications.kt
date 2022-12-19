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
private const val NOTIFICATION_ID_FOREGROUND_ONLINE = 1001
private const val NOTIFICATION_ID_FOREGROUND_OFFLINE = 1002

fun Context.createNotificationChannels() {
    val importance = NotificationManager.IMPORTANCE_DEFAULT
    val foregroundChannel = NotificationChannel(
        CHANNEL_ID_FOREGROUND,
        "Uptime Service Foreground",
        importance
    ).apply {
        description = "Triggers a notification when internet is back"
    }

    val notificationManager: NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannels(
        listOf(foregroundChannel)
    )
}

fun Service.showBackgroundNotificationOnline() {
    hideBackgroundNotification()
    val stopIntent = PendingIntent.getService(
        applicationContext,
        1,
        getScanStopIntent(clearBackOnlineNotification = false),
        PendingIntent.FLAG_IMMUTABLE
    )
    val builder = NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
        .setSmallIcon(R.drawable.ic_cloud_filled)
        .setContentTitle("Checking internet connection...")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setOngoing(true)
        .addAction(R.drawable.ic_close, "Stop", stopIntent)

    startForeground(NOTIFICATION_ID_FOREGROUND_ONLINE, builder.build())
}

fun Service.hideBackgroundNotification() {
    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_FOREGROUND_ONLINE)
    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_FOREGROUND_OFFLINE)
}

fun Service.showBackgroundNotificationOffline() {
    hideBackgroundNotification()
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

    startForeground(NOTIFICATION_ID_FOREGROUND_OFFLINE, builder.build())
}

fun Context.getScanStopIntent(clearBackOnlineNotification: Boolean) =
    Intent(this, UptimeService::class.java).also {
        it.putExtra(EXTRA_COMMAND, COMMAND_STOP)
        it.putExtra(EXTRA_CLEAR_BO_NOTIFICATION, clearBackOnlineNotification)
    }