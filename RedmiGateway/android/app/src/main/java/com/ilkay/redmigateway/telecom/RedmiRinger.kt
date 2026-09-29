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
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
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
    private const val WAIT_NEARBY_MS = 3_000L
    /** Only Apple push reaches the iPhone: the push leaves after 1.5 s and takes a moment. */
    private const val WAIT_PUSH_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())
    private var callId: String? = null
    private var silentRequested = false
    private var ringing = false
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
        val wait = if (!IncomingCallPresenter.quietWhenIPhoneRings) 0L else when (IPhoneReachability.current().level) {
            IPhoneReachability.Level.UNPAIRED -> 0L
            IPhoneReachability.Level.PUSH -> WAIT_PUSH_MS
            else -> WAIT_NEARBY_MS
        }
        if (wait == 0L) {
            start("no iPhone to ring")
        } else {
            Log.i(TAG, "Waiting up to ${wait / 1000} s for the iPhone before ringing the Redmi")
            handler.postDelayed(ringNow, wait)
        }
    }

    /** The iPhone shows [id]: the Redmi stays (or becomes) quiet. */
    fun onIPhonePresented(id: String) = handler.post {
        if (id != callId) return@post
        handler.removeCallbacks(ringNow)
        if (ringing) Log.i(TAG, "iPhone shows the call; Redmi ringer off")
        stopSound()
    }

    /** The call was answered, rejected or ended, on either phone. */
    fun stop() = handler.post {
        handler.removeCallbacks(ringNow)
        stopSound()
        callId = null
    }

    private fun start(reason: String) {
        handler.removeCallbacks(ringNow)
        if (callId == null || ringing) return
        ringing = true
        val audio = context.getSystemService(AudioManager::class.java)
        val mode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        Log.i(TAG, "Redmi rings: $reason (mode=$mode, silentRequested=$silentRequested)")
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
