package com.ilkay.redmigateway.journal

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Telephony
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Durable, replayable SMS history (received and sent) for the iPhone.
 *
 * Mirrors the system SMS provider, which the default SMS app and the platform fill for every
 * incoming message and every message this gateway sends. Each new message or status change gets
 * a new sequence number, so the iPhone syncs with "after seq N" and applies the newest state of a
 * message by its id. Messages that no SMS app stored are kept from the SMS_RECEIVED broadcast.
 */
class SmsJournal private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "gateway_sms_journal.db", null, 1) {

    data class Entry(
        val seq: Long,
        val id: Long,
        val address: String,
        val body: String,
        val date: Long,
        val box: String,
        val read: Boolean
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("seq", seq)
            .put("id", id)
            .put("address", address)
            .put("body", body)
            .put("date", date)
            .put("box", box)
            .put("read", read)
    }

    interface Listener {
        fun onSmsJournalEntry(entry: Entry)
    }

    companion object {
        private const val TAG = "SmsJournal"
        private const val RECHECK_RECENT = 60
        @Volatile private var instance: SmsJournal? = null

        fun get(context: Context): SmsJournal = instance ?: synchronized(this) {
            instance ?: SmsJournal(context).also { instance = it }
        }

        fun boxName(type: Int): String = when (type) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
            Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
            Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> "sending"
            Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
            else -> "other"
        }
    }

    private val appContext = context.applicationContext
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val worker = HandlerThread("SmsJournal").apply { start() }
    private val handler = Handler(worker.looper)
    private var observerRegistered = false
    private val importRunnable = Runnable { importNow() }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = scheduleImport(400L)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sms (seq INTEGER PRIMARY KEY AUTOINCREMENT, sms_id INTEGER UNIQUE NOT NULL, " +
                "address TEXT NOT NULL, body TEXT NOT NULL, date INTEGER NOT NULL, box TEXT NOT NULL, read INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v INTEGER NOT NULL)")
    }

    private fun meta(db: SQLiteDatabase, key: String): Long? =
        db.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun setMeta(db: SQLiteDatabase, key: String, value: Long) {
        db.execSQL("INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)", arrayOf<Any>(key, value))
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun start() {
        handler.post {
            if (!observerRegistered && hasPermission()) {
                appContext.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
                observerRegistered = true
            }
            importNow()
        }
    }

    fun scheduleImport(delayMs: Long = 0L) {
        handler.removeCallbacks(importRunnable)
        handler.postDelayed(importRunnable, delayMs)
    }

    /**
     * An SMS_RECEIVED broadcast arrived. If no SMS app stores it within a few seconds, keep it
     * here with a synthetic negative id so it is never lost.
     */
    fun noteReceived(address: String, body: String, date: Long) {
        scheduleImport(800L)
        handler.postDelayed({
            if (!existsInProvider(address, body, date)) insertFallback(address, body, date)
        }, 5000L)
    }

    private fun hasPermission() =
        appContext.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun existsInProvider(address: String, body: String, date: Long): Boolean = try {
        appContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.BODY} = ? AND ${Telephony.Sms.DATE} BETWEEN ? AND ?",
            arrayOf(body, (date - 120_000).toString(), (date + 120_000).toString()), null
        )?.use { it.count > 0 } ?: false
    } catch (_: Exception) {
        false
    }

    @Synchronized
    private fun insertFallback(address: String, body: String, date: Long) {
        val db = writableDatabase
        val syntheticId = -(date / 1000 * 1000 + (body.hashCode() and 0x3FF))
        val values = ContentValues().apply {
            put("sms_id", syntheticId); put("address", address); put("body", body)
            put("date", date); put("box", "inbox"); put("read", 0)
        }
        val seq = db.insertWithOnConflict("sms", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (seq > 0) publish(Entry(seq, syntheticId, address, body, date, "inbox", false))
    }

    @Synchronized
    private fun importNow() {
        if (!hasPermission()) return
        val db = writableDatabase
        val known = HashMap<Long, Pair<String, Boolean>>()
        db.rawQuery("SELECT sms_id, box, read FROM sms ORDER BY seq DESC LIMIT ?", arrayOf((RECHECK_RECENT * 4).toString())).use { c ->
            while (c.moveToNext()) known[c.getLong(0)] = c.getString(1) to (c.getInt(2) != 0)
        }
        val maxKnown = db.rawQuery("SELECT COALESCE(MAX(sms_id), 0) FROM sms", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        // First run: keep 30 days so the iPhone gets useful history without a flood. Provider rows
        // up to "floor" existed before the journal and are only kept if the first run took them.
        val firstImport = meta(db, "initialized") == null
        val since = if (firstImport) System.currentTimeMillis() - 30L * 24 * 3600 * 1000 else 0L
        val floor = meta(db, "floor") ?: 0L
        val watermark = maxOf(maxKnown, floor)
        var newest = 0L
        val changed = ArrayList<Entry>()
        try {
            appContext.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
                    Telephony.Sms.TYPE, Telephony.Sms.READ),
                "${Telephony.Sms.TYPE} != ?",
                arrayOf(Telephony.Sms.MESSAGE_TYPE_DRAFT.toString()),
                "${Telephony.Sms._ID} DESC"
            )?.use { c ->
                var recent = 0
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    newest = maxOf(newest, id)
                    if (firstImport) {
                        if (c.getLong(3) < since) continue
                    } else {
                        // New rows are always above the watermark; below it only recent rows can
                        // still change (sending -> sent/failed, unread -> read).
                        if (id <= watermark && recent >= RECHECK_RECENT) break
                        recent += 1
                        if (id <= floor && known[id] == null) continue
                    }
                    val box = boxName(c.getInt(4))
                    val read = c.getInt(5) != 0
                    val previous = known[id]
                    if (previous != null && previous.first == box && previous.second == read) continue
                    val entry = upsert(db, id, c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3), box, read)
                    if (entry != null) changed.add(entry)
                }
            }
            if (firstImport) {
                setMeta(db, "floor", newest)
                setMeta(db, "initialized", System.currentTimeMillis())
            }
        } catch (error: Exception) {
            Log.w(TAG, "SMS import failed: ${error.javaClass.simpleName}")
        }
        // The first import is history, not news; connected iPhones fetch it with sync.
        if (firstImport) return
        changed.sortBy { it.seq }
        for (entry in changed) publish(entry)
    }

    private fun upsert(db: SQLiteDatabase, id: Long, address: String, body: String, date: Long, box: String, read: Boolean): Entry? {
        db.delete("sms", "sms_id = ?", arrayOf(id.toString()))
        val values = ContentValues().apply {
            put("sms_id", id); put("address", address); put("body", body)
            put("date", date); put("box", box); put("read", if (read) 1 else 0)
        }
        val seq = db.insert("sms", null, values)
        return if (seq > 0) Entry(seq, id, address, body, date, box, read) else null
    }

    private fun publish(entry: Entry) {
        for (l in listeners) runCatching { l.onSmsJournalEntry(entry) }
    }

    @Synchronized
    fun readAfter(afterSeq: Long, limit: Int = 200): Pair<List<Entry>, Boolean> {
        val safeLimit = limit.coerceIn(1, 500)
        val rows = ArrayList<Entry>()
        readableDatabase.rawQuery(
            "SELECT seq, sms_id, address, body, date, box, read FROM sms WHERE seq > ? ORDER BY seq ASC LIMIT ?",
            arrayOf(afterSeq.coerceAtLeast(0).toString(), (safeLimit + 1).toString())
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(Entry(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4),
                    c.getString(5), c.getInt(6) != 0))
            }
        }
        val hasMore = rows.size > safeLimit
        return (if (hasMore) rows.subList(0, safeLimit) else rows) to hasMore
    }
}
