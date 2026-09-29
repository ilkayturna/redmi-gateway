package com.ilkay.redmigateway.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.ilkay.redmigateway.R
import com.ilkay.redmigateway.databinding.ActivityPhoneBinding
import com.ilkay.redmigateway.journal.CallJournal
import com.ilkay.redmigateway.service.GatewayService
import com.ilkay.redmigateway.service.IPhoneReachability
import com.ilkay.redmigateway.sms.SmsSender
import com.ilkay.redmigateway.telecom.CallManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The Redmi's everyday phone: recents, contacts, keypad and messages, with the iPhone's link
 * state on top. Gateway settings and diagnostics sit behind the gear.
 */
class PhoneActivity : AppCompatActivity(), CallManager.CallListener {
    private data class Person(val name: String, val number: String, val label: String, val photo: String?)
    private data class Recent(val number: String, val type: Int, val time: Long, val seconds: Long, val carrier: String?)
    private data class Message(val address: String, val body: String, val time: Long, val sent: Boolean, val failed: Boolean)
    private data class Header(val text: String)
    private data class RecentEntry(val call: Recent, val count: Int)
    private data class Conversation(val address: String, val latest: Message)

    private enum class Tab(val itemId: Int, val title: String) {
        RECENTS(R.id.nav_recents, "Son aramalar"),
        CONTACTS(R.id.nav_contacts, "Kişiler"),
        KEYPAD(R.id.nav_keypad, "Tuş takımı"),
        MESSAGES(R.id.nav_messages, "Mesajlar")
    }

    private enum class Kind { INCOMING, OUTGOING, MISSED, OTHER }

    private lateinit var binding: ActivityPhoneBinding
    private lateinit var views: PhoneViews
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("phone_screen", MODE_PRIVATE) }
    private val turkish = Locale("tr", "TR")

    private var tab = Tab.RECENTS
    private var openThread: String? = null
    private var suppressNav = false
    private var contacts: List<Person> = emptyList()
    private var contactByKey: Map<String, Person> = emptyMap()
    private var recents: List<Recent> = emptyList()
    private var messages: List<Message> = emptyList()
    private var loaded = false
    private var loading = false
    private var reloadAgain = false
    private var draftNumber = ""
    private var contactQuery = ""
    private val smsDrafts = HashMap<String, String>()
    /** Rebinds the visible list after new data without rebuilding it (keeps scroll, focus, keyboard). */
    private var contentUpdater: (() -> Unit)? = null
    private var keypadMatch: TextView? = null

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        loaded = false
        refreshPhoneData()
        render()
    }
    private val dialerRole = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { renderChrome() }
    private val backToThreads = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            openThread = null
            render()
        }
    }
    private val reload = Runnable { refreshPhoneData() }
    private val dataObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, 400)
        }
    }
    private val reachabilityListener: () -> Unit = { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhoneBinding.inflate(layoutInflater)
        setContentView(binding.root)
        views = PhoneViews(this)
        onBackPressedDispatcher.addCallback(this, backToThreads)

        tab = Tab.values().firstOrNull { it.name == prefs.getString("tab", null) } ?: Tab.RECENTS
        dialNumber(intent)?.let {
            draftNumber = it
            tab = Tab.KEYPAD
        }
        binding.phoneNav.selectedItemId = tab.itemId
        binding.phoneNav.setOnItemSelectedListener { item ->
            if (!suppressNav) {
                Tab.values().firstOrNull { it.itemId == item.itemId }?.let { selectTab(it) }
            }
            true
        }
        binding.phoneNav.setOnItemReselectedListener {
            if (!suppressNav && openThread != null) {
                openThread = null
                render()
            }
        }
        binding.phoneSettings.setOnClickListener { openSettings() }
        binding.phoneStatus.setOnClickListener { openSettings() }
        binding.phoneBack.setOnClickListener { openThread = null; render() }
        binding.phoneCall.setOnClickListener { openThread?.let(::callNumber) }
        binding.currentCallCard.setOnClickListener { openCurrentCall() }
        binding.openCurrentCall.setOnClickListener { openCurrentCall() }

        render()
        if (isCallLive()) binding.root.post { if (isCallLive()) openCurrentCall() }
        requestPermissionsIfNeeded()
        if (!GatewayService.isRunning) startBridge()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val number = dialNumber(intent)
        if (number != null) {
            draftNumber = number
            openThread = null
            selectTab(Tab.KEYPAD, syncNav = true)
        } else if (isCallLive()) {
            binding.root.post { if (isCallLive()) openCurrentCall() }
        }
    }

    override fun onStart() {
        super.onStart()
        CallManager.addListener(this)
        IPhoneReachability.addListener(reachabilityListener)
        for (uri in listOf(CallLog.Calls.CONTENT_URI, Telephony.Sms.CONTENT_URI, ContactsContract.Contacts.CONTENT_URI)) {
            runCatching { contentResolver.registerContentObserver(uri, true, dataObserver) }
        }
    }

    override fun onResume() {
        super.onResume()
        renderChrome()
        refreshPhoneData()
    }

    override fun onStop() {
        CallManager.removeListener(this)
        IPhoneReachability.removeListener(reachabilityListener)
        runCatching { contentResolver.unregisterContentObserver(dataObserver) }
        handler.removeCallbacks(reload)
        super.onStop()
    }

    private fun dialNumber(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart?.takeIf { it.isNotBlank() }

    private fun selectTab(next: Tab, syncNav: Boolean = false) {
        tab = next
        openThread = null
        prefs.edit().putString("tab", next.name).apply()
        if (syncNav && binding.phoneNav.selectedItemId != next.itemId) {
            suppressNav = true
            binding.phoneNav.selectedItemId = next.itemId
            suppressNav = false
        }
        render()
    }

    // ---- data ----

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val required = listOf(
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        )
        val missing = required.filter { !has(it) }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    private fun refreshPhoneData() {
        if (loading) {
            reloadAgain = true
            return
        }
        loading = true
        kotlin.concurrent.thread(name = "phone-data") {
            val people = readContacts()
            val calls = readRecents()
            val sms = readMessages()
            runOnUiThread {
                loading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val changed = !loaded || people != contacts || calls != recents || sms != messages
                val wasLoaded = loaded
                loaded = true
                contacts = people
                contactByKey = people.associateBy { numberKey(it.number) }
                recents = calls
                messages = sms
                if (changed) {
                    val update = contentUpdater
                    if (wasLoaded && update != null) update() else renderContent()
                    renderChrome()
                }
                if (reloadAgain) {
                    reloadAgain = false
                    refreshPhoneData()
                }
            }
        }
    }

    private fun readContacts(): List<Person> {
        if (!has(Manifest.permission.READ_CONTACTS)) return emptyList()
        val rows = ArrayList<Person>()
        runCatching {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                ), null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val number = cursor.getString(1) ?: continue
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        resources, cursor.getInt(3), cursor.getString(4)
                    ).toString()
                    rows.add(Person(cursor.getString(0) ?: number, number, label, cursor.getString(2)))
                }
            }
        }
        return rows.distinctBy { numberKey(it.number) + "|" + it.name }
    }

    private fun readRecents(): List<Recent> {
        if (!has(Manifest.permission.READ_CALL_LOG)) return emptyList()
        val rows = ArrayList<Recent>()
        val carriers = runCatching { CallJournal.get(this).carriers() }.getOrDefault(emptyMap())
        runCatching {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls._ID),
                null, null, CallLog.Calls.DATE + " DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < 300) {
                    rows.add(Recent(cursor.getString(0).orEmpty(), cursor.getInt(1), cursor.getLong(2), cursor.getLong(3),
                        carriers[cursor.getLong(4)]))
                }
            }
        }
        return rows
    }

    private fun readMessages(): List<Message> {
        if (!has(Manifest.permission.READ_SMS)) return emptyList()
        val rows = ArrayList<Message>()
        runCatching {
            contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, Telephony.Sms.DATE + " DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < 2000) {
                    val type = cursor.getInt(3)
                    rows.add(Message(
                        cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty(), cursor.getLong(2),
                        sent = type != Telephony.Sms.MESSAGE_TYPE_INBOX,
                        failed = type == Telephony.Sms.MESSAGE_TYPE_FAILED
                    ))
                }
            }
        }
        return rows
    }

    private fun numberKey(number: String): String {
        val digits = number.filter(Char::isDigit)
        return if (digits.length >= 7) digits.takeLast(10) else number.trim().lowercase(turkish)
    }

    private fun contactFor(number: String): Person? = contactByKey[numberKey(number)]

    private fun isHidden(number: String) = number.isBlank() || number.startsWith("-")

    /** Senders like "TURKCELL" cannot be called or answered. */
    private fun dialable(address: String) = address.any(Char::isDigit) && address.none(Char::isLetter)

    private fun formatNumber(number: String): String =
        if (dialable(number)) PhoneNumberUtils.formatNumber(number, "TR") ?: number else number

    private fun nameFor(number: String): String = when {
        isHidden(number) -> "Gizli numara"
        else -> contactFor(number)?.name ?: formatNumber(number)
    }

    // ---- chrome: title, iPhone status, attention, current call, badges ----

    private fun render() {
        renderChrome()
        renderContent()
    }

    private fun renderChrome() {
        val conversation = openThread
        backToThreads.isEnabled = conversation != null
        binding.phoneBack.visibility = visibleIf(conversation != null)
        binding.phoneSettings.visibility = visibleIf(conversation == null)
        binding.phoneCall.visibility = visibleIf(conversation != null && dialable(conversation))
        binding.phoneStatus.visibility = visibleIf(conversation == null)
        binding.phoneNav.visibility = visibleIf(conversation == null)
        binding.phoneNavDivider.visibility = visibleIf(conversation == null)
        if (conversation != null) {
            val person = contactFor(conversation)
            binding.phoneTitle.text = nameFor(conversation)
            binding.phoneTitle.textSize = 20f
            binding.phoneTitleDetail.text = formatNumber(conversation)
            binding.phoneTitleDetail.visibility = visibleIf(person != null)
            binding.phoneCall.contentDescription = "${nameFor(conversation)} ara"
        } else {
            binding.phoneTitle.text = tab.title
            binding.phoneTitle.textSize = 28f
            binding.phoneTitleDetail.visibility = View.GONE
        }
        updateStatus()
        updateAttention()
        updateCallCard()
        updateBadges()
    }

    private fun visibleIf(condition: Boolean) = if (condition) View.VISIBLE else View.GONE

    private fun updateStatus() {
        val status = IPhoneReachability.current()
        binding.phoneStatusText.text = status.short
        val dot = when (status.level) {
            IPhoneReachability.Level.CONNECTED, IPhoneReachability.Level.BLUETOOTH -> R.color.status_good
            IPhoneReachability.Level.PUSH -> R.color.accent
            IPhoneReachability.Level.OFFLINE -> R.color.status_attention
            IPhoneReachability.Level.UNPAIRED -> R.color.text_muted
        }
        binding.phoneStatusDot.backgroundTintList = ColorStateList.valueOf(views.color(dot))
        binding.phoneStatus.contentDescription = "${status.short}. ${status.detail} Ayarlar için dokun."
    }

    private fun updateAttention() {
        val dialer = getSystemService(TelecomManager::class.java)?.defaultDialerPackage == packageName
        val card = binding.attentionCard
        when {
            openThread != null -> card.visibility = View.GONE
            !dialer -> {
                card.visibility = View.VISIBLE
                binding.attentionText.text = "Aramalar için bu uygulama varsayılan telefon uygulaması olmalı."
                binding.attentionAction.text = "Ayarla"
                binding.attentionAction.setOnClickListener { requestDialerRole() }
            }
            !GatewayService.isRunning -> {
                card.visibility = View.VISIBLE
                binding.attentionText.text = "Telefon geçidi kapalı; iPhone bağlanamaz."
                binding.attentionAction.text = "Başlat"
                binding.attentionAction.setOnClickListener {
                    startBridge()
                    handler.postDelayed({ renderChrome() }, 1500)
                }
            }
            else -> card.visibility = View.GONE
        }
    }

    private fun isCallLive() = CallManager.currentCallState !in listOf("idle", "disconnected")

    private fun updateCallCard() {
        val live = isCallLive()
        binding.currentCallCard.visibility = visibleIf(live)
        if (!live) return
        val number = CallManager.currentPhoneNumber.orEmpty()
        val who = if (number.isBlank()) "Gizli numara" else nameFor(number)
        binding.currentCallState.text = when (CallManager.currentCallState) {
            "ringing" -> "Gelen arama"
            "dialing", "connecting" -> "Aranıyor"
            "holding" -> "Arama beklemede"
            else -> "Görüşme sürüyor"
        }
        binding.currentCallNumber.text = who
        binding.currentCallCard.contentDescription = "${binding.currentCallState.text}, $who. Arama ekranını aç."
    }

    private fun openCurrentCall() {
        if (!isCallLive()) return
        startActivity(Intent(this, CallActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        ))
    }

    /** Missed calls since Recents was last looked at. */
    private fun updateBadges() {
        if (!loaded) return
        val now = System.currentTimeMillis()
        if (tab == Tab.RECENTS && openThread == null) prefs.edit().putLong("recents_seen", now).apply()
        val seen = prefs.getLong("recents_seen", 0L)
        val missed = recents.count { kind(it.type) == Kind.MISSED && it.time > seen }
        val badge = binding.phoneNav.getOrCreateBadge(R.id.nav_recents)
        badge.isVisible = missed > 0
        if (missed > 0) badge.number = missed
    }

    // ---- content ----

    private fun renderContent() {
        contentUpdater = null
        keypadMatch = null
        binding.phoneContent.removeAllViews()
        val conversation = openThread
        when {
            conversation != null -> showConversation(conversation)
            tab == Tab.RECENTS -> showRecents()
            tab == Tab.CONTACTS -> showContacts()
            tab == Tab.KEYPAD -> showKeypad()
            else -> showThreads()
        }
    }

    private inner class RowAdapter(var items: List<Any>, val bind: (PhoneViews.Row, Any) -> Unit) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is Header) 0 else 1
        override fun isEnabled(position: Int) = items[position] !is Header
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            if (item is Header) {
                val view = convertView as? TextView ?: views.header()
                view.text = item.text
                return view
            }
            val row = convertView?.tag as? PhoneViews.Row ?: views.row().also { it.root.tag = it }
            bind(row, item)
            return row.root
        }
    }

    private fun listView() = ListView(this).apply {
        divider = null
        dividerHeight = 0
        selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        clipToPadding = false
        setPadding(0, 0, 0, views.dp(88))
    }

    private fun showEmpty(icon: Int, text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(views.dp(32), 0, views.dp(32), views.dp(48))
        }
        column.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(views.color(R.color.text_muted))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(views.dp(48), views.dp(48)))
        column.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(views.color(R.color.text_secondary))
            setPadding(0, views.dp(12), 0, views.dp(12))
        })
        if (actionLabel != null && action != null) {
            column.addView(MaterialButton(this).apply {
                this.text = actionLabel
                isAllCaps = false
                setOnClickListener { action() }
            })
        }
        binding.phoneContent.addView(column, FrameLayout.LayoutParams(-1, -1))
    }

    // Recents

    private fun kind(type: Int) = when (type) {
        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> Kind.INCOMING
        CallLog.Calls.OUTGOING_TYPE -> Kind.OUTGOING
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> Kind.MISSED
        else -> Kind.OTHER
    }

    private fun recentItems(): List<Any> {
        // Consecutive calls with the same number, kind and day read as one line with a count.
        val entries = ArrayList<RecentEntry>()
        for (call in recents) {
            val last = entries.lastOrNull()
            if (last != null && numberKey(last.call.number) == numberKey(call.number) &&
                kind(last.call.type) == kind(call.type) && dayNumber(last.call.time) == dayNumber(call.time)) {
                entries[entries.lastIndex] = last.copy(count = last.count + 1)
            } else {
                entries.add(RecentEntry(call, 1))
            }
        }
        val items = ArrayList<Any>()
        var header: String? = null
        for (entry in entries) {
            val title = dayHeader(entry.call.time)
            if (title != header) {
                items.add(Header(title))
                header = title
            }
            items.add(entry)
        }
        return items
    }

    private fun showRecents() {
        if (!has(Manifest.permission.READ_CALL_LOG)) {
            return showEmpty(R.drawable.ic_pb_history, "Arama geçmişi için izin gerekli.", "İzin ver") { requestPermissionsIfNeeded() }
        }
        if (recents.isEmpty()) return showEmpty(R.drawable.ic_pb_history, if (loaded) "Henüz arama yok." else "")
        val adapter = RowAdapter(recentItems()) { row, item -> bindRecent(row, item as RecentEntry) }
        val list = listView()
        list.adapter = adapter
        binding.phoneContent.addView(list)
        contentUpdater = {
            adapter.items = recentItems()
            adapter.notifyDataSetChanged()
        }
    }

    private fun bindRecent(row: PhoneViews.Row, entry: RecentEntry) {
        val call = entry.call
        val hidden = isHidden(call.number)
        val person = if (hidden) null else contactFor(call.number)
        val name = nameFor(call.number)
        val kind = kind(call.type)
        row.title.text = if (entry.count > 1) "$name (${entry.count})" else name
        row.title.setTextColor(views.color(if (kind == Kind.MISSED) R.color.status_bad else R.color.text_primary))
        views.bindAvatar(row.avatar, person?.name, person?.photo)
        row.subtitleIcon.visibility = View.VISIBLE
        row.subtitleIcon.setImageResource(when (kind) {
            Kind.OUTGOING -> R.drawable.ic_pb_call_made
            Kind.MISSED -> R.drawable.ic_pb_call_missed
            else -> R.drawable.ic_pb_call_received
        })
        row.subtitleIcon.imageTintList = ColorStateList.valueOf(views.color(
            when (kind) {
                Kind.MISSED -> R.color.status_bad
                Kind.INCOMING -> R.color.status_good
                else -> R.color.text_secondary
            }
        ))
        val parts = mutableListOf(when (call.type) {
            CallLog.Calls.INCOMING_TYPE -> "Gelen"
            CallLog.Calls.OUTGOING_TYPE -> "Giden"
            CallLog.Calls.MISSED_TYPE -> "Cevapsız"
            CallLog.Calls.REJECTED_TYPE -> "Reddedildi"
            CallLog.Calls.BLOCKED_TYPE -> "Engellendi"
            CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "Başka cihazda"
            CallLog.Calls.VOICEMAIL_TYPE -> "Sesli mesaj"
            else -> "Arama"
        })
        if (call.seconds > 0 && kind != Kind.MISSED) parts.add(duration(call.seconds))
        when (call.carrier) {
            "iphone" -> parts.add("iPhone'da")
            "redmi" -> parts.add("Redmi'de")
        }
        row.subtitle.text = parts.joinToString(" · ")
        row.trailing.visibility = View.VISIBLE
        row.trailing.text = timeLabel(call.time)
        if (!hidden && dialable(call.number)) {
            row.action.visibility = View.VISIBLE
            row.action.setImageResource(R.drawable.ic_pb_message)
            row.action.contentDescription = "$name kişisine mesaj yaz"
            row.action.setOnClickListener { openConversation(call.number) }
            row.root.setOnClickListener { callNumber(call.number) }
        } else {
            row.action.visibility = View.GONE
            row.root.setOnClickListener(null)
            row.root.isClickable = false
        }
        row.root.contentDescription = "$name, ${parts.joinToString(", ")}, ${timeLabel(call.time)}"
    }

    private fun duration(seconds: Long): String = when {
        seconds >= 3600 -> "${seconds / 3600} sa ${seconds / 60 % 60} dk"
        seconds >= 60 -> if (seconds % 60 == 0L) "${seconds / 60} dk" else "${seconds / 60} dk ${seconds % 60} sn"
        else -> "$seconds sn"
    }

    // Contacts

    private fun contactItems(): List<Any> {
        val query = contactQuery.trim().lowercase(turkish)
        val digits = query.filter(Char::isDigit)
        val filtered = if (query.isEmpty()) contacts else contacts.filter {
            it.name.lowercase(turkish).contains(query) ||
                (digits.length >= 2 && it.number.filter(Char::isDigit).contains(digits))
        }
        if (query.isNotEmpty()) return filtered
        val items = ArrayList<Any>()
        var header: String? = null
        for (person in filtered) {
            val first = person.name.trim().firstOrNull()
            val letter = if (first != null && first.isLetter()) first.toString().uppercase(turkish) else "#"
            if (letter != header) {
                items.add(Header(letter))
                header = letter
            }
            items.add(person)
        }
        return items
    }

    private fun showContacts() {
        if (!has(Manifest.permission.READ_CONTACTS)) {
            return showEmpty(R.drawable.ic_pb_person, "Kişiler için izin gerekli.", "İzin ver") { requestPermissionsIfNeeded() }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply {
            background = ContextCompat.getDrawable(this@PhoneActivity, R.drawable.pb_field)
            hint = "Kişilerde ara"
            setSingleLine(true)
            textSize = 16f
            setTextColor(views.color(R.color.text_primary))
            setHintTextColor(views.color(R.color.text_muted))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_pb_search, 0, 0, 0)
            compoundDrawablePadding = views.dp(10)
            compoundDrawableTintList = ColorStateList.valueOf(views.color(R.color.text_secondary))
            setPadding(views.dp(16), 0, views.dp(16), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            setText(contactQuery)
            contentDescription = "Kişilerde ara"
        }
        column.addView(search, LinearLayout.LayoutParams(-1, views.dp(48)).apply {
            setMargins(views.dp(16), views.dp(4), views.dp(16), views.dp(4))
        })
        val empty = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(views.color(R.color.text_secondary))
            setPadding(views.dp(24), views.dp(32), views.dp(24), 0)
        }
        column.addView(empty, LinearLayout.LayoutParams(-1, -2))
        val adapter = RowAdapter(contactItems()) { row, item -> bindContact(row, item as Person) }
        val list = listView()
        list.adapter = adapter
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        fun update() {
            adapter.items = contactItems()
            adapter.notifyDataSetChanged()
            empty.visibility = visibleIf(adapter.items.isEmpty())
            empty.text = when {
                contacts.isEmpty() && loaded -> "Rehberde kişi yok."
                contacts.isEmpty() -> ""
                else -> "\"${contactQuery.trim()}\" bulunamadı."
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                contactQuery = s?.toString().orEmpty()
                update()
            }
        })
        update()
        binding.phoneContent.addView(column)
        contentUpdater = { update() }
    }

    private fun bindContact(row: PhoneViews.Row, person: Person) {
        row.title.text = person.name
        row.title.setTextColor(views.color(R.color.text_primary))
        views.bindAvatar(row.avatar, person.name, person.photo)
        row.subtitleIcon.visibility = View.GONE
        row.subtitle.text = listOf(person.label, formatNumber(person.number)).filter { it.isNotBlank() }.joinToString(" · ")
        row.trailing.visibility = View.GONE
        row.root.setOnClickListener { callNumber(person.number) }
        row.root.contentDescription = "${person.name}, ${row.subtitle.text}. Aramak için dokun."
        if (dialable(person.number)) {
            row.action.visibility = View.VISIBLE
            row.action.setImageResource(R.drawable.ic_pb_message)
            row.action.contentDescription = "${person.name} kişisine mesaj yaz"
            row.action.setOnClickListener { openConversation(person.number) }
        } else {
            row.action.visibility = View.GONE
        }
    }

    // Keypad

    private fun showKeypad() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(views.dp(24), 0, views.dp(24), views.dp(12))
        }
        val number = EditText(this).apply {
            background = null
            textSize = 34f
            gravity = Gravity.CENTER
            setSingleLine(true)
            showSoftInputOnFocus = false
            inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(views.color(R.color.text_primary))
            setText(draftNumber)
            setSelection(text.length)
            contentDescription = "Aranacak numara"
        }
        column.addView(number, LinearLayout.LayoutParams(-1, -2).apply { topMargin = views.dp(8) })
        val match = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(views.color(R.color.accent))
            minHeight = views.dp(32)
            background = views.selectable()
        }
        column.addView(match, LinearLayout.LayoutParams(-2, -2))
        keypadMatch = match
        column.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        fun insert(text: String) {
            val start = number.selectionStart.coerceAtLeast(0)
            val end = number.selectionEnd.coerceAtLeast(start)
            number.text.replace(start, end, text)
        }
        val keyViews = ArrayList<View>()
        val rowViews = ArrayList<View>()
        val keys = listOf(
            "1" to "", "2" to "ABC", "3" to "DEF",
            "4" to "GHİ", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
            "*" to "", "0" to "+", "#" to ""
        )
        for (rowKeys in keys.chunked(3)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((digit, letters) in rowKeys) {
                val key = keypadKey(digit, letters)
                key.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    insert(digit)
                    if (CallManager.currentCallState == "active") CallManager.playDtmf(digit[0])
                }
                if (digit == "0") key.setOnLongClickListener { insert("+"); true }
                val cell = FrameLayout(this)
                cell.addView(key, FrameLayout.LayoutParams(views.dp(76), views.dp(76), Gravity.CENTER))
                line.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
                keyViews.add(key)
            }
            column.addView(line, LinearLayout.LayoutParams(-1, views.dp(88)))
            rowViews.add(line)
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottom.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f))
        val call = ImageButton(this).apply {
            background = ContextCompat.getDrawable(this@PhoneActivity, R.drawable.pb_call_button)
            setImageResource(R.drawable.ic_pb_call)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = "Ara"
            elevation = views.dp(2).toFloat()
            setOnClickListener {
                val typed = number.text.toString().trim()
                if (typed.isEmpty()) {
                    // Like most phones: an empty call key brings back the last number dialled.
                    recents.firstOrNull { it.type == CallLog.Calls.OUTGOING_TYPE && !isHidden(it.number) }?.let {
                        number.setText(it.number)
                        number.setSelection(number.text.length)
                    }
                } else {
                    callNumber(typed)
                }
            }
        }
        val callCell = FrameLayout(this)
        callCell.addView(call, FrameLayout.LayoutParams(views.dp(72), views.dp(72), Gravity.CENTER))
        bottom.addView(callCell, LinearLayout.LayoutParams(0, -1, 1f))
        val delete = ImageButton(this).apply {
            background = views.selectable(borderless = true)
            setImageResource(R.drawable.ic_pb_backspace)
            imageTintList = ColorStateList.valueOf(views.color(R.color.text_secondary))
            contentDescription = "Sil"
            setOnClickListener {
                val start = number.selectionStart
                val end = number.selectionEnd
                if (end > start) number.text.delete(start, end)
                else if (start > 0) number.text.delete(start - 1, start)
            }
            setOnLongClickListener {
                number.text.clear()
                true
            }
        }
        val deleteCell = FrameLayout(this)
        deleteCell.addView(delete, FrameLayout.LayoutParams(views.dp(56), views.dp(56), Gravity.CENTER))
        bottom.addView(deleteCell, LinearLayout.LayoutParams(0, -1, 1f))
        column.addView(bottom, LinearLayout.LayoutParams(-1, views.dp(88)))
        rowViews.add(bottom)

        // Keys shrink when the screen is short (a notice or a call card above), so the call key
        // always stays in view.
        fun fit() {
            val used = column.paddingTop + column.paddingBottom + number.height + views.dp(8) + match.height
            val row = ((column.height - used) / rowViews.size).coerceAtMost(views.dp(88))
            if (row <= 0) return
            val size = (row - views.dp(10)).coerceIn(views.dp(48), views.dp(76))
            var changed = false
            for (line in rowViews) {
                if (line.layoutParams.height != row) {
                    line.layoutParams.height = row
                    changed = true
                }
            }
            for (key in keyViews + call) {
                val wanted = if (key === call) (size - views.dp(4)).coerceAtLeast(views.dp(48)) else size
                if (key.layoutParams.width != wanted) {
                    key.layoutParams.width = wanted
                    key.layoutParams.height = wanted
                    changed = true
                }
            }
            if (changed) column.requestLayout()
        }
        column.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) column.post { fit() }
        }

        fun update() {
            draftNumber = number.text.toString()
            delete.visibility = if (draftNumber.isEmpty()) View.INVISIBLE else View.VISIBLE
            updateKeypadMatch(number)
        }
        number.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = update()
        })
        update()
        binding.phoneContent.addView(column)
        contentUpdater = { updateKeypadMatch(number) }
    }

    private fun keypadKey(digit: String, letters: String): View {
        val key = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@PhoneActivity, R.drawable.pb_round_key)
            isClickable = true
            isFocusable = true
            contentDescription = if (letters.isEmpty()) digit else "$digit $letters"
        }
        key.addView(TextView(this).apply {
            text = digit
            textSize = 28f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(views.color(R.color.text_primary))
        })
        if (letters.isNotEmpty()) {
            key.addView(TextView(this).apply {
                text = letters
                textSize = 10f
                letterSpacing = 0.15f
                gravity = Gravity.CENTER
                setTextColor(views.color(R.color.text_secondary))
            })
        }
        return key
    }

    /** Shows whose number is typed, or the first contact it could belong to (tap to use it). */
    private fun updateKeypadMatch(number: EditText) {
        val match = keypadMatch ?: return
        val typed = number.text.toString()
        val digits = typed.filter(Char::isDigit)
        val exact = if (digits.length >= 7) contactFor(typed) else null
        val partial = if (exact == null && digits.length >= 3) {
            contacts.firstOrNull { it.number.filter(Char::isDigit).contains(digits) }
        } else null
        when {
            exact != null -> {
                match.text = exact.name
                match.setOnClickListener(null)
                match.isClickable = false
            }
            partial != null -> {
                match.text = "${partial.name} · ${formatNumber(partial.number)}"
                match.contentDescription = "${partial.name} numarasını kullan"
                match.setOnClickListener {
                    number.setText(partial.number)
                    number.setSelection(number.text.length)
                }
            }
            else -> {
                match.text = ""
                match.setOnClickListener(null)
                match.isClickable = false
            }
        }
    }

    // Messages

    private fun threads(): List<Conversation> = messages.groupBy { numberKey(it.address) }
        .values.mapNotNull { group -> group.maxByOrNull(Message::time)?.let { Conversation(it.address, it) } }
        .sortedByDescending { it.latest.time }

    private fun showThreads() {
        if (!has(Manifest.permission.READ_SMS)) {
            return showEmpty(R.drawable.ic_pb_message, "Mesajlar için izin gerekli.", "İzin ver") { requestPermissionsIfNeeded() }
        }
        val frame = FrameLayout(this)
        val adapter = RowAdapter(threads()) { row, item -> bindThread(row, item as Conversation) }
        if (adapter.items.isEmpty()) {
            showEmpty(R.drawable.ic_pb_message, if (loaded) "Henüz mesaj yok." else "")
        } else {
            val list = listView()
            list.adapter = adapter
            frame.addView(list, FrameLayout.LayoutParams(-1, -1))
            contentUpdater = {
                adapter.items = threads()
                adapter.notifyDataSetChanged()
            }
        }
        frame.addView(FloatingActionButton(this).apply {
            setImageResource(R.drawable.ic_pb_add)
            backgroundTintList = ColorStateList.valueOf(views.color(R.color.accent))
            imageTintList = ColorStateList.valueOf(views.color(R.color.pb_on_accent))
            contentDescription = "Yeni mesaj"
            setOnClickListener { showNewMessage() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, views.dp(16), views.dp(16))
        })
        binding.phoneContent.addView(frame, FrameLayout.LayoutParams(-1, -1))
    }

    private fun bindThread(row: PhoneViews.Row, thread: Conversation) {
        val person = contactFor(thread.address)
        val name = nameFor(thread.address)
        row.title.text = name
        row.title.setTextColor(views.color(R.color.text_primary))
        views.bindAvatar(row.avatar, person?.name ?: thread.address.takeIf { it.any(Char::isLetter) }, person?.photo)
        row.subtitleIcon.visibility = View.GONE
        val body = thread.latest.body.replace('\n', ' ')
        row.subtitle.text = when {
            thread.latest.failed -> "Gönderilemedi: $body"
            thread.latest.sent -> "Sen: $body"
            else -> body
        }
        row.trailing.visibility = View.VISIBLE
        row.trailing.text = timeLabel(thread.latest.time)
        row.action.visibility = View.GONE
        row.root.setOnClickListener { openConversation(thread.address) }
        row.root.contentDescription = "$name, ${row.subtitle.text}, ${row.trailing.text}"
    }

    private fun openConversation(address: String) {
        if (tab != Tab.MESSAGES) {
            tab = Tab.MESSAGES
            prefs.edit().putString("tab", tab.name).apply()
            suppressNav = true
            binding.phoneNav.selectedItemId = tab.itemId
            suppressNav = false
        }
        openThread = address
        render()
    }

    private fun conversationItems(address: String): List<Any> {
        val key = numberKey(address)
        val items = ArrayList<Any>()
        var day = -1L
        for (message in messages.filter { numberKey(it.address) == key }.sortedBy(Message::time)) {
            val today = dayNumber(message.time)
            if (today != day) {
                items.add(Header(conversationDay(message.time)))
                day = today
            }
            items.add(message)
        }
        return items
    }

    private fun showConversation(address: String) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isStackFromBottom = true
            transcriptMode = AbsListView.TRANSCRIPT_MODE_NORMAL
            clipToPadding = false
            setPadding(views.dp(12), views.dp(8), views.dp(12), views.dp(8))
        }
        val adapter = object : BaseAdapter() {
            var items = conversationItems(address)
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (items[position] is Header) 0 else 1
            override fun isEnabled(position: Int) = false
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = items[position]
                if (item is Header) {
                    val view = convertView as? TextView ?: TextView(this@PhoneActivity).apply {
                        textSize = 12f
                        gravity = Gravity.CENTER
                        setTextColor(views.color(R.color.text_secondary))
                        setPadding(0, views.dp(14), 0, views.dp(6))
                        layoutParams = AbsListView.LayoutParams(-1, -2)
                    }
                    view.text = item.text
                    return view
                }
                return bubble(item as Message, convertView as? LinearLayout)
            }
        }
        list.adapter = adapter
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(View(this).apply { setBackgroundColor(views.color(R.color.border_subtle)) },
            LinearLayout.LayoutParams(-1, views.dp(1)))
        if (dialable(address)) {
            column.addView(composer(address), LinearLayout.LayoutParams(-1, -2))
        } else {
            column.addView(TextView(this).apply {
                text = "Bu gönderene yanıt verilemez."
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(views.color(R.color.text_secondary))
                setBackgroundColor(views.color(R.color.bg_card))
                setPadding(views.dp(16), views.dp(16), views.dp(16), views.dp(16))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        binding.phoneContent.addView(column)
        contentUpdater = {
            adapter.items = conversationItems(address)
            adapter.notifyDataSetChanged()
            renderChrome()
        }
    }

    private fun bubble(message: Message, recycled: LinearLayout?): View {
        val container = recycled ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = AbsListView.LayoutParams(-1, -2)
            setPadding(0, views.dp(3), 0, views.dp(3))
            addView(TextView(this@PhoneActivity).apply {
                textSize = 16f
                setTextIsSelectable(true)
                setPadding(views.dp(14), views.dp(9), views.dp(14), views.dp(9))
                maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
            }, LinearLayout.LayoutParams(-2, -2))
            addView(TextView(this@PhoneActivity).apply {
                textSize = 11f
                setPadding(views.dp(6), views.dp(2), views.dp(6), 0)
            }, LinearLayout.LayoutParams(-2, -2))
        }
        val text = container.getChildAt(0) as TextView
        val meta = container.getChildAt(1) as TextView
        container.gravity = if (message.sent) Gravity.END else Gravity.START
        text.text = message.body
        text.setTextColor(views.color(if (message.sent) R.color.pb_on_accent else R.color.text_primary))
        text.background = GradientDrawable().apply {
            cornerRadius = views.dp(18).toFloat()
            setColor(views.color(if (message.sent) R.color.accent else R.color.pb_surface_variant))
        }
        val time = SimpleDateFormat("HH:mm", turkish).format(Date(message.time))
        meta.text = if (message.failed) "$time · Gönderilemedi" else time
        meta.setTextColor(views.color(if (message.failed) R.color.status_bad else R.color.text_secondary))
        return container
    }

    private fun composer(address: String): View {
        val key = numberKey(address)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(views.color(R.color.bg_card))
            setPadding(views.dp(12), views.dp(8), views.dp(12), views.dp(10))
        }
        val input = EditText(this).apply {
            background = ContextCompat.getDrawable(this@PhoneActivity, R.drawable.pb_field)
            hint = "Mesaj"
            textSize = 16f
            minHeight = views.dp(48)
            maxLines = 5
            setTextColor(views.color(R.color.text_primary))
            setHintTextColor(views.color(R.color.text_muted))
            setPadding(views.dp(18), views.dp(12), views.dp(18), views.dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(smsDrafts[key].orEmpty())
            contentDescription = "Mesaj yaz"
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val send = ImageButton(this).apply {
            background = ContextCompat.getDrawable(this@PhoneActivity, R.drawable.pb_send_button)
            setImageResource(R.drawable.ic_pb_send)
            imageTintList = ColorStateList.valueOf(views.color(R.color.pb_on_accent))
            contentDescription = "Gönder"
        }
        bar.addView(send, LinearLayout.LayoutParams(views.dp(48), views.dp(48)).apply { marginStart = views.dp(8) })
        fun updateSend() {
            val ready = input.text.isNotBlank()
            send.isEnabled = ready
            send.alpha = if (ready) 1f else 0.4f
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                smsDrafts[key] = s?.toString().orEmpty()
                updateSend()
            }
        })
        updateSend()
        send.setOnClickListener {
            val body = input.text.toString()
            if (body.isBlank()) return@setOnClickListener
            send.isEnabled = false
            val immediate = SmsSender.send(this, address, body) { sent, _ ->
                runOnUiThread {
                    if (sent) {
                        smsDrafts.remove(key)
                        input.text.clear()
                        refreshPhoneData()
                    } else {
                        Toast.makeText(this, "SMS gönderilemedi", Toast.LENGTH_LONG).show()
                    }
                    updateSend()
                }
            }
            if (immediate != null) {
                updateSend()
                Toast.makeText(this, "SMS gönderilemedi", Toast.LENGTH_LONG).show()
            }
        }
        return bar
    }

    private fun showNewMessage() {
        val options = contacts.filter { dialable(it.number) }.map { "${it.name} · ${formatNumber(it.number)}" to it.number }
        var picked: String? = null
        val input = AutoCompleteTextView(this).apply {
            hint = "Ad veya numara"
            threshold = 1
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            setAdapter(ArrayAdapter(this@PhoneActivity, android.R.layout.simple_dropdown_item_1line, options.map { it.first }))
            setOnItemClickListener { parent, _, position, _ ->
                val label = parent.getItemAtPosition(position) as String
                picked = options.firstOrNull { it.first == label }?.second
            }
        }
        val frame = FrameLayout(this).apply {
            setPadding(views.dp(24), views.dp(8), views.dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Yeni mesaj")
            .setView(frame)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Yaz") { _, _ ->
                val typed = input.text.toString().trim()
                val address = picked
                    ?: contacts.firstOrNull { it.name.equals(typed, ignoreCase = true) && dialable(it.number) }?.number
                    ?: typed.takeIf(::dialable)
                if (address == null) {
                    Toast.makeText(this, "Bir kişi seç veya numara yaz", Toast.LENGTH_SHORT).show()
                } else {
                    openConversation(address)
                }
            }
            .show()
        input.requestFocus()
    }

    // ---- time labels ----

    private fun dayNumber(time: Long): Long = (time + TimeZone.getDefault().getOffset(time)) / 86_400_000L

    private fun daysAgo(time: Long) = dayNumber(System.currentTimeMillis()) - dayNumber(time)

    private fun timeLabel(time: Long): String {
        val days = daysAgo(time)
        val pattern = when {
            days <= 0L -> "HH:mm"
            days == 1L -> return "Dün"
            days < 7L -> "EEEE"
            SimpleDateFormat("yyyy", turkish).format(Date(time)) == SimpleDateFormat("yyyy", turkish).format(Date()) -> "d MMM"
            else -> "d.MM.yyyy"
        }
        return SimpleDateFormat(pattern, turkish).format(Date(time))
    }

    private fun dayHeader(time: Long) = when (daysAgo(time)) {
        0L -> "Bugün"
        1L -> "Dün"
        in 2L..6L -> "Bu hafta"
        else -> "Daha önce"
    }

    private fun conversationDay(time: Long): String = when (daysAgo(time)) {
        0L -> "Bugün"
        1L -> "Dün"
        in 2L..6L -> SimpleDateFormat("EEEE", turkish).format(Date(time))
        else -> SimpleDateFormat("d MMMM yyyy", turkish).format(Date(time))
    }

    // ---- actions ----

    private fun openSettings() = startActivity(Intent(this, MainActivity::class.java))

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
        if (isHidden(number)) return
        if (!CallManager.dial(this, number)) {
            Toast.makeText(this, "Arama başlatılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCallStateChanged(callId: String, state: String, number: String?) {
        runOnUiThread {
            updateCallCard()
            if (state == "disconnected" || state == "idle") handler.postDelayed(reload, 1500)
        }
    }

    override fun onLogMessage(message: String) = Unit
}
