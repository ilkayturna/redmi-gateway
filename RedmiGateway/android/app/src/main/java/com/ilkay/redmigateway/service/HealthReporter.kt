package com.ilkay.redmigateway.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ilkay.redmigateway.journal.DiagnosticsLog
import com.ilkay.redmigateway.push.PushNotifier
import com.ilkay.redmigateway.server.DiagnosticsReport
import com.ilkay.redmigateway.server.HealthBus
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where a priv-app install of this app is looked for. The repair package writes the same name. */
object InstallState {
    val privAppPaths = listOf(
        "/system/priv-app/RedmiGateway/RedmiGateway.apk",
        "/system_ext/priv-app/RedmiGateway/RedmiGateway.apk",
        "/product/priv-app/RedmiGateway/RedmiGateway.apk",
        "/system/priv-app/com.ilkay.redmigateway/com.ilkay.redmigateway.apk"
    )

    fun privAppPresent(): Boolean = privAppPaths.any { File(it).isFile }
}

/** The Redmi's own battery, as the health report carries it. */
data class RedmiBattery(val level: Int, val charging: Boolean) {
    val band: String get() = batteryBand(level, charging)
}

/** The ROM's OTA updater: an enabled one can install an update that removes the audio fix. */
data class UpdaterState(val present: Boolean, val enabled: Boolean)

/**
 * Turns [HealthCheck] into something the owner cannot miss, on the phone he actually looks at.
 *
 * The Redmi stays in a pocket, so the health snapshot goes to the iPhone through [HealthBus] (the
 * WebSocket server sends it on every authentication and forwards changes), and a new audio-critical
 * problem also goes out once as an Apple alert, for an iPhone that has no socket at that moment
 * (docs/WAVE1-CONTRACT.md section 4). The Redmi's own row stays, but silent: it used to beep and
 * vibrate in the pocket after every OTA and every service start (quiet-4).
 */
object HealthReporter {
    private const val TAG = "HealthReporter"
    /** The channel before wave 1: IMPORTANCE_HIGH with sound and vibration. Deleted, not changed:
     *  code cannot take the sound off an existing channel. */
    private const val OLD_CHANNEL_ID = "redmi_gateway_health"
    private const val CHANNEL_ID = "redmi_gateway_health_quiet"
    private const val NOTIFICATION_ID = 0x4EA1
    private const val PREFS = "gateway_health"
    /** The audio-critical codes the iPhone was last alerted about through Apple. */
    private const val KEY_ALERTED = "alerted_critical_codes"
    /** The ROM's OTA updater (LineageOS based /e/OS). Listed under <queries> in the manifest. */
    const val UPDATER_PACKAGE = "org.lineageos.updater"
    private const val ADB_WIFI_SETTING = "adb_wifi_enabled"

    // Main thread only: GatewayService calls evaluate from its handler.
    private var channelReady = false
    private var lastShownText: String? = null
    private var lastLoggedCritical: String? = null
    private var lastAlertBlocker: String? = null
    @Volatile private var alertInFlight: String? = null

    fun problems(context: Context): List<HealthProblem> {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val missing = HealthCheck.missingPrivileged(
            HealthCheck.requiredPrivilegedPermissions.associateWith {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        )
        return HealthCheck.problems(
            vendorFix = DiagnosticsReport.vendorAudioFixPresent(),
            privApp = InstallState.privAppPresent(),
            missingPrivileged = missing,
            defaultDialer = telecom?.defaultDialerPackage == context.packageName
        )
    }

    /**
     * Re-evaluates everything and passes it on: the snapshot to [HealthBus], the silent row on the
     * Redmi, and an Apple alert when an audio-critical problem is new. Cheap enough for the main
     * thread (the vendor fix check is cached by file stamps); called at service start, every 15
     * minutes and when the battery band changes. Logs only what changed (AGENTS.md 8.1).
     *
     * @param reason why it runs, for the diagnostics line
     */
    fun evaluate(context: Context, reason: String) {
        val now = System.currentTimeMillis()
        val problems = problems(context)
        val rom = UpdateWatch.rom(context) ?: UpdateWatch.Rom(UpdateWatch.currentBuild(), null, null)
        val battery = readBattery(context)
        val updater = readUpdater(context)
        val snapshot = healthSnapshot(now, problems, rom, battery, updater, readAdbWifi(context))
        val key = healthChangeKey(problems.map { it.code }, rom.build, updater, battery?.band)
        if (HealthBus.publish(snapshot, key)) {
            Log.i(TAG, "Health changed ($reason): $key")
            DiagnosticsLog.record("Health", "changed ($reason): $key")
        }
        val critical = HealthCheck.audioCritical(problems)
        showOnRedmi(context, critical, rom, now)
        alertIPhone(context, critical, rom, now)
    }

    /** The Redmi's own row: posted silently while call audio is broken, removed once it is back. */
    private fun showOnRedmi(context: Context, critical: List<HealthProblem>, rom: UpdateWatch.Rom, now: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: run {
            Log.w(TAG, "No NotificationManager; the health row is not shown")
            return
        }
        createChannel(manager)
        if (critical.isEmpty()) {
            if (lastShownText != null) CallManager.log("Health: iPhone audio works again")
            lastShownText = null
            lastLoggedCritical = null
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val text = HealthCheck.summary(critical)
        val body = healthAlertBody(critical, rom, now)
        if (text != lastLoggedCritical) {
            lastLoggedCritical = text
            CallManager.log("Health: iPhone audio broken — $text")
        }
        // The same row again is a post with nothing new; it is re-posted only when it changes.
        if (body == lastShownText) return
        lastShownText = body
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("iPhone sesi çalışmaz")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(open)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * One Apple alert per new audio-critical problem. What was alerted is kept in prefs, so a
     * service restart with the same fault stays quiet; it is recorded only once Apple accepted the
     * alert, so a missing key or token is retried at the next evaluation.
     */
    private fun alertIPhone(context: Context, critical: List<HealthProblem>, rom: UpdateWatch.Rom, now: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val codes = critical.map { it.code }
        val alerted = prefs.getString(KEY_ALERTED, null)
        when (healthAlertStep(alerted, codes)) {
            HealthAlertStep.NOTHING -> return
            HealthAlertStep.FORGET -> {
                prefs.edit().remove(KEY_ALERTED).apply()
                return
            }
            HealthAlertStep.REMEMBER -> {
                // Fewer problems than were alerted: nothing new to say, but a returning code alerts.
                prefs.edit().putString(KEY_ALERTED, codes.joinToString(",")).apply()
                return
            }
            HealthAlertStep.SEND -> Unit
        }
        val joined = codes.joinToString(",")
        if (alertInFlight == joined) return
        alertInFlight = joined
        val sent = PushNotifier.pushHealth("Redmi: iPhone sesi çalışmaz", healthAlertBody(critical, rom, now)) { problem ->
            alertInFlight = null
            if (problem == null) {
                prefs.edit().putString(KEY_ALERTED, joined).apply()
                DiagnosticsLog.record("Health", "Apple alert accepted for $joined")
            } else {
                Log.w(TAG, "Health alert for $joined not accepted: $problem")
            }
        }
        if (!sent) {
            alertInFlight = null
            val blocker = "no APNs key or iPhone alert token"
            if (blocker != lastAlertBlocker) {
                lastAlertBlocker = blocker
                DiagnosticsLog.record("Health", "Apple alert for $joined not sent: $blocker")
            }
        } else {
            lastAlertBlocker = null
        }
    }

    private fun createChannel(manager: NotificationManager) {
        if (channelReady) return
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ses düzeltmesi uyarısı",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "ROM güncellemesi iPhone ses düzeltmesini kaldırdığında; sessiz, uyarı iPhone'a gider"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
        channelReady = true
    }

    /** The battery now, from the sticky ACTION_BATTERY_CHANGED; null if the system has none yet. */
    fun readBattery(context: Context): RedmiBattery? {
        val intent = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
            .onFailure { Log.w(TAG, "Battery state unreadable: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return null
        return batteryFrom(intent)
    }

    /** The battery carried by an ACTION_BATTERY_CHANGED intent. */
    fun batteryFrom(intent: Intent): RedmiBattery? = redmiBattery(
        level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
        scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    )

    /**
     * Whether the OTA updater is installed and enabled. A missing package is "absent"; anything
     * else that goes wrong is "unknown" (null) rather than a guess.
     */
    private fun readUpdater(context: Context): UpdaterState? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(UPDATER_PACKAGE, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(UPDATER_PACKAGE, 0)
        }
        UpdaterState(present = true, enabled = info.enabled)
    } catch (_: PackageManager.NameNotFoundException) {
        UpdaterState(present = false, enabled = false)
    } catch (error: Exception) {
        Log.w(TAG, "Updater state unreadable: ${error.javaClass.simpleName}")
        null
    }

    /**
     * Whether wireless debugging is on (it keeps the Redmi awake; AGENTS.md 0.5). The setting is
     * hidden: a system app may read it, anything else gets a SecurityException, hence null.
     */
    private fun readAdbWifi(context: Context): Boolean? =
        runCatching { Settings.Global.getInt(context.contentResolver, ADB_WIFI_SETTING, 0) == 1 }.getOrNull()
}

/**
 * The battery band the change key uses: the level moving by one percent is not news, crossing
 * one of these lines is (contract: ok > 20 %, low <= 20 %, critical <= 10 %, charging).
 */
internal fun batteryBand(level: Int, charging: Boolean): String = when {
    charging -> "charging"
    level < 0 -> "unknown"
    level <= 10 -> "critical"
    level <= 20 -> "low"
    else -> "ok"
}

/**
 * The battery from ACTION_BATTERY_CHANGED extras, or null when the level is unknown.
 * "Charging" means on external power, which is what makes a low level harmless.
 */
internal fun redmiBattery(level: Int, scale: Int, plugged: Int, status: Int): RedmiBattery? {
    if (level < 0 || scale <= 0) return null
    val percent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
    val charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING
    return RedmiBattery(percent, charging)
}

/**
 * What decides whether a new snapshot is news for the iPhone: the problem codes, the ROM build,
 * the updater state and the battery band. The level, the time and adbWifi change too often or
 * matter too little to wake anyone (HealthBus).
 */
internal fun healthChangeKey(problemCodes: List<String>, romBuild: String, updater: UpdaterState?, batteryBand: String?): String {
    val updaterKey = when {
        updater == null -> "unknown"
        !updater.present -> "absent"
        updater.enabled -> "enabled"
        else -> "disabled"
    }
    return "problems=${problemCodes.joinToString(",")}|rom=$romBuild|updater=$updaterKey|battery=${batteryBand ?: "unknown"}"
}

/** The `health` frame (docs/WAVE1-CONTRACT.md section 4). Unknown parts are left out, never guessed. */
internal fun healthSnapshot(
    at: Long,
    problems: List<HealthProblem>,
    rom: UpdateWatch.Rom,
    battery: RedmiBattery?,
    updater: UpdaterState?,
    adbWifi: Boolean?
): JSONObject {
    val list = JSONArray()
    for (problem in problems) {
        list.put(JSONObject()
            .put("code", problem.code)
            .put("audioCritical", problem.audioCritical)
            .put("title", problem.title)
            .put("detail", problem.detail)
            .put("fix", problem.fix))
    }
    val romJson = JSONObject().put("build", rom.build)
    rom.previous?.let { romJson.put("previous", it) }
    rom.changedAt?.let { romJson.put("changedAt", it) }
    val snapshot = JSONObject()
        .put("type", "health")
        .put("at", at)
        .put("problems", list)
        .put("rom", romJson)
    battery?.let { snapshot.put("battery", JSONObject().put("level", it.level).put("charging", it.charging)) }
    updater?.let {
        val json = JSONObject().put("present", it.present)
        if (it.present) json.put("enabled", it.enabled)
        snapshot.put("updater", json)
    }
    adbWifi?.let { snapshot.put("adbWifi", it) }
    return snapshot
}

/** How long after a ROM change the warning still names the update as the likely cause. */
internal const val ROM_CHANGE_RECENT_MS = 24L * 3600 * 1000

/** The warning text for the Redmi's row and the Apple alert. */
internal fun healthAlertBody(critical: List<HealthProblem>, rom: UpdateWatch.Rom, now: Long): String {
    val text = HealthCheck.summary(critical)
    val changedAt = rom.changedAt
    val recent = changedAt != null && now - changedAt in 0L until ROM_CHANGE_RECENT_MS
    return if (recent) "ROM güncellendi. $text" else text
}

internal enum class HealthAlertStep { NOTHING, FORGET, REMEMBER, SEND }

/**
 * Whether the iPhone gets an Apple alert for the audio-critical [current] codes, given the codes
 * it was last alerted about. Only a code it has not been told about is news; a shrinking set is
 * remembered without a sound, and an empty one is forgotten so the same fault alerts if it returns.
 */
internal fun healthAlertStep(alerted: String?, current: List<String>): HealthAlertStep {
    val before = alerted?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()
    return when {
        current.isEmpty() -> if (before.isEmpty()) HealthAlertStep.NOTHING else HealthAlertStep.FORGET
        current.any { it !in before } -> HealthAlertStep.SEND
        current.toSet() == before -> HealthAlertStep.NOTHING
        else -> HealthAlertStep.REMEMBER
    }
}
