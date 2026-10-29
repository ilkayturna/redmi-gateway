package com.ilkay.redmigateway.journal

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * On-device copy of what the iPhone reports about itself.
 *
 * The iPhone has no USB debugging, so a call that failed to ring on it leaves nothing readable:
 * the phone cannot be cabled to a computer and its own log ring dies with the app. This file is
 * the other end of a one-way channel - the iPhone pushes its log lines and a status snapshot over
 * the sealed socket, and they are written here next to the call log so `adb pull` can read them.
 *
 * Separate from [DiagnosticsLog] on purpose: the iPhone can send a burst of lines after a reconnect,
 * and mixing that into the call history would push the few lines that explain the call out of the
 * ring. Writes are serialised off the network thread and the file is rotated so it cannot grow
 * without bound.
 *
 * Two things sit beside it: `iphone-status.json`, the latest `client_status` as structured JSON so
 * a PC script does not have to parse the rotating log, and the `ios-diag/` folder of large reports
 * ([IosDiagStore]). Both are written on the same single writer thread.
 */
object IphoneDiagnostics {
    private const val TAG = "IphoneDiagnostics"
    private const val FILE_NAME = "iphone-diagnostics.log"
    private const val PREVIOUS_NAME = "iphone-diagnostics.1.log"
    /** Rotated once when the file passes this size; the newest lines are the ones worth reading. */
    internal const val MAX_BYTES = 512L * 1024L
    /** A single line longer than this is truncated; a broken client must not fill the file. */
    internal const val MAX_LINE_CHARS = 4000
    /** Upper bound for one frame, defensive: the iPhone already caps its batches at 40. */
    internal const val MAX_LINES_PER_FRAME = 200
    private const val STATUS_FILE_NAME = "iphone-status.json"
    private const val DIAG_DIR_NAME = "ios-diag"
    private const val STATUS_TAG = "iPhone-status"
    private const val DIAG_TAG = "iPhone-diag"

    /** The latest `client_status`, already sanitised, and the Redmi wall time it arrived at. */
    class Status(val values: JSONObject, val receivedAt: Long)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "IphoneDiagnostics").apply { isDaemon = true }
    }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    // Only used on the writer thread, like [stamp]; the year and zone make the file self-explaining.
    private val statusStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)

    @Volatile private var file: File? = null
    private var appContext: Context? = null
    @Volatile private var latestStatus: Status? = null
    @Volatile private var diagStore: IosDiagStore? = null

    /** Safe to call more than once; the first context wins. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (file != null) return
        file = resolve(context)
        if (file == null) Log.w(TAG, "No iPhone diagnostics file yet; will retry on the first record")
    }

    /** Where the file lives; the same directory as the call diagnostics file. */
    private fun resolve(context: Context): File? = runCatching {
        val dir = context.getExternalFilesDir("diagnostics") ?: context.filesDir
        File(dir, FILE_NAME)
    }.getOrNull()

    /** Appends one line. Never blocks the caller and never throws. */
    fun record(tag: String, message: String) {
        val target = file ?: lateInitFile() ?: return
        val text = if (message.length > MAX_LINE_CHARS) {
            message.substring(0, MAX_LINE_CHARS) + "...(truncated)"
        } else {
            message
        }
        // Formatted on the log thread; see DiagnosticsLog.record for why.
        val at = System.currentTimeMillis()
        // Mirrored to logcat so a live adb session shows it without waiting for the file.
        Log.i("IphoneDiag", "$tag: $text")
        runCatching {
            executor.execute {
                runCatching {
                    val line = "${stamp.format(Date(at))} $tag: $text"
                    target.parentFile?.mkdirs()
                    rotateIfNeeded(target)
                    target.appendText(line + "\n")
                }.onFailure { Log.w(TAG, "Could not write iPhone diagnostics: ${it.message}") }
            }
        }
    }

    /** One late attempt to find the path, mirroring [DiagnosticsLog]'s recovery on this device. */
    private fun lateInitFile(): File? {
        val context = appContext ?: return null
        val resolved = resolve(context) ?: return null
        file = resolved
        Log.i(TAG, "iPhone diagnostics file ready at ${resolved.absolutePath} (late)")
        return resolved
    }

    private fun rotateIfNeeded(target: File) {
        if (target.length() <= MAX_BYTES) return
        val previous = File(target.parentFile, PREVIOUS_NAME)
        previous.delete()
        target.renameTo(previous)
    }

    /**
     * Stores one `client_status`: the line in the log as before, the snapshot in memory for
     * DiagnosticsReport (which runs on the main thread and must not read files), and the same
     * snapshot in `iphone-status.json`, replaced atomically on the writer thread.
     */
    fun recordStatus(json: JSONObject) {
        val receivedAt = System.currentTimeMillis()
        val adjusted = ArrayList<String>()
        val values = sanitizeIphoneStatus(json, adjusted)
        // Logcat only, once per frame: the same iPhone build sends the same keys every minute, and
        // the file must not repeat it (AGENTS.md 8.1). It tells an iOS author why a key looks
        // different here or is missing.
        if (adjusted.isNotEmpty()) Log.w(TAG, "client_status keys adjusted: ${adjusted.joinToString(", ").take(400)}")
        latestStatus = Status(values, receivedAt)
        record(STATUS_TAG, iphoneStatusLine(values))
        val target = file ?: lateInitFile() ?: run {
            Log.w(TAG, "No diagnostics folder yet; $STATUS_FILE_NAME not written")
            return
        }
        runCatching {
            executor.execute {
                runCatching {
                    val document = iphoneStatusDocument(values, receivedAt, statusStamp.format(Date(receivedAt)))
                    val dir = target.parentFile ?: throw java.io.IOException("no parent folder")
                    dir.mkdirs()
                    writeAtomically(File(dir, STATUS_FILE_NAME), document.toString(2).toByteArray(Charsets.UTF_8))
                }.onFailure { Log.w(TAG, "Could not write $STATUS_FILE_NAME: ${it.message}") }
            }
        }.onFailure { Log.w(TAG, "Writer thread refused $STATUS_FILE_NAME: ${it.message}") }
    }

    /** The latest `client_status` since this process started, or null before the first one. */
    fun latestStatus(): Status? = latestStatus

    /**
     * Stores one `client_diag` part on the writer thread and hands the outcome to [onDone] there,
     * so the ack leaves only once the bytes are on disk. A completed payload gets one summary line
     * in the log; the parts themselves never go into it.
     */
    fun storeDiagPart(part: IosDiagStore.Part, onDone: (IosDiagStore.Outcome) -> Unit) {
        val store = diagStore ?: (file ?: lateInitFile())?.parentFile?.let { dir ->
            IosDiagStore(File(dir, DIAG_DIR_NAME)).also { diagStore = it }
        }
        if (store == null) {
            Log.w(TAG, "No diagnostics folder yet; client_diag ${part.id} part ${part.part} not stored")
            onDone(IosDiagStore.Outcome.Failed("no_storage"))
            return
        }
        val queued = runCatching {
            executor.execute {
                val outcome = store.put(part)
                val completed = (outcome as? IosDiagStore.Outcome.Stored)?.completed
                if (completed != null) {
                    record(
                        DIAG_TAG,
                        "id=${part.id} kind=${part.kind} bytes=${completed.bytes} parts=${completed.parts} stored"
                    )
                    if (completed.pruned > 0) {
                        record(DIAG_TAG, "removed ${completed.pruned} older reports to stay within the folder cap")
                    }
                }
                runCatching { onDone(outcome) }.onFailure { Log.w(TAG, "client_diag ack failed: ${it.message}") }
            }
        }
        if (queued.isFailure) {
            Log.w(TAG, "Writer thread refused client_diag ${part.id}: ${queued.exceptionOrNull()?.message}")
            onDone(IosDiagStore.Outcome.Failed("busy"))
        }
    }

    /** The newest [lines] lines, newest last; used by the settings screen and by support. */
    fun tail(lines: Int = 400): String {
        val target = file ?: return ""
        return runCatching { target.readLines().takeLast(lines).joinToString("\n") }
            .getOrElse { "" }
    }

    /** Where the file lives, for the settings screen and for support. */
    fun path(): String? = file?.absolutePath
}

/**
 * The `client_status` keys every build has known, written first and in this order so a line from
 * iPhone build 155 reads exactly as it always has. Newer keys follow, sorted.
 */
internal val LEGACY_STATUS_KEYS = listOf(
    "appVersion", "appBuild", "appState", "callkit", "audio", "mic", "notify",
    "ble", "battery", "thermal", "lowPower", "network"
)

/** Each value is cut here; a status field is a short summary, never a report. */
internal const val MAX_STATUS_VALUE_CHARS = 200
internal const val MAX_STATUS_KEY_CHARS = 40
/** Defensive: a frame of thousands of tiny keys would otherwise become one huge line. */
internal const val MAX_STATUS_KEYS = 64
private val STATUS_KEY_UNSAFE = Regex("[^A-Za-z0-9_.-]")
private val CONTROL_CHARS = Regex("\\p{Cntrl}")

/** [keys] in write order: the legacy keys that are present, then every other key sorted. */
internal fun iphoneStatusOrder(keys: Collection<String>): List<String> {
    val present = keys.toSet()
    return LEGACY_STATUS_KEYS.filter { it in present } + (present - LEGACY_STATUS_KEYS.toSet()).sorted()
}

/**
 * A safe copy of one `client_status` frame: every key the iPhone sent except `type`, so a new iOS
 * field is kept without a Redmi update. Booleans and numbers stay as they are; anything else becomes
 * a string without control characters, cut to [MAX_STATUS_VALUE_CHARS]. Null and blank values are
 * left out, as they always were.
 *
 * Keys are cut to [MAX_STATUS_KEY_CHARS] with every character outside `[A-Za-z0-9_.-]` replaced by
 * `_`, and at most [MAX_STATUS_KEYS] are kept. Each key renamed or dropped that way is described in
 * [adjusted], so the caller can say so instead of losing it without a word.
 */
internal fun sanitizeIphoneStatus(json: JSONObject, adjusted: MutableList<String>? = null): JSONObject {
    val out = JSONObject()
    val keys = ArrayList<String>()
    json.keys().forEach { keys.add(it) }
    val ordered = iphoneStatusOrder(keys)
    for ((index, key) in ordered.withIndex()) {
        if (key == "type") continue
        if (out.length() >= MAX_STATUS_KEYS) {
            val over = ordered.subList(index, ordered.size).count { it != "type" }
            adjusted?.add("dropped $over key(s) over the cap of $MAX_STATUS_KEYS")
            break
        }
        val name = key.replace(STATUS_KEY_UNSAFE, "_").take(MAX_STATUS_KEY_CHARS)
        if (name.isEmpty() || out.has(name)) {
            adjusted?.add("dropped ${printableKey(key)} (empty or same as another key once made safe)")
            continue
        }
        if (name != key) adjusted?.add("renamed ${printableKey(key)} to $name")
        when (val value = json.opt(key)) {
            null, JSONObject.NULL -> Unit
            is Boolean, is Int, is Long -> out.put(name, value)
            is Number -> if (value.toDouble().isFinite()) out.put(name, value)
            else -> {
                // A newline would start a log line without a timestamp and break every line reader.
                val text = value.toString().replace(CONTROL_CHARS, " ").trim().take(MAX_STATUS_VALUE_CHARS)
                if (text.isNotEmpty()) out.put(name, text)
            }
        }
    }
    return out
}

/** A key the iPhone sent, as it can be shown on one log line. */
private fun printableKey(key: String): String {
    val text = key.replace(CONTROL_CHARS, "?")
    return "\"" + (if (text.length > MAX_STATUS_KEY_CHARS) text.take(MAX_STATUS_KEY_CHARS) + "..." else text) + "\""
}

/** The `iPhone-status` log line: `key=value` pairs in [iphoneStatusOrder], space separated. */
internal fun iphoneStatusLine(values: JSONObject): String {
    val keys = ArrayList<String>()
    values.keys().forEach { keys.add(it) }
    return iphoneStatusOrder(keys).joinToString(" ") { "$it=${values.opt(it)}" }
}

/**
 * The content of `iphone-status.json` (docs/PROTOCOL.md "client_status"): the sanitised [values]
 * with the Redmi wall time they arrived at, as a number and as [receivedAtText] for a person.
 */
internal fun iphoneStatusDocument(values: JSONObject, receivedAt: Long, receivedAtText: String): JSONObject =
    JSONObject()
        .put("receivedAt", receivedAt)
        .put("receivedAtText", receivedAtText)
        .put("status", values)
