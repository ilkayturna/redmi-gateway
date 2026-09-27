package com.ilkay.redmigateway.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telecom.TelecomManager
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import com.ilkay.redmigateway.R
import com.ilkay.redmigateway.databinding.ActivityPhoneBinding
import com.ilkay.redmigateway.service.GatewayService
import com.ilkay.redmigateway.sms.SmsManagerHelper
import com.ilkay.redmigateway.telecom.CallManager
import java.util.Date

/** The Redmi's everyday phone shell. Gateway diagnostics live behind Settings. */
class PhoneActivity : AppCompatActivity(), CallManager.CallListener {
    private data class Person(val name: String, val number: String, val photo: String?)
    private data class Recent(val number: String, val type: Int, val time: Long, val seconds: Long)
    private data class Message(val address: String, val body: String, val time: Long, val sent: Boolean)

    private lateinit var binding: ActivityPhoneBinding
    private var selectedTab = 2
    private var openThread: String? = null
    private var contacts: List<Person> = emptyList()
    private var recents: List<Recent> = emptyList()
    private var messages: List<Message> = emptyList()
    private var draftNumber = ""

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPhoneData() }
    private val dialerRole = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (selectedTab == 4) showSelectedTab() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhoneBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val labels = listOf("Son", "Kişiler", "Tuşlar", "Mesajlar", "Ayarlar")
        labels.forEach { binding.phoneTabs.addTab(binding.phoneTabs.newTab().setText(it)) }
        binding.phoneTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = tab.position
                openThread = null
                showSelectedTab()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                openThread = null
                showSelectedTab()
            }
        })
        val incomingNumber = intent?.data?.schemeSpecificPart.orEmpty()
        if (incomingNumber.isNotBlank()) draftNumber = incomingNumber
        binding.phoneTabs.getTabAt(selectedTab)?.select()
        showSelectedTab()
        binding.currentCallCard.setOnClickListener { openCurrentCall() }
        binding.openCurrentCall.setOnClickListener { openCurrentCall() }
        if (isCallLive()) {
            binding.root.post { if (isCallLive()) openCurrentCall() }
        }
        requestPermissionsIfNeeded()
        if (!GatewayService.isRunning) startBridge()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() }?.let {
            draftNumber = it
            binding.phoneTabs.getTabAt(2)?.select()
        }
        if (isCallLive() && intent.data == null) {
            binding.root.post { if (isCallLive()) openCurrentCall() }
        }
    }

    override fun onStart() {
        super.onStart()
        CallManager.addListener(this)
    }

    override fun onResume() {
        super.onResume()
        refreshPhoneData()
        updateSubtitle()
    }

    override fun onStop() {
        CallManager.removeListener(this)
        super.onStop()
    }

    private fun requestPermissionsIfNeeded() {
        val required = listOf(
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        )
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    private fun refreshPhoneData() {
        Thread {
            val people = readContacts()
            val calls = readRecents()
            val sms = readMessages()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                contacts = people
                recents = calls
                messages = sms
                if (selectedTab != 2) showSelectedTab()
            }
        }.start()
    }

    private fun readContacts(): List<Person> {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val rows = ArrayList<Person>()
        runCatching {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
                ), null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val number = cursor.getString(1) ?: continue
                    rows.add(Person(cursor.getString(0) ?: number, number, cursor.getString(2)))
                }
            }
        }
        return rows.distinctBy { numberKey(it.number) + it.name }
    }

    private fun readRecents(): List<Recent> {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val rows = ArrayList<Recent>()
        runCatching {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, CallLog.Calls.DATE + " DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < 250) {
                    rows.add(Recent(cursor.getString(0).orEmpty(), cursor.getInt(1), cursor.getLong(2), cursor.getLong(3)))
                }
            }
        }
        return rows
    }

    private fun readMessages(): List<Message> {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val rows = ArrayList<Message>()
        runCatching {
            contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, Telephony.Sms.DATE + " DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < 1000) {
                    rows.add(Message(cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty(),
                        cursor.getLong(2), cursor.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT))
                }
            }
        }
        return rows
    }

    private fun numberKey(number: String): String {
        val digits = number.filter(Char::isDigit)
        return digits.takeLast(10).ifBlank { number.trim() }
    }

    private fun nameFor(number: String): String =
        contacts.firstOrNull { numberKey(it.number) == numberKey(number) }?.name
            ?: number.ifBlank { "Gizli numara" }

    private fun showSelectedTab() {
        binding.phoneContent.removeAllViews()
        binding.phoneTitle.text = when (selectedTab) {
            0 -> "Son aramalar"
            1 -> "Kişiler"
            2 -> "Telefon"
            3 -> if (openThread == null) "Mesajlar" else nameFor(openThread!!)
            else -> "Ayarlar"
        }
        updateSubtitle()
        when (selectedTab) {
            0 -> showRecents()
            1 -> showContacts()
            2 -> showKeypad()
            3 -> if (openThread == null) showThreads() else showConversation(openThread!!)
            else -> showSettings()
        }
    }

    private fun updateSubtitle() {
        val state = CallManager.currentCallState
        val live = isCallLive()
        binding.currentCallCard.visibility = if (live) View.VISIBLE else View.GONE
        if (live) {
            val number = CallManager.currentPhoneNumber?.takeIf { it.isNotBlank() } ?: "Gizli numara"
            binding.currentCallState.text = when (state) {
                "ringing" -> "Gelen arama"
                "dialing", "connecting" -> "Aranıyor"
                "holding" -> "Arama beklemede"
                else -> "Görüşme sürüyor"
            }
            binding.currentCallNumber.text = number
            binding.currentCallCard.contentDescription = "${binding.currentCallState.text}, $number, arama ekranını aç"
        }
        binding.phoneSubtitle.text = if (live) {
            "Redmi hattı görüşmede"
        } else if (GatewayService.isRunning) {
            "Redmi hattı hazır · geçit açık"
        } else {
            "Redmi hattı hazır · geçit kapalı"
        }
        binding.phoneSubtitle.setOnClickListener {
            if (isCallLive()) openCurrentCall()
        }
    }

    private fun isCallLive() = CallManager.currentCallState !in listOf("idle", "disconnected")

    private fun openCurrentCall() {
        if (!isCallLive()) return
        startActivity(Intent(this, CallActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        ))
    }

    private fun showRecents() {
        if (recents.isEmpty()) return showEmpty("Henüz arama yok veya arama izni verilmedi.")
        val rows = recents.map { call ->
            val direction = when (call.type) {
                CallLog.Calls.INCOMING_TYPE -> "Gelen"
                CallLog.Calls.OUTGOING_TYPE -> "Giden"
                CallLog.Calls.MISSED_TYPE -> "Cevapsız"
                CallLog.Calls.REJECTED_TYPE -> "Reddedilen"
                else -> "Arama"
            }
            mapOf(
                "title" to nameFor(call.number),
                "detail" to "$direction · ${DateFormat.format("dd MMM HH:mm", Date(call.time))}" +
                    if (call.seconds > 0) " · ${call.seconds / 60}:${(call.seconds % 60).toString().padStart(2, '0')}" else ""
            )
        }
        showTwoLineList(rows) { position -> callNumber(recents[position].number) }
    }

    private fun showContacts() {
        val column = column()
        val search = EditText(this).apply {
            hint = "Kişi veya numara ara"
            setSingleLine(true)
            contentDescription = "Kişi ara"
        }
        column.addView(search, LinearLayout.LayoutParams(-1, -2))
        val list = ListView(this)
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        fun update(query: String) {
            val filtered = contacts.filter {
                it.name.contains(query, ignoreCase = true) || it.number.contains(query)
            }
            list.adapter = object : BaseAdapter() {
                override fun getCount() = filtered.size
                override fun getItem(position: Int) = filtered[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val person = filtered[position]
                    val row = LinearLayout(this@PhoneActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                    }
                    val avatar = FrameLayout(this@PhoneActivity).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(ContextCompat.getColor(this@PhoneActivity, R.color.neon_emerald))
                        }
                        clipToOutline = true
                    }
                    if (person.photo != null) {
                        avatar.addView(ImageView(this@PhoneActivity).apply {
                            setImageURI(Uri.parse(person.photo))
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }, FrameLayout.LayoutParams(-1, -1))
                    } else {
                        avatar.addView(TextView(this@PhoneActivity).apply {
                            text = person.name.take(1).uppercase()
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                            textSize = 20f
                        }, FrameLayout.LayoutParams(-1, -1))
                    }
                    row.addView(avatar, LinearLayout.LayoutParams(dp(44), dp(44)))
                    row.addView(LinearLayout(this@PhoneActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), 0, 0, 0)
                        addView(TextView(this@PhoneActivity).apply {
                            text = person.name
                            textSize = 17f
                        })
                        addView(TextView(this@PhoneActivity).apply {
                            text = person.number
                            textSize = 14f
                        })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    return row
                }
            }
            list.setOnItemClickListener { _, _, index, _ -> callNumber(filtered[index].number) }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = update(s.toString())
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        update("")
        binding.phoneContent.addView(column)
    }

    private fun showKeypad() {
        val column = column()
        column.gravity = Gravity.CENTER_HORIZONTAL
        val number = EditText(this).apply {
            hint = "Telefon numarası"
            inputType = InputType.TYPE_CLASS_PHONE
            textSize = 28f
            gravity = Gravity.CENTER
            setSingleLine(true)
            setText(draftNumber)
            setSelection(text.length)
            contentDescription = "Aranacak numara"
        }
        column.addView(number, LinearLayout.LayoutParams(-1, -2))
        val grid = GridLayout(this).apply { columnCount = 3; alignmentMode = GridLayout.ALIGN_BOUNDS }
        "123456789*0#".forEachIndexed { index, digit ->
            val button = Button(this).apply {
                text = digit.toString()
                textSize = 24f
                setOnClickListener {
                    number.text.insert(number.selectionStart.coerceAtLeast(0), digit.toString())
                    if (CallManager.currentCallState == "active") CallManager.playDtmf(digit)
                }
            }
            grid.addView(button, GridLayout.LayoutParams(GridLayout.spec(index / 3), GridLayout.spec(index % 3, 1f)).apply {
                width = 0
                height = dp(64)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        column.addView(grid, LinearLayout.LayoutParams(-1, -2))
        val delete = Button(this).apply {
            text = "Sil"
            setOnClickListener {
                val start = number.selectionStart
                if (start > 0) number.text.delete(start - 1, start)
            }
        }
        column.addView(delete, LinearLayout.LayoutParams(-1, -2))
        val call = Button(this).apply {
            text = "Ara"
            textSize = 20f
            setOnClickListener {
                draftNumber = number.text.toString()
                callNumber(draftNumber)
            }
        }
        column.addView(call, LinearLayout.LayoutParams(-1, dp(56)))
        binding.phoneContent.addView(column)
    }

    private fun showThreads() {
        val latest = messages.groupBy { numberKey(it.address) }
            .values.mapNotNull { it.maxByOrNull(Message::time) }
            .sortedByDescending(Message::time)
        val column = column()
        val newMessage = Button(this).apply {
            text = "Yeni mesaj"
            setOnClickListener { showNewMessage() }
        }
        column.addView(newMessage, LinearLayout.LayoutParams(-1, -2))
        val list = ListView(this)
        val rows = latest.map {
            mapOf("title" to nameFor(it.address),
                "detail" to "${if (it.sent) "Ben: " else ""}${it.body.take(70)} · ${DateFormat.format("dd MMM", Date(it.time))}")
        }
        list.adapter = twoLineAdapter(rows)
        list.setOnItemClickListener { _, _, position, _ ->
            openThread = latest[position].address
            showSelectedTab()
        }
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        binding.phoneContent.addView(column)
    }

    private fun showNewMessage() {
        val input = EditText(this).apply {
            hint = "Alıcı numarası"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Yeni mesaj")
            .setView(input)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Devam") { _, _ ->
                val address = input.text.toString().trim()
                if (address.isNotBlank()) {
                    openThread = address
                    showSelectedTab()
                }
            }.show()
    }

    private fun showConversation(address: String) {
        val column = column()
        val back = Button(this).apply {
            text = "‹ Mesajlar"
            setOnClickListener { openThread = null; showSelectedTab() }
        }
        column.addView(back, LinearLayout.LayoutParams(-1, -2))
        val history = messages.filter { numberKey(it.address) == numberKey(address) }.sortedBy(Message::time)
        val list = ListView(this)
        list.adapter = twoLineAdapter(history.map {
            mapOf("title" to if (it.sent) "Ben" else nameFor(it.address),
                "detail" to "${it.body}\n${DateFormat.format("dd MMM HH:mm", Date(it.time))}")
        })
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        list.setSelection(history.lastIndex.coerceAtLeast(0))
        val compose = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val message = EditText(this).apply {
            hint = "Mesaj yaz"
            minLines = 1
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        compose.addView(message, LinearLayout.LayoutParams(0, -2, 1f))
        val send = Button(this).apply {
            text = "Gönder"
            setOnClickListener {
                val body = message.text.toString()
                if (body.isBlank()) return@setOnClickListener
                isEnabled = false
                Thread {
                    val (accepted, error) = SmsManagerHelper.sendSms(this@PhoneActivity, address, body)
                    runOnUiThread {
                        isEnabled = true
                        if (accepted) {
                            messages = listOf(Message(address, body, System.currentTimeMillis(), true)) + messages
                            message.text.clear()
                            showConversation(address)
                            Toast.makeText(this@PhoneActivity, "SMS gönderim isteği kabul edildi", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@PhoneActivity, error ?: "SMS gönderilemedi", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }
        compose.addView(send, LinearLayout.LayoutParams(-2, -2))
        column.addView(compose, LinearLayout.LayoutParams(-1, -2))
        binding.phoneContent.addView(column)
    }

    private fun showSettings() {
        val column = column()
        column.addView(label(if (GatewayService.isRunning) "Telefon geçidi çalışıyor" else "Telefon geçidi kapalı"))
        val telecom = getSystemService(TelecomManager::class.java)
        val defaultDialer = telecom.defaultDialerPackage == packageName
        column.addView(label(if (defaultDialer) "Varsayılan telefon uygulaması" else "Varsayılan telefon uygulaması değil"))
        column.addView(Button(this).apply {
            text = "Varsayılan telefon uygulaması yap"
            visibility = if (defaultDialer) View.GONE else View.VISIBLE
            setOnClickListener { requestDialerRole() }
        })
        column.addView(Button(this).apply {
            text = if (GatewayService.isRunning) "Geçit açık" else "Geçidi başlat"
            isEnabled = !GatewayService.isRunning
            setOnClickListener { startBridge(); showSelectedTab() }
        })
        column.addView(Button(this).apply {
            text = "Gelişmiş ayarlar ve tanılama"
            setOnClickListener { startActivity(Intent(this@PhoneActivity, MainActivity::class.java)) }
        })
        binding.phoneContent.addView(column)
    }

    private fun requestDialerRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
            dialerRole.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }
    }

    private fun startBridge() {
        ContextCompat.startForegroundService(this, Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_START
        })
    }

    private fun callNumber(number: String) {
        if (!CallManager.dial(this, number)) {
            Toast.makeText(this, "Arama başlatılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(8))
        layoutParams = FrameLayout.LayoutParams(-1, -1)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(dp(8), dp(16), dp(8), dp(16))
    }

    private fun showEmpty(text: String) {
        binding.phoneContent.addView(label(text))
    }

    private fun twoLineAdapter(rows: List<Map<String, String>>) = SimpleAdapter(
        this, rows, android.R.layout.simple_list_item_2,
        arrayOf("title", "detail"), intArrayOf(android.R.id.text1, android.R.id.text2)
    )

    private fun showTwoLineList(rows: List<Map<String, String>>, onClick: (Int) -> Unit) {
        val list = ListView(this)
        list.adapter = twoLineAdapter(rows)
        list.setOnItemClickListener { _, _, position, _ -> onClick(position) }
        binding.phoneContent.addView(list)
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    override fun onCallStateChanged(callId: String, state: String, number: String?) {
        runOnUiThread { updateSubtitle() }
    }

    override fun onLogMessage(message: String) = Unit
}
