package com.ilkay.redmigateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ilkay.redmigateway.server.GatewayWebSocketServer
import com.ilkay.redmigateway.server.GatewayWebPageServer
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.ui.MainActivity

class GatewayService : Service(), GatewayWebSocketServer.ServerListener {

    companion object {
        private const val TAG = "GatewayService"
        private const val CHANNEL_ID = "redmi_gateway_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.ilkay.redmigateway.ACTION_START"
        const val ACTION_STOP = "com.ilkay.redmigateway.ACTION_STOP"

        var isRunning: Boolean = false
            private set
    }

    private var webSocketServer: GatewayWebSocketServer? = null
    private var webPageServer: GatewayWebPageServer? = null

    inner class LocalBinder : Binder() {
        fun getService(): GatewayService = this@GatewayService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand action=$action")

        when (action) {
            ACTION_START -> startGateway()
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
        val notification = buildNotification("Geçit etkin (0 bağlı cihaz)")
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
            webSocketServer = GatewayWebSocketServer(this, 8787).apply {
                serverListener = this@GatewayService
                start()
            }
            webPageServer = GatewayWebPageServer(this, 8788).apply { start() }
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
        webPageServer?.stop()
        webPageServer = null
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
        val launchIntent = Intent(this, MainActivity::class.java)
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onClientCountChanged(authenticatedCount: Int) {
        updateNotification("Geçit etkin ($authenticatedCount bağlı cihaz)")
    }

    override fun onServerError(error: String) {
        CallManager.log("Server Error: $error")
    }

    fun getAuthenticatedClientCount(): Int {
        return webSocketServer?.getAuthenticatedClientCount() ?: 0
    }

    override fun onDestroy() {
        stopGateway()
        super.onDestroy()
    }
}
