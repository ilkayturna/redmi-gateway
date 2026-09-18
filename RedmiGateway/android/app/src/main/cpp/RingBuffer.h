#ifndef RING_BUFFER_H
#define RING_BUFFER_H

#include <vector>
#include <mutex>
#include <cstdint>
#include <algorithm>

class AudioRingBuffer {
public:
    explicit AudioRingBuffer(size_t capacity_bytes = 65536)
        : buffer_(capacity_bytes), capacity_(capacity_bytes), head_(0), tail_(0), size_(0) {}

    size_t write(const uint8_t* data, size_t bytes) {
        std::lock_guard<std::mutex> lock(mutex_);
        size_t bytes_to_write = std::min(bytes, capacity_ - size_);
        for (size_t i = 0; i < bytes_to_write; ++i) {
            buffer_[head_] = data[i];
            head_ = (head_ + 1) % capacity_;
        }
        size_ += bytes_to_write;
        return bytes_to_write;
    }

    size_t read(uint8_t* out_data, size_t bytes) {
        std::lock_guard<std::mutex> lock(mutex_);
        size_t bytes_to_read = std::min(bytes, size_);
        for (size_t i = 0; i < bytes_to_read; ++i) {
            out_data[i] = buffer_[tail_];
            tail_ = (tail_ + 1) % capacity_;
        }
        size_ -= bytes_to_read;
        return bytes_to_read;
    }

    size_t available() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return size_;
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        head_ = 0;
        tail_ = 0;
        size_ = 0;
    }

private:
    std::vector<uint8_t> buffer_;
    size_t capacity_;
    size_t head_;
    size_t tail_;
    size_t size_;
    mutable std::mutex mutex_;
};

#endif // RING_BUFFER_H
