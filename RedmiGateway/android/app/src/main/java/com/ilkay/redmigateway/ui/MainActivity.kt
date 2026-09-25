package com.ilkay.redmigateway.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ilkay.redmigateway.RedmiGatewayApp
import com.ilkay.redmigateway.audio.AudioProbeManager
import com.ilkay.redmigateway.databinding.ActivityMainBinding
import com.ilkay.redmigateway.service.GatewayService
import com.ilkay.redmigateway.telecom.CallManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), CallManager.CallListener {

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gatewayService: GatewayService? = null
    private var isBound = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val statusRefresh = object : Runnable {
        override fun run() {
            updateNetworkIp()
            updateGatewayStatusUi()
            mainHandler.postDelayed(this, 15_000)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)

                val pct = if (level >= 0 && scale > 0) (level * 100) / scale else level
                val tempC = if (tempTenths > 0) tempTenths / 10.0 else 0.0
                val chargeStr = when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC Şarj"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB Şarj"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Kablosuz Şarj"
                    else -> "Pilde"
                }

                binding.tvBatteryStats.text = "%d%% • %.1f°C • %s".format(Locale.getDefault(), pct, tempC, chargeStr)
            }
        }
    }

    private val dialerRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateDialerStatus()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            CallManager.log("Tüm izinler başarıyla verildi.")
        } else {
            CallManager.log("Bazı izinler reddedildi: $permissions")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GatewayService.LocalBinder
            gatewayService = binder.getService()
            isBound = true
            updateGatewayStatusUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gatewayService = null
            isBound = false
            updateGatewayStatusUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupStaticInfo()
        setupListeners()
        requestAppPermissions()

        CallManager.addListener(this)
        startGatewayService()
    }

    override fun onResume() {
        super.onResume()
        updateDialerStatus()
        updateNetworkIp()
        updateGatewayStatusUi()

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        mainHandler.post(statusRefresh)

        if (!GatewayService.isRunning) {
            startGatewayService()
        } else if (!isBound) {
            val intent = Intent(this, GatewayService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        mainHandler.removeCallbacks(statusRefresh)
    }

    override fun onDestroy() {
        CallManager.removeListener(this)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun setupStaticInfo() {
        binding.tvDeviceModel.text = "${Build.MANUFACTURER} ${Build.MODEL}"
        binding.tvPort.text = "8787 / 8788"
        binding.tvPairingToken.text = RedmiGatewayApp.getPairingToken()
    }

    private fun setupListeners() {
        binding.btnRequestDialer.setOnClickListener {
            requestDefaultDialerRole()
        }

        binding.btnStartGateway.setOnClickListener {
            startGatewayService()
        }

        binding.btnStopGateway.setOnClickListener {
            stopGatewayService()
        }

        binding.btnCopyToken.setOnClickListener {
            val token = binding.tvPairingToken.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Gateway Token", token))
            Toast.makeText(this, "Kod kopyalandı", Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenWebUi.setOnClickListener {
            val ip = getLocalIpAddress() ?: return@setOnClickListener
            val url = "http://$ip:8788"
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (e: Exception) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Web UI URL", url))
                Toast.makeText(this, "Web adresi kopyalandı: $url", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearLogs.setOnClickListener {
            binding.tvEventLog.text = "[KONSOL] Temizlendi.\n"
        }

        binding.btnAnswerCall.setOnClickListener {
            val success = CallManager.answer()
            if (!success) {
                Toast.makeText(this, "Arama yanıtlanamadı", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRejectCall.setOnClickListener {
            val success = CallManager.reject()
            if (!success) {
                Toast.makeText(this, "Arama reddedilemedi", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnHangupCall.setOnClickListener {
            val success = CallManager.hangup()
            if (!success) {
                Toast.makeText(this, "Arama sonlandırılamadı", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDialCall.setOnClickListener {
            val number = binding.etDialNumber.text?.toString() ?: ""
            if (number.isNotBlank()) {
                val success = CallManager.dial(this, number)
                if (success) {
                    binding.etDialNumber.text?.clear()
                } else {
                    Toast.makeText(this, "Arama başlatılamadı", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Lütfen bir telefon numarası girin", Toast.LENGTH_SHORT).show()
            }
        }

        // Audio Probe Listeners
        binding.btnProbeMic.setOnClickListener { runSingleProbe("MIC") }
        binding.btnProbeVoiceComm.setOnClickListener { runSingleProbe("VOICE_COMMUNICATION") }
        binding.btnProbeVoiceCall.setOnClickListener { runSingleProbe("VOICE_CALL") }
        binding.btnProbeVoiceUplink.setOnClickListener { runSingleProbe("VOICE_UPLINK") }
        binding.btnProbeVoiceDownlink.setOnClickListener { runSingleProbe("VOICE_DOWNLINK") }
        binding.btnProbeRemoteSubmix.setOnClickListener { runSingleProbe("REMOTE_SUBMIX") }
        binding.btnProbeAll.setOnClickListener { runAllProbes() }
    }

    private fun runSingleProbe(sourceName: String) {
        binding.tvProbeStatus.text = "Test başlatıldı: $sourceName (5s)..."
        Thread {
            val res = AudioProbeManager.runProbeForSource(sourceName)
            mainHandler.post {
                val line = "${res.sourceName}: ${res.status} | RMS=%.1f | Peak=%d | %s".format(
                    res.rmsEnergy, res.peakAmplitude, res.classification
                )
                binding.tvProbeStatus.text = line
            }
        }.start()
    }

    private fun runAllProbes() {
        binding.btnProbeAll.isEnabled = false
        binding.tvProbeStatus.text = "Tüm 6 ses kaynağı taranıyor (yaklaşık 35 sn)..."
        Thread {
            val sb = StringBuilder()
            val results = AudioProbeManager.runAllProbes()
            for (r in results) {
                sb.append("%-18s: %s (RMS=%.1f, Peak=%d, Class=%s)\n".format(
                    r.sourceName, r.status, r.rmsEnergy, r.peakAmplitude, r.classification
                ))
            }
            mainHandler.post {
                binding.btnProbeAll.isEnabled = true
                binding.tvProbeStatus.text = sb.toString().trimEnd()
            }
        }.start()
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    Toast.makeText(this, "Telefon Geçidi zaten varsayılan arama uygulaması", Toast.LENGTH_SHORT).show()
                    updateDialerStatus()
                    return
                }
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                dialerRoleLauncher.launch(intent)
                return
            }
        }

        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecomManager != null && packageName != telecomManager.defaultDialerPackage) {
            @Suppress("DEPRECATION")
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            dialerRoleLauncher.launch(intent)
        } else {
            Toast.makeText(this, "Zaten varsayılan arama uygulaması", Toast.LENGTH_SHORT).show()
            updateDialerStatus()
        }
    }

    private fun updateDialerStatus() {
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val isDefault = telecomManager?.defaultDialerPackage == packageName
        if (isDefault) {
            binding.chipDialer.text = "Arama izni verildi"
            binding.chipDialer.setTextColor(ContextCompat.getColor(this, com.ilkay.redmigateway.R.color.status_good))
            binding.btnRequestDialer.visibility = View.GONE
        } else {
            binding.chipDialer.text = "Arama izni gerekli"
            binding.chipDialer.setTextColor(ContextCompat.getColor(this, com.ilkay.redmigateway.R.color.status_bad))
            binding.btnRequestDialer.visibility = View.VISIBLE
        }
    }

    private fun updateNetworkIp() {
        val ip = getLocalIpAddress()
        binding.tvLocalIp.text = if (ip == null) "İnternet paylaşımı kapalı" else "http://$ip:8788"
        binding.btnOpenWebUi.isEnabled = ip != null
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if ((iface.name.startsWith("wlan") || iface.name.startsWith("ap")) && addr.isSiteLocalAddress) return host
                    }
                }
            }
        } catch (e: Exception) {
            CallManager.log("IP tespiti hatası: ${e.message}")
        }
        return null
    }

    private fun startGatewayService() {
        val intent = Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        updateGatewayStatusUi()
    }

    private fun stopGatewayService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        val intent = Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_STOP
        }
        startService(intent)
        updateGatewayStatusUi()
    }

    private fun updateGatewayStatusUi() {
        val running = GatewayService.isRunning
        if (running) {
            binding.tvGatewayStatus.text = "Çalışıyor"
            binding.tvGatewayStatus.setTextColor(ContextCompat.getColor(this, com.ilkay.redmigateway.R.color.status_good))
            binding.ivStatusDot.setBackgroundResource(com.ilkay.redmigateway.R.drawable.bg_pulse_dot)
            binding.btnStartGateway.isEnabled = false
            binding.btnStartGateway.visibility = View.GONE
            binding.btnStopGateway.isEnabled = true
        } else {
            binding.tvGatewayStatus.text = "Bağlantı kapalı"
            binding.tvGatewayStatus.setTextColor(ContextCompat.getColor(this, com.ilkay.redmigateway.R.color.status_bad))
            binding.btnStartGateway.isEnabled = true
            binding.btnStartGateway.visibility = View.VISIBLE
            binding.btnStopGateway.isEnabled = false
        }

        val clientCount = gatewayService?.getAuthenticatedClientCount() ?: 0
        binding.tvClientsCount.text = "$clientCount cihaz bağlı"
    }

    // CallManager.CallListener implementation
    override fun onCallStateChanged(callId: String, state: String, number: String?) {
        mainHandler.post {
            val stateUpper = state.uppercase(Locale.getDefault())
            binding.tvCallNumber.text = if (state == "idle" || state == "disconnected") "" else (number?.takeIf { it.isNotBlank() } ?: "Gizli numara")
            binding.tvCallId.text = if (callId != "none") "Arama kimliği: $callId" else "Arama yok"
            binding.tvAudioProbeCallState.text = "Arama: $stateUpper"

            when (state.lowercase(Locale.getDefault())) {
                "ringing" -> {
                    binding.tvCallState.text = "Gelen arama"
                    binding.tvCallState.setTextColor(ContextCompat.getColor(this, com.ilkay.redmigateway.R.color.status_attention))
                    binding.tvAudioProbeCallState.setTextColor(ContextCompat.getColor(this, com.ilkay.redmigateway.R.color.status_attention))
                    binding.layoutIncomingActions.visibility = View.VISIBLE
                    binding.btnHangupCall.visibility = View.GONE
                }
                "active" -> {
                    binding.tvCallState.text = "Görüşme sürüyor"
                    binding.tvCallState.setTextColor(ContextCompat.getColor(this, com.ilkay.redmigateway.R.color.status_good))
                    binding.tvAudioProbeCallState.setTextColor(ContextCompat.getColor(this, com.ilkay.redmigateway.R.color.status_good))
                    binding.layoutIncomingActions.visibility = View.GONE
                    binding.btnHangupCall.visibility = View.VISIBLE
                }
                "dialing", "connecting", "holding" -> {
                    binding.tvCallState.text = "Bağlanıyor"
                    binding.tvCallState.setTextColor(ContextCompat.getColor(this, com.ilkay.redmigateway.R.color.accent))
                    binding.tvAudioProbeCallState.setTextColor(ContextCompat.getColor(this, com.ilkay.redmigateway.R.color.accent))
                    binding.layoutIncomingActions.visibility = View.GONE
                    binding.btnHangupCall.visibility = View.VISIBLE
                }
                "disconnected", "idle" -> {
                    binding.tvCallState.text = "Arama yok"
                    binding.tvCallState.setTextColor(ContextCompat.getColor(this, com.ilkay.redmigateway.R.color.text_secondary))
                    binding.tvAudioProbeCallState.setTextColor(ContextCompat.getColor(this, com.ilkay.redmigateway.R.color.text_secondary))
                    binding.layoutIncomingActions.visibility = View.GONE
                    binding.btnHangupCall.visibility = View.GONE
                }
                else -> {
                    binding.tvCallState.text = stateUpper
                    binding.tvCallState.setTextColor(ContextCompat.getColor(this, com.ilkay.redmigateway.R.color.text_secondary))
                    binding.tvAudioProbeCallState.setTextColor(ContextCompat.getColor(this, com.ilkay.redmigateway.R.color.text_secondary))
                    binding.layoutIncomingActions.visibility = View.GONE
                    binding.btnHangupCall.visibility = View.GONE
                }
            }
        }
    }

    override fun onLogMessage(message: String) {
        mainHandler.post {
            val timestamp = timeFormat.format(Date())
            binding.tvEventLog.append("[$timestamp] $message\n")
            binding.scrollEventLog.post {
                binding.scrollEventLog.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
}
