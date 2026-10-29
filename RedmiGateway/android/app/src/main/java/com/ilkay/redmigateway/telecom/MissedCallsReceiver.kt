package com.ilkay.redmigateway.telecom

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import com.ilkay.redmigateway.journal.DiagnosticsLog
import com.ilkay.redmigateway.push.PushNotifier
import com.ilkay.redmigateway.security.PairingStore
import java.util.concurrent.Executors

/**
 * Telecom's missed-call notification, handed to this app because it is the default dialer.
 *
 * Without a receiver for ACTION_SHOW_MISSED_CALLS_NOTIFICATION, Telecom posts "Cevapsız çağrılar"
 * on its own TelecomMissedCalls channel, which buzzes the Redmi in the pocket, never clears, and
 * comes back after every reboot (device fact 29.10.2026: four rows re-posted at once). With one,
 * Telecom sends this broadcast instead, for live misses and for its reload after a boot, and
 * posts nothing itself. The iPhone already learns every missed call from CallJournal and Apple
 * push, so while an iPhone is paired this receiver shows nothing (quiet-1, N1, CALL-03, CONS-1).
 *
 * The component is off in the manifest and switched on by [MissedCalls.takeOver] at service start.
 * See there for why.
 */
class MissedCallsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) {
            Log.w(TAG, "Ignored unexpected broadcast ${intent.action}")
            return
        }
        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)
        val callBack = pendingIntentExtra(intent, EXTRA_CALL_BACK_INTENT)
        MissedCalls.runAsync(this, context) { MissedCalls.onTelecomCount(it, count, number, callBack) }
    }

    private companion object {
        const val TAG = "MissedCallsReceiver"
        /** TelecomManager.EXTRA_CALL_BACK_INTENT: hidden from the SDK, sent by Telecom when count is 1. */
        const val EXTRA_CALL_BACK_INTENT = "android.telecom.extra.CALL_BACK_INTENT"
    }
}

/**
 * The phone process's voicemail indicator (MWI), handed to the default dialer the same way
 * (quiet-7). Without it com.android.phone posts its own alerting "Yeni sesli mesaj" in the pocket
 * and the iPhone never hears of it. Whether this line's carrier sends MWI at all is unknown.
 */
class VoicemailNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_SHOW_VOICEMAIL_NOTIFICATION) {
            Log.w(TAG, "Ignored unexpected broadcast ${intent.action}")
            return
        }
        val count = intent.getIntExtra(TelephonyManager.EXTRA_NOTIFICATION_COUNT, -1)
        // A refresh repeats a known state after a reboot or SIM change; it must not alert.
        val refresh = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            intent.getBooleanExtra(TelephonyManager.EXTRA_IS_REFRESH, false)
        val call = pendingIntentExtra(intent, TelephonyManager.EXTRA_CALL_VOICEMAIL_INTENT)
        MissedCalls.runAsync(this, context) { MissedCalls.onVoicemail(it, count, refresh, call) }
    }

    private companion object {
        const val TAG = "VoicemailReceiver"
    }
}

/** What the Redmi does with Telecom's missed-call count. */
internal enum class MissedCallAction {
    /** Paired: show nothing, mark the calls read and reset Telecom's count. */
    CLEAR_SILENTLY,
    /** No iPhone: the Redmi is the phone in the owner's hand and shows the row like any dialer. */
    SHOW_ON_REDMI,
    /** Telecom's count went to zero: remove the Redmi's row. Never answered with another cancel. */
    REMOVE_ROWS
}

internal fun missedCallAction(count: Int, paired: Boolean): MissedCallAction = when {
    count <= 0 -> MissedCallAction.REMOVE_ROWS
    paired -> MissedCallAction.CLEAR_SILENTLY
    else -> MissedCallAction.SHOW_ON_REDMI
}

/** What the Redmi does with a voicemail indicator. */
internal enum class VoicemailAction { REMOVE_ROWS, FORWARD_TO_IPHONE, SHOW_ON_REDMI, NOTHING }

/**
 * @param count waiting messages; 0 clears, -1 means the carrier only said "waiting"
 * @param refresh the indicator is repeated after a reboot or SIM change, not new
 */
internal fun voicemailAction(count: Int, refresh: Boolean, paired: Boolean): VoicemailAction = when {
    count == 0 -> VoicemailAction.REMOVE_ROWS
    !paired -> VoicemailAction.SHOW_ON_REDMI
    refresh -> VoicemailAction.NOTHING
    else -> VoicemailAction.FORWARD_TO_IPHONE
}

/**
 * Whether Telecom's own missed-call row has to be cleared before the receiver is switched on.
 *
 * cancelMissedCallsNotification makes Telecom remove its row and mark the CallLog rows read only
 * while no dialer receiver exists; once one does, Telecom forwards even the cancel to the dialer
 * and leaves its earlier row standing. So the clear has to come first, and only while paired:
 * without an iPhone the row is the owner's only record of those calls.
 */
internal fun clearTelecomBeforeTakeover(receiverEnabled: Boolean, paired: Boolean): Boolean =
    !receiverEnabled && paired

/**
 * Whether a diagnostics line may be written now. A reboot replays every unread missed call as a
 * burst of broadcasts (count 1, 2, ... N) and each reset answers with count 0; one line per kind
 * of event in a few seconds is enough (AGENTS.md 8.1). Logcat still gets every one.
 */
internal fun shouldRecordMissedCallEvent(lastKind: String?, lastAtMs: Long, kind: String, nowMs: Long, windowMs: Long = 5_000L): Boolean =
    kind != lastKind || nowMs - lastAtMs >= windowMs

/**
 * The missed-call and voicemail state shared by the receivers, the service and the Redmi's row.
 */
object MissedCalls {
    private const val TAG = "MissedCalls"
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MissedCalls").apply { isDaemon = true }
    }
    @Volatile private var lastRecordedKind: String? = null
    @Volatile private var lastRecordedAt = 0L
    @Volatile private var writeCallLogMissingLogged = false

    /**
     * Runs [block] off the main thread while keeping the broadcast alive: marking CallLog rows read
     * is disk work and the Telecom call is a binder round trip.
     */
    fun runAsync(receiver: BroadcastReceiver, context: Context, block: (Context) -> Unit) {
        val app = context.applicationContext
        val pending = receiver.goAsync()
        worker.execute {
            try {
                block(app)
            } catch (error: Exception) {
                Log.w(TAG, "Missed-call handling failed", error)
                DiagnosticsLog.record(TAG, "Missed-call handling failed: ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Makes this app the owner of the missed-call notification. Called at every service start;
     * does work only the first time after an install.
     *
     * The receiver ships disabled so that this moment exists: while Telecom still sees no dialer
     * receiver, cancelMissedCallsNotification removes Telecom's own row (the four stale rows on
     * the device) and marks the CallLog rows read, so the next boot replays nothing. Only then is
     * the receiver switched on (see [clearTelecomBeforeTakeover]).
     */
    fun takeOver(context: Context) {
        runCatching {
            val component = ComponentName(context, MissedCallsReceiver::class.java)
            val pm = context.packageManager
            val enabled = pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            if (enabled) return
            val paired = PairingStore.pairedClient() != null
            val cleared = if (clearTelecomBeforeTakeover(enabled, paired)) cancelTelecomCount(context) else false
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            CallUiNotifier.cancelMissedCalls(context)
            record("takeover", "Missed-call notification taken over from Telecom (paired=$paired, Telecom row cleared=$cleared)")
        }.onFailure {
            Log.w(TAG, "Missed-call takeover failed", it)
            record("takeover-failed", "Missed-call takeover failed: ${it.javaClass.simpleName}; Telecom keeps posting its own row")
        }
    }

    /** One ACTION_SHOW_MISSED_CALLS_NOTIFICATION from Telecom. The number is never logged. */
    fun onTelecomCount(context: Context, count: Int, number: String?, callBack: PendingIntent?) {
        val paired = PairingStore.pairedClient() != null
        when (val action = missedCallAction(count, paired)) {
            MissedCallAction.CLEAR_SILENTLY -> {
                val marked = markCallLogSeen(context)
                val reset = cancelTelecomCount(context)
                CallUiNotifier.cancelMissedCalls(context)
                record(action.name, "Telecom missed calls count=$count; paired, shown on the iPhone only " +
                    "(CallLog rows marked read=${marked ?: "no permission"}, count reset=$reset)")
            }
            MissedCallAction.SHOW_ON_REDMI -> {
                CallUiNotifier.showMissedCalls(context, count, number, callBack)
                record(action.name, "Telecom missed calls count=$count; no paired iPhone, shown on the Redmi")
            }
            MissedCallAction.REMOVE_ROWS -> {
                CallUiNotifier.cancelMissedCalls(context)
                record(action.name, "Telecom missed calls count=0; Redmi rows removed")
            }
        }
    }

    /** The owner dismissed the Redmi's row: the calls are seen, as in any dialer. */
    fun markSeen(context: Context, reason: String) {
        val marked = markCallLogSeen(context)
        val reset = cancelTelecomCount(context)
        CallUiNotifier.cancelMissedCalls(context)
        record("seen", "Missed calls seen ($reason): CallLog rows marked read=${marked ?: "no permission"}, count reset=$reset")
    }

    fun onVoicemail(context: Context, count: Int, refresh: Boolean, call: PendingIntent?) {
        val paired = PairingStore.pairedClient() != null
        val action = voicemailAction(count, refresh, paired)
        when (action) {
            VoicemailAction.REMOVE_ROWS -> CallUiNotifier.cancelVoicemail(context)
            VoicemailAction.SHOW_ON_REDMI -> CallUiNotifier.showVoicemail(context, count, call, alert = !refresh)
            VoicemailAction.FORWARD_TO_IPHONE -> {
                CallUiNotifier.cancelVoicemail(context)
                PushNotifier.pushVoicemail(count)
            }
            VoicemailAction.NOTHING -> CallUiNotifier.cancelVoicemail(context)
        }
        record("voicemail-${action.name}", "Voicemail indicator count=$count refresh=$refresh paired=$paired: ${action.name}")
    }

    /**
     * Marks every unread missed call in the CallLog read, which is what Telecom replays after a
     * boot. CallJournal imports by _ID and never reads these flags, so the iPhone's history does
     * not change. Returns the number of rows changed, or null without WRITE_CALL_LOG (the dialer
     * role normally grants it; `pm grant` is the fallback in POST_UNLOCK_RESTORE.md).
     */
    private fun markCallLogSeen(context: Context): Int? {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            if (!writeCallLogMissingLogged) {
                writeCallLogMissingLogged = true
                record("no-write-call-log", "WRITE_CALL_LOG not granted; missed calls stay unread in the CallLog " +
                    "and Telecom hands them over again after each reboot")
            }
            return null
        }
        val values = ContentValues().apply {
            put(CallLog.Calls.NEW, 0)
            put(CallLog.Calls.IS_READ, 1)
        }
        return runCatching {
            context.contentResolver.update(
                CallLog.Calls.CONTENT_URI, values,
                "${CallLog.Calls.TYPE} = ? AND (${CallLog.Calls.NEW} = 1 OR ${CallLog.Calls.IS_READ} = 0 OR ${CallLog.Calls.IS_READ} IS NULL)",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString())
            )
        }.onFailure {
            Log.w(TAG, "Could not mark missed calls read", it)
            record("mark-failed", "Could not mark missed calls read: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    /**
     * Resets Telecom's missed-call count. The default dialer may call it. With the receiver active
     * Telecom answers with a count=0 broadcast, which only removes rows and never calls this again.
     */
    private fun cancelTelecomCount(context: Context): Boolean {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: run {
            Log.w(TAG, "No TelecomManager; missed-call count not reset")
            return false
        }
        return runCatching { telecom.cancelMissedCallsNotification() }
            .onFailure { record("cancel-refused", "Telecom refused cancelMissedCallsNotification: ${it.javaClass.simpleName}") }
            .isSuccess
    }

    private fun record(kind: String, message: String) {
        Log.i(TAG, message)
        val now = SystemClock.elapsedRealtime()
        if (!shouldRecordMissedCallEvent(lastRecordedKind, lastRecordedAt, kind, now)) return
        lastRecordedKind = kind
        lastRecordedAt = now
        DiagnosticsLog.record(TAG, message)
    }
}

/** A PendingIntent extra without the API 33 deprecation warning. */
private fun pendingIntentExtra(intent: Intent, key: String): PendingIntent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(key, PendingIntent::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(key)
    }
