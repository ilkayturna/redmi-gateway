package com.ilkay.redmigateway.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.telecom.Call
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ilkay.redmigateway.R
import com.ilkay.redmigateway.audio.CallAudioRelay
import com.ilkay.redmigateway.databinding.ActivityCallBinding
import com.ilkay.redmigateway.server.ContactResolver
import com.ilkay.redmigateway.server.GatewayWebSocketServer
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.telecom.GatewayInCallService
import com.ilkay.redmigateway.telecom.IncomingCallPresenter
import com.ilkay.redmigateway.telecom.RedmiRinger

/**
 * Modernized in-call experience matching iOS CallKit and Google Pixel Dialer standards.
 * Features edge-to-edge dark immersive mode, 3x2 glass action grid, hero caller typography,
 * instant 72dp action buttons, DTMF keypad, and proximity screen management.
 */
class CallActivity : AppCompatActivity(), CallManager.CallListener, CallAudioRelay.Listener, SensorEventListener, ContactResolver.Listener {
    private companion object {
        /** Grace period before re-reading the audio route after a failed confirmation. */
        const val SPEAKER_RECHECK_MS = 1000L
    }

    private lateinit var binding: ActivityCallBinding

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var isMuted = false
    private var lastResolvedNumber: String? = null
    private var activeSinceMillis = 0L
    private val durationHandler = Handler(Looper.getMainLooper())
    private val durationTicker = object : Runnable {
        override fun run() {
            if (CallManager.currentCallState == "active" && activeSinceMillis > 0L) {
                val seconds = (System.currentTimeMillis() - activeSinceMillis).coerceAtLeast(0L) / 1000L
                binding.callDuration.text = "%02d:%02d".format(seconds / 60, seconds % 60)
                durationHandler.postDelayed(this, 1000L)
            }
        }
    }

    /** Route state before the last speaker tap, so a late switch can be told from a real failure. */
    private var speakerWasOn = false

    /** The iPhone link came or went; the transfer keys must follow it while the call is up. */
    private val reachabilityListener: () -> Unit = { updateAudioCard(CallAudioRelay.owner) }

    private val speakerRecheck = Runnable {
        if (!isFinishing && !isDestroyed) {
            val nowSpeaker = GatewayInCallService.isSpeakerOn()
            updateSpeakerLabel()
            binding.help.text = when {
                nowSpeaker != speakerWasOn && nowSpeaker -> "Hoparlör açık."
                nowSpeaker != speakerWasOn -> "Ses ahizede."
                else -> "Hoparlöre geçilemedi."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Edge-to-edge dark immersive system UI
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.topBarContainer.setPadding(0, statusBars.top, 0, 0)
            binding.bottomContainer.setPadding(0, 0, 0, navBars.bottom)
            insets
        }

        initProximitySensor()
        setupActionButtons()
        setupActiveControls()
        setupDtmfKeypad()
        setupAudioTransfer()
    }

    override fun onStart() {
        super.onStart()
        CallManager.addListener(this)
        CallAudioRelay.addListener(this)
        ContactResolver.addListener(this)
        com.ilkay.redmigateway.service.IPhoneReachability.addListener(reachabilityListener)
        CallManager.addWaitingListener(waitingListener)
        IncomingCallPresenter.onChanged = {
            runOnUiThread { onCallStateChanged(CallManager.currentCallId ?: "none", CallManager.currentCallState, CallManager.currentPhoneNumber) }
        }
        updateSpeakerLabel()
        updateMuteState()
        updateAudioCard(CallAudioRelay.owner)
        proximitySensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onResume() {
        super.onResume()
        updateSpeakerLabel()
        updateMuteState()
        onCallStateChanged(CallManager.currentCallId ?: "none", CallManager.currentCallState, CallManager.currentPhoneNumber)
    }

    override fun onStop() {
        CallManager.removeListener(this)
        CallAudioRelay.removeListener(this)
        ContactResolver.removeListener(this)
        com.ilkay.redmigateway.service.IPhoneReachability.removeListener(reachabilityListener)
        IncomingCallPresenter.onChanged = null
        CallManager.removeWaitingListener(waitingListener)
        sensorManager?.unregisterListener(this)
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock?.release()
        }
        durationHandler.removeCallbacks(durationTicker)
        durationHandler.removeCallbacks(speakerRecheck)
        super.onStop()
    }

    override fun onDestroy() {
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock?.release()
        }
        super.onDestroy()
    }

    private fun initProximitySensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "RedmiGateway:CallProximity"
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            CallManager.currentCallState == "ringing") {
            RedmiRinger.silence()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5f
            val isNear = distance < maxRange && distance < 5f
            val isSpeaker = GatewayInCallService.isSpeakerOn()

            if (isNear && !isSpeaker && CallManager.currentCallState in setOf("active", "dialing", "connecting")) {
                if (proximityWakeLock?.isHeld == false) {
                    proximityWakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max
                }
            } else {
                if (proximityWakeLock?.isHeld == true) {
                    proximityWakeLock?.release()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun setupActionButtons() {
        binding.answer.setOnClickListener {
            CallManager.answer(CallManager.currentCallId)
        }
        binding.reject.setOnClickListener {
            CallManager.reject(CallManager.currentCallId)
        }
    }

    private fun setupActiveControls() {
        binding.mute.setOnClickListener {
            isMuted = !isMuted
            GatewayInCallService.setGatewayMuted(isMuted)
            updateMuteState()
        }

        binding.speaker.setOnClickListener {
            speakerWasOn = GatewayInCallService.isSpeakerOn()
            GatewayInCallService.toggleSpeaker { success ->
                runOnUiThread {
                    if (success) {
                        updateSpeakerLabel()
                        if (GatewayInCallService.isSpeakerOn() && proximityWakeLock?.isHeld == true) {
                            proximityWakeLock?.release()
                        }
                    } else {
                        // Telecom can switch the endpoint after the confirmation window closes.
                        // Leaving the old label behind invites a second tap that reverses a route
                        // which had in fact changed, so re-read the real route once.
                        binding.help.text = "Hoparlöre geçilemedi"
                        durationHandler.removeCallbacks(speakerRecheck)
                        durationHandler.postDelayed(speakerRecheck, SPEAKER_RECHECK_MS)
                    }
                }
            }
        }

        binding.keypadToggle.setOnClickListener {
            val visible = binding.dtmfContainer.visibility == View.VISIBLE
            setKeypadVisible(!visible)
        }

        binding.dtmfHide.setOnClickListener {
            setKeypadVisible(false)
        }

        binding.holdButton.setOnClickListener {
            val call = CallManager.activeCall
            if (call != null) {
                val callState = runCatching { call.details?.state }.getOrNull() ?: @Suppress("DEPRECATION") call.state
                if (callState == Call.STATE_HOLDING) {
                    runCatching { call.unhold() }
                } else {
                    runCatching { call.hold() }
                }
            }
        }

        binding.gridTransferButton.setOnClickListener {
            when (CallAudioRelay.owner) {
                CallAudioRelay.Owner.IPHONE, CallAudioRelay.Owner.PREPARING_IPHONE ->
                    CallAudioRelay.transferAudioToRedmi()
                CallAudioRelay.Owner.REDMI -> requestIPhoneAudio()
            }
        }

        binding.moreButton.setOnClickListener {
            // Quick toggle speaker / earphone route
            binding.speaker.performClick()
        }

        binding.hangup.setOnClickListener {
            if (!CallManager.hangup(CallManager.currentCallId)) {
                binding.state.text = "Sonlandırılamadı"
            }
        }
    }

    private fun updateMuteState() {
        binding.muteLabel.text = if (isMuted) "Sesi Aç" else "Sessiz"
        // The label carries the state; mirror it so TalkBack announces the button once, correctly.
        binding.mute.contentDescription = binding.muteLabel.text
        if (isMuted) {
            binding.mute.setIconResource(R.drawable.ic_mic_off)
            binding.mute.setBackgroundResource(R.drawable.bg_glass_circle_active)
            binding.mute.setIconTintResource(R.color.call_bg_start)
            binding.muteLabel.setTextColor(ContextCompat.getColor(this, R.color.neon_amber))
        } else {
            binding.mute.setIconResource(R.drawable.ic_mic)
            binding.mute.setBackgroundResource(R.drawable.bg_glass_circle)
            binding.mute.setIconTintResource(android.R.color.white)
            binding.muteLabel.setTextColor(ContextCompat.getColor(this, R.color.call_text_slate))
        }
    }

    private fun updateSpeakerLabel() {
        val isSpeaker = GatewayInCallService.isSpeakerOn()
        binding.speakerLabel.text = if (isSpeaker) "Ahize" else "Hoparlör"
        binding.speaker.contentDescription = binding.speakerLabel.text
        if (isSpeaker) {
            binding.speaker.setBackgroundResource(R.drawable.bg_glass_circle_active)
            binding.speaker.setIconTintResource(R.color.call_bg_start)
            binding.speakerLabel.setTextColor(ContextCompat.getColor(this, R.color.neon_sky))
        } else {
            binding.speaker.setBackgroundResource(R.drawable.bg_glass_circle)
            binding.speaker.setIconTintResource(android.R.color.white)
            binding.speakerLabel.setTextColor(ContextCompat.getColor(this, R.color.call_text_slate))
        }
    }

    private fun setKeypadVisible(visible: Boolean) {
        binding.dtmfContainer.visibility = if (visible) View.VISIBLE else View.GONE
        binding.controlsGrid.visibility = if (visible) View.GONE else View.VISIBLE
        binding.keypadLabel.text = if (visible) "Gizle" else "Tuşlar"
        binding.keypadToggle.contentDescription = if (visible) "Tuş takımını kapat" else "Tuş takımını aç"
        if (visible) {
            binding.keypadToggle.setBackgroundResource(R.drawable.bg_glass_circle_active)
            binding.keypadToggle.setIconTintResource(R.color.call_bg_start)
            binding.keypadLabel.setTextColor(ContextCompat.getColor(this, R.color.neon_sky))
        } else {
            binding.keypadToggle.setBackgroundResource(R.drawable.bg_glass_circle)
            binding.keypadToggle.setIconTintResource(android.R.color.white)
            binding.keypadLabel.setTextColor(ContextCompat.getColor(this, R.color.call_text_slate))
        }
    }

    private fun setupDtmfKeypad() {
        val dtmfMap = mapOf(
            binding.dtmf1 to '1', binding.dtmf2 to '2', binding.dtmf3 to '3',
            binding.dtmf4 to '4', binding.dtmf5 to '5', binding.dtmf6 to '6',
            binding.dtmf7 to '7', binding.dtmf8 to '8', binding.dtmf9 to '9',
            binding.dtmfStar to '*', binding.dtmf0 to '0', binding.dtmfHash to '#'
        )
        for ((btn, digit) in dtmfMap) {
            btn.setOnClickListener {
                CallManager.playDtmf(digit)
            }
        }
    }

    private val waitingListener = object : CallManager.WaitingListener {
        override fun onWaitingCallChanged(callId: String?, number: String?) {
            runOnUiThread { showWaiting(callId, number) }
        }
    }

    /** A second call is ringing during this one: decline it, or end this call and answer it. */
    private fun showWaiting(callId: String?, number: String?) {
        if (callId == null) {
            binding.waitingCard.visibility = View.GONE
            return
        }
        val cached = ContactResolver.getCached(number)
        binding.waitingTitle.text = "Bekleyen arama: ${cached ?: number?.takeIf { it.isNotBlank() } ?: "Gizli numara"}"
        binding.waitingCard.visibility = View.VISIBLE
        binding.waitingReject.setOnClickListener { CallManager.rejectWaiting() }
        binding.waitingAnswer.setOnClickListener { CallManager.answer(callId) }

        if (cached == null && !number.isNullOrBlank()) {
            GatewayWebSocketServer.requestContactResolution(number)
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                Thread {
                    val name = runCatching {
                        val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                        contentResolver.query(lookup, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    }.getOrNull()
                    if (!name.isNullOrBlank()) {
                        runOnUiThread {
                            if (CallManager.waitingCallId == callId) {
                                binding.waitingTitle.text = "Bekleyen arama: $name"
                            }
                        }
                    }
                }.start()
            }
        }
    }

    private fun setupAudioTransfer() {
        binding.audioTransferButton.setOnClickListener {
            when (CallAudioRelay.owner) {
                CallAudioRelay.Owner.IPHONE, CallAudioRelay.Owner.PREPARING_IPHONE ->
                    CallAudioRelay.transferAudioToRedmi()
                CallAudioRelay.Owner.REDMI -> requestIPhoneAudio()
            }
        }
    }

    /** True only when there is an authenticated iPhone socket, i.e. a link that can carry audio. */
    private fun iPhoneAudioLinkUp(): Boolean = com.ilkay.redmigateway.service.GatewayService.hasAuthenticatedClient()

    /**
     * Hands the call audio to the iPhone. With no authenticated link there is nothing to hand it
     * to, so say so instead of going silent: the user pressed this once and nothing happened.
     */
    private fun requestIPhoneAudio() {
        if (!iPhoneAudioLinkUp()) {
            Toast.makeText(this, "iPhone bağlantısı yok", Toast.LENGTH_SHORT).show()
            binding.audioStatusDetail.text = "iPhone bağlı değil; önce iPhone uygulaması bağlanmalı."
            return
        }
        CallAudioRelay.requestIPhoneAudio()
        binding.audioStatusDetail.text = "iPhone'a soruluyor. O sırada görüşme Redmi'de sürer."
    }

    /** Shows only the real owner reported by the relay, never an intent. */
    private fun updateAudioCard(owner: CallAudioRelay.Owner) {
        runOnUiThread {
            val onIPhone = owner == CallAudioRelay.Owner.IPHONE
            // While the iPhone owns the call, Redmi mute/speaker would act on a muted device.
            binding.mute.isEnabled = !onIPhone
            binding.speaker.isEnabled = !onIPhone
            val iPhoneConnected = iPhoneAudioLinkUp()
            when (owner) {
                CallAudioRelay.Owner.IPHONE -> {
                    binding.audioStatusCard.strokeColor = ContextCompat.getColor(this, R.color.neon_sky)
                    binding.audioStatusIcon.setImageResource(R.drawable.ic_transfer_iphone)
                    binding.audioStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.neon_sky))
                    binding.audioStatusTitle.text = "Görüşme iPhone'da"
                    binding.audioStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.neon_sky))
                    binding.audioStatusDetail.text = "Bu telefonun mikrofonu ve ahizesi kapalı."
                    binding.audioTransferButton.visibility = View.VISIBLE
                    binding.audioTransferButton.isEnabled = true
                    binding.audioTransferButton.text = "Redmi'ye al"
                    binding.audioTransferButton.setTextColor(ContextCompat.getColor(this, R.color.neon_sky))
                    binding.audioTransferButton.strokeColor = ContextCompat.getColorStateList(this, R.color.neon_sky)
                    binding.gridTransferLabel.text = "Redmi'ye Al"
                }
                CallAudioRelay.Owner.PREPARING_IPHONE -> {
                    binding.audioStatusCard.strokeColor = ContextCompat.getColor(this, R.color.neon_sky)
                    binding.audioStatusIcon.setImageResource(R.drawable.ic_transfer_iphone)
                    binding.audioStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.neon_sky))
                    binding.audioStatusTitle.text = "iPhone hazırlanıyor"
                    binding.audioStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.neon_sky))
                    binding.audioStatusDetail.text = "iPhone sesi doğrulanana kadar görüşme bu telefonda sürer."
                    binding.audioTransferButton.visibility = View.VISIBLE
                    binding.audioTransferButton.isEnabled = true
                    binding.audioTransferButton.text = "Redmi'de kal"
                    binding.audioTransferButton.setTextColor(ContextCompat.getColor(this, R.color.neon_sky))
                    binding.audioTransferButton.strokeColor = ContextCompat.getColorStateList(this, R.color.neon_sky)
                    binding.gridTransferLabel.text = "Redmi'de Kal"
                }
                CallAudioRelay.Owner.REDMI -> {
                    binding.audioStatusCard.strokeColor = ContextCompat.getColor(this, R.color.call_answer)
                    binding.audioStatusIcon.setImageResource(R.drawable.ic_volume_up)
                    binding.audioStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.call_answer))
                    binding.audioStatusTitle.text = "Görüşme bu telefonda"
                    binding.audioStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.call_answer))
                    binding.audioStatusDetail.text = when (CallAudioRelay.lastReason) {
                        "iphone_audio_lost", "iphone_not_receiving", "uplink_stalled", "downlink_stalled" ->
                            "iPhone bağlantısı zayıfladı; ses bu telefona alındı."
                        "uplink_path_unavailable" ->
                            "Bu telefon iPhone sesini hatta aktaramadı; görüşme burada sürer."
                        else -> if (iPhoneConnected) "Ahize veya hoparlörden konuşabilirsin." else
                            "iPhone bağlı değil."
                    }
                    // With no authenticated iPhone socket the key must not look ready: it says why
                    // and stays passive instead of doing nothing when tapped.
                    binding.audioTransferButton.visibility = View.VISIBLE
                    binding.audioTransferButton.isEnabled = iPhoneConnected
                    binding.audioTransferButton.text = if (iPhoneConnected) "iPhone'a aktar" else "iPhone bağlı değil"
                    val transferColor = if (iPhoneConnected) R.color.call_answer else R.color.text_muted
                    binding.audioTransferButton.setTextColor(ContextCompat.getColor(this, transferColor))
                    binding.audioTransferButton.strokeColor = ContextCompat.getColorStateList(this, transferColor)
                    binding.gridTransferLabel.text = if (iPhoneConnected) "iPhone'a Aktar" else "iPhone bağlı değil"
                }
            }
            // The grid key owns a custom ripple background, so Material's disabled tint never shows;
            // dim the key and its label explicitly. Bringing audio back to Redmi needs no iPhone link.
            val gridUsable = owner != CallAudioRelay.Owner.REDMI || iPhoneConnected
            binding.gridTransferButton.isEnabled = gridUsable
            binding.gridTransferButton.alpha = if (gridUsable) 1f else 0.45f
            binding.gridTransferLabel.alpha = if (gridUsable) 1f else 0.45f
            binding.gridTransferButton.contentDescription = binding.gridTransferLabel.text
        }
    }

    override fun onAudioOwnerChanged(owner: CallAudioRelay.Owner, reason: String) {
        updateAudioCard(owner)
    }

    override fun onCallStateChanged(callId: String, state: String, number: String?) {
        runOnUiThread {
            if (state == "idle" || state == "disconnected") {
                if (CallManager.isCallLive()) {
                    return@runOnUiThread
                }
                finish()
                return@runOnUiThread
            }
            val isConf = CallManager.isConference
            if (isConf) {
                binding.contactName.visibility = View.VISIBLE
                binding.contactName.text = "Konferans Görüşme"
                val summary = CallManager.getConferenceParticipantSummary()
                binding.number.text = summary ?: (number?.takeIf { it.isNotBlank() } ?: "Konferans Görüşmesi")
                binding.number.textSize = 17f
                binding.avatarContainer.visibility = View.GONE
            } else {
                binding.avatarContainer.visibility = View.VISIBLE
                binding.number.text = number?.takeIf { it.isNotBlank() } ?: "Gizli numara"
                if (number != lastResolvedNumber) {
                    lastResolvedNumber = number
                    resolveContact(number)
                }
            }
            binding.state.text = when {
                isConf -> "Konferans Görüşmesi"
                state == "ringing" -> "Gelen Arama"
                state == "active" -> "Görüşme Sürüyor"
                state == "dialing" || state == "connecting" -> "Aranıyor..."
                state == "holding" -> "Beklemede"
                else -> "Arama Bağlanıyor"
            }

            if (state == "holding") {
                binding.holdButton.setBackgroundResource(R.drawable.bg_glass_circle_active)
                binding.holdButton.setIconTintResource(R.color.call_bg_start)
                binding.holdLabel.text = "Sürdür"
                binding.holdLabel.setTextColor(ContextCompat.getColor(this, R.color.neon_amber))
            } else {
                binding.holdButton.setBackgroundResource(R.drawable.bg_glass_circle)
                binding.holdButton.setIconTintResource(android.R.color.white)
                binding.holdLabel.text = "Beklet"
                binding.holdLabel.setTextColor(ContextCompat.getColor(this, R.color.call_text_slate))
            }
            binding.holdButton.contentDescription = binding.holdLabel.text

            val incoming = state == "ringing"
            if (state == "active") {
                if (activeSinceMillis == 0L) {
                    activeSinceMillis = CallManager.activeSinceMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
                }
                binding.callDuration.visibility = View.VISIBLE
                durationHandler.removeCallbacks(durationTicker)
                durationHandler.post(durationTicker)
            } else {
                durationHandler.removeCallbacks(durationTicker)
                binding.callDuration.visibility = View.GONE
            }

            binding.incomingActions.visibility = if (incoming) View.VISIBLE else View.GONE
            binding.activeControls.visibility = if (incoming) View.GONE else View.VISIBLE
            binding.audioStatusCard.visibility = if (state == "active") View.VISIBLE else View.GONE
            if (incoming) {
                setKeypadVisible(false)
            }

            updateAudioCard(CallAudioRelay.owner)
            updateSpeakerLabel()

            binding.help.text = when {
                isConf -> "Konferans görüşmesini bu telefondan da sonlandırabilirsin."
                incoming && IncomingCallPresenter.presentedOnIPhoneCallId == callId ->
                    "iPhone'da da çalıyor. Burada ya da iPhone'da yanıtlayabilirsin."
                incoming -> "Burada yanıtla. iPhone bağlıysa arama orada da görünür."
                else -> "Görüşmeyi bu telefondan da sonlandırabilirsin."
            }
        }
    }

    override fun onLogMessage(message: String) = Unit

    override fun onContactResolved(number: String, name: String) {
        runOnUiThread {
            val normalizedTarget = ContactResolver.normalize(number)
            val currentNum = CallManager.currentPhoneNumber
            if (currentNum != null && ContactResolver.normalize(currentNum) == normalizedTarget) {
                binding.contactName.text = name
                binding.contactName.visibility = View.VISIBLE
                binding.number.textSize = 17f
            }
            val waitingNum = CallManager.waitingNumber
            if (waitingNum != null && ContactResolver.normalize(waitingNum) == normalizedTarget) {
                binding.waitingTitle.text = "Bekleyen arama: $name"
            }
        }
    }

    private fun resolveContact(number: String?) {
        binding.contactName.visibility = View.GONE
        binding.contactPhoto.setImageResource(R.drawable.ic_person_avatar)
        binding.number.textSize = 28f
        if (number.isNullOrBlank()) return

        val cached = ContactResolver.getCached(number)
        if (cached != null) {
            binding.contactName.text = cached
            binding.contactName.visibility = View.VISIBLE
            binding.number.textSize = 18f
        } else {
            GatewayWebSocketServer.requestContactResolution(number)
        }

        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        Thread {
            runCatching {
                val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                contentResolver.query(lookup, arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI
                ), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        val photo = cursor.getString(1)
                        runOnUiThread {
                            val currentNum = CallManager.currentPhoneNumber
                            if (currentNum != null && ContactResolver.normalize(currentNum) == ContactResolver.normalize(number)) {
                                if (!name.isNullOrBlank()) {
                                    binding.contactName.text = name
                                    binding.contactName.visibility = View.VISIBLE
                                    binding.number.textSize = 17f
                                }
                                if (!photo.isNullOrBlank()) {
                                    binding.contactPhoto.setImageURI(Uri.parse(photo))
                                } else {
                                    binding.contactPhoto.setImageResource(R.drawable.ic_person_avatar)
                                }
                            }
                        }
                    }
                }
            }
        }.start()
    }
}
