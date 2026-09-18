#include "RedmiAudioBridge.h"
#include <chrono>
#include <cmath>
#include <cstring>
#include <sstream>
#include <android/log.h>

#define TAG "RedmiAudioBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

RedmiAudioBridge::RedmiAudioBridge()
    : running_(false), initialized_(false),
      rx_seq_(0), tx_seq_(0), total_rx_bytes_(0), total_tx_bytes_(0) {}

RedmiAudioBridge::~RedmiAudioBridge() {
    stop();
}

bool RedmiAudioBridge::initialize(const AudioBridgeConfig& config) {
    stop();
    config_ = config;
    rx_ring_buffer_.clear();
    tx_jitter_buffer_.reset();
    rx_seq_ = 0;
    tx_seq_ = 0;
    total_rx_bytes_ = 0;
    total_tx_bytes_ = 0;
    initialized_ = true;
    LOGI("AudioBridge initialized: rx(C%dD%d), tx(C%dD%d), rate=%d, mockLoopback=%d",
         config_.rx_card, config_.rx_device, config_.tx_card, config_.tx_device,
         config_.sample_rate, config_.mock_loopback ? 1 : 0);
    return true;
}

bool RedmiAudioBridge::start() {
    if (!initialized_) return false;
    if (running_) return true;

    running_ = true;
    rx_thread_ = std::thread(&RedmiAudioBridge::rx_worker_loop, this);
    tx_thread_ = std::thread(&RedmiAudioBridge::tx_worker_loop, this);
    LOGI("AudioBridge worker threads started.");
    return true;
}

void RedmiAudioBridge::stop() {
    if (!running_) return;
    running_ = false;
    if (rx_thread_.joinable()) rx_thread_.join();
    if (tx_thread_.joinable()) tx_thread_.join();
    LOGI("AudioBridge worker threads stopped.");
}

void RedmiAudioBridge::set_mock_loopback(bool enable) {
    config_.mock_loopback = enable;
    LOGI("Mock loopback set to: %d", enable ? 1 : 0);
}

void RedmiAudioBridge::on_network_packet(uint32_t seq, uint32_t timestamp, const uint8_t* data, size_t size) {
    if (!running_) return;
    total_tx_bytes_ += size;
    tx_jitter_buffer_.push(seq, timestamp, data, size);

    if (config_.mock_loopback) {
        // Direct loopback for testing: write received packet into rx_ring_buffer_
        rx_ring_buffer_.write(data, size);
    }
}

size_t RedmiAudioBridge::get_network_packet(uint8_t* out_buffer, size_t max_size, uint32_t& out_seq, uint32_t& out_ts) {
    size_t frame_bytes = config_.frame_size_samples * sizeof(int16_t);
    if (max_size < frame_bytes) return 0;

    size_t read_bytes = rx_ring_buffer_.read(out_buffer, frame_bytes);
    if (read_bytes == frame_bytes) {
        out_seq = rx_seq_++;
        auto now = std::chrono::steady_clock::now().time_since_epoch();
        out_ts = static_cast<uint32_t>(std::chrono::duration_cast<std::chrono::milliseconds>(now).count());
        total_rx_bytes_ += read_bytes;
        return read_bytes;
    }
    return 0;
}

void RedmiAudioBridge::rx_worker_loop() {
    size_t frame_bytes = config_.frame_size_samples * sizeof(int16_t);
    std::vector<uint8_t> mock_pcm(frame_bytes, 0);

    while (running_) {
        auto start_time = std::chrono::steady_clock::now();

        if (config_.rx_device < 0 && !config_.mock_loopback) {
            // Mock source: generate low-amplitude 440Hz test sine wave
            static double phase = 0.0;
            int16_t* samples = reinterpret_cast<int16_t*>(mock_pcm.data());
            for (int i = 0; i < config_.frame_size_samples; ++i) {
                samples[i] = static_cast<int16_t>(1000.0 * std::sin(phase));
                phase += 2.0 * M_PI * 440.0 / config_.sample_rate;
                if (phase > 2.0 * M_PI) phase -= 2.0 * M_PI;
            }
            rx_ring_buffer_.write(mock_pcm.data(), frame_bytes);
        }

        // Sleep for frame interval (20ms)
        auto elapsed = std::chrono::steady_clock::now() - start_time;
        auto sleep_dur = std::chrono::milliseconds(20) - elapsed;
        if (sleep_dur > std::chrono::milliseconds(0)) {
            std::this_thread::sleep_for(sleep_dur);
        }
    }
}

void RedmiAudioBridge::tx_worker_loop() {
    std::vector<uint8_t> frame;
    while (running_) {
        if (tx_jitter_buffer_.pop(frame)) {
            // In hardware mode (tx_device >= 0), write to ALSA playback device
            // In mock mode, packet is consumed or already looped back
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
}

std::string RedmiAudioBridge::get_stats_json() {
    std::ostringstream ss;
    ss << "{"
       << "\"running\":" << (running_ ? "true" : "false") << ","
       << "\"mockLoopback\":" << (config_.mock_loopback ? "true" : "false") << ","
       << "\"rxSeq\":" << rx_seq_.load() << ","
       << "\"txPacketsReceived\":" << tx_jitter_buffer_.get_packets_received() << ","
       << "\"txPacketsLost\":" << tx_jitter_buffer_.get_packet_loss() << ","
       << "\"totalRxBytes\":" << total_rx_bytes_.load() << ","
       << "\"totalTxBytes\":" << total_tx_bytes_.load() << ","
       << "\"sampleRate\":" << config_.sample_rate
       << "}";
    return ss.str();
}
