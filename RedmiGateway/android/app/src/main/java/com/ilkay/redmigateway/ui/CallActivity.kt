package com.ilkay.redmigateway.ui

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ilkay.redmigateway.R
import com.ilkay.redmigateway.audio.CallAudioRelay
import com.ilkay.redmigateway.databinding.ActivityCallBinding
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.telecom.GatewayInCallService

/**
 * A polished in-call experience when this app is the Redmi's default dialer.
 * Supports swipe-to-answer/reject, clear audio ownership status, 2x2 control grid,
 * DTMF keypad, and proximity screen-off.
 */
class CallActivity : AppCompatActivity(), CallManager.CallListener, CallAudioRelay.Listener, SensorEventListener {
    private lateinit var binding: ActivityCallBinding

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var isMuted = false
    private var isAudioOnIPhone = false
    private var lastResolvedNumber: String? = null
    private var activeSinceMillis = 0L
    private val durationHandler = Handler(Looper.getMainLooper())
    private val durationTicker = object : Runnable {
        override fun run() {
            if (CallManager.currentCallState == "active" && activeSinceMillis > 0L) {
                val seconds = (System.currentTimeMillis() - activeSinceMillis).coerceAtLeast(0L) / 1000L
                binding.callDuration.text = "%02d:%02d".format(seconds / 60, seconds % 60)
                if (CallAudioRelay.isRedirectTrialActive ||
                    (CallAudioRelay.isAudioTransferUnavailable &&
                        binding.audioTransferButton.visibility == View.VISIBLE)) {
                    updateAudioCard(false)
                }
                durationHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initProximitySensor()
        setupSwipeSlider()
        setupActiveControls()
        setupDtmfKeypad()
        setupAudioTransfer()
    }

    override fun onStart() {
        super.onStart()
        CallManager.addListener(this)
        CallAudioRelay.addListener(this)
        updateSpeakerLabel()
        updateAudioCard(CallAudioRelay.isIPhoneAudioConfirmed)
        proximitySensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onStop() {
        CallManager.removeListener(this)
        CallAudioRelay.removeListener(this)
        sensorManager?.unregisterListener(this)
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock?.release()
        }
        durationHandler.removeCallbacks(durationTicker)
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

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5f
            val isNear = distance < maxRange && distance < 5f
            val isSpeaker = GatewayInCallService.isSpeakerOn()

            if (isNear && !isSpeaker && CallManager.currentCallState == "active") {
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeSlider() {
        var startX = 0f
        binding.sliderThumb.setOnTouchListener { v, event ->
            val trackWidth = binding.sliderTrack.width
            val thumbWidth = v.width
            val maxSwipe = ((trackWidth - thumbWidth) / 2f).coerceAtLeast(100f)
            val threshold = maxSwipe * 0.65f

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startX
                    v.translationX = deltaX.coerceIn(-maxSwipe, maxSwipe)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val currentTransX = v.translationX
                    if (currentTransX >= threshold) {
                        // Swiped right -> Answer
                        CallManager.answer(CallManager.currentCallId)
                    } else if (currentTransX <= -threshold) {
                        // Swiped left -> Reject
                        CallManager.reject(CallManager.currentCallId)
                    }
                    v.animate().translationX(0f).setDuration(200).start()
                    true
                }
                else -> false
            }
        }

        // Tap fallbacks
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
            binding.mute.text = if (isMuted) "Sesi Aç" else "Sessiz"
            binding.mute.strokeColor = ContextCompat.getColorStateList(
                this, if (isMuted) R.color.neon_amber else R.color.border_subtle
            )
        }
        binding.speaker.setOnClickListener {
            GatewayInCallService.toggleSpeaker { success ->
                runOnUiThread {
                    if (success) {
                        updateSpeakerLabel()
                        if (GatewayInCallService.isSpeakerOn() && proximityWakeLock?.isHeld == true) {
                            proximityWakeLock?.release()
                        }
                    } else {
                        binding.help.text = "Hoparlöre geçilemedi"
                    }
                }
            }
        }
        binding.keypadToggle.setOnClickListener {
            val visible = binding.dtmfContainer.visibility == View.VISIBLE
            binding.dtmfContainer.visibility = if (visible) View.GONE else View.VISIBLE
            binding.keypadToggle.text = if (visible) "Tuşlar" else "Tuşları Kapat"
        }
        binding.hangup.setOnClickListener {
            if (!CallManager.hangup(CallManager.currentCallId)) {
                binding.state.text = "Sonlandırılamadı"
            }
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

    private fun setupAudioTransfer() {
        binding.audioTransferButton.setOnClickListener {
            if (CallAudioRelay.isRedirectTrialActive) {
                CallAudioRelay.transferAudioToRedmi()
                return@setOnClickListener
            }
            if (CallAudioRelay.isAudioTransferUnavailable) {
                updateAudioCard(false)
                return@setOnClickListener
            }
            if (isAudioOnIPhone) {
                CallAudioRelay.transferAudioToRedmi()
            } else {
                CallAudioRelay.transferAudioToIPhone()
                binding.audioStatusDetail.text = "iPhone ses bağlantısı bekleniyor. Redmi'de konuşmaya devam edebilirsiniz."
            }
        }
    }

    private fun updateAudioCard(streaming: Boolean) {
        isAudioOnIPhone = streaming
        runOnUiThread {
            val redirectTrial = CallAudioRelay.isRedirectTrialActive
            binding.mute.isEnabled = !streaming && !redirectTrial
            if (streaming) {
                binding.audioTransferButton.visibility = View.VISIBLE
                binding.audioStatusCard.strokeColor = ContextCompat.getColor(this, R.color.neon_sky)
                binding.audioStatusTitle.text = "SES iPHONE'DA"
                binding.audioStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.neon_sky))
                binding.audioStatusDetail.text = "Ses iPhone'a yönlendirildi. Karşı taraf seni duymuyorsa sesi Redmi'ye al."
                binding.audioTransferButton.text = "Sesi Redmi'ye Al"
                binding.audioTransferButton.strokeColor = ContextCompat.getColorStateList(this, R.color.neon_sky)
            } else if (redirectTrial) {
                binding.audioStatusCard.strokeColor = ContextCompat.getColor(this, R.color.neon_sky)
                binding.audioStatusTitle.text = "SES iPHONE'A BAĞLANIYOR"
                binding.audioStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.neon_sky))
                binding.audioStatusDetail.text = "Karşı taraf seni duymuyorsa görüşmeye Redmi'den devam et."
                binding.audioTransferButton.visibility = View.VISIBLE
                binding.audioTransferButton.text = "Sesi Redmi'ye Al"
                binding.audioTransferButton.strokeColor = ContextCompat.getColorStateList(this, R.color.neon_sky)
            } else {
                val unavailable = CallAudioRelay.isAudioTransferUnavailable
                binding.audioStatusCard.strokeColor = ContextCompat.getColor(this, R.color.status_good)
                binding.audioStatusTitle.text = "SES BU TELEFONDA (REDMİ)"
                binding.audioStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_good))
                binding.audioStatusDetail.text = if (unavailable)
                    "iPhone görüşme sesi bu Redmi'de güvenilir biçimde yönlendirilemedi. Görüşmeye Redmi'den devam edin."
                else "Ahize veya hoparlörden konuşabilirsiniz."
                binding.audioTransferButton.visibility = if (unavailable) View.GONE else View.VISIBLE
                binding.audioTransferButton.text = "iPhone'a Aktar"
                binding.audioTransferButton.strokeColor = ContextCompat.getColorStateList(this, R.color.status_good)
            }
        }
    }

    override fun onAudioRelayState(running: Boolean, detail: String) {
        updateAudioCard(running)
    }

    override fun onCallStateChanged(callId: String, state: String, number: String?) {
        runOnUiThread {
            if (state == "idle" || state == "disconnected") {
                finish()
                return@runOnUiThread
            }
            binding.number.text = number?.takeIf { it.isNotBlank() } ?: "Gizli numara"
            if (number != lastResolvedNumber) {
                lastResolvedNumber = number
                resolveContact(number)
            }
            binding.state.text = when (state) {
                "ringing" -> "Gelen Arama"
                "active" -> "Görüşme Sürüyor"
                "dialing", "connecting" -> "Aranıyor..."
                "holding" -> "Beklemede"
                else -> "Arama Bağlanıyor"
            }
            val incoming = state == "ringing"
            if (state == "active") {
                if (activeSinceMillis == 0L) activeSinceMillis = System.currentTimeMillis()
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
            if (incoming) binding.dtmfContainer.visibility = View.GONE

            updateAudioCard(CallAudioRelay.isIPhoneAudioConfirmed)
            updateSpeakerLabel()

            binding.help.text = if (incoming)
                "Redmi'de yanıtla veya iPhone'da aç. Bağlantı yoksa Redmi çalmaya devam eder."
            else "Görüşmeyi bu telefondan da sonlandırabilirsiniz."
        }
    }

    override fun onLogMessage(message: String) = Unit

    private fun resolveContact(number: String?) {
        binding.contactName.visibility = View.GONE
        binding.contactPhoto.visibility = View.GONE
        binding.number.textSize = 28f
        if (number.isNullOrBlank() || checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(lookup, arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI
            ), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) {
                        binding.contactName.text = name
                        binding.contactName.visibility = View.VISIBLE
                        binding.number.textSize = 18f
                    }
                    cursor.getString(1)?.let { photo ->
                        binding.contactPhoto.setImageURI(Uri.parse(photo))
                        binding.contactPhoto.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun updateSpeakerLabel() {
        binding.speaker.text = if (GatewayInCallService.isSpeakerOn())
            "Ahizeye Al" else "Hoparlör"
    }
}
