package com.ilkay.redmigateway.journal

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.HandlerThread
import android.provider.CallLog
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Durable, replayable call history for the iPhone.
 *
 * Android's CallLog is the source of truth (Telecom writes every call, including missed calls
 * while the gateway process was dead). This journal mirrors it with a monotonic sequence number,
 * so a reconnecting iPhone asks for "everything after seq N" and never misses or duplicates a
 * call. The gateway adds which device answered, which CallLog does not know.
 */
class CallJournal private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "gateway_calls.db", null, 1) {

    data class Entry(
        val seq: Long,
        val logId: Long,
        val number: String,
        val type: String,
        val date: Long,
        val durationSeconds: Long,
        val answeredOn: String?
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("seq", seq)
            .put("id", logId)
            .put("number", number)
            .put("type", type)
            .put("date", date)
            .put("duration", durationSeconds)
            .put("answeredOn", answeredOn ?: JSONObject.NULL)
    }

    interface Listener {
        fun onCallJournalEntry(entry: Entry)
    }

    companion object {
        private const val TAG = "CallJournal"
        @Volatile private var instance: CallJournal? = null

        fun get(context: Context): CallJournal = instance ?: synchronized(this) {
            instance ?: CallJournal(context).also { instance = it }
        }

        private fun typeName(type: Int): String = when (type) {
            CallLog.Calls.INCOMING_TYPE -> "incoming"
            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
            CallLog.Calls.MISSED_TYPE -> "missed"
            CallLog.Calls.REJECTED_TYPE -> "declined"
            CallLog.Calls.BLOCKED_TYPE -> "blocked"
            CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
            CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "answered_elsewhere"
            else -> "other"
        }
    }

    private val appContext = context.applicationContext
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val worker = HandlerThread("CallJournal").apply { start() }
    private val handler = Handler(worker.looper)
    private var observerRegistered = false
    // Which device answered the most recent calls, keyed by approximate start time.
    private val answeredOnByStart = java.util.concurrent.ConcurrentHashMap<Long, String>()

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = scheduleImport(700L)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE calls (seq INTEGER PRIMARY KEY AUTOINCREMENT, log_id INTEGER UNIQUE NOT NULL, " +
                "number TEXT NOT NULL, type TEXT NOT NULL, date INTEGER NOT NULL, duration INTEGER NOT NULL, " +
                "answered_on TEXT)"
        )
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v INTEGER NOT NULL)")
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

    /** Starts mirroring the CallLog; safe to call repeatedly. */
    fun start() {
        handler.post {
            if (!observerRegistered && hasPermission()) {
                appContext.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
                observerRegistered = true
            }
            importNow()
        }
    }

    /** The relay reports who carried the audio so Recents can show it. */
    fun noteAnsweredOn(callStartMillis: Long, device: String) {
        answeredOnByStart[callStartMillis] = device
        handler.post { applyAnsweredOn(callStartMillis, device) }
        scheduleImport(1500L)
    }

    /** The CallLog row may already be imported; update it and publish it again with a new seq. */
    @Synchronized
    private fun applyAnsweredOn(start: Long, device: String) {
        val db = writableDatabase
        val row = db.rawQuery(
            "SELECT log_id, number, type, date, duration FROM calls WHERE answered_on IS NULL " +
                "AND type IN ('incoming', 'outgoing') AND date BETWEEN ? AND ? ORDER BY seq DESC LIMIT 1",
            arrayOf((start - 15_000).toString(), (start + 15_000).toString())
        ).use { c -> if (c.moveToFirst()) Entry(0, c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), device) else null }
            ?: return
        db.delete("calls", "log_id = ?", arrayOf(row.logId.toString()))
        val values = ContentValues().apply {
            put("log_id", row.logId); put("number", row.number); put("type", row.type)
            put("date", row.date); put("duration", row.durationSeconds); put("answered_on", device)
        }
        val seq = db.insert("calls", null, values)
        if (seq > 0) {
            val entry = row.copy(seq = seq)
            for (l in listeners) runCatching { l.onCallJournalEntry(entry) }
        }
    }

    fun scheduleImport(delayMs: Long = 0L) {
        handler.removeCallbacks(importRunnable)
        handler.postDelayed(importRunnable, delayMs)
    }

    private val importRunnable = Runnable { importNow() }

    private fun hasPermission() =
        appContext.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    private fun lastImportedLogId(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT COALESCE(MAX(log_id), 0) FROM calls", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    @Synchronized
    private fun importNow() {
        if (!hasPermission()) return
        answeredOnByStart.keys.removeIf { System.currentTimeMillis() - it > 3_600_000L }
        val db = writableDatabase
        // The first run keeps 30 days of history; older rows (ids up to "floor") are never imported.
        val firstImport = meta(db, "initialized") == null
        val since = if (firstImport) System.currentTimeMillis() - 30L * 24 * 3600 * 1000 else 0L
        val after = maxOf(lastImportedLogId(db), meta(db, "floor") ?: 0L)
        var highestSeen = after
        val added = ArrayList<Entry>()
        try {
            appContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls._ID} > ? AND ${CallLog.Calls.DATE} >= ?",
                arrayOf(after.toString(), since.toString()),
                "${CallLog.Calls._ID} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val logId = c.getLong(0)
                    highestSeen = maxOf(highestSeen, logId)
                    val number = c.getString(1).orEmpty()
                    val type = typeName(c.getInt(2))
                    val date = c.getLong(3)
                    val duration = c.getLong(4)
                    val answeredOn = answeredOnByStart.entries
                        .firstOrNull { kotlin.math.abs(it.key - date) < 15_000 }?.value
                        ?.takeIf { type == "incoming" || type == "outgoing" }
                    val values = ContentValues().apply {
                        put("log_id", logId); put("number", number); put("type", type)
                        put("date", date); put("duration", duration); put("answered_on", answeredOn)
                    }
                    val seq = db.insertWithOnConflict("calls", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    if (seq > 0) added.add(Entry(seq, logId, number, type, date, duration, answeredOn))
                }
            }
            if (firstImport) {
                setMeta(db, "floor", maxOf(highestSeen, newestLogId()))
                setMeta(db, "initialized", System.currentTimeMillis())
            }
        } catch (error: Exception) {
            Log.w(TAG, "Call log import failed: ${error.javaClass.simpleName}")
        }
        if (added.isNotEmpty()) {
            Log.i(TAG, "Imported ${added.size} call record(s)")
            // The first import is history, not news; connected iPhones fetch it with sync.
            if (!firstImport) for (entry in added) for (l in listeners) runCatching { l.onCallJournalEntry(entry) }
        }
    }

    private fun newestLogId(): Long =
        appContext.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, "${CallLog.Calls._ID} DESC"
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L

    /** CallLog id → "iphone" or "redmi" for calls whose carrier is known. */
    @Synchronized
    fun carriers(): Map<Long, String> {
        val result = HashMap<Long, String>()
        readableDatabase.rawQuery("SELECT log_id, answered_on FROM calls WHERE answered_on IS NOT NULL", null).use { c ->
            while (c.moveToNext()) result[c.getLong(0)] = c.getString(1)
        }
        return result
    }

    /** Sequence of the newest entry; a paired iPhone compares it with what it has seen. */
    @Synchronized
    fun latestSeq(): Long = readableDatabase.rawQuery("SELECT COALESCE(MAX(seq), 0) FROM calls", null).use { c ->
        if (c.moveToFirst()) c.getLong(0) else 0L
    }

    @Synchronized
    fun readAfter(afterSeq: Long, limit: Int = 200): Pair<List<Entry>, Boolean> {
        val safeLimit = limit.coerceIn(1, 500)
        val rows = ArrayList<Entry>()
        readableDatabase.rawQuery(
            "SELECT seq, log_id, number, type, date, duration, answered_on FROM calls WHERE seq > ? ORDER BY seq ASC LIMIT ?",
            arrayOf(afterSeq.coerceAtLeast(0).toString(), (safeLimit + 1).toString())
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(Entry(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5),
                    if (c.isNull(6)) null else c.getString(6)))
            }
        }
        val hasMore = rows.size > safeLimit
        return (if (hasMore) rows.subList(0, safeLimit) else rows) to hasMore
    }
}
