package com.ilkay.redmigateway.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.LinkProperties
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
            CallManager.log("All requested permissions granted")
        } else {
            CallManager.log("Some permissions were denied: $permissions")
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
    }

    override fun onResume() {
        super.onResume()
        updateDialerStatus()
        updateNetworkIp()
        updateGatewayStatusUi()

        if (GatewayService.isRunning && !isBound) {
            val intent = Intent(this, GatewayService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
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
        binding.tvDeviceModel.text = "Device: Xiaomi Redmi Note 8 (ginkgo)"
        binding.tvPort.text = "8787"
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

        binding.btnAnswerCall.setOnClickListener {
            val success = CallManager.answer()
            if (!success) {
                Toast.makeText(this, "Failed to answer call", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRejectCall.setOnClickListener {
            val success = CallManager.reject()
            if (!success) {
                Toast.makeText(this, "Failed to reject call", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnHangupCall.setOnClickListener {
            val success = CallManager.hangup()
            if (!success) {
                Toast.makeText(this, "Failed to hang up call", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDialCall.setOnClickListener {
            val number = binding.etDialNumber.text?.toString() ?: ""
            if (number.isNotBlank()) {
                val success = CallManager.dial(this, number)
                if (success) {
                    binding.etDialNumber.text?.clear()
                } else {
                    Toast.makeText(this, "Failed to initiate call", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Please enter a phone number", Toast.LENGTH_SHORT).show()
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
        binding.tvProbeStatus.text = "Running probe: $sourceName (5s)..."
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
        binding.tvProbeStatus.text = "Starting sequential test of all 6 AudioSources (approx 35s)..."
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
            Manifest.permission.MODIFY_AUDIO_SETTINGS
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
                    Toast.makeText(this, "Redmi Gateway is already default dialer", Toast.LENGTH_SHORT).show()
                    updateDialerStatus()
                    return
                }
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                dialerRoleLauncher.launch(intent)
                return
            }
        }

        // Fallback for earlier versions or standard telecom intent
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecomManager != null && packageName != telecomManager.defaultDialerPackage) {
            @Suppress("DEPRECATION")
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            dialerRoleLauncher.launch(intent)
        } else {
            Toast.makeText(this, "Already default dialer", Toast.LENGTH_SHORT).show()
            updateDialerStatus()
        }
    }

    private fun updateDialerStatus() {
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val isDefault = telecomManager?.defaultDialerPackage == packageName
        if (isDefault) {
            binding.tvDialerStatus.text = "YES"
            binding.tvDialerStatus.setTextColor(Color.parseColor("#22C55E"))
            binding.btnRequestDialer.visibility = View.GONE
        } else {
            binding.tvDialerStatus.text = "NO"
            binding.tvDialerStatus.setTextColor(Color.parseColor("#EF4444"))
            binding.btnRequestDialer.visibility = View.VISIBLE
        }
    }

    private fun updateNetworkIp() {
        val ip = getLocalIpAddress()
        binding.tvLocalIp.text = ip ?: "0.0.0.0"
    }

    private fun getLocalIpAddress(): String? {
        var selectedIp: String? = null
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
                        CallManager.log("Network discovered: ${iface.name} -> $host")
                        // Prioritize Wi-Fi / SoftAP interfaces (wlan, ap) or private hotspot subnets (192.168.x.x)
                        if (iface.name.contains("wlan") || iface.name.contains("ap") || host.startsWith("192.168.")) {
                            if (selectedIp == null || iface.name.startsWith("wlan") || iface.name.startsWith("ap")) {
                                selectedIp = host
                            }
                        } else if (selectedIp == null) {
                            selectedIp = host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            CallManager.log("Error determining IP: ${e.message}")
        }
        return selectedIp
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
            binding.tvGatewayStatus.text = "RUNNING"
            binding.tvGatewayStatus.setTextColor(Color.parseColor("#22C55E"))
            binding.btnStartGateway.isEnabled = false
            binding.btnStopGateway.isEnabled = true
        } else {
            binding.tvGatewayStatus.text = "STOPPED"
            binding.tvGatewayStatus.setTextColor(Color.parseColor("#EF4444"))
            binding.btnStartGateway.isEnabled = true
            binding.btnStopGateway.isEnabled = false
        }

        val clientCount = gatewayService?.getAuthenticatedClientCount() ?: 0
        binding.tvClientsCount.text = "$clientCount"
    }

    // CallManager.CallListener implementation
    override fun onCallStateChanged(callId: String, state: String, number: String?) {
        mainHandler.post {
            binding.tvCallState.text = state.uppercase(Locale.getDefault())
            binding.tvCallNumber.text = if (!number.isNullOrBlank()) number else "None"
            binding.tvCallId.text = if (callId != "none") callId else "None"

            binding.tvAudioProbeCallState.text = "CALL: ${state.uppercase(Locale.getDefault())}"

            when (state.lowercase(Locale.getDefault())) {
                "ringing" -> {
                    binding.tvCallState.setTextColor(Color.parseColor("#F59E0B"))
                    binding.tvAudioProbeCallState.setTextColor(Color.parseColor("#F59E0B"))
                    binding.layoutIncomingActions.visibility = View.VISIBLE
                    binding.btnHangupCall.visibility = View.GONE
                }
                "active" -> {
                    binding.tvCallState.setTextColor(Color.parseColor("#22C55E"))
                    binding.tvAudioProbeCallState.setTextColor(Color.parseColor("#22C55E"))
                    binding.layoutIncomingActions.visibility = View.GONE
                    binding.btnHangupCall.visibility = View.VISIBLE
                }
                "dialing", "connecting", "holding" -> {
                    binding.tvCallState.setTextColor(Color.parseColor("#38BDF8"))
                    binding.tvAudioProbeCallState.setTextColor(Color.parseColor("#38BDF8"))
                    binding.layoutIncomingActions.visibility = View.GONE
                    binding.btnHangupCall.visibility = View.VISIBLE
                }
                "disconnected", "idle" -> {
                    binding.tvCallState.setTextColor(Color.parseColor("#94A3B8"))
                    binding.tvAudioProbeCallState.setTextColor(Color.parseColor("#94A3B8"))
                    binding.layoutIncomingActions.visibility = View.GONE
                    binding.btnHangupCall.visibility = View.GONE
                }
                else -> {
                    binding.tvCallState.setTextColor(Color.parseColor("#94A3B8"))
                    binding.tvAudioProbeCallState.setTextColor(Color.parseColor("#94A3B8"))
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
