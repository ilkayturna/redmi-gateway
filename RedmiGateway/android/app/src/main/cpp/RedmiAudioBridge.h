#ifndef REDMI_AUDIO_BRIDGE_H
#define REDMI_AUDIO_BRIDGE_H

#include <string>
#include <thread>
#include <atomic>
#include <cstdint>
#include "RingBuffer.h"
#include "JitterBuffer.h"

struct AudioBridgeConfig {
    int rx_card = 0;
    int rx_device = -1; // -1 = Mock PCM mode
    int tx_card = 0;
    int tx_device = -1; // -1 = Mock PCM mode
    int sample_rate = 16000;
    int channels = 1;
    int frame_size_samples = 320; // 20ms @ 16kHz
    bool mock_loopback = false;
};

class RedmiAudioBridge {
public:
    RedmiAudioBridge();
    ~RedmiAudioBridge();

    bool initialize(const AudioBridgeConfig& config);
    bool start();
    void stop();

    void set_mock_loopback(bool enable);
    bool is_running() const { return running_; }

    // Feeds audio received from UDP network (iPhone -> Redmi TX)
    void on_network_packet(uint32_t seq, uint32_t timestamp, const uint8_t* data, size_t size);

    // Reads encoded PCM chunk for network transmission (Redmi RX -> iPhone)
    size_t get_network_packet(uint8_t* out_buffer, size_t max_size, uint32_t& out_seq, uint32_t& out_ts);

    std::string get_stats_json();

private:
    void rx_worker_loop();
    void tx_worker_loop();

    AudioBridgeConfig config_;
    std::atomic<bool> running_;
    std::atomic<bool> initialized_;

    AudioRingBuffer rx_ring_buffer_;
    JitterBuffer tx_jitter_buffer_;

    std::thread rx_thread_;
    std::thread tx_thread_;

    std::atomic<uint32_t> rx_seq_;
    std::atomic<uint32_t> tx_seq_;
    std::atomic<uint64_t> total_rx_bytes_;
    std::atomic<uint64_t> total_tx_bytes_;
};

#endif // REDMI_AUDIO_BRIDGE_H
