package app.gaborbiro.permutator.uptime

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.gaborbiro.permutator.PermutatorApp
import app.gaborbiro.permutator.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class UptimeService : Service() {

    companion object {

        fun start(parent: Activity) {
            Intent(parent, UptimeService::class.java).also {
                it.putExtra(EXTRA_COMMAND, COMMAND_START)
                ContextCompat.startForegroundService(parent, it)
            }
        }

        fun stop(parent: Activity) {
            parent.startService(getStopIntent(parent))
        }

        fun getStopIntent(context: Context) = Intent(context, UptimeService::class.java).also {
            it.putExtra(EXTRA_COMMAND, COMMAND_STOP)
        }
    }

    private lateinit var serviceLooper: Looper
    private lateinit var serviceHandler: ServiceHandler
    private var wakeLock: PowerManager.WakeLock? = null

    private inner class ServiceHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            onHandleIntent(msg.obj as Intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        val thread = HandlerThread("UptimeServiceWorkerThread")
        thread.start()

        serviceLooper = thread.looper
        serviceHandler = ServiceHandler(serviceLooper)
    }

    private fun onHandleIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_START -> startConnectivityCheck()
            COMMAND_STOP -> stopConnectivityCheck()
            else -> {
            }
        }
    }

    private fun startConnectivityCheck() {
        setBackgroundNotificationEnabled(true)
        sendStatus(enabled = true)

        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UptimeService::lock").apply {
                acquire()
            }
        }

        val status = (application as PermutatorApp).uptimeServiceRunning
        var isInternetAvailable = false
        GlobalScope.launch(Dispatchers.IO) {
            while (status.value == true && !isInternetAvailable) {
                isInternetAvailable = isInternetAvailable()
                if (isInternetAvailable) {
                    showBackOnlineNotification()
                    stopConnectivityCheck()
                } else {
                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            applicationContext,
                            "Ping (${isInternetAvailable})",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    delay(TimeUnit.SECONDS.toMillis(10))
                }
            }
        }
    }

    private fun stopConnectivityCheck() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val msg = serviceHandler.obtainMessage()
        msg.arg1 = startId
        msg.obj = intent
        serviceHandler.sendMessage(msg)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        serviceLooper.quit()
        setBackgroundNotificationEnabled(false)
        sendStatus(false)
        super.onDestroy()
    }

    private fun sendStatus(enabled: Boolean) {
        (application as PermutatorApp).uptimeServiceRunning.apply {
            if (value != enabled) {
                (application as PermutatorApp).uptimeServiceRunning.postValue(enabled)
            }
        }
    }

    private fun createNotificationChannels() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannels(listOf(foregroundChannel, onlineChannel))
        }
    }

    private fun setBackgroundNotificationEnabled(enabled: Boolean) {
        if (enabled) {
            val stopIntent = PendingIntent.getService(
                applicationContext,
                1,
                getStopIntent(applicationContext),
                0
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

    private fun showBackOnlineNotification() {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID_UPDATE)
            .setSmallIcon(R.drawable.ic_cloud_filled)
            .setContentTitle("Internet is back")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false)
            .setAutoCancel(true)
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_BACK_ONLINE, builder.build())
    }

    private fun isInternetAvailable(): Boolean {
        val command = "ping -c 1 google.com"
        return Runtime.getRuntime().exec(command).waitFor() == 0
    }
}

private const val CHANNEL_ID_FOREGROUND = "UptimeServiceForeground"
private const val CHANNEL_ID_UPDATE = "UptimeServiceUpdate"
private const val NOTIFICATION_ID_FOREGROUND = 1001
private const val NOTIFICATION_ID_BACK_ONLINE = 1002
private const val EXTRA_COMMAND = "EXTRA_COMMAND"
private const val COMMAND_START = "start"
private const val COMMAND_STOP = "stop"