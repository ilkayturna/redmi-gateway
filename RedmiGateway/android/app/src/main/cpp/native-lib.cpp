#include <jni.h>
#include <string>
#include "RedmiAudioBridge.h"

static RedmiAudioBridge g_bridge;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ilkay_redmigateway_audio_RedmiAudioBridge_nativeInit(
        JNIEnv* env, jobject /* this */,
        jint rxCard, jint rxDevice,
        jint txCard, jint txDevice,
        jint sampleRate, jboolean mockLoopback) {
    AudioBridgeConfig cfg;
    cfg.rx_card = rxCard;
    cfg.rx_device = rxDevice;
    cfg.tx_card = txCard;
    cfg.tx_device = txDevice;
    cfg.sample_rate = sampleRate;
    cfg.mock_loopback = mockLoopback;
    return g_bridge.initialize(cfg) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_ilkay_redmigateway_audio_RedmiAudioBridge_nativeStart(
        JNIEnv* env, jobject /* this */) {
    return g_bridge.start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_ilkay_redmigateway_audio_RedmiAudioBridge_nativeStop(
        JNIEnv* env, jobject /* this */) {
    g_bridge.stop();
}

JNIEXPORT void JNICALL
Java_com_ilkay_redmigateway_audio_RedmiAudioBridge_nativeSetMockLoopback(
        JNIEnv* env, jobject /* this */, jboolean enable) {
    g_bridge.set_mock_loopback(enable);
}

JNIEXPORT jstring JNICALL
Java_com_ilkay_redmigateway_audio_RedmiAudioBridge_nativeGetStats(
        JNIEnv* env, jobject /* this */) {
    std::string stats = g_bridge.get_stats_json();
    return env->NewStringUTF(stats.c_str());
}

JNIEXPORT void JNICALL
Java_com_ilkay_redmigateway_audio_RedmiAudioBridge_nativePushPacket(
        JNIEnv* env, jobject /* this */,
        jint seq, jint timestamp, jbyteArray data, jint length) {
    jbyte* buffer = env->GetByteArrayElements(data, nullptr);
    if (buffer) {
        g_bridge.on_network_packet(static_cast<uint32_t>(seq),
                                   static_cast<uint32_t>(timestamp),
                                   reinterpret_cast<const uint8_t*>(buffer),
                                   static_cast<size_t>(length));
        env->ReleaseByteArrayElements(data, buffer, JNI_ABORT);
    }
}

JNIEXPORT jint JNICALL
Java_com_ilkay_redmigateway_audio_RedmiAudioBridge_nativePullPacket(
        JNIEnv* env, jobject /* this */,
        jbyteArray outData, jintArray outMeta) {
    jbyte* buf = env->GetByteArrayElements(outData, nullptr);
    if (!buf) return 0;

    jsize max_size = env->GetArrayLength(outData);
    uint32_t seq = 0, ts = 0;
    size_t read_bytes = g_bridge.get_network_packet(
            reinterpret_cast<uint8_t*>(buf), static_cast<size_t>(max_size), seq, ts);

    env->ReleaseByteArrayElements(outData, buf, 0);

    if (read_bytes > 0) {
        jint meta[2] = {static_cast<jint>(seq), static_cast<jint>(ts)};
        env->SetIntArrayRegion(outMeta, 0, 2, meta);
    }
    return static_cast<jint>(read_bytes);
}

} // extern "C"
