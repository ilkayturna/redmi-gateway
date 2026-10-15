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
class BleServerManager(private val context: Context) : CallManager.CallListener, CallManager.WaitingListener {

    companion object {
        private const val TAG = "BleServerManager"
        private const val SEALED_VERSION: Byte = 2
        /** ATT_MTU before any exchange: a notification carries at most this minus the 3 byte header. */
        private const val ATT_DEFAULT_MTU = 23
        private const val ATT_HEADER_BYTES = 3
        private const val MAX_CONTROL_AGE_MS = 60_000L
        private const val SUBSCRIBE_GRACE_MS = 10_000L
        private const val RECONNECT_GRACE_MS = 20_000L
        private const val SWIPED_AWAY_GRACE_MS = 3_000L
        private const val BEACON_MAJORS = 4
        private const val PULSE_MS = 10_000L
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
                .put("reconnectPulses", server.reconnectPulses)
                .put("mtu", server.mtuByDevice.values.minOrNull() ?: ATT_DEFAULT_MTU)
                .put("skippedNotifications", server.skippedNotifications)
        }

        /** Bluetooth links to this Redmi that still listen; in practice only the paired iPhone connects. */
        fun linkedCentrals(): Int = current?.listeningCentrals() ?: 0
    }

    private var lastControlSeq = 0L

    // New journal entries change the "k"/"m" fields of the call state, so a sleeping iPhone
    // wakes up for a new SMS or missed call and fetches it.
    private val callJournalListener = object : com.ilkay.redmigateway.journal.CallJournal.Listener {
        override fun onCallJournalEntry(entry: com.ilkay.redmigateway.journal.CallJournal.Entry) {
            notifyJournalChange()
            if (entry.type == "missed") wakeForNews("missed call")
        }
    }
    private val smsJournalListener = object : com.ilkay.redmigateway.journal.SmsJournal.Listener {
        override fun onSmsJournalEntry(entry: com.ilkay.redmigateway.journal.SmsJournal.Entry) {
            notifyJournalChange()
            if (entry.box == "inbox") wakeForNews("SMS")
        }
    }

    private fun notifyJournalChange() {
        mainHandler.post { charState?.let { notifyCharacteristicToSubscribers(it, buildStateBytes()) } }
    }
    private val mtuByDevice = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Last payload size already reported per device, so the MTU warning is logged once. */
    private val mtuWarned = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Notifications that could not fit the ATT MTU; visible in diagnostics. */
    @Volatile private var skippedNotifications = 0
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
    // A swiped-away iPhone app turns call-state updates off, but iOS can keep the Bluetooth link
    // itself up. Such a link reaches nobody. A link kept across a restart of this app has no
    // record either way and still counts, because the iPhone keeps listening on the same handle.
    private val stoppedListening = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    private fun listeningCentrals() = connectedDevices.count { it !in stoppedListening }
    private val clientLastSeq = ConcurrentHashMap<String, Long>()
    private val stateSeq = AtomicLong(0)

    private data class PreparedWriteEntry(
        val characteristic: BluetoothGattCharacteristic,
        val offset: Int,
        val value: ByteArray
    )
    private val preparedWrites = ConcurrentHashMap<BluetoothDevice, MutableList<PreparedWriteEntry>>()

    private var isAdvertising = false
    private var advertiseStarting = false
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
            advertiseStarting = false
            Log.i(TAG, "BLE advertising started successfully")
            // A start that raced a freshly linked iPhone is undone here, where its result is known.
            if (beaconCallback == null && shouldBeRunning && iPhoneLinked()) {
                Log.i(TAG, "iPhone linked while advertising started; stopping it")
                stopAdvertising()
            }
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            advertiseStarting = false
            Log.w(TAG, "BLE advertising failed to start, error code: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE client connected: ${device.address}")
                connectedDevices.add(device)
                linkChanged()
                mainHandler.postDelayed({
                    val subscribed = subscribedDevices[device]?.contains(BleConstants.CHAR_STATE_UUID) == true
                    if (connectedDevices.contains(device) && !subscribed) {
                        Log.i(TAG, "BLE client kept its link across a gateway restart; call state still reaches it")
                    }
                }, SUBSCRIBE_GRACE_MS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BLE client disconnected: ${device.address}")
                mtuByDevice.remove(device.address)
                connectedDevices.remove(device)
                subscribedDevices.remove(device)
                stoppedListening.remove(device)
                preparedWrites.remove(device)
                linkChanged()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtuByDevice[device.address] = mtu
            mtuWarned.remove(device.address)
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
                    if (enabled) stoppedListening.remove(device) else stoppedListening.add(device)
                    linkChanged(quick = !enabled)
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
        com.ilkay.redmigateway.service.IPhoneReachability.addListener(reachabilityListener)
        linkChanged()
    }

    fun updatePort(port: Int) {
        this.activePort = port
    }

    private var lastAnnouncedAddresses: List<String> = emptyList()

    fun updateActiveIp(ip: String) {
        val addresses = NetworkUtils.localAddresses()
        val changed = this.activeIp != ip || addresses != lastAnnouncedAddresses
        this.activeIp = ip
        lastAnnouncedAddresses = addresses
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
        CallManager.removeWaitingListener(this)
        com.ilkay.redmigateway.service.IPhoneReachability.removeListener(reachabilityListener)
        mainHandler.removeCallbacks(reconnectPulse)
        pulseScheduled = false
        pulseUntil = 0L
        stopCallBeacon()
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
            CallManager.addWaitingListener(this)
            com.ilkay.redmigateway.journal.CallJournal.get(context).addListener(callJournalListener)
            com.ilkay.redmigateway.journal.SmsJournal.get(context).addListener(smsJournalListener)
            isServerRunning = true
            Log.i(TAG, "BleServerManager started for port $activePort")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BleServerManager: ${e.message}", e)
        }
    }

    private fun tearDownGattServerAndAdvertiser() {
        stopAdvertising()
        CallManager.removeListener(this)
        CallManager.removeWaitingListener(this)
        com.ilkay.redmigateway.journal.CallJournal.get(context).removeListener(callJournalListener)
        com.ilkay.redmigateway.journal.SmsJournal.get(context).removeListener(smsJournalListener)
        try {
            gattServer?.clearServices()
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT server: ${e.message}")
        }
        gattServer = null
        connectedDevices.clear()
        subscribedDevices.clear()
        stoppedListening.clear()
        linkChanged()
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
            advertiseStarting = true
        } catch (e: Exception) {
            advertiseStarting = false
            Log.e(TAG, "Failed to start BLE advertising: ${e.message}", e)
        }
    }

    /**
     * Advertise only while the iPhone has to find this Redmi again.
     *
     * A paired iPhone that already holds a Bluetooth link or an authenticated socket needs no
     * advertisement, yet the balanced advertiser kept transmitting four times a second on three
     * channels for the whole call. That is radio time and heat on a phone whose Wi-Fi retries
     * already run at 25 %. Advertising comes back the moment the link drops, so reconnecting is
     * unchanged. The call wake beacon owns the advertiser while it runs.
     */
    private fun syncIdleAdvertising() {
        if (!shouldBeRunning) return
        if (beaconCallback != null) return
        if (iPhoneLinked()) {
            if (isAdvertising) {
                Log.i(TAG, "iPhone is linked; BLE advertising paused until it must find this Redmi")
                stopAdvertising()
            }
        } else if (!isAdvertising && !advertiseStarting) {
            Log.i(TAG, "iPhone has no link; BLE advertising on")
            startAdvertising()
        }
    }

    // ---- Call wake beacon ----
    //
    // iOS relaunches an app for iBeacon region events even after the person swiped it away,
    // which Bluetooth notifications cannot do. While an incoming call rings and the iPhone has
    // not confirmed showing it, the Redmi advertises an iBeacon whose UUID is derived from the
    // pairing key; the iPhone monitors that region and wakes up to present the call.

    private var beaconCallback: AdvertiseCallback? = null
    private var beaconIsUrgent: Boolean? = null
    private var beaconMajor = 0
    private val beaconCheck = object : Runnable {
        override fun run() {
            updateCallBeacon()
            if (beaconCallback != null) mainHandler.postDelayed(this, 500)
        }
    }

    private fun updateCallBeacon() {
        val ringingHere = CallManager.currentCallState == "ringing" && CallManager.currentDirection == "incoming" &&
            IncomingCallPresenter.presentedOnIPhoneCallId != CallManager.currentCallId
        val pulsing = android.os.SystemClock.elapsedRealtime() < pulseUntil
        if ((ringingHere || pulsing) && shouldBeRunning) startCallBeacon(isUrgent = ringingHere) else stopCallBeacon()
    }

    // ---- Reconnect pulse ----
    //
    // A swiped-away iPhone app loses its Bluetooth link, and iOS does not relaunch it for
    // Bluetooth. While the paired iPhone has neither link, the same beacon is shown for a few
    // seconds now and then: iOS relaunches the app for the region entry, it reconnects over
    // Bluetooth and is back to "connected, asleep" without being opened. The pause between
    // pulses lets iOS see the region empty again; nobody hears the pulses while the iPhone is away.

    @Volatile private var reconnectPulses = 0
    private var pulseUntil = 0L
    private val reachabilityListener: () -> Unit = { linkChanged() }
    private var pulseScheduled = false
    private val reconnectPulse = Runnable { pulseScheduled = false; pulse() }

    private fun schedulePulse(delayMs: Long) {
        mainHandler.removeCallbacks(reconnectPulse)
        pulseScheduled = true
        mainHandler.postDelayed(reconnectPulse, delayMs)
    }

    private fun iPhoneLinked() =
        listeningCentrals() > 0 || com.ilkay.redmigateway.service.GatewayService.authenticatedClientCount() > 0

    /** New SMS or missed call while the iPhone has no link: wake it now instead of at the next pulse. */
    private fun wakeForNews(what: String) {
        mainHandler.post {
            if (iPhoneLinked() || !shouldBeRunning || PairingStore.pairedClient() == null) return@post
            if (android.os.SystemClock.elapsedRealtime() < pulseUntil) return@post
            Log.i(TAG, "New $what while the iPhone has no link; waking it")
            schedulePulse(0)
        }
    }

    private fun linkChanged(quick: Boolean = false) {
        com.ilkay.redmigateway.service.IPhoneReachability.changed()
        mainHandler.post {
            val linked = iPhoneLinked()
            if (linked || !shouldBeRunning || PairingStore.pairedClient() == null) {
                if (reconnectPulses > 0 || pulseUntil != 0L) Log.i(TAG, "iPhone linked again; reconnect pulses stop")
                mainHandler.removeCallbacks(reconnectPulse)
                pulseScheduled = false
                reconnectPulses = 0
                pulseUntil = 0L
                updateCallBeacon()
            } else if (quick) {
                // The app turned call-state updates off: it was swiped away. Wake it at once.
                schedulePulse(SWIPED_AWAY_GRACE_MS)
            } else if (!pulseScheduled) {
                // A short grace first: Bluetooth restarts and range blips reconnect by themselves.
                schedulePulse(RECONNECT_GRACE_MS)
            }
            syncIdleAdvertising()
        }
    }

    private fun pulse() {
        if (iPhoneLinked() || !shouldBeRunning || PairingStore.pairedClient() == null) return
        reconnectPulses++
        Log.i(TAG, "iPhone has no link; reconnect pulse $reconnectPulses")
        pulseUntil = android.os.SystemClock.elapsedRealtime() + PULSE_MS
        updateCallBeacon()
        // Pulses keep coming until a link returns: every 3 minutes, then every 10 once the
        // iPhone has been gone for half an hour (probably away from home).
        val pause = if (reconnectPulses < 10) 3 * 60_000L else 10 * 60_000L
        schedulePulse(PULSE_MS + pause)
        mainHandler.postDelayed({ updateCallBeacon() }, PULSE_MS + 100)
    }

    private fun startCallBeacon(isUrgent: Boolean = false) {
        if (!hasAdvertisePermission()) return
        if (beaconCallback != null) {
            if (beaconIsUrgent == isUrgent) return
            stopCallBeacon()
        }
        // One legacy advertising set: the wake beacon needs the advertiser itself.
        stopAdvertising()
        val key = PairingStore.pairedClient()?.key ?: return
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        val uuid = BridgeCrypto.derive(key, "beacon").copyOf(16)
        // iBeacon layout after Apple's company id: type 0x02, length 0x15, UUID, major, minor, power.
        val payload = java.nio.ByteBuffer.allocate(23)
            .put(0x02.toByte()).put(0x15.toByte()).put(uuid)
            .putShort(nextBeaconMajor().toShort()).putShort(1).put((-59).toByte()).array()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(0x004C, payload)
            .build()
        val mode = if (isUrgent) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY else AdvertiseSettings.ADVERTISE_MODE_BALANCED
        val txPower = if (isUrgent) AdvertiseSettings.ADVERTISE_TX_POWER_HIGH else AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(mode)
            .setTxPowerLevel(txPower)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "Call wake beacon on (major $beaconMajor, urgent=$isUrgent)")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "Call wake beacon failed: $errorCode")
                if (beaconCallback === this) {
                    beaconCallback = null
                    beaconIsUrgent = null
                }
            }
        }
        beaconCallback = callback
        beaconIsUrgent = isUrgent
        try {
            advertiser.startAdvertising(settings, data, callback)
            mainHandler.removeCallbacks(beaconCheck)
            mainHandler.postDelayed(beaconCheck, 500)
        } catch (e: Exception) {
            beaconCallback = null
            beaconIsUrgent = null
            Log.w(TAG, "Call wake beacon failed: ${e.javaClass.simpleName}")
        }
    }

    private fun nextBeaconMajor(): Int {
        beaconMajor = beaconMajor % BEACON_MAJORS + 1
        return beaconMajor
    }

    private fun stopCallBeacon() {
        val callback = beaconCallback ?: return
        beaconCallback = null
        beaconIsUrgent = null
        mainHandler.removeCallbacks(beaconCheck)
        runCatching { if (hasAdvertisePermission()) bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        Log.i(TAG, "Call wake beacon off")
        // The beacon held the advertiser; hand it back if the iPhone still has to find this Redmi.
        mainHandler.post { syncIdleAdvertising() }
    }

    private fun stopAdvertising() {
        if (isAdvertising || advertiseStarting) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping BLE advertising: ${e.message}")
            }
            isAdvertising = false
            advertiseStarting = false
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
            // Wi-Fi and hotspot can be up together; the iPhone tries every address.
            put("ips", org.json.JSONArray(NetworkUtils.localAddresses()))
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
        val isConf = CallManager.isConference
        val state = JSONObject()
            .put("s", CallManager.currentCallState)
            .put("id", CallManager.currentCallId ?: "")
            .put("d", CallManager.currentDirection.take(1))
            .put("q", stateSeq.incrementAndGet())
            .put("t", System.currentTimeMillis())
            .put("k", runCatching { com.ilkay.redmigateway.journal.CallJournal.get(context).latestSeq() }.getOrDefault(0L))
            .put("m", runCatching { com.ilkay.redmigateway.journal.SmsJournal.get(context).latestSeq() }.getOrDefault(0L))
            .put("conf", isConf)
        CallManager.currentPhoneNumber?.takeIf { it.isNotBlank() }?.let { state.put("n", it) }
        CallManager.waitingCallId?.let { state.put("wid", it) }
        CallManager.waitingNumber?.takeIf { it.isNotBlank() }?.let { state.put("wn", it) }
        if (key != null) {
            return byteArrayOf(SEALED_VERSION) + BridgeCrypto.seal(key, state.toString().toByteArray(Charsets.UTF_8), BLE_STATE_AAD)
        }
        if (!PairingStore.legacyAuthAllowed) return ByteArray(0)
        // Protocol 1 layout for iPhone builds up to 1.0 (33).
        val legacy = JSONObject()
            .put("state", CallManager.currentCallState)
            .put("callId", CallManager.currentCallId ?: "")
            .put("number", CallManager.currentPhoneNumber ?: "")
            .put("seq", state.getLong("q"))
            .put("ts", state.getLong("t"))
            .put("conf", isConf)
        CallManager.waitingCallId?.let { legacy.put("wid", it) }
        CallManager.waitingNumber?.takeIf { it.isNotBlank() }?.let { legacy.put("wn", it) }
        return legacy.toString().toByteArray(Charsets.UTF_8)
    }

    private fun notifyCharacteristicToSubscribers(
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray
    ) {
        if (!hasConnectPermission()) return
        characteristic.value = bytes
        // Call state goes to every connected device. A link can outlive a restart of this app
        // (the Bluetooth stack keeps it); the iPhone then still listens on the same handle,
        // while this new server instance has no record of its subscription.
        val toAll = characteristic.uuid == BleConstants.CHAR_STATE_UUID || characteristic.uuid == BleConstants.CHAR_DEVICE_INFO_UUID
        if (characteristic.uuid == BleConstants.CHAR_STATE_UUID) {
            Log.i(TAG, "BLE call state notified to ${connectedDevices.size} device(s), ${bytes.size} bytes")
        }
        for (device in connectedDevices) {
            val subs = subscribedDevices[device]
            if (toAll || subs?.contains(characteristic.uuid) == true) {
                val mtu = mtuByDevice[device.address] ?: ATT_DEFAULT_MTU
                val limit = mtu - ATT_HEADER_BYTES
                if (bytes.size > limit) {
                    // An ATT notification never carries more than MTU-3 bytes, and the MTU stays at
                    // its 23 byte default until the iPhone asks for a larger one. The sealed state
                    // frame is 110 bytes, so it cannot arrive: sending it anyway only spends radio
                    // time on a link that already retries a quarter of its Wi-Fi frames. Reported
                    // once per device and size; the iPhone reads the same characteristic on demand.
                    skippedNotifications++
                    if (mtuWarned.put(device.address, bytes.size) != bytes.size) {
                        Log.w(TAG, "Skipped a ${bytes.size} byte notification: MTU $mtu allows $limit")
                    }
                    continue
                }
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
        mainHandler.post { updateCallBeacon() }
    }

    // CallManager.WaitingListener implementation
    override fun onWaitingCallChanged(callId: String?, number: String?) {
        charState?.let { notifyCharacteristicToSubscribers(it, buildStateBytes()) }
        mainHandler.post { updateCallBeacon() }
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
