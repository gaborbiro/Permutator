package app.gaborbiro.permutator.uptime

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.gaborbiro.permutator.PermutatorApp
import app.gaborbiro.permutator.R
import app.gaborbiro.permutator.UptimeState
import kotlinx.coroutines.*
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
            parent.startService(getStopIntent(parent, clearBackOnlineNotification = true))
        }

        fun getStopIntent(context: Context, clearBackOnlineNotification: Boolean) =
            Intent(context, UptimeService::class.java).also {
                it.putExtra(EXTRA_COMMAND, COMMAND_STOP)
                it.putExtra(EXTRA_CLEAR_BO_NOTIFICATION, clearBackOnlineNotification)
            }
    }

    private lateinit var serviceLooper: Looper
    private lateinit var serviceHandler: ServiceHandler
    private lateinit var handler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val connectivityManager: ConnectivityManager by lazy { applicationContext.getSystemService()!! }
    private var pingJob: Job? = null

    private inner class ServiceHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            onHandleIntent(msg.obj as Intent?)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        val thread = HandlerThread("UptimeServiceWorkerThread")
        thread.start()

        serviceLooper = thread.looper
        serviceHandler = ServiceHandler(serviceLooper)
        handler = Handler(serviceLooper)
    }

    private fun onHandleIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_START -> postEvent(UptimeEvent.Start)
            COMMAND_STOP -> postEvent(UptimeEvent.Stop)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun startConnectivityCheck() {
        setBackgroundNotificationEnabled(true)
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

        pingJob?.cancel()
        pingJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                showMessage("ping")
                pingNow()
                delay(TimeUnit.SECONDS.toMillis(5))
            }
        }
    }

    private fun pingNow(): Boolean {
        return canPingGoogle().also { pingSuccess ->
            if (pingSuccess) {
                postEvent(UptimeEvent.PingSuccess)
            } else {
                postEvent(UptimeEvent.PingFailed)
            }
        }
    }

    private fun pingWithBackoff(expectFail: Boolean, delaySecs: Int = 1) {
        CoroutineScope(Dispatchers.IO).launch {
            if (pingNow()) { // ping succeeded
                if (expectFail && delaySecs < 10) {
                    handler.postDelayed({
                        showMessage("ping at $delaySecs secs")
                        pingWithBackoff(expectFail, delaySecs * 2)
                    }, this, delaySecs * 1000L)
                }
            } else { // ping failed
                if (!expectFail && delaySecs < 10) {
                    handler.postDelayed({
                        showMessage("ping at $delaySecs secs")
                        pingWithBackoff(expectFail, delaySecs * 2)
                    }, this, delaySecs * 1000L)
                }
            }
        }
    }

    private fun stopConnectivityCheck() {
        pingJob?.cancel()
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

    private fun startNetworkAvailabilityListener() {
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                postEvent(UptimeEvent.NetworkAvailable)
            }

            override fun onUnavailable() {
                super.onUnavailable()
                postEvent(UptimeEvent.NetworkUnavailable)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                postEvent(UptimeEvent.NetworkUnavailable)
            }
        }.also {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(request, it)
        }
    }

    private fun stopNetworkAvailabilityListener() {
        callback?.let(connectivityManager::unregisterNetworkCallback)
        callback = null
    }

    override fun onDestroy() {
        pingJob?.cancel()
        serviceLooper.quit()
        stopNetworkAvailabilityListener()
        setBackgroundNotificationEnabled(false)
        postEvent(UptimeEvent.Stop)
        super.onDestroy()
    }

    private fun postEvent(uptimeEvent: UptimeEvent) {
        reduceUptimeState(uptimeEvent)?.let {
            CoroutineScope(Dispatchers.Main).launch {
                (application as PermutatorApp).uptimeState.apply {
                    if (value != it) {
                        (application as PermutatorApp).uptimeState.value = it
                    }
                }
            }
        }
    }

    private fun reduceUptimeState(event: UptimeEvent): UptimeState? {
        return when ((application as PermutatorApp).uptimeState.value) {
            UptimeState.Disabled -> {
                when (event) {
                    UptimeEvent.Start -> {
                        startConnectivityCheck()
                        startNetworkAvailabilityListener()
                        UptimeState.WaitingForOffline
                    }
                    // we're offline, we don't care about any other event
                    UptimeEvent.Stop -> null
                    UptimeEvent.PingFailed -> null
                    UptimeEvent.PingSuccess -> null
                    UptimeEvent.NetworkAvailable -> null
                    UptimeEvent.NetworkUnavailable -> null
                }
            }
            UptimeState.WaitingForOffline -> {
                when (event) {
                    UptimeEvent.Start -> null // already started
                    UptimeEvent.Stop -> {
                        stopConnectivityCheck()
                        clearBackOnlineNotification()
                        stopNetworkAvailabilityListener()
                        UptimeState.Disabled
                    }
                    UptimeEvent.PingFailed -> {
                        showMessage("No connection")
                        clearBackOnlineNotification()
                        UptimeState.WaitingForOnline
                    }
                    UptimeEvent.PingSuccess -> null // already online
                    UptimeEvent.NetworkUnavailable -> {
                        pingWithBackoff(expectFail = true)
                        null
                    }
                    UptimeEvent.NetworkAvailable -> null // already online
                }
            }
            UptimeState.WaitingForOnline -> {
                when (event) {
                    UptimeEvent.Start -> null // already started
                    UptimeEvent.Stop -> {
                        stopConnectivityCheck()
                        clearBackOnlineNotification()
                        stopNetworkAvailabilityListener()
                        UptimeState.Disabled
                    }
                    UptimeEvent.PingFailed -> {
                        clearBackOnlineNotification()
                        null
                    }
                    UptimeEvent.PingSuccess -> {
                        showBackOnlineNotification()
                        UptimeState.WaitingForOffline
                    }
                    UptimeEvent.NetworkUnavailable -> null // already offline
                    UptimeEvent.NetworkAvailable -> {
                        pingWithBackoff(expectFail = false)
                        null
                    }
                }
            }
            else -> null
        }
    }

    private fun createNotificationChannels() {
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

    private fun setBackgroundNotificationEnabled(enabled: Boolean) {
        if (enabled) {
            val stopIntent = PendingIntent.getService(
                applicationContext,
                1,
                getStopIntent(applicationContext, clearBackOnlineNotification = false),
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
        val stopIntent = PendingIntent.getService(
            applicationContext,
            1,
            getStopIntent(applicationContext, clearBackOnlineNotification = true),
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

    private fun clearBackOnlineNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_BACK_ONLINE)
    }

    private fun canPingGoogle(): Boolean {
        val command = "ping -c 1 www.google.com"
        return Runtime.getRuntime().exec(command).waitFor() == 0
    }

    private fun showMessage(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(this@UptimeService, message, Toast.LENGTH_SHORT).show()
        }
    }
}

private const val CHANNEL_ID_FOREGROUND = "UptimeServiceForeground"
private const val CHANNEL_ID_UPDATE = "UptimeServiceUpdate"
private const val NOTIFICATION_ID_FOREGROUND = 1001
private const val NOTIFICATION_ID_BACK_ONLINE = 1002
private const val EXTRA_COMMAND = "EXTRA_COMMAND"
private const val EXTRA_CLEAR_BO_NOTIFICATION = "EXTRA_CLEAR_NOTIFICATION"
private const val COMMAND_START = "start"
private const val COMMAND_STOP = "stop"