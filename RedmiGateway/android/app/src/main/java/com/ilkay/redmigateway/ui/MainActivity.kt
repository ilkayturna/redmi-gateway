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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.ilkay.redmigateway.R
import com.ilkay.redmigateway.security.PairingStore
import com.ilkay.redmigateway.server.DiagnosticsReport
import com.ilkay.redmigateway.service.GatewayService
import com.ilkay.redmigateway.telecom.IncomingCallPresenter
import org.json.JSONObject
import java.io.File
import java.util.Date

/**
 * Settings (the gear on the phone screen). Everyday use never needs this screen; it holds pairing,
 * the ringing preference and the technical status used for support.
 */
class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var codeView: TextView
    private lateinit var pairedView: TextView
    private lateinit var statusList: LinearLayout
    private lateinit var remoteStatus: TextView
    private lateinit var iphoneTitle: TextView
    private lateinit var iphoneDetail: TextView
    private lateinit var hotspotStatus: TextView
    private lateinit var diagnosticsPath: TextView
    private lateinit var diagnosticsShare: View
    private val pickKey = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importKey(uri)
    }
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
        title = "Ayarlar"

        content.addView(heading("Ayarlar"))

        // How the iPhone is reached right now (network, Bluetooth while it sleeps, or Apple push).
        val link = section("iPhone bağlantısı")
        iphoneTitle = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
            setTextColor(color(R.color.text_primary))
            compoundDrawablePadding = dp(10)
        }
        link.addView(iphoneTitle)
        iphoneDetail = body("")
        link.addView(iphoneDetail)

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
        calls.addView(SwitchMaterial(this).apply {
            text = "iPhone çağrıyı gösterince Redmi sessiz çalsın"
            isChecked = IncomingCallPresenter.quietWhenIPhoneRings
            setOnCheckedChangeListener { _, checked -> IncomingCallPresenter.quietWhenIPhoneRings = checked }
            minHeight = dp(48)
        })
        calls.addView(body("Redmi önce iPhone'un çağrıyı göstermesini bekler ve hiç ses çıkarmaz. iPhone birkaç saniye " +
            "içinde göstermezse ya da eşleşmiş iPhone yoksa Redmi normal çalar."))

        // Hotspot only where there is no known Wi-Fi.
        val hotspot = section("Hotspot")
        hotspot.addView(SwitchMaterial(this).apply {
            text = "Bilinen Wi-Fi'da hotspot'u kapat, dışarıda aç"
            isChecked = com.ilkay.redmigateway.service.SmartHotspot.enabled
            setOnCheckedChangeListener { _, checked -> com.ilkay.redmigateway.service.SmartHotspot.enabled = checked }
            minHeight = dp(48)
        })
        hotspot.addView(body("Evde ya da işte kayıtlı Wi-Fi varken Redmi o ağa bağlanır ve hotspot'u kapatır, " +
            "mobil verin harcanmaz. Ağdan çıkınca hotspot kendiliğinden açılır. Görüşme sırasında hiçbir şey değişmez. " +
            "Bu ağların iPhone'da da kayıtlı olması gerekir."))
        hotspotStatus = body("")
        hotspot.addView(hotspotStatus)

        // Away from home: Apple push for calls and messages; Tailscale carries the connection.
        val remote = section("Uzaktan kullanım")
        remote.addView(body("Redmi evdeyken ve sen dışarıdayken aramaların ve mesajların iPhone'a gelmesi için:\n" +
            "1) İki telefona Tailscale kur ve aynı hesapla giriş yap.\n" +
            "2) Apple Developer › Keys bölümünde \"Apple Push Notifications service\" işaretli bir anahtar oluştur, " +
            "AuthKey_XXXXXXXXXX.p8 dosyasını bu telefona kopyala ve aşağıdan seç."))
        remoteStatus = body("")
        remote.addView(remoteStatus)
        remote.addView(button("Anahtar dosyasını seç (.p8)", outlined = true) {
            runCatching { pickKey.launch(arrayOf("*/*")) }
        })
        remote.addView(button("Test bildirimi gönder", outlined = true) {
            val apns = com.ilkay.redmigateway.push.ApnsSender
            if (apns.hasKey && !apns.hasAlertToken) {
                // No notification token yet (older iPhone app): check the key with Apple instead.
                apns.verifyKey { problem ->
                    runOnUiThread {
                        Toast.makeText(this, problem ?: "Apple anahtarı doğruladı. Aramalar Apple üzerinden gelir; " +
                            "mesaj bildirimi için iPhone uygulamasını güncelleyip bir kez aç.", Toast.LENGTH_LONG).show()
                    }
                }
                return@button
            }
            apns.sendTest { problem ->
                runOnUiThread {
                    Toast.makeText(this, problem ?: "Gönderildi. iPhone'da bildirim görünmeli.", Toast.LENGTH_LONG).show()
                }
            }
        })
        remote.addView(SwitchMaterial(this).apply {
            text = "Uzaktayken SMS metni bildirimde görünsün"
            isChecked = com.ilkay.redmigateway.push.PushNotifier.smsContentInPush
            setOnCheckedChangeListener { _, checked -> com.ilkay.redmigateway.push.PushNotifier.smsContentInPush = checked }
            minHeight = dp(48)
        })
        remote.addView(button("Anahtarı kaldır", outlined = true) {
            com.ilkay.redmigateway.push.ApnsSender.removeKey()
            refresh()
        })

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

        // Call diagnostics: the phone travels without the computer, so the log has to be
        // readable and shareable straight from this screen.
        val diagnostics = section("Çağrı teşhis kaydı")
        diagnostics.addView(body("Çağrı sorunlarını incelemek için tutulur. Son 4000 satır saklanır."))
        diagnosticsPath = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextColor(color(R.color.text_muted))
            setPadding(0, dp(4), 0, dp(4))
            setTextIsSelectable(true)
            contentDescription = "Teşhis kaydı dosya yolu"
        }
        diagnostics.addView(diagnosticsPath)
        diagnostics.addView(button("Son kayıtları göster", outlined = true) { showDiagnosticsTail() })
        diagnosticsShare = button("Paylaş", outlined = true) { shareDiagnostics() }
        diagnostics.addView(diagnosticsShare)
        diagnostics.addView(button("Temizle", outlined = true) { confirmClearDiagnostics() })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun importKey(uri: Uri) {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull().orEmpty()
        val keyId = Regex("AuthKey_([A-Z0-9]{10})").find(name)?.groupValues?.get(1)
        val pem = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        val ok = keyId != null && pem != null && com.ilkay.redmigateway.push.ApnsSender.importKey(pem, keyId)
        Toast.makeText(this, if (ok) "Apple bildirim anahtarı yüklendi. Apple'a soruluyor…" else
            "Dosya okunamadı. Apple'dan indirilen AuthKey_XXXXXXXXXX.p8 dosyasını seç.", Toast.LENGTH_LONG).show()
        if (ok) com.ilkay.redmigateway.push.ApnsSender.verifyKey { problem ->
            runOnUiThread {
                Toast.makeText(this, problem ?: "Apple anahtarı doğruladı.", Toast.LENGTH_LONG).show()
            }
        }
        refresh()
    }

    private fun refresh() {
        codeView.text = PairingStore.pairingCode()
        val iphone = com.ilkay.redmigateway.service.IPhoneReachability.current()
        iphoneTitle.text = iphone.title
        iphoneTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(when (iphone.level) {
            com.ilkay.redmigateway.service.IPhoneReachability.Level.CONNECTED,
            com.ilkay.redmigateway.service.IPhoneReachability.Level.BLUETOOTH -> R.color.status_good
            com.ilkay.redmigateway.service.IPhoneReachability.Level.PUSH -> R.color.accent
            com.ilkay.redmigateway.service.IPhoneReachability.Level.OFFLINE -> R.color.status_attention
            com.ilkay.redmigateway.service.IPhoneReachability.Level.UNPAIRED -> R.color.text_muted
        }), null, null, null)
        iphoneDetail.text = iphone.detail
        val apns = com.ilkay.redmigateway.push.ApnsSender
        remoteStatus.text = when {
            apns.isReady && apns.alertsReady -> "Hazır: iPhone uygulaması kapalıyken ya da iPhone uzaktayken aramalar ve mesajlar Apple üzerinden iletilir (anahtar ${apns.keyId})."
            apns.isReady -> "Aramalar hazır (anahtar ${apns.keyId}). Mesaj bildirimleri için iPhone uygulamasını güncelleyip bir kez aç."
            apns.hasKey -> "Anahtar yüklü (${apns.keyId}). iPhone uygulaması bir kez bağlanınca hazır olur."
            else -> "Apple bildirim anahtarı yok."
        }
        val smart = com.ilkay.redmigateway.service.SmartHotspot
        val known = smart.knownNetworks.sorted()
        hotspotStatus.text = (if (smart.hotspotOn) "Hotspot açık. " else "Hotspot kapalı. ") +
            (if (known.isEmpty()) "Henüz bilinen ağ yok; Redmi bir Wi-Fi'a bağlandığında öğrenir."
            else "Bilinen ağlar: ${known.joinToString(", ")}")
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
        val dialer = service.getBoolean("defaultDialer")
        row("Varsayılan telefon uygulaması", if (dialer) "Evet" else "Hayır — aramalar için gerekli", dialer)
        if (!dialer) statusList.addView(button("Varsayılan telefon uygulaması yap", outlined = false) { requestDialerRole() })
        row("Görüşme sesi", ownerText(audio.optString("owner")), true)
        val fix = audio.optBoolean("vendorFix")
        row("iPhone ses kanalı (SIM 2)", if (fix) "Hazır" else "Düzeltme yok — iPhone sesi çalışmaz", fix)
        row("Bluetooth", if (ble.optBoolean("running")) "Açık · ${ble.optInt("centrals")} bağlantı" else "Kapalı", ble.optBoolean("running"))
        row("Ağ bağlantısı", if (service.getInt("clients") > 0) "Açık" else "Uykuda (iPhone açınca kurulur)", true)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        row("Pil optimizasyonu", if (battery) "Muaf" else "Kısıtlı olabilir", battery)
        if (!battery) statusList.addView(button("Pil kısıtlamasını kaldır", outlined = false) { requestBatteryExemption() })
        val missing = missingPermissions(report.getJSONObject("permissions"))
        row("İzinler", if (missing.isEmpty()) "Tamam" else "Eksik: $missing", missing.isEmpty())
        row("Sürüm", "${app.getString("version")} (${app.getInt("build")}) · protokol ${app.getInt("protocol")}", true)

        val logPath = com.ilkay.redmigateway.journal.DiagnosticsLog.path()
        val logFile = logPath?.let(::File)
        diagnosticsPath.text = logPath ?: "Kayıt dosyası henüz oluşmadı; ilk çağrı olayından sonra burada görünür."
        // Nothing to send until the log exists and has content.
        diagnosticsShare.isEnabled = logFile?.isFile == true && logFile.length() > 0L
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

    // ---- call diagnostics ----

    /** The newest lines, scrollable and selectable so they can also be copied by hand. */
    private fun showDiagnosticsTail() {
        val text = com.ilkay.redmigateway.journal.DiagnosticsLog.tail(80)
        if (text.isBlank()) {
            Toast.makeText(this, "Henüz kayıt yok. Bir çağrıdan sonra tekrar dene.", Toast.LENGTH_SHORT).show()
            return
        }
        val log = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextColor(color(R.color.text_primary))
            setTextIsSelectable(true)
        }
        // Fixed height: the dialog then always scrolls instead of growing past the screen.
        val frame = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(ScrollView(this@MainActivity).apply {
                addView(log)
                // tail() puts the newest line last, so open on the newest event rather than the oldest.
                post { fullScroll(View.FOCUS_DOWN) }
            }, FrameLayout.LayoutParams(-1, dp(380)))
        }
        AlertDialog.Builder(this)
            .setTitle("Son kayıtlar")
            .setView(frame)
            .setPositiveButton("Kapat", null)
            .show()
    }

    /** Hands the log to WhatsApp, mail or any other text target. */
    private fun shareDiagnostics() {
        val text = com.ilkay.redmigateway.journal.DiagnosticsLog.tail(2000)
        if (text.isBlank()) {
            Toast.makeText(this, "Paylaşılacak kayıt yok.", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Telefon Geçidi çağrı teşhis kaydı")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(send, "Teşhis kaydını paylaş")) }
            .onFailure { Toast.makeText(this, "Paylaşacak uygulama bulunamadı.", Toast.LENGTH_SHORT).show() }
    }

    private fun confirmClearDiagnostics() {
        AlertDialog.Builder(this)
            .setTitle("Teşhis kaydı temizlensin mi?")
            .setMessage("Kayıtlı çağrı teşhis satırları silinir. Bu işlem geri alınamaz.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Temizle") { _, _ ->
                com.ilkay.redmigateway.journal.DiagnosticsLog.clear()
                Toast.makeText(this, "Teşhis kaydı temizlendi.", Toast.LENGTH_SHORT).show()
                // clear() writes on its own thread; re-read the file once it has landed.
                handler.postDelayed({ refresh() }, 300)
            }
            .show()
    }

    // ---- small view helpers ----

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun dot(colorId: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(color(colorId))
        setSize(dp(10), dp(10))
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline5)
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
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1)
            setTextColor(color(R.color.text_primary))
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(inner)
        content.addView(card)
        return inner
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
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
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
            setTextColor(color(R.color.text_primary))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(TextView(this).apply {
            text = value
            gravity = Gravity.END
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
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
            letterSpacing = 0f
            minHeight = dp(48)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
    }
}
