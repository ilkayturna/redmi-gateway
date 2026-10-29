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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.ilkay.redmigateway.server.ContactResolver
import com.ilkay.redmigateway.ui.CallActivity
import com.ilkay.redmigateway.ui.PhoneActivity

/**
 * The default dialer's visible call controls, and the Redmi's own missed-call and voicemail rows.
 *
 * The ringtone is RedmiRinger's (IN_CALL_SERVICE_RINGING), not Telecom's. Missed calls are no
 * longer guessed here from "rang and never became active": that also counted calls declined on
 * the iPhone and added a second row next to Telecom's (quiet-5). MissedCallsReceiver now follows
 * Telecom's own missed-call count, and the rows below are shown only without a paired iPhone.
 */
object CallUiNotifier : CallManager.CallListener {
    private const val TAG = "CallUiNotifier"
    private const val CHANNEL = "cellular_calls"
    private const val QUIET_CHANNEL = "cellular_calls_quiet"
    private const val MISSED_CHANNEL = "missed_calls"
    /** Held the per-call rows posted while paired; deleted so those rows go with it. */
    private const val MISSED_QUIET_CHANNEL = "missed_calls_quiet"
    private const val NOTIFICATION_ID = 4301
    private const val VOICEMAIL_NOTIFICATION_ID = 4302
    /** One row for all unseen missed calls; the old per-call rows used 4400 + a hash. */
    private const val MISSED_NOTIFICATION_ID = 4400
    const val ACTION_MISSED_SEEN = "com.ilkay.redmigateway.call.missed_seen"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    @Volatile private var channelsReady = false

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

    /**
     * Telecom's unseen missed calls, on the Redmi itself. Only called without a paired iPhone:
     * then this phone is the one in the owner's hand, so it behaves like any dialer (one row that
     * counts up, "Geri ara" through Telecom for a single call, swiping it marks the calls seen).
     *
     * @param count Telecom's count of unseen missed calls, at least 1
     * @param number the latest caller, or null when hidden
     * @param callBack Telecom's own call-back intent, sent only when [count] is 1
     */
    fun showMissedCalls(context: Context, count: Int, number: String?, callBack: PendingIntent?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: run {
            Log.w(TAG, "No NotificationManager; missed calls not shown")
            return
        }
        ensureChannels(context)
        val caller = if (number.isNullOrBlank()) "Gizli numara" else resolveCallerName(context, number)
        val builder = NotificationCompat.Builder(context, MISSED_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle(missedCallsTitle(count))
            .setContentText(if (count > 1) "Son arayan: $caller" else caller)
            .setContentIntent(openRecents(context, MISSED_NOTIFICATION_ID))
            .setDeleteIntent(missedSeenIntent(context))
            .setAutoCancel(true)
            .setNumber(count)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (callBack != null) builder.addAction(android.R.drawable.sym_action_call, "Geri ara", callBack)
        runCatching { manager.notify(MISSED_NOTIFICATION_ID, builder.build()) }
            .onFailure { CallManager.log("Cevapsız arama bildirimi gösterilemedi: ${it.message}") }
    }

    /**
     * Removes the missed-call row, and the per-call rows earlier versions posted (4400 + a hash,
     * never cancelled; quiet-5). Those are found by channel, because their ids cannot be listed.
     */
    fun cancelMissedCalls(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: run {
            Log.w(TAG, "No NotificationManager; missed-call rows not removed")
            return
        }
        ensureChannels(context)
        manager.cancel(MISSED_NOTIFICATION_ID)
        runCatching {
            manager.activeNotifications
                .filter { it.notification.channelId == MISSED_CHANNEL && it.id != VOICEMAIL_NOTIFICATION_ID }
                .forEach { manager.cancel(it.tag, it.id) }
        }.onFailure { Log.w(TAG, "Could not list the old missed-call rows: ${it.javaClass.simpleName}") }
    }

    /**
     * A carrier voicemail indicator on the Redmi itself, only without a paired iPhone.
     *
     * @param count waiting messages, or -1 when the carrier only says "waiting"
     * @param call the phone app's intent that dials the voicemail box, if it sent one
     * @param alert false for a refresh (after a reboot or SIM change), which must not sound
     */
    fun showVoicemail(context: Context, count: Int, call: PendingIntent?, alert: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: run {
            Log.w(TAG, "No NotificationManager; voicemail not shown")
            return
        }
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, MISSED_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_voicemail)
            .setContentTitle("Sesli mesaj")
            .setContentText(if (count > 1) "$count yeni sesli mesaj" else "Yeni sesli mesaj")
            .setContentIntent(call ?: openRecents(context, VOICEMAIL_NOTIFICATION_ID))
            .setOnlyAlertOnce(true)
            .setSilent(!alert)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        runCatching { manager.notify(VOICEMAIL_NOTIFICATION_ID, builder.build()) }
            .onFailure { CallManager.log("Sesli mesaj bildirimi gösterilemedi: ${it.message}") }
    }

    fun cancelVoicemail(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: run {
            Log.w(TAG, "No NotificationManager; voicemail row not removed")
            return
        }
        manager.cancel(VOICEMAIL_NOTIFICATION_ID)
    }

    private fun openRecents(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, PhoneActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Swiping the row away is "seen", as in any dialer: the calls stop counting and are marked read. */
    private fun missedSeenIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        MISSED_NOTIFICATION_ID,
        Intent(context, CallActionReceiver::class.java).setAction(ACTION_MISSED_SEEN),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun ensureChannels(context: Context) {
        if (channelsReady) return
        createChannel(context)
    }

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val callChannel = NotificationChannel(
            CHANNEL, "Telefon aramaları", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Redmi'ye gelen ve Redmi'den yapılan aramalar"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            // RedmiRinger plays the ringtone itself. A second notification tone would duplicate it.
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
            description = "iPhone eşli değilken Redmi'deki cevapsız arama ve sesli mesaj bildirimleri"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(callChannel)
        manager.createNotificationChannel(missedChannel)
        // Nothing is posted on the quiet missed-call channel any more; deleting it also removes
        // every per-call row it still holds from earlier versions.
        manager.deleteNotificationChannel(MISSED_QUIET_CHANNEL)
        channelsReady = true
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CallUiNotifier.ACTION_MISSED_SEEN) {
            MissedCalls.runAsync(this, context) { MissedCalls.markSeen(it, "row dismissed on the Redmi") }
            return
        }
        val callId = intent.getStringExtra("callId") ?: run {
            Log.w("CallActionReceiver", "Ignored ${intent.action}: no call id")
            return
        }
        when (intent.action) {
            "com.ilkay.redmigateway.call.answer" -> CallManager.answer(callId)
            "com.ilkay.redmigateway.call.reject" -> CallManager.reject(callId)
            "com.ilkay.redmigateway.call.hangup" -> CallManager.hangup(callId)
        }
    }
}

/** The title of the Redmi's missed-call row: Telecom's count, as a person would say it. */
internal fun missedCallsTitle(count: Int): String =
    if (count > 1) "$count cevapsız arama" else "Cevapsız arama"
