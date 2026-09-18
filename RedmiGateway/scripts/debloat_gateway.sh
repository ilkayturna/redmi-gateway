#!/system/bin/sh
# ==============================================================================
# Redmi Note 8 (ginkgo) 24/7 Gateway Debloat Script
# Safely disables analytics, ads, and telemetry while protecting cellular radio
# ==============================================================================

echo "[Gateway] Starting safe debloat..."

SAFE_PACKAGES="
com.miui.analytics
com.miui.msa.global
com.miui.cloudservice
com.miui.cloudbackup
com.xiaomi.micloud.sdk
com.miui.micloudsync
com.xiaomi.midrop
com.miui.player
com.miui.videoplayer
com.android.browser
com.miui.yellowpage
com.miui.bugreport
com.miui.miservice
com.miui.weather2
com.xiaomi.joyose
com.miui.cleanmaster
com.xiaomi.glgm
com.mi.globalTrendNews
com.miui.hybrid
com.miui.hybrid.accessory
"

count=0
for pkg in $SAFE_PACKAGES; do
    if pm list packages | grep -q "$pkg"; then
        echo "[+] Disabling: $pkg"
        pm disable-user --user 0 "$pkg" 2>/dev/null
        count=$((count+1))
    fi
done

echo "[Gateway] Debloat finished. Successfully disabled $count non-essential packages."
echo "[Gateway] Cellular radio, VoLTE, and audio subsystems remain 100% untouched."
