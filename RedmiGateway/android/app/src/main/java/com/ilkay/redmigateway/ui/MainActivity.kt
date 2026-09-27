package com.ilkay.redmigateway.ui

import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.ilkay.redmigateway.R
import com.ilkay.redmigateway.security.PairingStore
import com.ilkay.redmigateway.server.DiagnosticsReport
import com.ilkay.redmigateway.service.GatewayService
import com.ilkay.redmigateway.telecom.IncomingCallPresenter
import org.json.JSONObject
import java.util.Date

/**
 * Settings > Advanced and Diagnostics. Everyday use never needs this screen; it holds pairing,
 * the ringing preference and the technical status used for support.
 */
class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var codeView: TextView
    private lateinit var pairedView: TextView
    private lateinit var statusList: LinearLayout
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        scroll.addView(content)
        setContentView(scroll)
        title = "Gelişmiş ve tanılama"

        content.addView(heading("Gelişmiş ve tanılama"))

        // Pairing
        val pairing = section("iPhone eşleştirme")
        pairing.addView(body("iPhone'daki Telefon Geçidi uygulamasında bu kodu bir kez gir. Kod eşleşmeden sonra yenilenir."))
        codeView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.25f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.text_primary))
            setPadding(0, dp(12), 0, dp(12))
            contentDescription = "Eşleştirme kodu"
        }
        pairing.addView(codeView)
        pairedView = body("")
        pairing.addView(pairedView)
        pairing.addView(button("Yeni kod oluştur", outlined = true) {
            PairingStore.rotatePairingCode()
            refresh()
        })
        pairing.addView(button("iPhone eşleşmesini kaldır", outlined = true) {
            AlertDialog.Builder(this)
                .setTitle("iPhone eşleşmesi kaldırılsın mı?")
                .setMessage("iPhone bu Redmi'ye yeniden bağlanmak için yeni kodla eşleşmelidir. Arama geçmişi ve mesajlar silinmez.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Kaldır") { _, _ ->
                    PairingStore.forget()
                    restartGateway()
                    refresh()
                }
                .show()
        })

        // Calls
        val calls = section("Aramalar")
        calls.addView(MaterialSwitch(this).apply {
            text = "iPhone çağrıyı gösterince Redmi sessiz çalsın"
            isChecked = IncomingCallPresenter.quietWhenIPhoneRings
            setOnCheckedChangeListener { _, checked -> IncomingCallPresenter.quietWhenIPhoneRings = checked }
            minHeight = dp(48)
        })
        calls.addView(body("iPhone bağlı değilse veya çağrıyı göstermezse Redmi her zaman normal çalar."))

        // Status
        val status = section("Durum")
        statusList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status.addView(statusList)
        status.addView(button("Tanılama raporunu kopyala", outlined = true) {
            val report = DiagnosticsReport.build(this).toString(2)
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Telefon Geçidi tanılama", report))
            Toast.makeText(this, "Rapor kopyalandı (numara ve mesaj içermez)", Toast.LENGTH_SHORT).show()
        })
        status.addView(button("Geçidi yeniden başlat", outlined = true) { restartGateway() })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        codeView.text = PairingStore.pairingCode()
        val paired = PairingStore.pairedClient()
        pairedView.text = if (paired == null) "Eşleşmiş iPhone yok." else
            "Eşleşmiş: ${paired.name} · ${DateFormat.format("d MMM yyyy HH:mm", Date(paired.pairedAt))}"

        val report = DiagnosticsReport.build(this)
        statusList.removeAllViews()
        val service = report.getJSONObject("service")
        val audio = report.getJSONObject("audio")
        val ble = report.getJSONObject("ble")
        val app = report.getJSONObject("app")
        row("Geçit servisi", if (service.getBoolean("running")) "Çalışıyor" else "Kapalı", service.getBoolean("running"))
        row("Bağlı iPhone", if (service.getInt("clients") > 0) "Bağlı" else "Bağlı değil", service.getInt("clients") > 0)
        val dialer = service.getBoolean("defaultDialer")
        row("Varsayılan telefon uygulaması", if (dialer) "Evet" else "Hayır — aramalar için gerekli", dialer)
        if (!dialer) statusList.addView(button("Varsayılan telefon uygulaması yap", outlined = false) { requestDialerRole() })
        row("Görüşme sesi", ownerText(audio.optString("owner")), true)
        val fix = audio.optBoolean("vendorFix")
        row("iPhone ses kanalı (SIM 2)", if (fix) "Hazır" else "Düzeltme yok — iPhone sesi çalışmaz", fix)
        row("Bluetooth", if (ble.optBoolean("running")) "Açık · ${ble.optInt("centrals")} bağlantı" else "Kapalı", ble.optBoolean("running"))
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        row("Pil optimizasyonu", if (battery) "Muaf" else "Kısıtlı olabilir", battery)
        if (!battery) statusList.addView(button("Pil kısıtlamasını kaldır", outlined = false) { requestBatteryExemption() })
        val missing = missingPermissions(report.getJSONObject("permissions"))
        row("İzinler", if (missing.isEmpty()) "Tamam" else "Eksik: $missing", missing.isEmpty())
        row("Sürüm", "${app.getString("version")} (${app.getInt("build")}) · protokol ${app.getInt("protocol")}", true)
    }

    private fun ownerText(owner: String) = when (owner) {
        "IPHONE" -> "iPhone'da"
        "PREPARING_IPHONE" -> "iPhone'a geçiyor"
        else -> "Redmi'de"
    }

    private fun missingPermissions(permissions: JSONObject): String =
        permissions.keys().asSequence().filter { !permissions.getBoolean(it) }.joinToString(", ")

    private fun restartGateway() {
        startService(Intent(this, GatewayService::class.java).setAction(GatewayService.ACTION_STOP))
        handler.postDelayed({
            ContextCompat.startForegroundService(this, Intent(this, GatewayService::class.java).setAction(GatewayService.ACTION_START))
        }, 800)
    }

    private fun requestDialerRole() {
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
            @Suppress("DEPRECATION")
            startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER), 1)
        }
    }

    private fun requestBatteryExemption() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    // ---- small view helpers ----

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
        setTextColor(color(R.color.text_primary))
        setPadding(dp(4), 0, dp(4), dp(16))
    }

    private fun section(title: String): LinearLayout {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(R.color.border_subtle)
            setCardBackgroundColor(color(R.color.bg_card))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        inner.addView(TextView(this).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(color(R.color.text_primary))
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(inner)
        content.addView(card)
        return inner
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(color(R.color.text_secondary))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun row(label: String, value: String, ok: Boolean) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            minimumHeight = dp(40)
            contentDescription = "$label: $value"
        }
        line.addView(TextView(this).apply {
            text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setTextColor(color(R.color.text_primary))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(TextView(this).apply {
            text = value
            gravity = Gravity.END
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(color(if (ok) R.color.text_secondary else R.color.status_attention))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        statusList.addView(line)
    }

    private fun button(text: String, outlined: Boolean, onClick: () -> Unit): View {
        val style = if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle
        else com.google.android.material.R.attr.materialButtonStyle
        return MaterialButton(this, null, style).apply {
            this.text = text
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
    }
}
