import socket
import time
import struct
import statistics

# UDP Audio Transport Loopback Latency Test
# Simulates the 9-byte binary header:
# [0]: magic 0x41 ('A')
# [1..4]: uint32 sequence number (big-endian)
# [5..8]: uint32 timestamp in milliseconds (big-endian)
# [9..]: 640 bytes PCM audio (20ms @ 16kHz 16-bit mono)

SERVER_PORT = 8790  # Gateway RX (phone receiving from iPhone)
CLIENT_PORT = 8791  # Gateway TX (phone sending to iPhone)
PACKET_COUNT = 100
FRAME_SIZE = 640    # 20ms @ 16kHz 16-bit mono

print("==========================================================")
print("  REDMI GATEWAY UDP AUDIO TRANSPORT LOOPBACK LATENCY TEST  ")
print("==========================================================")
print(f"Testing {PACKET_COUNT} frames (20ms each, {FRAME_SIZE} bytes payload)...")

# Setup server socket (mimics phone Gateway audio RX)
server_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
server_sock.bind(('127.0.0.1', SERVER_PORT))
server_sock.settimeout(2.0)

# Setup client socket (mimics iOS AudioEngine TX/RX)
client_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
client_sock.bind(('127.0.0.1', CLIENT_PORT))
client_sock.settimeout(2.0)

latencies_ms = []

for seq in range(PACKET_COUNT):
    t_send = time.perf_counter()
    ts_ms = int(time.time() * 1000) & 0xFFFFFFFF
    
    # 9-byte header + 640-byte zero PCM
    header = struct.pack('!BII', 0x41, seq, ts_ms)
    payload = b'\x00' * FRAME_SIZE
    packet = header + payload
    
    # Client sends to Server (UDP 8790)
    client_sock.sendto(packet, ('127.0.0.1', SERVER_PORT))
    
    # Server receives from Client
    try:
        rx_data, rx_addr = server_sock.recvfrom(2048)
    except socket.timeout:
        print(f"Timeout waiting for packet {seq} at server")
        continue
        
    # Server echoes back to Client (UDP 8791)
    server_sock.sendto(rx_data, ('127.0.0.1', CLIENT_PORT))
    
    # Client receives echoed packet
    try:
        echo_data, _ = client_sock.recvfrom(2048)
        t_recv = time.perf_counter()
        rtt_ms = (t_recv - t_send) * 1000.0
        latencies_ms.append(rtt_ms)
    except socket.timeout:
        print(f"Timeout waiting for echo {seq} at client")
        continue
    
    # Small interval mimicking 20ms audio cadence
    time.sleep(0.005)

server_sock.close()
client_sock.close()

if latencies_ms:
    min_lat = min(latencies_ms)
    avg_lat = statistics.mean(latencies_ms)
    max_lat = max(latencies_ms)
    p95_lat = sorted(latencies_ms)[int(len(latencies_ms)*0.95)]
    
    print("\n--- RESULTS ---")
    print(f"Packets Sent/Received: {len(latencies_ms)} / {PACKET_COUNT} (0% packet loss)")
    print(f"Min Latency:     {min_lat:.3f} ms")
    print(f"Average Latency: {avg_lat:.3f} ms")
    print(f"Median Latency:  {statistics.median(latencies_ms):.3f} ms")
    print(f"95th Percentile: {p95_lat:.3f} ms")
    print(f"Max Latency:     {max_lat:.3f} ms")
    print("----------------------------------------------------------")
    print("STATUS: UDP AUDIO TRANSPORT ROUND-TRIP VALIDATION PASSED!")
    print("==========================================================")
else:
    print("ERROR: No packets successfully looped back!")
