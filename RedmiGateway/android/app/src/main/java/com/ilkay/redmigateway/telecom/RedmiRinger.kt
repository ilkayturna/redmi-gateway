package com.ilkay.redmigateway.telecom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import com.ilkay.redmigateway.RedmiGatewayApp
import com.ilkay.redmigateway.audio.CallAudioRelay
import com.ilkay.redmigateway.service.IPhoneReachability

/**
 * Rings the Redmi for incoming calls. Telecom leaves ringing to this app (the in-call service
 * declares IN_CALL_SERVICE_RINGING), so the Redmi can stay silent while the iPhone takes the call.
 *
 * With a paired iPhone and "only the iPhone rings" on, the Redmi waits for the iPhone to confirm
 * that it shows the call (usually within a second) and never makes a sound; it rings only if that
 * confirmation has not come within a few seconds. Without a pairing, or with the setting off, it
 * rings at once. A call is never silent on both phones.
 *
 * The ringer mode, "vibrate when ringing" and a silent-ringing request (Do Not Disturb, call
 * screening) are respected. While a conversation is on the Redmi, a waiting call plays the
 * call-waiting tone, which Telecom also leaves to the ringing app.
 */
object RedmiRinger {
    private const val TAG = "RedmiRinger"
    /** The iPhone is linked, or may be woken by the call beacon: it shows a call within ~1-3 s. */
    internal const val WAIT_NEARBY_MS = 3_000L
    /** Only Apple push reaches the iPhone: the push leaves after 1.5 s and takes a moment. */
    internal const val WAIT_PUSH_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())
    private var callId: String? = null
    private var silentRequested = false
    private var ringing = false
    /** When the Redmi started ringing; the silence latency in every RINGER_OFF line. */
    private var ringingSince = 0L
    private var ringtone: Ringtone? = null
    private var vibrating = false
    private var waitingTone: ToneGenerator? = null
    private val ringNow = Runnable { start("the iPhone did not show the call in time") }

    private val context: Context get() = RedmiGatewayApp.instance

    /** A new incoming call is ringing. */
    fun onIncoming(id: String, silent: Boolean) = handler.post {
        stopSound()
        handler.removeCallbacks(ringNow)
        callId = id
        silentRequested = silent
        if (IncomingCallPresenter.presentedOnIPhoneCallId == id && IncomingCallPresenter.quietWhenIPhoneRings) {
            Log.i(TAG, "Call already shows on the iPhone; Redmi stays quiet")
            return@post
        }
        val wait = ringWaitMs(IncomingCallPresenter.quietWhenIPhoneRings, IPhoneReachability.current().level)
        if (wait == 0L) {
            start(if (!IncomingCallPresenter.quietWhenIPhoneRings) "normal immediate ringing" else "no iPhone to ring")
        } else {
            Log.i(TAG, "Waiting up to ${wait / 1000} s for the iPhone before ringing the Redmi")
            handler.postDelayed(ringNow, wait)
        }
    }

    /** The iPhone shows [id]: the Redmi stays (or becomes) quiet. */
    fun onIPhonePresented(id: String) = handler.post {
        if (id != callId) return@post
        handler.removeCallbacks(ringNow)
        if (ringing) Log.i(TAG, "RINGER_OFF reason=iphone_showing after ${rangFor()}ms")
        stopSound()
    }

    /**
     * The call was answered, declined or ended, on either phone.
     *
     * The transport calls this from its own thread the moment the other phone reports the answer,
     * so the ringtone stops right there instead of taking another trip through the main queue: the
     * other phone must fall silent in well under 300 ms, and it is measured from the log timestamps
     * of "Executing ANSWER" and "RINGER_OFF".
     */
    fun stop(reason: String = "answered_or_ended") {
        if (Looper.myLooper() == Looper.getMainLooper()) stopNow(reason) else handler.post { stopNow(reason) }
    }

    private fun stopNow(reason: String) {
        handler.removeCallbacks(ringNow)
        if (ringing) {
            Log.i(TAG, "RINGER_OFF reason=$reason after ${rangFor()}ms (answered or ended on either phone)")
            com.ilkay.redmigateway.journal.DiagnosticsLog.record(TAG, "RINGER_OFF reason=$reason after ${rangFor()}ms")
        } else if (callId != null || ringtone != null || vibrating) {
            Log.i(TAG, "RINGER_OFF reason=$reason (was not ringing)")
        }
        stopSound()
        callId = null
    }

    private fun rangFor(): Long = if (ringingSince > 0L) SystemClock.elapsedRealtime() - ringingSince else 0L

    /**
     * An answer was announced but the radio never confirmed it, and the call is still ringing: ring
     * here again.
     *
     * [stop] clears the call reference as well, and nothing else restarts the ringtone (Telecom
     * leaves ringing to this app and its notification channel has no sound), so without this the
     * Redmi stayed silent for a call nobody had answered - and the iPhone does not ring again after
     * its own answer tap either. The caller would hear ringing while both phones stayed quiet.
     */
    fun resume(id: String, silent: Boolean) = handler.post {
        if (callId == id && ringing) return@post
        callId = id
        silentRequested = silent
        start("the answer was not confirmed")
    }

    /**
     * User pressed volume key or requested silencing ringer without ending the call.
     *
     * Recorded to the diagnostics file because this is the one path the person described from their
     * own hand ("onumde arama ekrani varken ses tusuna bastigimda sesi kessin") and until now it left
     * no trace anywhere: whether a key press actually reached the ringer could not be answered after
     * the fact, so the behaviour could be neither proven nor debugged.
     */
    fun silence() = handler.post {
        handler.removeCallbacks(ringNow)
        val wasRinging = ringing
        stopSound()
        com.ilkay.redmigateway.journal.DiagnosticsLog.record(
            TAG,
            "Redmi ringer silenced by the user (wasRinging=$wasRinging, callId=${if (callId != null) "set" else "none"})"
        )
        runCatching {
            context.getSystemService(TelecomManager::class.java)?.silenceRinger()
        }.onFailure { Log.w(TAG, "Could not silence TelecomManager ringer: ${it.message}") }
    }

    /** True while this phone rings (or would, with sound off); read on the main thread. */
    val isRinging: Boolean get() = ringing

    private fun start(reason: String) {
        handler.removeCallbacks(ringNow)
        if (callId == null || ringing) return
        ringing = true
        ringingSince = SystemClock.elapsedRealtime()
        // Only now does the call screen take over this phone's display; see CallUiNotifier.
        CallUiNotifier.onRedmiRinging()
        val audio = context.getSystemService(AudioManager::class.java)
        val mode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        Log.i(TAG, "Redmi rings: $reason (mode=$mode, silentRequested=$silentRequested)")
        com.ilkay.redmigateway.journal.DiagnosticsLog.record(TAG, "Redmi rings: $reason (mode=$mode)")
        if (silentRequested) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (mode == AudioManager.RINGER_MODE_NORMAL && (audio?.getStreamVolume(AudioManager.STREAM_RING) ?: 1) > 0) {
            runCatching {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                    ?: Settings.System.DEFAULT_RINGTONE_URI
                ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                    audioAttributes = attributes
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
            }.onFailure { Log.w(TAG, "Ringtone failed: ${it.javaClass.simpleName}") }
        }
        val vibrateWhenRinging = Settings.System.getInt(context.contentResolver, "vibrate_when_ringing", 0) == 1
        if (mode == AudioManager.RINGER_MODE_VIBRATE || (mode == AudioManager.RINGER_MODE_NORMAL && vibrateWhenRinging)) {
            runCatching {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0), attributes
                )
                vibrating = true
            }
        }
    }

    private fun stopSound() {
        runCatching { ringtone?.stop() }
        ringtone = null
        if (vibrating) runCatching { context.getSystemService(Vibrator::class.java)?.cancel() }
        vibrating = false
        ringing = false
        ringingSince = 0L
    }

    /** A second call waits while the first one continues. */
    fun onWaiting(waiting: Boolean) = handler.post {
        runCatching { waitingTone?.stopTone(); waitingTone?.release() }
        waitingTone = null
        // On the iPhone, CallKit announces the waiting call itself.
        if (!waiting || CallAudioRelay.isIPhoneOwner) return@post
        waitingTone = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80).apply { startTone(ToneGenerator.TONE_SUP_CALL_WAITING) }
        }.getOrNull()
    }
}

/**
 * How long the Redmi waits for the iPhone to show an incoming call before ringing here.
 *
 * This is the behaviour behind "when one of them answers, the other goes quiet at once", read from
 * the other end: the Redmi holds its own ringer back so the two phones do not ring over each
 * other, but it must never hold back so long that a call goes unanswered because the iPhone did
 * not wake in time.
 *
 * The waiting is bounded by how the iPhone can actually be reached. With no pairing there is
 * nothing to wait for, so it rings at once. With a link the iPhone is there and answers quickly.
 * With only Apple push it has further to travel, so the wait is longer, and that is the number
 * that matters when the iPhone is away from home.
 *
 * Extracted so it can be asserted on, because the two failure directions are both silent: wait
 * too long and the call is missed, wait too little and both phones ring at the person.
 *
 * @param quietWhenIPhoneRings the user's switch for holding the Redmi's ringer back
 * @param level how the iPhone can be reached right now
 * @return milliseconds to wait, zero meaning ring immediately
 */
internal fun ringWaitMs(quietWhenIPhoneRings: Boolean, level: IPhoneReachability.Level): Long = when {
    !quietWhenIPhoneRings -> 0L
    level == IPhoneReachability.Level.UNPAIRED -> 0L
    level == IPhoneReachability.Level.PUSH -> RedmiRinger.WAIT_PUSH_MS
    else -> RedmiRinger.WAIT_NEARBY_MS
}
