package com.ilkay.redmigateway.service

import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import com.ilkay.redmigateway.ble.BleServerManager
import com.ilkay.redmigateway.push.ApnsSender
import com.ilkay.redmigateway.security.PairingStore
import java.util.Date
import java.util.concurrent.CopyOnWriteArraySet

/**
 * How the paired iPhone can be reached right now, as the Redmi shows it.
 *
 * iOS does not keep a sleeping app's network socket alive, so the iPhone closes it on purpose
 * when its app leaves the screen and stays connected over Bluetooth; away from home, Apple push
 * reaches it. Only the network socket counted before, which showed "not connected" for a
 * sleeping iPhone that was still ringing for every call.
 */
object IPhoneReachability {
    enum class Level { UNPAIRED, CONNECTED, BLUETOOTH, PUSH, OFFLINE }

    /** [title] for a status row, [short] for one line (notification, phone header), [detail] explains it. */
    data class Status(val level: Level, val title: String, val short: String, val detail: String) {
        val linked get() = level == Level.CONNECTED || level == Level.BLUETOOTH
        val reachable get() = linked || level == Level.PUSH
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var lastSeenAt = 0L
    @Volatile private var lastLevel: Level? = null

    fun current(): Status {
        val level = when {
            PairingStore.pairedClient() == null -> Level.UNPAIRED
            GatewayService.authenticatedClientCount() > 0 -> Level.CONNECTED
            BleServerManager.linkedCentrals() > 0 -> Level.BLUETOOTH
            ApnsSender.isReady -> Level.PUSH
            else -> Level.OFFLINE
        }
        if (level == Level.CONNECTED || level == Level.BLUETOOTH) lastSeenAt = System.currentTimeMillis()
        return when (level) {
            Level.UNPAIRED -> Status(level, "Eşleşmiş iPhone yok", "iPhone eşleşmesi bekleniyor",
                "iPhone'daki Telefon Geçidi uygulamasına buradaki eşleştirme kodunu gir.")
            Level.CONNECTED -> Status(level, "Bağlı", "iPhone bağlı",
                "iPhone uygulaması açık. Aramalar, mesajlar ve ses anında iletilir.")
            Level.BLUETOOTH -> Status(level, "Bağlı · iPhone uykuda", "iPhone bağlı · uykuda",
                "Bluetooth ile bağlı. Çağrılar iPhone'da çalar; açtığında ses ağ üzerinden hemen bağlanır.")
            Level.PUSH -> Status(level, "Apple bildirimiyle ulaşılır", "iPhone · Apple bildirimiyle ulaşılır",
                "iPhone uygulaması kapalı ya da iPhone uzakta. Çağrılar ve mesajlar Apple bildirimiyle iPhone'a gider; " +
                    "iPhone yakındaysa uygulama kendiliğinden yeniden bağlanır.")
            Level.OFFLINE -> Status(level, "Bağlantı yok · uyandırılır", "iPhone uyandırılarak ulaşılır", buildString {
                append("iPhone uygulaması kapalı ya da iPhone uzakta. Çağrı ya da SMS gelince Redmi iPhone'u " +
                    "Bluetooth işaretiyle uyandırır; iPhone yakındaysa çalar ve bildirim gelir. Uygulama kısa sürede " +
                    "kendiliğinden yeniden bağlanır.")
                if (lastSeenAt > 0) append(" Son bağlantı ${DateFormat.format("HH:mm", Date(lastSeenAt))}.")
                append(" Evden uzaktayken de gelmesi için Uzaktan kullanım bölümündeki Apple anahtarını yükle.")
            })
        }
    }

    /** Short line for the ongoing notification. */
    fun notificationText(): String = current().short

    fun addListener(listener: () -> Unit) { listeners.add(listener) }

    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    /** A link, the pairing or the push setup changed; listeners run on the main thread. */
    fun changed() {
        main.post {
            val level = current().level
            if (level == lastLevel) return@post
            if (lastLevel == Level.CONNECTED || lastLevel == Level.BLUETOOTH) lastSeenAt = System.currentTimeMillis()
            lastLevel = level
            listeners.forEach { runCatching { it() } }
        }
    }
}
