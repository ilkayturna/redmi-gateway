package com.ilkay.redmigateway.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import com.ilkay.redmigateway.RedmiGatewayApp
import com.ilkay.redmigateway.journal.CallJournal
import com.ilkay.redmigateway.journal.SmsJournal
import com.ilkay.redmigateway.security.BridgeCrypto
import com.ilkay.redmigateway.security.PairingStore
import com.ilkay.redmigateway.server.ContactResolver
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.telecom.IncomingCallPresenter
import org.json.JSONObject

/**
 * Decides when the Redmi pushes through Apple instead of relying on the local link.
 *
 * - Incoming call: if the iPhone has not confirmed showing it within 1.5 s (it is away, or the
 *   app was swiped away), a VoIP push rings it. The call id and number are sealed with a key
 *   derived from the pairing; Apple relays only ciphertext.
 * - New SMS or missed call: if no connected iPhone received the journal entry within 8 s, an
 *   ordinary notification is pushed. At home the iPhone fetches entries itself (Bluetooth wake),
 *   so nothing is sent twice. Once Apple accepts it, the journal marks the entry "pushed", so the
 *   iPhone's later sync stores it without a second sound (docs/WAVE1-CONTRACT.md section 7).
 */
object PushNotifier : CallManager.CallListener, CallJournal.Listener, SmsJournal.Listener, CallManager.WaitingListener {
    private const val TAG = "PushNotifier"
    private const val PREFS = "phonebridge_push"
    private const val KEY_SMS_CONTENT = "sms_content_in_push"
    private val handler = Handler(Looper.getMainLooper())
    /** How long a connected iPhone gets to fetch a new SMS or missed call before Apple is asked. */
    private const val FALLBACK_DELAY_MS = 8_000L
    private val wake = com.ilkay.redmigateway.service.ShortWake("PushFallback")
    private var started = false
    private var pushedCallId: String? = null

    /** Highest journal entries a connected iPhone has received (set by the WebSocket server). */
    @Volatile var deliveredCallSeq = 0L
    @Volatile var deliveredSmsSeq = 0L

    private val prefs get() = RedmiGatewayApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Owner setting, default on: pushed SMS notifications show the text (like any SMS app). */
    var smsContentInPush: Boolean
        get() = prefs.getBoolean(KEY_SMS_CONTENT, true)
        set(value) { prefs.edit().putBoolean(KEY_SMS_CONTENT, value).apply() }

    fun start(context: Context) {
        if (started) return
        started = true
        CallManager.addListener(this)
        CallManager.addWaitingListener(this)
        CallJournal.get(context).addListener(this)
        SmsJournal.get(context).addListener(this)
    }

    override fun onWaitingCallChanged(callId: String?, number: String?) {
        if (callId == null || callId == pushedCallId) return
        handler.postDelayed({ maybePushCall(callId, number) }, 1500)
    }

    override fun onCallStateChanged(callId: String, state: String, number: String?) {
        if (state == "idle" && CallManager.waitingCallId == null) {
            pushedCallId = null
        }
        if (state != "ringing" || CallManager.currentDirection != "incoming" || callId == pushedCallId) return
        handler.postDelayed({ maybePushCall(callId, number) }, 1500)
    }

    override fun onLogMessage(message: String) = Unit

    private fun maybePushCall(callId: String, number: String? = null) {
        if (!ApnsSender.isReady || callId == pushedCallId) return
        val isCurrentRinging = CallManager.currentCallId == callId && CallManager.currentCallState == "ringing"
        val isWaiting = CallManager.waitingCallId == callId
        if (!isCurrentRinging && !isWaiting) return
        if (IncomingCallPresenter.presentedOnIPhoneCallId == callId) return
        val key = PairingStore.pairedClient()?.key ?: return
        pushedCallId = callId
        val phoneNum = number ?: (if (isWaiting) CallManager.waitingNumber else CallManager.currentPhoneNumber) ?: ""
        val body = JSONObject()
            .put("id", callId)
            .put("n", phoneNum)
            .put("t", System.currentTimeMillis())
        ApnsSender.sendVoip(JSONObject().put("p", sealPushRoute(key, body)))
        CallManager.log("Incoming call (waiting=$isWaiting) pushed to the iPhone through Apple")
    }

    override fun onSmsJournalEntry(entry: SmsJournal.Entry) {
        if (!isPushableSms(entry.box, entry.read)) return
        // Already announced: a status change of a pushed message, or the provider copy of a message
        // the iPhone already got as a fallback entry (CONS-11). A second alert is the duplicate.
        if (entry.pushed || entry.replaces != null) {
            Log.i(TAG, "SMS ${entry.id} not pushed again (pushed=${entry.pushed} replaces=${entry.replaces})")
            return
        }
        // The 8 s wait counts uptime; asleep in a pocket it could become minutes (ShortWake).
        wake.stayAwakeFor(FALLBACK_DELAY_MS + 2_000L)
        handler.postDelayed({
            if (deliveredSmsSeq >= entry.seq) return@postDelayed
            if (!ApnsSender.alertsReady) {
                Log.i(TAG, "SMS ${entry.id} not pushed: no APNs key or iPhone alert token")
                return@postDelayed
            }
            val sender = contactName(entry.address) ?: entry.address.ifBlank { "Bilinmeyen" }
            val text = smsPushBody(smsContentInPush, entry.body)
            val route = JSONObject().put("address", entry.address).put("gid", entry.id)
            ApnsSender.sendAlert(
                sender, text, smsPushThreadId(entry.address), "sms-${entry.id}",
                category = SMS_CATEGORY, extra = routeExtra("sms", route)
            ) { problem ->
                if (problem == null) SmsJournal.get(RedmiGatewayApp.instance).markPushed(entry.id)
                else Log.w(TAG, "SMS ${entry.id} alert not accepted: $problem")
            }
        }, FALLBACK_DELAY_MS)
    }

    override fun onCallJournalEntry(entry: CallJournal.Entry) {
        if (entry.type != "missed") return
        if (entry.pushed) {
            Log.i(TAG, "Missed call ${entry.logId} not pushed again")
            return
        }
        wake.stayAwakeFor(FALLBACK_DELAY_MS + 2_000L)
        handler.postDelayed({
            if (deliveredCallSeq >= entry.seq) return@postDelayed
            if (!ApnsSender.alertsReady) {
                Log.i(TAG, "Missed call ${entry.logId} not pushed: no APNs key or iPhone alert token")
                return@postDelayed
            }
            val caller = contactName(entry.number) ?: entry.number.ifBlank { "Gizli numara" }
            val route = JSONObject().put("number", entry.number).put("logId", entry.logId)
            ApnsSender.sendAlert(
                "Cevapsız arama", caller, MISSED_THREAD_ID, "call-${entry.logId}",
                category = MISSED_CATEGORY, extra = routeExtra("recents", route)
            ) { problem ->
                if (problem == null) CallJournal.get(RedmiGatewayApp.instance).markPushed(entry.logId)
                else Log.w(TAG, "Missed call ${entry.logId} alert not accepted: $problem")
            }
        }, FALLBACK_DELAY_MS)
    }

    /**
     * The Redmi's health alert (docs/WAVE1-CONTRACT.md section 4). It carries no sealed part: the
     * text names a fault on the Redmi, never a person. Returns false when nothing could be sent.
     */
    fun pushHealth(title: String, body: String, done: (String?) -> Unit): Boolean {
        if (!ApnsSender.alertsReady) return false
        ApnsSender.sendAlert(
            title, body, HEALTH_THREAD_ID, HEALTH_THREAD_ID,
            category = HEALTH_CATEGORY, extra = JSONObject().put("open", "health"), done = done
        )
        return true
    }

    /**
     * A carrier voicemail indicator, forwarded once because the iPhone has no other way to learn
     * it (quiet-7). The count is all it says; the voicemail number stays on the Redmi.
     */
    fun pushVoicemail(count: Int) {
        if (!ApnsSender.alertsReady) {
            Log.i(TAG, "Voicemail indicator not pushed: no APNs key or iPhone alert token")
            return
        }
        ApnsSender.sendAlert(
            "Sesli mesaj", voicemailPushBody(count), VOICEMAIL_THREAD_ID, VOICEMAIL_THREAD_ID,
            extra = JSONObject().put("open", "recents")
        ) { problem -> if (problem != null) Log.w(TAG, "Voicemail alert not accepted: $problem") }
    }

    /**
     * The plaintext route plus the sealed "p": what the app needs to open the right screen, with
     * the phone number never visible to Apple.
     */
    private fun routeExtra(open: String, route: JSONObject): JSONObject {
        val extra = JSONObject().put("open", open)
        val key = PairingStore.pairedClient()?.key ?: run {
            Log.w(TAG, "No pairing key; the alert goes without its sealed route")
            return extra
        }
        return extra.put("p", sealPushRoute(key, route))
    }

    /**
     * The iPhone's own contact names come first: they are what its app shows, and the Redmi's
     * address book only holds the bundled seed list (CONS-14).
     */
    private fun contactName(number: String): String? {
        if (number.isBlank()) return null
        ContactResolver.getCached(number)?.let { return it }
        val context = RedmiGatewayApp.instance
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}

/** iOS notification categories the app registers at launch (docs/WAVE1-CONTRACT.md section 7). */
internal const val SMS_CATEGORY = "SMS"
internal const val MISSED_CATEGORY = "MISSED"
internal const val HEALTH_CATEGORY = "HEALTH"

/** Notification thread ids that do not depend on a sender. */
internal const val MISSED_THREAD_ID = "missed"
internal const val HEALTH_THREAD_ID = "health"
internal const val VOICEMAIL_THREAD_ID = "voicemail"

/** Associated data of every sealed push payload; the iPhone opens it with the same bytes. */
internal val PUSH_AAD: ByteArray = "phonebridge-push".toByteArray(Charsets.UTF_8)

/**
 * Seals [route] exactly like the VoIP payload: AES-GCM under derive(pairing key, "push") with
 * [PUSH_AAD], as standard base64. The iPhone opens it the same way it opens the call push.
 */
internal fun sealPushRoute(pairingKey: ByteArray, route: JSONObject): String =
    BridgeCrypto.b64(
        BridgeCrypto.seal(BridgeCrypto.derive(pairingKey, "push"), route.toString().toByteArray(Charsets.UTF_8), PUSH_AAD)
    )

/**
 * Whether an SMS is worth an Apple notification at all.
 *
 * Only an unread inbox message is news. Sent messages and messages already read on the Redmi are
 * things the person has seen, and pushing them would make the iPhone buzz about its own history.
 */
internal fun isPushableSms(box: String, read: Boolean): Boolean = box == "inbox" && !read

/**
 * What the SMS notification is allowed to say.
 *
 * This is the privacy control, and it is the reason it is a function rather than a line inside the
 * listener. With the switch off the notification says only that a message arrived, and the body
 * never leaves the Redmi. With it on the body is included, cut to 400 characters so a long message
 * does not turn into a large push. Apple's servers carry whatever is put here, so the default and
 * the switch both matter.
 *
 * @param includeContent the user's switch for putting the message text in the notification
 * @param body the message as received
 */
internal fun smsPushBody(includeContent: Boolean, body: String): String =
    if (includeContent) body.take(400) else "Yeni mesaj"

/** The voicemail alert line; an unknown count (the carrier only said "waiting") reads as one. */
internal fun voicemailPushBody(count: Int): String =
    if (count > 1) "$count yeni sesli mesaj" else "Yeni sesli mesaj"

/**
 * The notification grouping key for a sender: "sms-" plus [phoneIdentityKey].
 *
 * It has to be the key the iPhone app uses for the conversation, or a pushed banner lands in a
 * different stack than the app's own and opening the conversation does not clear it (N10).
 */
internal fun smsPushThreadId(address: String): String = "sms-" + phoneIdentityKey(address)

/**
 * A Kotlin port of the iPhone's PhoneIdentity.key (PhoneContactPicker.swift), rule for rule.
 *
 * - No digits at all (bank and operator senders such as "TURKCELL"): the trimmed text, uppercased.
 * - 12 digits starting with 90, or 11 starting with 0: the last 10, so +90 532..., 0532... and
 *   532... are one person.
 * - Anything else, foreign numbers included: all the digits.
 *
 * Deliberately not ContactResolver.normalize, which has extra rules (0090, Turkish last ten) the
 * iPhone does not apply. Kotlin's uppercase() and Swift's uppercased() are both locale free, so
 * a Turkish dotted i is mapped the same way on both phones.
 */
internal fun phoneIdentityKey(number: String): String {
    val digits = number.filter(Char::isDigit)
    if (digits.isEmpty()) return number.trim().uppercase()
    if (digits.length == 12 && digits.startsWith("90")) return digits.takeLast(10)
    if (digits.length == 11 && digits.startsWith("0")) return digits.takeLast(10)
    return digits
}
