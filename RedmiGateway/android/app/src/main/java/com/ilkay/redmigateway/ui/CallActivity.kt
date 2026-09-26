package com.ilkay.redmigateway.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.ilkay.redmigateway.databinding.ActivityCallBinding
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.telecom.GatewayInCallService

/** A single clear answer screen when this app is the Redmi's default dialer. */
class CallActivity : AppCompatActivity(), CallManager.CallListener {
    private lateinit var binding: ActivityCallBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.answer.setOnClickListener {
            if (!CallManager.answer(CallManager.currentCallId)) binding.state.text = "Yanıtlanamadı"
        }
        binding.reject.setOnClickListener {
            if (!CallManager.reject(CallManager.currentCallId)) binding.state.text = "Reddedilemedi"
        }
        binding.hangup.setOnClickListener {
            if (!CallManager.hangup(CallManager.currentCallId)) binding.state.text = "Sonlandırılamadı"
        }
        binding.speaker.setOnClickListener {
            GatewayInCallService.toggleSpeaker { success ->
                runOnUiThread {
                    if (success) updateSpeakerLabel()
                    else binding.help.text = "Hoparlöre geçilemedi"
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        CallManager.addListener(this)
        updateSpeakerLabel()
    }

    override fun onStop() {
        CallManager.removeListener(this)
        super.onStop()
    }

    override fun onCallStateChanged(callId: String, state: String, number: String?) {
        runOnUiThread {
            if (state == "idle" || state == "disconnected") {
                finish()
                return@runOnUiThread
            }
            binding.number.text = number?.takeIf { it.isNotBlank() } ?: "Gizli numara"
            binding.state.text = when (state) {
                "ringing" -> "Gelen arama"
                "active" -> "Görüşme sürüyor"
                "dialing", "connecting" -> "Aranıyor"
                "holding" -> "Beklemede"
                else -> "Arama bağlanıyor"
            }
            val incoming = state == "ringing"
            binding.incomingActions.visibility = if (incoming) View.VISIBLE else View.GONE
            binding.hangup.visibility = if (incoming) View.GONE else View.VISIBLE
            binding.speaker.visibility = if (state == "active") View.VISIBLE else View.GONE
            binding.help.text = if (incoming)
                "Redmi'de yanıtla veya iPhone'da aç. Bağlantı yoksa Redmi çalmaya devam eder."
            else "Görüşmeyi bu telefondan da sonlandırabilirsin."
        }
    }

    override fun onLogMessage(message: String) = Unit

    private fun updateSpeakerLabel() {
        binding.speaker.text = if (GatewayInCallService.isSpeakerOn())
            "Ahizeye al" else "Hoparlöre al"
    }
}
