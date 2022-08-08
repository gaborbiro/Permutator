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
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.gaborbiro.permutator.PermutatorApp
import app.gaborbiro.permutator.UptimeState
import app.gaborbiro.permutator.setIfDifferent
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class UptimeService : Service() {

    companion object {

        fun start(parent: Context) {
            ContextCompat.startForegroundService(parent, parent.getScanStartIntent())
        }

        fun Context.getScanStartIntent() = Intent(this, UptimeService::class.java).also {
            it.putExtra(EXTRA_COMMAND, COMMAND_START)
        }

        fun stop(parent: Activity) {
            parent.startService(parent.getScanStopIntent(clearBackOnlineNotification = true))
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
        val state = (application as PermutatorApp).uptimeState.value
        reduceUptimeState(state, uptimeEvent)?.let { newState ->
            CoroutineScope(Dispatchers.Main).launch {
                (application as PermutatorApp).uptimeState.setIfDifferent(newState)
            }
        }
    }

    private fun reduceUptimeState(state: UptimeState?, event: UptimeEvent): UptimeState? {
        return when (state) {
            UptimeState.Disabled -> {
                when (event) {
                    UptimeEvent.Start -> {
                        startConnectivityCheck()
                        startNetworkAvailabilityListener()
                        UptimeState.WaitingForOffline
                    }
                    // we're offline, we don't care about any other event
                    else -> null
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