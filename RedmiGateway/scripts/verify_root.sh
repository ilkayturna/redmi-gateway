#!/system/bin/sh
# Redmi Note 8 - Post-Root First Boot Audio & System Verification
OUT_DIR="/sdcard/Download/root_first_boot"
mkdir -p "$OUT_DIR"

echo "=== 1. Root Identity ===" > "$OUT_DIR/01_id.txt"
su -c id >> "$OUT_DIR/01_id.txt" 2>&1

echo "=== 2. SELinux Enforce Status ===" > "$OUT_DIR/02_selinux.txt"
su -c 'getenforce' >> "$OUT_DIR/02_selinux.txt" 2>&1

echo "=== 3. Sound Character Devices (/dev/snd) ===" > "$OUT_DIR/03_dev_snd.txt"
su -c 'ls -la /dev/snd' >> "$OUT_DIR/03_dev_snd.txt" 2>&1

echo "=== 4. ALSA Cards (/proc/asound/cards) ===" > "$OUT_DIR/04_asound_cards.txt"
su -c 'cat /proc/asound/cards' >> "$OUT_DIR/04_asound_cards.txt" 2>&1

echo "=== 5. ALSA PCM Nodes (/proc/asound/pcm) ===" > "$OUT_DIR/05_asound_pcm.txt"
su -c 'cat /proc/asound/pcm' >> "$OUT_DIR/05_asound_pcm.txt" 2>&1

echo "=== 6. Qualcomm Tinymix Mixer State ===" > "$OUT_DIR/06_tinymix.txt"
su -c 'tinymix' >> "$OUT_DIR/06_tinymix.txt" 2>&1

echo "=== 7. AudioPolicyService State ===" > "$OUT_DIR/07_audio_policy.txt"
su -c 'dumpsys media.audio_policy' >> "$OUT_DIR/07_audio_policy.txt" 2>&1

echo "=== 8. AudioFlinger State ===" > "$OUT_DIR/08_audio_flinger.txt"
su -c 'dumpsys media.audio_flinger' >> "$OUT_DIR/08_audio_flinger.txt" 2>&1

echo "Verification complete. All logs saved to $OUT_DIR"
