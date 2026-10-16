package com.ilkay.redmigateway.sms

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.Manifest
import android.os.SystemClock
import android.provider.Telephony
import android.util.Log
import java.security.MessageDigest

data class StoredSms(val id: Long, val sender: String, val message: String, val timestamp: Long)
data class SmsHistoryPage(val messages: List<StoredSms>, val hasMore: Boolean)

/** App-private inbox journal so a reconnecting iPhone can recover messages received while offline. */
class SmsStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "gateway_sms.db", null, 2) {

    private val appContext = context.applicationContext
    private var lastInboxScanElapsed = 0L

    companion object {
        @Volatile private var instance: SmsStore? = null

        fun get(context: Context): SmsStore = instance ?: synchronized(this) {
            instance ?: SmsStore(context).also { instance = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE incoming_sms (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "sender TEXT NOT NULL, body TEXT NOT NULL, received_at INTEGER NOT NULL, " +
                "fingerprint TEXT UNIQUE)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE incoming_sms ADD COLUMN fingerprint TEXT")
            db.execSQL("CREATE UNIQUE INDEX incoming_sms_fingerprint ON incoming_sms(fingerprint)")
        }
    }

    @Synchronized
    fun saveIncoming(sender: String, message: String, timestamp: Long): Long {
        val fingerprint = fingerprint(sender, message, timestamp)
        val values = ContentValues().apply {
            put("sender", sender)
            put("body", message)
            put("received_at", timestamp)
            put("fingerprint", fingerprint)
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "incoming_sms", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted > 0) return inserted
        readableDatabase.query(
            "incoming_sms", arrayOf("_id"), "fingerprint = ?", arrayOf(fingerprint),
            null, null, null, "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        }
    }

    /** Reconcile with the system inbox if MIUI stopped a broadcast while the app was inactive. */
    @Synchronized
    fun importFromSystemInbox() {
        if (appContext.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        val elapsed = SystemClock.elapsedRealtime()
        if (lastInboxScanElapsed != 0L && elapsed - lastInboxScanElapsed < 10_000L) return

        val prefs = appContext.getSharedPreferences("redmi_gateway_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val firstSync = prefs.getLong("sms_first_sync", 0L).takeIf { it > 0 }
            ?: (now - 24L * 60 * 60 * 1000).also {
                prefs.edit().putLong("sms_first_sync", it).apply()
            }
        val lastScan = prefs.getLong("sms_last_scan", firstSync)
        val lowerBound = maxOf(firstSync, lastScan - 24L * 60 * 60 * 1000)

        try {
            appContext.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf("address", "body", "date", "date_sent"),
                "date >= ?",
                arrayOf(lowerBound.toString()),
                "date ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val sender = cursor.getString(0) ?: continue
                    val body = cursor.getString(1) ?: continue
                    val receivedAt = cursor.getLong(2)
                    val sentAt = cursor.getLong(3)
                    saveIncoming(sender, body, if (sentAt > 0) sentAt else receivedAt)
                }
            }
            prefs.edit().putLong("sms_last_scan", now).apply()
            lastInboxScanElapsed = elapsed
        } catch (e: Exception) {
            Log.e("SmsStore", "System inbox reconciliation failed", e)
        }
    }

    private fun fingerprint(sender: String, body: String, timestamp: Long): String =
        smsFingerprint(sender, body, timestamp)

    @Synchronized
    fun readAfter(afterId: Long, limit: Int = 100): SmsHistoryPage {
        val safeLimit = smsPageLimit(limit)
        val messages = ArrayList<StoredSms>(safeLimit)
        var hasMore = false
        readableDatabase.query(
            "incoming_sms",
            arrayOf("_id", "sender", "body", "received_at"),
            "_id > ?",
            arrayOf(afterId.coerceAtLeast(0).toString()),
            null,
            null,
            "_id ASC",
            (safeLimit + 1).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (messages.size == safeLimit) {
                    hasMore = true
                    break
                }
                messages.add(
                    StoredSms(
                        id = cursor.getLong(0),
                        sender = cursor.getString(1),
                        message = cursor.getString(2),
                        timestamp = cursor.getLong(3)
                    )
                )
            }
        }
        return SmsHistoryPage(messages, hasMore)
    }
}

/**
 * The deduplication key for a stored message.
 *
 * The store inserts with this fingerprint and ignores conflicts, so it decides which messages are
 * considered the same one. Getting it wrong is not symmetric: too loose and two different messages
 * collapse into one, which loses a message silently and is very hard to notice; too tight and the
 * same message is stored twice every time the system inbox is rescanned.
 *
 * The separator is a NUL byte rather than a dash or a colon, and that matters. With an ordinary
 * separator the fields can be shuffled into the same string, so a message from "0555" saying
 * "1234" and one from "05551234" saying nothing would produce the same key. NUL cannot appear in
 * a phone number or in a timestamp, so the three fields stay separate.
 *
 * Kept as a function so it can be asserted on. A unit test cannot open the store's database, and
 * this is the kind of line that gets "simplified" later by someone who does not know why the
 * separator is unusual.
 */
internal fun smsFingerprint(sender: String, body: String, timestamp: Long): String {
    val bytes = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$sender\u0000$timestamp\u0000$body".toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

/** How many messages one page may hold. Guards against a caller asking for everything at once. */
internal fun smsPageLimit(limit: Int): Int = limit.coerceIn(1, 100)
