#ifndef JITTER_BUFFER_H
#define JITTER_BUFFER_H

#include <map>
#include <vector>
#include <mutex>
#include <cstdint>

struct AudioPacket {
    uint32_t seq;
    uint32_t timestamp;
    std::vector<uint8_t> payload;
};

class JitterBuffer {
public:
    explicit JitterBuffer(size_t max_packets = 10, size_t target_delay_packets = 2)
        : max_packets_(max_packets), target_delay_packets_(target_delay_packets),
          expected_seq_(0), initialized_(false), packets_lost_(0), packets_received_(0) {}

    void push(uint32_t seq, uint32_t timestamp, const uint8_t* data, size_t size) {
        std::lock_guard<std::mutex> lock(mutex_);
        packets_received_++;

        if (!initialized_) {
            expected_seq_ = seq;
            initialized_ = true;
        } else if (seq < expected_seq_ && (expected_seq_ - seq) < 1000) {
            // Late / duplicate packet, discard
            return;
        }

        AudioPacket pkt;
        pkt.seq = seq;
        pkt.timestamp = timestamp;
        pkt.payload.assign(data, data + size);
        packet_map_[seq] = pkt;

        // Keep buffer bounded
        while (packet_map_.size() > max_packets_) {
            packet_map_.erase(packet_map_.begin());
        }
    }

    bool pop(std::vector<uint8_t>& out_payload) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!initialized_ || packet_map_.size() < target_delay_packets_) {
            return false;
        }

        auto it = packet_map_.find(expected_seq_);
        if (it != packet_map_.end()) {
            out_payload = std::move(it->second.payload);
            packet_map_.erase(it);
            expected_seq_++;
            return true;
        }

        // Expected packet is missing; check if newer packets exist
        if (!packet_map_.empty() && packet_map_.begin()->first > expected_seq_) {
            packets_lost_++;
            // Concealment / skip missing frame
            expected_seq_ = packet_map_.begin()->first;
            out_payload = std::move(packet_map_.begin()->second.payload);
            packet_map_.erase(packet_map_.begin());
            expected_seq_++;
            return true;
        }

        return false;
    }

    uint32_t get_packet_loss() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return packets_lost_;
    }

    uint32_t get_packets_received() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return packets_received_;
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        packet_map_.clear();
        initialized_ = false;
        expected_seq_ = 0;
        packets_lost_ = 0;
        packets_received_ = 0;
    }

private:
    size_t max_packets_;
    size_t target_delay_packets_;
    uint32_t expected_seq_;
    bool initialized_;
    uint32_t packets_lost_;
    uint32_t packets_received_;
    std::map<uint32_t, AudioPacket> packet_map_;
    mutable std::mutex mutex_;
};

#endif // JITTER_BUFFER_H
