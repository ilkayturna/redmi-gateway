package com.ilkay.redmigateway.service

/**
 * A precondition that silently breaks iPhone audio or calling when it is gone.
 *
 * [code] is stable and goes into the diagnostics report; the three sentences go to the user.
 */
data class HealthProblem(
    val code: String,
    val title: String,
    val detail: String,
    val fix: String,
    val audioCritical: Boolean
)

/**
 * What has to be true on the phone for iPhone audio to work, and how to say it when it is not.
 *
 * All of these live in /vendor or /system, which a ROM update replaces wholesale. The app cannot
 * restore them by itself — writing those partitions or holding a signature permission needs root
 * or the ROM — so the next best thing is to name exactly what disappeared, on every boot, before
 * the user finds out through a call that has no sound.
 */
object HealthCheck {
    const val VENDOR_FIX = "vendorFix"
    const val PRIVILEGED_APP = "privApp"
    const val PRIVILEGED_PERMISSIONS = "privilegedPermissions"
    const val DEFAULT_DIALER = "defaultDialer"
    const val BLUETOOTH_PAIRING = "bluetoothPairing"

    /**
     * The signature|privileged permissions the call audio capture needs.
     *
     * TETHER_PRIVILEGED and MODIFY_PHONE_STATE are declared too, but the hotspot and the in-call
     * controls keep working without them on this ROM. Requiring them here would keep the warning
     * up on a setup whose audio is fine, which is how a warning stops being read.
     */
    val requiredPrivilegedPermissions = listOf(
        "android.permission.CAPTURE_AUDIO_OUTPUT",
        "android.permission.CALL_AUDIO_INTERCEPTION"
    )

    /** The required permissions that are not granted, in declaration order. */
    fun missingPrivileged(granted: Map<String, Boolean>): List<String> =
        requiredPrivilegedPermissions.filter { granted[it] != true }

    fun problems(
        vendorFix: Boolean,
        privApp: Boolean,
        missingPrivileged: List<String>,
        defaultDialer: Boolean,
        bluetoothPairingStale: Boolean = false
    ): List<HealthProblem> = buildList {
        if (!vendorFix) add(
            HealthProblem(
                VENDOR_FIX,
                "iPhone ses kanalı (SIM 2) düzeltmesi yok",
                "Redmi'nin /vendor ses dosyaları SIM 2 için eşlenmemiş. Düzeltme olmadan iPhone'a " +
                    "ses gitmez ve iPhone'un sesi aramaya ulaşmaz.",
                "Onarım paketini uygula ya da ROM'u düzeltmeyi içerecek şekilde yeniden kur.",
                true
            )
        )
        if (!privApp) add(
            HealthProblem(
                PRIVILEGED_APP,
                "Uygulama ayrıcalıklı (priv-app) kurulu değil",
                "Görüşme sesini yakalayan izinler yalnızca /system/priv-app altındaki kuruluma verilir; " +
                    "/data/app altındaki normal kurulumda iPhone'a giden ses açılamaz.",
                "Onarım paketi uygulamayı ve izin listesini sistem bölümüne kurar.",
                true
            )
        )
        if (missingPrivileged.isNotEmpty()) add(
            HealthProblem(
                PRIVILEGED_PERMISSIONS,
                "Ayrıcalıklı izin eksik: " + missingPrivileged.joinToString(", ") { it.substringAfterLast('.') },
                "Bu izinler olmadan aramadaki ses iPhone'a açılamaz; uygulama hatayı yakalar ve sessiz kalır.",
                "Onarım paketi privapp-permissions listesini yerine koyar; ROM'da da bulunmalı.",
                true
            )
        )
        if (!defaultDialer) add(
            HealthProblem(
                DEFAULT_DIALER,
                "Varsayılan telefon uygulaması bu uygulama değil",
                "Aramalar geçit üzerinden yürümez; iPhone ne çalar ne de arama yapabilir.",
                "Ayarlar'daki düğmeyle bu uygulamayı varsayılan telefon uygulaması yap.",
                false
            )
        )
        // Not audio-critical: calls still ring through the beacon and Apple push, only later.
        if (bluetoothPairingStale) add(
            HealthProblem(
                BLUETOOTH_PAIRING,
                "iPhone'daki eski Bluetooth eşleşmesi bağlantıyı koparıyor",
                "iPhone, Redmi'ye eski bir eşleşmenin anahtarıyla bağlanmaya çalışıyor; Redmi'de o eşleşme " +
                    "yok ve bağlantı hemen kopuyor. Gelen aramalar yine çalar ama Bluetooth yolu kapalı " +
                    "kalır ve Redmi iPhone'u beş dakikada bir uyandırır.",
                "iPhone'da Ayarlar > Bluetooth > Redmi Note 8 > (i) > Bu Aygıtı Unut. Uygulama Bluetooth'a " +
                    "kendisi yeniden bağlanır; eşleştirme isteği çıkarsa kabul et.",
                false
            )
        )
    }

    /** The problems that stop call audio, which is what the warning is about. */
    fun audioCritical(problems: List<HealthProblem>): List<HealthProblem> = problems.filter { it.audioCritical }

    /** One line, for the notification body and the settings banner. */
    fun summary(problems: List<HealthProblem>): String = problems.joinToString(" · ") { it.title }
}
