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
import com.ilkay.redmigateway.push.phoneIdentityKey
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

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

    /**
     * One message as the iPhone receives it.
     *
     * @param pushed the Redmi already announced it through Apple, so the iPhone stores it without a
     *   second sound (docs/WAVE1-CONTRACT.md section 7)
     * @param replaces the synthetic id of the fallback entry this provider row stands for, so the
     *   iPhone can drop that earlier copy of the same message (CONS-11)
     */
    data class Entry(
        val seq: Long,
        val id: Long,
        val address: String,
        val body: String,
        val date: Long,
        val box: String,
        val read: Boolean,
        val pushed: Boolean = false,
        val replaces: Long? = null
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("seq", seq)
            .put("id", id)
            .put("address", address)
            .put("body", body)
            .put("date", date)
            .put("box", box)
            .put("read", read)
            .apply {
                if (pushed) put("pushed", true)
                if (replaces != null) put("replaces", replaces)
            }
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

    /** A provider row the import will write, kept until the newest-first scan is done. */
    private class ProviderRow(
        val id: Long, val address: String, val body: String, val date: Long, val dateSent: Long,
        val box: String, val read: Boolean, val isNew: Boolean
    )

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
        // Side tables rather than new columns: the database is at version 1 with a no-op upgrade,
        // and a table that may not exist yet is created here on every open, like meta.
        db.execSQL("CREATE TABLE IF NOT EXISTS pushed (sms_id INTEGER PRIMARY KEY)")
        db.execSQL("CREATE TABLE IF NOT EXISTS replaced (sms_id INTEGER PRIMARY KEY, replaces INTEGER NOT NULL)")
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

    /**
     * Apple accepted an alert for message [smsId]. Kept by message id, so a later status change of
     * the same message (a new seq) still says "pushed".
     */
    @Synchronized
    fun markPushed(smsId: Long) {
        runCatching { markPushed(writableDatabase, smsId) }
            .onFailure { Log.w(TAG, "Could not mark SMS $smsId pushed: ${it.javaClass.simpleName}") }
    }

    private fun markPushed(db: SQLiteDatabase, smsId: Long) {
        db.execSQL("INSERT OR IGNORE INTO pushed (sms_id) VALUES (?)", arrayOf<Any>(smsId))
    }

    private fun isPushed(db: SQLiteDatabase, smsId: Long): Boolean =
        db.rawQuery("SELECT 1 FROM pushed WHERE sms_id = ?", arrayOf(smsId.toString())).use { it.moveToFirst() }

    private fun replacedFallback(db: SQLiteDatabase, smsId: Long): Long? =
        db.rawQuery("SELECT replaces FROM replaced WHERE sms_id = ?", arrayOf(smsId.toString())).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    private fun hasPermission() =
        appContext.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * [date] is the network's send time from the PDU, while an SMS app stores the time it received
     * the message in DATE and the send time in DATE_SENT. A message the network held back for more
     * than two minutes (the Redmi out of coverage or rebooting) therefore missed a DATE-only window,
     * got a synthetic copy here, and reached the iPhone twice. Either column now counts, and DATE
     * may be anything up to the present moment.
     */
    private fun existsInProvider(address: String, body: String, date: Long): Boolean = try {
        val now = System.currentTimeMillis()
        appContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.BODY} = ? AND (${Telephony.Sms.DATE_SENT} BETWEEN ? AND ? " +
                "OR ${Telephony.Sms.DATE} BETWEEN ? AND ?)",
            arrayOf(
                body,
                (date - 120_000).toString(), (date + 120_000).toString(),
                (date - 120_000).toString(), (maxOf(date, now) + 120_000).toString()
            ), null
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
        if (seq > 0) {
            DiagnosticsLog.record(TAG, "No SMS app stored a received message within 5 s; kept fallback $syntheticId")
            publish(Entry(seq, syntheticId, address, body, date, "inbox", false))
        }
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
        val rows = ArrayList<ProviderRow>()
        val changed = ArrayList<Entry>()
        try {
            appContext.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
                    Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.DATE_SENT),
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
                    rows.add(ProviderRow(id, c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3),
                        c.getLong(6), box, read, isNew = id > watermark))
                }
            }
            // The scan runs newest first so it can stop early. Writing in arrival order gives the
            // oldest message the lowest seq, so live publishing, sync and Apple push all announce
            // the newest message last, which puts it on top of the iPhone's lock screen (CONS-5).
            for (row in smsWriteOrder(rows) { it.id }) {
                val replaces = if (!firstImport && row.isNew && row.box == "inbox") takeOverFallback(db, row) else null
                val entry = upsert(db, row.id, row.address, row.body, row.date, row.box, row.read, replaces)
                if (entry != null) changed.add(entry)
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

    /**
     * A new provider row that is the late-stored copy of a message already kept as a fallback
     * (the SMS app stored it more than 5 s after the broadcast). The fallback row is dropped and
     * the provider row names it in "replaces", so the iPhone keeps one bubble; an Apple alert
     * already sent for the fallback counts for the provider row too. Returns the fallback's id.
     */
    private fun takeOverFallback(db: SQLiteDatabase, row: ProviderRow): Long? {
        val match = db.rawQuery(
            "SELECT sms_id, address, date FROM sms WHERE sms_id < 0 AND box = 'inbox' AND body = ? ORDER BY seq ASC",
            arrayOf(row.body)
        ).use { c ->
            var found: Long? = null
            while (found == null && c.moveToNext()) {
                if (isLateCopyOfFallback(c.getString(1), c.getLong(2), row.address, row.date, row.dateSent)) found = c.getLong(0)
            }
            found
        } ?: return null
        val wasPushed = isPushed(db, match)
        db.delete("sms", "sms_id = ?", arrayOf(match.toString()))
        db.delete("pushed", "sms_id = ?", arrayOf(match.toString()))
        db.execSQL("INSERT OR REPLACE INTO replaced (sms_id, replaces) VALUES (?, ?)", arrayOf<Any>(row.id, match))
        if (wasPushed) markPushed(db, row.id)
        DiagnosticsLog.record(TAG, "SMS ${row.id} stored late by the SMS app; replaces fallback $match (pushed=$wasPushed)")
        return match
    }

    private fun upsert(
        db: SQLiteDatabase, id: Long, address: String, body: String, date: Long, box: String, read: Boolean,
        replaces: Long?
    ): Entry? {
        db.delete("sms", "sms_id = ?", arrayOf(id.toString()))
        val values = ContentValues().apply {
            put("sms_id", id); put("address", address); put("body", body)
            put("date", date); put("box", box); put("read", if (read) 1 else 0)
        }
        val seq = db.insert("sms", null, values)
        if (seq <= 0) return null
        return Entry(seq, id, address, body, date, box, read, isPushed(db, id), replaces ?: replacedFallback(db, id))
    }

    private fun publish(entry: Entry) {
        for (l in listeners) runCatching { l.onSmsJournalEntry(entry) }
    }

    /** Sequence of the newest entry; a paired iPhone compares it with what it has seen. */
    @Synchronized
    fun latestSeq(): Long = readableDatabase.rawQuery("SELECT COALESCE(MAX(seq), 0) FROM sms", null).use { c ->
        if (c.moveToFirst()) c.getLong(0) else 0L
    }

    @Synchronized
    fun readAfter(afterSeq: Long, limit: Int = 200): Pair<List<Entry>, Boolean> {
        val safeLimit = limit.coerceIn(1, 500)
        val rows = ArrayList<Entry>()
        readableDatabase.rawQuery(
            "SELECT s.seq, s.sms_id, s.address, s.body, s.date, s.box, s.read, p.sms_id IS NOT NULL, r.replaces " +
                "FROM sms s LEFT JOIN pushed p ON p.sms_id = s.sms_id LEFT JOIN replaced r ON r.sms_id = s.sms_id " +
                "WHERE s.seq > ? ORDER BY s.seq ASC LIMIT ?",
            arrayOf(afterSeq.coerceAtLeast(0).toString(), (safeLimit + 1).toString())
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(Entry(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4),
                    c.getString(5), c.getInt(6) != 0, c.getInt(7) != 0, if (c.isNull(8)) null else c.getLong(8)))
            }
        }
        val hasMore = rows.size > safeLimit
        return (if (hasMore) rows.subList(0, safeLimit) else rows) to hasMore
    }
}

/**
 * The order the import writes provider rows in: oldest (lowest _ID) first.
 *
 * The scan itself runs newest first so it can stop early, and writing in that order gave the
 * newest message the lowest seq: the iPhone then posted the oldest message last, on top of the
 * stack, so an expired one-time code sat above the current one (CONS-5).
 */
internal fun <T> smsWriteOrder(rowsAsScanned: List<T>, id: (T) -> Long): List<T> = rowsAsScanned.sortedBy(id)

/** How long after the network's send time an SMS app may still store a message we kept as a fallback. */
internal const val LATE_STORE_WINDOW_MS = 10 * 60_000L

/**
 * Whether a provider row is the same message as a fallback entry kept from the broadcast.
 *
 * The fallback's date is the network's send time from the PDU. A provider row matches when it
 * has the same sender (by the iPhone's conversation key, so +90 and 0 spellings agree) and either
 * its DATE_SENT is within the two minutes the fallback check also allows, or, for SMS apps that
 * leave DATE_SENT empty, it was stored up to [LATE_STORE_WINDOW_MS] after that time.
 */
internal fun isLateCopyOfFallback(
    fallbackAddress: String, fallbackDate: Long, address: String, date: Long, dateSent: Long
): Boolean {
    if (phoneIdentityKey(fallbackAddress) != phoneIdentityKey(address)) return false
    val sentMatches = dateSent > 0 && abs(dateSent - fallbackDate) <= 120_000L
    val storedMatches = date >= fallbackDate - 120_000L && date <= fallbackDate + LATE_STORE_WINDOW_MS
    return sentMatches || storedMatches
}
