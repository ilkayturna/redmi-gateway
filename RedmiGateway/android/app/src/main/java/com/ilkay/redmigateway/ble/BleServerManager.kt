package com.ilkay.redmigateway.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.ilkay.redmigateway.RedmiGatewayApp
import com.ilkay.redmigateway.security.BridgeCrypto
import com.ilkay.redmigateway.security.PairingStore
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.telecom.IncomingCallPresenter
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Bluetooth LE companion: presence, the current IP/port for discovery, and a wake-up path.
 *
 * iOS keeps a Bluetooth connection alive for a suspended app and wakes it on notifications, so
 * the call-state characteristic is how a locked iPhone learns about an incoming call. Once an
 * iPhone is paired with a key (protocol 2), state notifications are sealed with AES-GCM and call
 * control writes must be sealed, fresh and sequence-increasing; plain JSON is only accepted while
 * no keyed pairing exists. BLE never carries call audio.
 */
class BleServerManager(private val context: Context) : CallManager.CallListener {

    companion object {
        private const val TAG = "BleServerManager"
        private const val SEALED_VERSION: Byte = 2
        private const val MAX_CONTROL_AGE_MS = 60_000L
        private val BLE_STATE_AAD = "phonebridge-ble-state".toByteArray(Charsets.UTF_8)
        private val BLE_CONTROL_AAD = "phonebridge-ble-control".toByteArray(Charsets.UTF_8)
        @Volatile private var current: BleServerManager? = null

        fun diagnostics(): JSONObject {
            val server = current ?: return JSONObject().put("running", false)
            return JSONObject()
                .put("running", server.isServerRunning)
                .put("advertising", server.isAdvertising)
                .put("centrals", server.connectedDevices.size)
                .put("subscribed", server.subscribedDevices.count { it.value.contains(BleConstants.CHAR_STATE_UUID) })
                .put("sealed", PairingStore.pairedClient() != null)
        }
    }

    private var lastControlSeq = 0L
    private val mtuByDevice = java.util.concurrent.ConcurrentHashMap<String, Int>()
    // A long read continues at an offset; it must see the same sealed bytes as its first part.
    @Volatile private var lastStateRead = ByteArray(0)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activePort: Int = 8787
    @Volatile
    private var activeIp: String? = null

    private val connectedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val subscribedDevices = ConcurrentHashMap<BluetoothDevice, MutableSet<java.util.UUID>>()
    private val clientLastSeq = ConcurrentHashMap<String, Long>()
    private val stateSeq = AtomicLong(0)

    private data class PreparedWriteEntry(
        val characteristic: BluetoothGattCharacteristic,
        val offset: Int,
        val value: ByteArray
    )
    private val preparedWrites = ConcurrentHashMap<BluetoothDevice, MutableList<PreparedWriteEntry>>()

    private var isAdvertising = false
    private var isServerRunning = false
    private var shouldBeRunning = false
    private var isReceiverRegistered = false

    private var charDeviceInfo: BluetoothGattCharacteristic? = null
    private var charState: BluetoothGattCharacteristic? = null
    private var charCallControl: BluetoothGattCharacteristic? = null
    private var charHeartbeat: BluetoothGattCharacteristic? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "Bluetooth turned ON: recovering BLE companion server and advertiser")
                        if (shouldBeRunning) {
                            mainHandler.postDelayed({
                                restartBleServer()
                            }, 500L)
                        }
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        Log.i(TAG, "Bluetooth turned OFF: safely tearing down BLE GATT server and advertiser")
                        tearDownGattServerAndAdvertiser()
                    }
                }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.w(TAG, "BLE advertising failed to start, error code: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE client connected: ${device.address}")
                connectedDevices.add(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BLE client disconnected: ${device.address}")
                mtuByDevice.remove(device.address)
                connectedDevices.remove(device)
                subscribedDevices.remove(device)
                preparedWrites.remove(device)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtuByDevice[device.address] = mtu
            Log.i(TAG, "BLE MTU $mtu")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (!hasConnectPermission()) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }

            val value = when (characteristic.uuid) {
                BleConstants.CHAR_DEVICE_INFO_UUID -> buildDeviceInfoJson().toByteArray(Charsets.UTF_8)
                BleConstants.CHAR_STATE_UUID ->
                    if (offset == 0) buildStateBytes().also { lastStateRead = it } else lastStateRead
                BleConstants.CHAR_HEARTBEAT_UUID -> System.currentTimeMillis().toString().toByteArray(Charsets.UTF_8)
                else -> null
            }

            if (value != null) {
                val sliced = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, sliced)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (!hasConnectPermission()) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
                return
            }

            // ADV-04: Support prepared write chunks for payloads exceeding MTU
            if (preparedWrite) {
                if (value != null) {
                    val list = preparedWrites.computeIfAbsent(device) {
                        Collections.synchronizedList(mutableListOf())
                    }
                    list.add(PreparedWriteEntry(characteristic, offset, value))
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                return
            }

            var success = false
            var responseBytes: ByteArray? = null

            if (characteristic.uuid == BleConstants.CHAR_CALL_CONTROL_UUID && value != null) {
                responseBytes = handleCallControlWrite(device, value)
                success = true
            } else if (characteristic.uuid == BleConstants.CHAR_HEARTBEAT_UUID && value != null) {
                success = true
                responseBytes = value
            }

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (success) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    offset,
                    responseBytes
                )
            }

            if (characteristic.uuid == BleConstants.CHAR_CALL_CONTROL_UUID && responseBytes != null) {
                notifyCharacteristicToSubscribers(characteristic, responseBytes)
            }
        }

        override fun onExecuteWrite(
            device: BluetoothDevice,
            requestId: Int,
            execute: Boolean
        ) {
            if (!hasConnectPermission()) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }

            val entries = preparedWrites.remove(device)
            if (!execute || entries.isNullOrEmpty()) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                return
            }

            val byChar = entries.groupBy { it.characteristic }
            for ((characteristic, chunkList) in byChar) {
                val sorted = chunkList.sortedBy { it.offset }
                val totalSize = sorted.sumOf { it.value.size }
                val combined = ByteArray(totalSize)
                var curr = 0
                for (chunk in sorted) {
                    System.arraycopy(chunk.value, 0, combined, curr, chunk.value.size)
                    curr += chunk.value.size
                }

                var responseBytes: ByteArray? = null
                if (characteristic.uuid == BleConstants.CHAR_CALL_CONTROL_UUID) {
                    responseBytes = handleCallControlWrite(device, combined)
                    if (responseBytes != null) {
                        notifyCharacteristicToSubscribers(characteristic, responseBytes)
                    }
                } else if (characteristic.uuid == BleConstants.CHAR_HEARTBEAT_UUID) {
                    responseBytes = combined
                }
            }

            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (!hasConnectPermission()) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }

            if (descriptor.uuid == BleConstants.CCCD_UUID) {
                val charUuid = descriptor.characteristic.uuid
                val subs = subscribedDevices[device]
                val isSubbed = subs?.contains(charUuid) == true
                val response = if (isSubbed) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (!hasConnectPermission()) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
                return
            }

            if (descriptor.uuid == BleConstants.CCCD_UUID && value != null) {
                val charUuid = descriptor.characteristic.uuid
                val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)

                val subs = subscribedDevices.computeIfAbsent(device) { ConcurrentHashMap.newKeySet() }
                if (enabled) {
                    subs.add(charUuid)
                } else {
                    subs.remove(charUuid)
                }
                if (charUuid == BleConstants.CHAR_STATE_UUID) {
                    Log.i(TAG, "BLE call-state updates ${if (enabled) "enabled" else "disabled"}")
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }
    }

    fun start(port: Int) {
        current = this
        this.activePort = port
        this.shouldBeRunning = true
        registerBluetoothReceiver()
        restartBleServer()
    }

    fun updatePort(port: Int) {
        this.activePort = port
    }

    fun updateActiveIp(ip: String) {
        val changed = (this.activeIp != ip)
        this.activeIp = ip
        Log.i(TAG, "Active IP updated: $ip (changed=$changed)")
        if (changed && isServerRunning) {
            charDeviceInfo?.let { char ->
                val bytes = buildDeviceInfoJson().toByteArray(Charsets.UTF_8)
                notifyCharacteristicToSubscribers(char, bytes)
            }
            charState?.let { char -> notifyCharacteristicToSubscribers(char, buildStateBytes()) }
        }
    }

    fun stop() {
        if (current === this) current = null
        this.shouldBeRunning = false
        unregisterBluetoothReceiver()
        CallManager.removeListener(this)
        tearDownGattServerAndAdvertiser()
        Log.i(TAG, "BleServerManager stopped")
    }

    private fun registerBluetoothReceiver() {
        if (!isReceiverRegistered) {
            try {
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                context.registerReceiver(bluetoothReceiver, filter)
                isReceiverRegistered = true
                Log.d(TAG, "Registered BluetoothAdapter state receiver")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register bluetoothReceiver: ${e.message}")
            }
        }
    }

    private fun unregisterBluetoothReceiver() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothReceiver)
                isReceiverRegistered = false
                Log.d(TAG, "Unregistered BluetoothAdapter state receiver")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister bluetoothReceiver: ${e.message}")
            }
        }
    }

    private fun restartBleServer() {
        if (!shouldBeRunning) return
        tearDownGattServerAndAdvertiser()

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is not available or disabled; BLE companion will start automatically once enabled")
            return
        }

        if (!hasConnectPermission() || !hasAdvertisePermission()) {
            Log.w(TAG, "Missing Bluetooth permissions; BLE companion server cannot start")
            return
        }

        try {
            setupGattServer()
            startAdvertising()
            CallManager.addListener(this)
            isServerRunning = true
            Log.i(TAG, "BleServerManager started for port $activePort")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BleServerManager: ${e.message}", e)
        }
    }

    private fun tearDownGattServerAndAdvertiser() {
        stopAdvertising()
        CallManager.removeListener(this)
        try {
            gattServer?.clearServices()
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT server: ${e.message}")
        }
        gattServer = null
        connectedDevices.clear()
        subscribedDevices.clear()
        clientLastSeq.clear()
        preparedWrites.clear()
        isServerRunning = false
    }

    private fun setupGattServer() {
        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback) ?: return

        val service = BluetoothGattService(
            BleConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // 1. Device Info (Read + Notify)
        val devInfo = BluetoothGattCharacteristic(
            BleConstants.CHAR_DEVICE_INFO_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(createCccdDescriptor())
        }
        charDeviceInfo = devInfo
        service.addCharacteristic(devInfo)

        // 2. Call State (Read + Notify)
        val stateChar = BluetoothGattCharacteristic(
            BleConstants.CHAR_STATE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(createCccdDescriptor())
        }
        charState = stateChar
        service.addCharacteristic(stateChar)

        // 3. Call Control (Write + Write_No_Response + Notify)
        val controlChar = BluetoothGattCharacteristic(
            BleConstants.CHAR_CALL_CONTROL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            addDescriptor(createCccdDescriptor())
        }
        charCallControl = controlChar
        service.addCharacteristic(controlChar)

        // 4. Heartbeat (Read + Write + Notify)
        val hbChar = BluetoothGattCharacteristic(
            BleConstants.CHAR_HEARTBEAT_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            addDescriptor(createCccdDescriptor())
        }
        charHeartbeat = hbChar
        service.addCharacteristic(hbChar)

        gattServer?.addService(service)
    }

    private fun createCccdDescriptor(): BluetoothGattDescriptor {
        return BluetoothGattDescriptor(
            BleConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
    }

    private fun startAdvertising() {
        if (advertiser == null) {
            advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        }
        if (advertiser == null) {
            Log.w(TAG, "BluetoothLeAdvertiser is not supported on this device")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val opaqueId = RedmiGatewayApp.getOpaqueDeviceId()
        val idBytes = opaqueId.toByteArray(Charsets.UTF_8)

        // Primary advertisement (18 bytes): 128-bit Service UUID (fits within 31-byte legacy limit)
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        // Scan response (26 bytes): 128-bit Service Data containing opaque device ID (merged by CoreBluetooth)
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(BleConstants.SERVICE_UUID), idBytes)
            .build()

        try {
            advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE advertising: ${e.message}", e)
        }
    }

    private fun stopAdvertising() {
        if (isAdvertising) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping BLE advertising: ${e.message}")
            }
            isAdvertising = false
        }
    }

    private fun bleKey(): ByteArray? = PairingStore.pairedClient()?.key?.let { BridgeCrypto.derive(it, "ble") }

    /** Answers are sealed the same way as the request, so only the paired iPhone can read them. */
    private fun controlReply(key: ByteArray?, action: String, callId: String, seq: Long, success: Boolean, error: String? = null): ByteArray {
        val json = JSONObject().put("ack", action).put("callId", callId).put("seq", seq).put("success", success)
        if (error != null) json.put("error", error)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        return if (key == null) bytes else byteArrayOf(SEALED_VERSION) + BridgeCrypto.seal(key, bytes, BLE_CONTROL_AAD)
    }

    private fun handleCallControlWrite(device: BluetoothDevice, value: ByteArray): ByteArray {
        val key = bleKey()
        val json: JSONObject
        if (key != null) {
            // Keyed pairing exists: only sealed, fresh, increasing commands are accepted.
            if (value.isEmpty() || value[0] != SEALED_VERSION) {
                Log.w(TAG, "Rejected an unsealed BLE command")
                return controlReply(null, "unknown", "", 0, false, "unauthorized")
            }
            val plain = BridgeCrypto.open(key, value.copyOfRange(1, value.size), BLE_CONTROL_AAD)
                ?: return controlReply(null, "unknown", "", 0, false, "unauthorized").also {
                    Log.w(TAG, "Rejected a BLE command that failed authentication")
                }
            json = try { JSONObject(String(plain, Charsets.UTF_8)) } catch (_: Exception) {
                return controlReply(key, "unknown", "", 0, false, "malformed")
            }
            val seq = json.optLong("q", 0)
            val sentAt = json.optLong("t", 0)
            if (seq <= lastControlSeq || kotlin.math.abs(System.currentTimeMillis() - sentAt) > MAX_CONTROL_AGE_MS) {
                return controlReply(key, json.optString("a"), json.optString("id"), seq, false, "stale")
            }
            lastControlSeq = seq
        } else {
            if (!PairingStore.legacyAuthAllowed) return controlReply(null, "unknown", "", 0, false, "unauthorized")
            json = try { JSONObject(String(value, Charsets.UTF_8)) } catch (_: Exception) {
                return controlReply(null, "unknown", "", 0, false, "malformed")
            }
        }
        val action = (json.optString("a").ifBlank { json.optString("action") }).lowercase()
        val callId = json.optString("id").ifBlank { json.optString("callId") }
        val seq = json.optLong("q", json.optLong("seq", 0))
        val executed = when (action) {
            "answer" -> CallManager.answer(callId.ifEmpty { null })
            "reject" -> CallManager.reject(callId.ifEmpty { null })
            "hangup" -> CallManager.hangup(callId.ifEmpty { null })
            "ringing_ack" -> {
                IncomingCallPresenter.onIPhonePresented(callId)
                true
            }
            else -> false
        }
        Log.i(TAG, "BLE command $action executed=$executed")
        return controlReply(key, action, callId, seq, executed)
    }

    private fun buildDeviceInfoJson(): String {
        val ip = activeIp ?: NetworkUtils.getLocalIPv4Address() ?: ""
        return JSONObject().apply {
            put("id", RedmiGatewayApp.getOpaqueDeviceId())
            put("ip", ip)
            put("port", activePort)
            put("v", 2)
            put("ble", if (PairingStore.pairedClient() != null) 2 else 1)
        }.toString()
    }

    /**
     * Current call state for the iPhone. Sealed (version byte 2, nonce, ciphertext, tag) once a
     * keyed pairing exists; compact keys keep it within a typical iOS notification MTU.
     */
    private fun buildStateBytes(): ByteArray {
        val key = bleKey()
        val state = JSONObject()
            .put("s", CallManager.currentCallState)
            .put("id", CallManager.currentCallId ?: "")
            .put("d", CallManager.currentDirection.take(1))
            .put("q", stateSeq.incrementAndGet())
            .put("t", System.currentTimeMillis())
        CallManager.currentPhoneNumber?.takeIf { it.isNotBlank() }?.let { state.put("n", it) }
        if (key != null) {
            return byteArrayOf(SEALED_VERSION) + BridgeCrypto.seal(key, state.toString().toByteArray(Charsets.UTF_8), BLE_STATE_AAD)
        }
        if (!PairingStore.legacyAuthAllowed) return ByteArray(0)
        // Protocol 1 layout for iPhone builds up to 1.0 (33).
        return JSONObject()
            .put("state", CallManager.currentCallState)
            .put("callId", CallManager.currentCallId ?: "")
            .put("number", CallManager.currentPhoneNumber ?: "")
            .put("seq", state.getLong("q"))
            .put("ts", state.getLong("t"))
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun notifyCharacteristicToSubscribers(
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray
    ) {
        if (!hasConnectPermission()) return
        characteristic.value = bytes
        if (characteristic.uuid == BleConstants.CHAR_STATE_UUID) {
            val targets = connectedDevices.count { subscribedDevices[it]?.contains(characteristic.uuid) == true }
            Log.i(TAG, "BLE call state notified to $targets device(s), ${bytes.size} bytes")
        }
        for (device in connectedDevices) {
            val subs = subscribedDevices[device]
            if (subs?.contains(characteristic.uuid) == true) {
                val mtu = mtuByDevice[device.address] ?: 23
                if (bytes.size > mtu - 3) Log.w(TAG, "Notification of ${bytes.size} bytes exceeds MTU $mtu")
                try {
                    gattServer?.notifyCharacteristicChanged(device, characteristic, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to notify characteristic to ${device.address}: ${e.message}")
                }
            }
        }
    }

    // CallManager.CallListener implementation
    override fun onCallStateChanged(callId: String, state: String, number: String?) {
        charState?.let { notifyCharacteristicToSubscribers(it, buildStateBytes()) }
    }

    override fun onLogMessage(message: String) {}

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
