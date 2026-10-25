package com.ilkay.redmigateway.telecom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.ilkay.redmigateway.server.ContactResolver
import com.ilkay.redmigateway.ui.CallActivity
import com.ilkay.redmigateway.ui.PhoneActivity
import java.util.concurrent.ConcurrentHashMap

/** The default dialer's visible call controls. Telecom continues to own the ringtone. */
object CallUiNotifier : CallManager.CallListener {
    private const val CHANNEL = "cellular_calls"
    private const val QUIET_CHANNEL = "cellular_calls_quiet"
    private const val MISSED_CHANNEL = "missed_calls"
    private const val MISSED_QUIET_CHANNEL = "missed_calls_quiet"
    private const val NOTIFICATION_ID = 4301
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    private val ringingCallNumbers = ConcurrentHashMap<String, String?>()
    private val activeCallIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastRingingId: String? = null
    @Volatile private var lastRingingNumber: String? = null
    @Volatile private var lastWasActive: Boolean = false

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

            if (state == "ringing") {
                val key = if (callId.isNotBlank() && callId != "none") callId else "current"
                val num = number ?: CallManager.currentPhoneNumber
                ringingCallNumbers[key] = num
                lastRingingId = key
                lastRingingNumber = num
                lastWasActive = false
            } else if (state == "active") {
                val key = if (callId.isNotBlank() && callId != "none") callId else "current"
                activeCallIds.add(key)
                lastWasActive = true
            }

            if (state == "idle" || state == "disconnected" || state == "disconnecting") {
                // Check if this was a missed call (rang without ever becoming active)
                val key = callUiKey(callId, lastRingingId)
                if (key != null) {
                    val missedNum = ringingCallNumbers.remove(key) ?: lastRingingNumber
                    val becameActive = activeCallIds.remove(key) || lastWasActive
                    if (wasMissed(missedNum, becameActive)) {
                        postMissedCallNotification(context, key, missedNum as String)
                    }
                }
                lastRingingId = null
                lastRingingNumber = null
                lastWasActive = false

                if (CallManager.isCallLive()) {
                    return@post
                }
                manager?.cancel(NOTIFICATION_ID)
                lastPostedSignature = null
                return@post
            }
            post(context, callId, state, number)
        }
    }

    /**
     * The Redmi's own ringer has started (the iPhone did not show the call, or there is no iPhone):
     * now the full-screen call screen is wanted, so the ringing notification is posted again with it.
     */
    fun onRedmiRinging() {
        mainHandler.post {
            val context = appContext ?: return@post
            if (CallManager.currentCallState != "ringing") return@post
            post(context, CallManager.currentCallId ?: "none", "ringing", CallManager.currentPhoneNumber)
        }
    }

    /**
     * What the last posted notification showed. Telecom reports connecting, dialing, active and the
     * same state again within a few hundred milliseconds, and each used to be a fresh post: Android
     * allows five per second per app and silently dropped the rest ("Package enqueue rate is 6.95.
     * Shedding", 23.10.2026 20:11), which can leave a stale row behind. Those states all look the
     * same in the notification, so an identical one is not posted again.
     */
    private var lastPostedSignature: String? = null

    private fun post(context: Context, callId: String, state: String, number: String?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        run {
            val isConf = CallManager.isConference
            val caller = when {
                isConf -> number?.takeIf { it.isNotBlank() } ?: "Konferans Görüşme"
                number.isNullOrBlank() -> "Gizli numara"
                else -> ContactResolver.getCached(number) ?: resolveCallerName(context, number)
            }
            // Only a ringing Redmi takes over its screen. While the iPhone is being given its few
            // seconds to show the call, this phone is in a pocket and a full-screen intent would
            // light it up with Answer and Reject exposed (23.10.2026 16:53). RedmiRinger calls
            // onRedmiRinging the moment it decides to ring here, which adds the screen.
            val fullScreen = state == "ringing" && RedmiRinger.isRinging
            val signature = if (state == "ringing") "ring|$callId|$caller|$fullScreen" else "call|$callId|$caller|$isConf"
            if (signature == lastPostedSignature) return
            lastPostedSignature = signature
            val person = Person.Builder().setName(caller).setImportant(true).build()
            val screen = PendingIntent.getActivity(
                context, 1,
                Intent(context, CallActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Everything except a ringing Redmi goes to the quiet channel. The ROM's ambient display
            // lights the screen for every interruptive notification: measured on 25.10.2026 at
            // 12:17:39 (incoming call shown on the iPhone) and 12:18:03 (outgoing call), the Redmi's
            // screen came on in the pocket with no wake-up behind it, only this row on the high channel.
            val builder = NotificationCompat.Builder(context, if (fullScreen) CHANNEL else QUIET_CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentIntent(screen)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(if (fullScreen) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
                .setSilent(!fullScreen)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)

            if (state == "ringing" && fullScreen) {
                builder.setStyle(
                    NotificationCompat.CallStyle.forIncomingCall(
                        person,
                        action(context, "reject", callId, 2),
                        action(context, "answer", callId, 3)
                    )
                ).setFullScreenIntent(screen, true)
            } else if (state == "ringing") {
                // A plain row, not CallStyle: Android refuses a CallStyle notification that has no
                // full-screen intent unless it belongs to a foreground service.
                builder.setContentTitle(caller)
                    .setContentText("Gelen arama · iPhone'a iletiliyor")
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reddet", action(context, "reject", callId, 2))
                    .addAction(android.R.drawable.sym_action_call, "Cevapla", action(context, "answer", callId, 3))
            } else {
                builder.setContentTitle(caller)
                    .setContentText(if (isConf) "Konferans görüşmesi sürüyor" else "Görüşme sürüyor")
                    .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Sonlandır",
                        action(context, "hangup", callId, 4)
                    )
            }
            runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
                .onFailure {
                    lastPostedSignature = null
                    CallManager.log("Arama bildirimi gösterilemedi: ${it.message}")
                }
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

    private fun resolveCallerName(context: Context, number: String?): String {
        if (number.isNullOrBlank()) return "Gizli numara"
        ContactResolver.getCached(number)?.let { return it }
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val name = runCatching {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number)
                )
                context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            if (!name.isNullOrBlank()) return name
        }
        return number
    }

    private fun postMissedCallNotification(context: Context, callId: String, number: String?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val caller = resolveCallerName(context, number)
        val notifId = 4400 + (callId.hashCode() and 0x7FFF)
        val contentIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, PhoneActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // With a paired iPhone the missed call is shown there (journal and Apple push), and on the
        // Redmi this row only lit the screen in the pocket (25.10.2026 15:06:59). Without a pairing
        // the Redmi is the only place to see it, so it keeps its normal channel.
        val quiet = com.ilkay.redmigateway.security.PairingStore.pairedClient() != null
        val builder = NotificationCompat.Builder(context, if (quiet) MISSED_QUIET_CHANNEL else MISSED_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Cevapsız Arama")
            .setContentText(caller)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setSilent(quiet)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (!number.isNullOrBlank()) {
            val dialIntent = PendingIntent.getActivity(
                context,
                notifId + 1,
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.sym_action_call,
                "Geri Ara",
                dialIntent
            )
        }

        runCatching {
            manager.notify(notifId, builder.build())
            CallManager.log("Cevapsız arama bildirimi gösterildi: $caller ($number)")
        }.onFailure {
            CallManager.log("Cevapsız arama bildirimi gösterilemedi: ${it.message}")
        }
    }

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val callChannel = NotificationChannel(
            CHANNEL, "Telefon aramaları", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Redmi'ye gelen ve Redmi'den yapılan aramalar"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            // Android Telecom rings the handset. A second notification tone would duplicate it.
            setSound(null, null)
        }
        // A call the iPhone carries, and any call in progress: shown in the shade and on the lock
        // screen, but never interruptive, so the ambient display does not light the pocket.
        val quietChannel = NotificationChannel(
            QUIET_CHANNEL, "Süren aramalar", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "iPhone'a iletilen ve süren aramalar; ekranı yakmaz"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(quietChannel)
        val missedChannel = NotificationChannel(
            MISSED_CHANNEL, "Cevapsız aramalar", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Redmi'deki cevapsız arama bildirimleri"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val missedQuietChannel = NotificationChannel(
            MISSED_QUIET_CHANNEL, "Cevapsız aramalar (iPhone'da da)", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "iPhone eşliyken Redmi'deki cevapsız aramalar; ekranı yakmaz"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(callChannel)
        manager.createNotificationChannel(missedChannel)
        manager.createNotificationChannel(missedQuietChannel)
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

/**
 * The key a call is tracked under while it rings and then ends.
 *
 * Telecom sometimes reports a call id of "none" or an empty string, and the missed call decision
 * needs something to look the number up by, so the last id that rang is used instead. When there
 * is neither, the answer is null and no notification is posted, which is the right outcome: there
 * is no call to report.
 *
 * @param callId the id from the call state callback
 * @param lastRingingId the id of the call that last rang, or null
 */
internal fun callUiKey(callId: String, lastRingingId: String?): String? =
    if (callId.isNotBlank() && callId != "none") callId else lastRingingId

/**
 * Whether a call that has just ended was missed.
 *
 * A call is missed when it rang, so a number was recorded, and it never became active. A call the
 * person answered and then hung up is not missed, and neither is one that never rang.
 *
 * @param missedNumber the number the ringing call came from, or null if none was recorded
 * @param becameActive whether the call was ever answered
 */
internal fun wasMissed(missedNumber: String?, becameActive: Boolean): Boolean =
    missedNumber != null && !becameActive
