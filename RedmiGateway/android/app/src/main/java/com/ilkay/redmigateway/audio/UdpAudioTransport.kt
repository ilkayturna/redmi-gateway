package com.ilkay.redmigateway.audio

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-Latency UDP Audio Transport Layer for RedmiGateway.
 * Handles bidirectional PCM/Opus packet transport decoupled from WebSocket signaling.
 *
 * Header Format (9 bytes):
 * - Sequence Number: uint32 (4 bytes)
 * - Timestamp:       uint32 (4 bytes, milliseconds)
 * - Payload Type:    uint8  (1 byte, 0x01 = PCM 16-bit mono 16kHz)
 * - Payload Data:    variable
 */
class UdpAudioTransport(
    val rxPort: Int = 8790, // Outgoing stream to iPhone (cellular downlink)
    val txPort: Int = 8791, // Incoming stream from iPhone (cellular uplink)
    private val audioBridge: RedmiAudioBridge
) {
    companion object {
        private const val TAG = "UdpAudioTransport"
        const val PAYLOAD_PCM_16K_MONO: Byte = 0x01
        const val HEADER_SIZE = 9
    }

    private var txSocket: DatagramSocket? = null
    private var rxSocket: DatagramSocket? = null

    private var targetClientAddress: InetAddress? = null
    private var targetClientPort: Int = rxPort

    private val running = AtomicBoolean(false)
    private var rxWorkerThread: Thread? = null
    private var txWorkerThread: Thread? = null

    val packetsSent = AtomicLong(0)
    val packetsReceived = AtomicLong(0)
    val bytesSent = AtomicLong(0)
    val bytesReceived = AtomicLong(0)

    fun setTargetClient(host: String, port: Int = rxPort) {
        targetClientAddress = InetAddress.getByName(host)
        targetClientPort = port
        Log.i(TAG, "Target client configured: $host:$port")
    }

    fun start() {
        if (running.getAndSet(true)) return

        // 1. Socket for receiving TX stream from iPhone
        txSocket = DatagramSocket(txPort).apply {
            soTimeout = 1000
            receiveBufferSize = 65536
        }

        // 2. Socket for sending RX stream to iPhone
        rxSocket = DatagramSocket().apply {
            sendBufferSize = 65536
        }

        // Receiver thread (iPhone -> Redmi TX)
        txWorkerThread = Thread({
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)

            while (running.get()) {
                try {
                    txSocket?.receive(packet)
                    if (packet.length >= HEADER_SIZE) {
                        val byteBuf = ByteBuffer.wrap(packet.data, 0, packet.length).order(ByteOrder.BIG_ENDIAN)
                        val seq = byteBuf.int
                        val ts = byteBuf.int
                        val payloadType = byteBuf.get()

                        val payloadLength = packet.length - HEADER_SIZE
                        val pcmData = ByteArray(payloadLength)
                        byteBuf.get(pcmData)

                        audioBridge.pushIncomingNetworkAudio(seq, ts, pcmData, payloadLength)
                        packetsReceived.incrementAndGet()
                        bytesReceived.addAndGet(packet.length.toLong())
                    }
                } catch (e: Exception) {
                    // Timeout or socket closed
                }
            }
        }, "UdpAudio-TX-Receiver").apply { start() }

        // Transmitter thread (Redmi RX -> iPhone)
        rxWorkerThread = Thread({
            val pcmBuffer = ByteArray(1280) // up to 40ms @ 16kHz mono
            val meta = IntArray(2) // [seq, ts]
            val sendBuffer = ByteArray(2048)

            while (running.get()) {
                val readBytes = audioBridge.pullOutgoingNetworkAudio(pcmBuffer, meta)
                if (readBytes > 0 && targetClientAddress != null) {
                    val seq = meta[0]
                    val ts = meta[1]

                    val byteBuf = ByteBuffer.wrap(sendBuffer).order(ByteOrder.BIG_ENDIAN)
                    byteBuf.putInt(seq)
                    byteBuf.putInt(ts)
                    byteBuf.put(PAYLOAD_PCM_16K_MONO)
                    byteBuf.put(pcmBuffer, 0, readBytes)

                    val packetLength = HEADER_SIZE + readBytes
                    val packet = DatagramPacket(sendBuffer, packetLength, targetClientAddress, targetClientPort)
                    try {
                        rxSocket?.send(packet)
                        packetsSent.incrementAndGet()
                        bytesSent.addAndGet(packetLength.toLong())
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending UDP audio packet: ${e.message}")
                    }
                } else {
                    Thread.sleep(10)
                }
            }
        }, "UdpAudio-RX-Transmitter").apply { start() }

        Log.i(TAG, "UDP Audio Transport started on RX port $rxPort, TX port $txPort")
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        txSocket?.close()
        rxSocket?.close()

        rxWorkerThread?.join(500)
        txWorkerThread?.join(500)

        txSocket = null
        rxSocket = null
        rxWorkerThread = null
        txWorkerThread = null

        Log.i(TAG, "UDP Audio Transport stopped.")
    }
}
