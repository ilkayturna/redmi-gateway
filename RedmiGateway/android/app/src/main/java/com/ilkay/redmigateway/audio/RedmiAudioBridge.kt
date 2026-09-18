package com.ilkay.redmigateway.audio

import org.json.JSONObject

data class AudioBridgeConfig(
    val rxCard: Int = 0,
    val rxDevice: Int = -1, // -1: Mock PCM
    val txCard: Int = 0,
    val txDevice: Int = -1, // -1: Mock PCM
    val sampleRate: Int = 16000,
    val mockLoopback: Boolean = false
)

class RedmiAudioBridge {

    companion object {
        init {
            System.loadLibrary("redmiaudiobridge")
        }
    }

    private external fun nativeInit(
        rxCard: Int, rxDevice: Int,
        txCard: Int, txDevice: Int,
        sampleRate: Int, mockLoopback: Boolean
    ): Boolean

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeSetMockLoopback(enable: Boolean)
    private external fun nativeGetStats(): String
    private external fun nativePushPacket(seq: Int, timestamp: Int, data: ByteArray, length: Int)
    private external fun nativePullPacket(outData: ByteArray, outMeta: IntArray): Int

    fun init(config: AudioBridgeConfig): Boolean {
        return nativeInit(
            config.rxCard, config.rxDevice,
            config.txCard, config.txDevice,
            config.sampleRate, config.mockLoopback
        )
    }

    fun start(): Boolean = nativeStart()
    fun stop() = nativeStop()
    fun setMockLoopback(enable: Boolean) = nativeSetMockLoopback(enable)
    fun getStats(): JSONObject = JSONObject(nativeGetStats())

    fun pushIncomingNetworkAudio(seq: Int, timestamp: Int, data: ByteArray, length: Int) {
        nativePushPacket(seq, timestamp, data, length)
    }

    fun pullOutgoingNetworkAudio(outData: ByteArray, outMeta: IntArray): Int {
        return nativePullPacket(outData, outMeta)
    }
}
