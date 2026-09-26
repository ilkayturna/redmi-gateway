package com.ilkay.redmigateway.telecom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.ilkay.redmigateway.ui.CallActivity

/** The default dialer's visible call controls. Telecom continues to own the ringtone. */
object CallUiNotifier : CallManager.CallListener {
    private const val CHANNEL = "cellular_calls"
    private const val NOTIFICATION_ID = 4301
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        createChannel(context)
        CallManager.addListener(this)
    }

    fun stop() {
        if (appContext == null) return
        CallManager.removeListener(this)
        appContext?.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        appContext = null
    }

    override fun onCallStateChanged(callId: String, state: String, number: String?) {
        mainHandler.post {
            val context = appContext ?: return@post
            val manager = context.getSystemService(NotificationManager::class.java)
            if (state == "idle" || state == "disconnected" || state == "disconnecting") {
                manager.cancel(NOTIFICATION_ID)
                return@post
            }
            val caller = number?.takeIf { it.isNotBlank() } ?: "Gizli numara"
            val person = Person.Builder().setName(caller).setImportant(true).build()
            val screen = PendingIntent.getActivity(
                context, 1,
                Intent(context, CallActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentIntent(screen)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)

            if (state == "ringing") {
                builder.setStyle(
                    NotificationCompat.CallStyle.forIncomingCall(
                        person,
                        action(context, "reject", callId, 2),
                        action(context, "answer", callId, 3)
                    )
                ).setFullScreenIntent(screen, true)
            } else {
                builder.setContentTitle(caller)
                    .setContentText("Görüşme sürüyor")
                    .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Sonlandır",
                        action(context, "hangup", callId, 4)
                    )
            }
            runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
                .onFailure { CallManager.log("Arama bildirimi gösterilemedi: ${it.message}") }
        }
    }

    override fun onLogMessage(message: String) = Unit

    private fun action(context: Context, verb: String, callId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java)
            .setAction("com.ilkay.redmigateway.call.$verb")
            .putExtra("callId", callId)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL, "Telefon aramaları", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Redmi'ye gelen ve Redmi'den yapılan aramalar"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            // Android Telecom rings the handset. A second notification tone would duplicate it.
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra("callId") ?: return
        when (intent.action) {
            "com.ilkay.redmigateway.call.answer" -> CallManager.answer(callId)
            "com.ilkay.redmigateway.call.reject" -> CallManager.reject(callId)
            "com.ilkay.redmigateway.call.hangup" -> CallManager.hangup(callId)
        }
    }
}
