package com.ilkay.redmigateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ilkay.redmigateway.audio.CallAudioRelay
import com.ilkay.redmigateway.ble.BleServerManager
import com.ilkay.redmigateway.discovery.NsdDiscoveryManager
import com.ilkay.redmigateway.server.GatewayWebSocketServer
import com.ilkay.redmigateway.journal.CallJournal
import com.ilkay.redmigateway.journal.SmsJournal
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.ui.CallActivity
import com.ilkay.redmigateway.ui.PhoneActivity

class GatewayService : Service(), GatewayWebSocketServer.ServerListener {

    companion object {
        private const val TAG = "GatewayService"
        private const val CHANNEL_ID = "redmi_gateway_channel"
        private const val NOTIFICATION_ID = 1001

        /**
         * The iPhone reconnects many times an hour and every authentication flips the status line.
         * On 16.10.2026 that produced 460 notification posts in 40 minutes (937 enqueues in the
         * events buffer), each one a NotificationManagerService:post wakelock, for a line the user
         * cannot read that fast anyway. Two rules keep the same information with far fewer posts:
         * the same text is never posted twice, and a change arriving inside this window waits for
         * the quiet moment. The window is short enough that the row still follows the phone.
         */
        private const val NOTIFICATION_MIN_INTERVAL_MS = 15_000L

        const val ACTION_START = "com.ilkay.redmigateway.ACTION_START"
        const val ACTION_STOP = "com.ilkay.redmigateway.ACTION_STOP"

        var isRunning: Boolean = false
            private set

        @Volatile private var instance: GatewayService? = null

        /**
         * Adds the microphone and phone-call types to the foreground service while call audio
         * is relayed, so Android keeps delivering call audio with the screen off.
         */
        /** True while at least one paired iPhone is connected and authenticated. */
        fun hasAuthenticatedClient(): Boolean = authenticatedClientCount() > 0

        fun authenticatedClientCount(): Int = instance?.getAuthenticatedClientCount() ?: 0

        fun setCallAudioForeground(active: Boolean) {
            val service = instance ?: return
            service.handler.post { service.applyForegroundTypes(active) }
        }

        /** The status line changed for a reason other than reachability (the smart hotspot hint). */
        fun refreshStatus() {
            val service = instance ?: return
            service.handler.post { if (isRunning) service.updateNotification(service.statusText()) }
        }
    }

    private var webSocketServer: GatewayWebSocketServer? = null
    private var nsdDiscoveryManager: NsdDiscoveryManager? = null
    private var bleServerManager: BleServerManager? = null
    private val handler = Handler(Looper.getMainLooper())
    // With the smart hotspot on, SmartHotspot decides; otherwise the saved hotspot comes back.
    private val restoreHotspot = Runnable { if (isRunning && !SmartHotspot.enabled) HotspotRestarter.startIfNeeded(this) }
    @Volatile private var callAudioOnIPhone = false
    private val reachabilityListener: () -> Unit = { if (isRunning) updateNotification(statusText()) }

    // Notification coalescing (see NOTIFICATION_MIN_INTERVAL_MS). All three fields are touched on
    // the main thread only: updateNotification runs from this handler or from IPhoneReachability's
    // listener, which posts to the main thread.
    private var lastPostedNotificationText: String? = null
    private var lastPostedNotificationAt = 0L
    private var pendingNotificationText: String? = null
    private val postPendingNotification = Runnable {
        val text = pendingNotificationText ?: return@Runnable
        pendingNotificationText = null
        postNotificationNow(text)
    }

    inner class LocalBinder : Binder() {
        fun getService(): GatewayService = this@GatewayService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand action=$action")

        when (action) {
            ACTION_START -> {
                startGateway()
                if (isRunning) {
                    handler.removeCallbacks(restoreHotspot)
                    handler.postDelayed(restoreHotspot, 10_000L)
                }
            }
            ACTION_STOP -> stopGateway()
        }

        return START_STICKY
    }

    private fun startGateway() {
        if (isRunning) {
            Log.d(TAG, "Gateway already running")
            return
        }

        Log.i(TAG, "Starting Gateway Service...")
        val notification = buildNotification(statusText())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val bleManager = BleServerManager(this)
            bleServerManager = bleManager
            nsdDiscoveryManager = NsdDiscoveryManager(this).apply {
                onIpChangedListener = { newIp ->
                    bleManager.updateActiveIp(newIp)
                }
            }
            webSocketServer = GatewayWebSocketServer(this, 8787).apply {
                serverListener = this@GatewayService
                start()
            }
            CallJournal.get(this).start()
            SmsJournal.get(this).start()
            com.ilkay.redmigateway.push.PushNotifier.start(this)
            SmartHotspot.start(this)
            IPhoneReachability.addListener(reachabilityListener)
            isRunning = true
            CallManager.log("Gateway Foreground Service started on port 8787")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting WebSocket server", e)
            CallManager.log("Failed to start WebSocket server: ${e.message}")
            stopGateway()
        }
    }

    private fun stopGateway() {
        Log.i(TAG, "Stopping Gateway Service...")
        handler.removeCallbacks(restoreHotspot)
        SmartHotspot.stop(this)
        IPhoneReachability.removeListener(reachabilityListener)
        nsdDiscoveryManager?.stop()
        nsdDiscoveryManager = null
        bleServerManager?.stop()
        bleServerManager = null
        try {
            webSocketServer?.stopServer()
            webSocketServer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket server", e)
        }

        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        CallManager.log("Gateway Foreground Service stopped")
    }

    override fun onServerStarted(port: Int) {
        handler.post {
            val currentIp = com.ilkay.redmigateway.ble.NetworkUtils.getLocalIPv4Address()
            if (currentIp != null) {
                bleServerManager?.updateActiveIp(currentIp)
            }
            nsdDiscoveryManager?.start(port)
            bleServerManager?.start(port)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Telefon Geçidi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service maintaining cellular gateway link"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        // The ongoing notification always leads back to the call while one exists.
        val inCall = CallManager.currentCallState !in setOf("idle", "disconnected")
        val launchIntent = Intent(this, if (inCall) CallActivity::class.java else PhoneActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Telefon Geçidi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastPostedNotificationText && pendingNotificationText == null) return
        val waitMs = lastPostedNotificationAt + NOTIFICATION_MIN_INTERVAL_MS - SystemClock.elapsedRealtime()
        if (waitMs <= 0L) {
            handler.removeCallbacks(postPendingNotification)
            pendingNotificationText = null
            postNotificationNow(text)
            return
        }
        // The state is flapping faster than a person can read it: keep only the newest text and
        // let one post cover every change that arrived inside the window.
        pendingNotificationText = text
        handler.removeCallbacks(postPendingNotification)
        handler.postDelayed(postPendingNotification, waitMs)
    }

    private fun postNotificationNow(text: String) {
        lastPostedNotificationText = text
        lastPostedNotificationAt = SystemClock.elapsedRealtime()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun applyForegroundTypes(callAudio: Boolean) {
        if (!isRunning || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (callAudio) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        }
        callAudioOnIPhone = callAudio
        try {
            startForeground(NOTIFICATION_ID, buildNotification(statusText()), types)
        } catch (error: Exception) {
            Log.w(TAG, "Could not change foreground service types", error)
            CallManager.log("Foreground call audio type rejected: ${error.message}")
        }
    }

    override fun onClientCountChanged(authenticatedCount: Int) {
        IPhoneReachability.changed()
        // A press the user made while the iPhone was asleep is replayed the moment it is back, and
        // the wake latency of the call beacon is measured to exactly this moment.
        if (authenticatedCount > 0) {
            BleServerManager.onControlLinkUp()
            // The iPhone is reachable again, so nothing needs waking and the retry push is cancelled.
            com.ilkay.redmigateway.push.CallWakePush.onControlLinkUp()
            CallAudioRelay.onControlClientAvailable()
        }
    }

    /** The ongoing notification: call audio on the iPhone, otherwise how the iPhone is reached. */
    private fun statusText(): String = when {
        callAudioOnIPhone -> "Görüşme sesi iPhone'da"
        else -> SmartHotspot.networkWaitingForWifi
            ?.let { "Ev/iş ağı \"$it\" menzilde · Wi-Fi'ı aç, iPhone mobil veri harcamasın" }
            ?: IPhoneReachability.notificationText()
    }

    override fun onServerError(error: String) {
        CallManager.log("Server Error: $error")
    }

    fun getAuthenticatedClientCount(): Int {
        return webSocketServer?.authenticatedCount() ?: 0
    }

    override fun onDestroy() {
        stopGateway()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
