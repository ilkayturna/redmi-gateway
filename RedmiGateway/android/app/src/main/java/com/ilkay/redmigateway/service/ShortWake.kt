package com.ilkay.redmigateway.service

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.ilkay.redmigateway.RedmiGatewayApp

/**
 * Keeps the CPU up until a short deadline has run.
 *
 * Handler delays count uptime, and uptime stops while the phone sleeps in a pocket. Measured on
 * 23 and 25.10.2026: home Wi-Fi was back at 20:35:07 and 00:32:24, but the hotspot went off only
 * at 20:39:05 and 00:38:42, because the fifteen-second check waited for some unrelated wake. The
 * iPhone spent those minutes on mobile data. The same sleep can hold back the 5 s "Wi-Fi is gone,
 * hotspot on" step, the end of a 30 s join attempt and the 8 s fallback push for an SMS.
 *
 * The app is on the battery optimisation allowlist, so a timed partial wake lock is honoured in
 * Doze. Each use costs a few seconds of CPU per event, never an open-ended lock.
 */
internal class ShortWake(private val tag: String) {
    private val lock by lazy {
        (RedmiGatewayApp.instance.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RedmiGateway:$tag")
            .apply { setReferenceCounted(false) }
    }
    private var awakeUntil = 0L

    @Synchronized
    fun stayAwakeFor(ms: Long) {
        val now = SystemClock.elapsedRealtime()
        // A non reference counted lock takes the newest timeout, so a short request must not cut a
        // longer one that is still running.
        if (now + ms <= awakeUntil) return
        awakeUntil = now + ms
        runCatching { lock.acquire(ms) }
            .onFailure { Log.w("ShortWake", "$tag wake lock not taken: ${it.javaClass.simpleName}") }
    }
}
