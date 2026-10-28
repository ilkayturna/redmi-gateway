package com.ilkay.redmigateway.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import com.ilkay.redmigateway.server.DiagnosticsReport
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.ui.MainActivity
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

/**
 * Turns [HealthCheck] into something the user cannot miss.
 *
 * The service runs every day without anyone opening the settings screen, so a banner there is not
 * enough: a ROM update at night would leave the phone silently unable to carry call audio until
 * the next time the user happened to look. The high-priority notification is posted once per
 * service start, and only while an audio-critical problem exists.
 */
object HealthReporter {
    private const val CHANNEL_ID = "redmi_gateway_health"
    private const val NOTIFICATION_ID = 0x4EA1

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
     * Posts the warning while call audio is broken and removes it once everything is back.
     *
     * @param romChanged true when the ROM build string differs from the last start, which makes
     * the notification say that the update is the likely cause.
     */
    fun publish(context: Context, romChanged: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val critical = HealthCheck.audioCritical(problems(context))
        if (critical.isEmpty()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        createChannel(manager)
        val text = HealthCheck.summary(critical)
        val body = if (romChanged) "ROM güncellendi. $text" else text
        CallManager.log("Health: iPhone audio broken — $text")
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
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel(manager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ses düzeltmesi uyarısı",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "ROM güncellemesi iPhone ses düzeltmesini kaldırdığında uyarır"
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}
