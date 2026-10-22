package com.ilkay.redmigateway.journal

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Rolling on-device diagnostics for call work.
 *
 * The user carries the phone away from the computer, so a call that went wrong has to be
 * readable afterwards without a cable: the important call events are appended to a file in
 * the app's external files directory, which the phone's own file manager can hand over.
 *
 * Writes are serialised on one daemon thread and the file is trimmed so it can never grow
 * without bound. Everything here is best-effort: a failure to write must never disturb a call.
 */
object DiagnosticsLog {
    private const val TAG = "DiagnosticsLog"
    private const val FILE_NAME = "call-diagnostics.log"
    /** Trim once the file passes this many lines, keeping the newest [TRIM_TO_LINES]. */
    internal const val MAX_LINES = 4000
    internal const val TRIM_TO_LINES = 2500
    internal const val TRIM_EVERY_WRITES = 200L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DiagnosticsLog").apply { isDaemon = true }
    }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val writes = AtomicLong()

    @Volatile private var file: File? = null
    private var appContext: Context? = null

    /** Safe to call more than once; the first context wins. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (file != null) return
        file = resolve(context)
        if (file == null) Log.w(TAG, "No diagnostics file yet; will retry on the first record")
    }

    /**
     * Where the rolling file lives.
     *
     * Null when the app process starts before external storage is ready. That is a real state on
     * this phone: the process that started at 21:03 on 21.10.2026, right after the package update,
     * never wrote a single line again, so a whole day of calls had no on-device evidence at all.
     * [record] therefore retries instead of trusting this one attempt.
     */
    private fun resolve(context: Context): File? = runCatching {
        val dir = context.getExternalFilesDir("diagnostics") ?: context.filesDir
        File(dir, FILE_NAME)
    }.getOrNull()

    /** Appends one line. Never blocks the caller and never throws. */
    fun record(tag: String, message: String) {
        val target = file ?: lateInitFile() ?: return
        val line = "${stamp.format(Date())} $tag: $message"
        runCatching {
            executor.execute {
                runCatching {
                    target.parentFile?.mkdirs()
                    target.appendText(line + "\n")
                    if (shouldTrimDiagnosticsLog(writes.incrementAndGet())) trim(target)
                }.onFailure { Log.w(TAG, "Could not write diagnostics: ${it.message}") }
            }
        }
    }

    /**
     * The file, or the result of one late attempt to find it.
     *
     * Called only while the path is still unknown, so a process that started before external
     * storage was mounted recovers on its first record instead of staying blind for its whole
     * life. The recovery is logged so it shows up in logcat rather than vanishing silently.
     */
    private fun lateInitFile(): File? {
        val context = appContext ?: return null
        val resolved = resolve(context) ?: return null
        file = resolved
        Log.i(TAG, "Diagnostics file ready at ${resolved.absolutePath} (late)")
        return resolved
    }

    /** Where the file lives, for the settings screen and for support. */
    fun path(): String? = file?.absolutePath

    /** The newest [lines] lines, newest last. */
    fun tail(lines: Int = 400): String {
        val target = file ?: return ""
        return runCatching { target.readLines().takeLast(lines).joinToString("\n") }
            .getOrElse { "" }
    }

    /** Clears the file; used by the settings screen before a fresh test. */
    fun clear() {
        val target = file ?: return
        runCatching { executor.execute { runCatching { target.writeText("") } } }
    }

    private fun trim(target: File) {
        val all = target.readLines()
        val keep = diagnosticsLogLinesToKeep(all.size) ?: return
        target.writeText(all.takeLast(keep).joinToString("\n") + "\n")
    }
}


/**
 * Whether this write is the one that triggers a trim.
 *
 * Trimming reads and rewrites the whole file, so it is not done on every line: the counter decides
 * and this is the rule. Kept as a function because "every two hundred writes" turning into "every
 * write" would make the log thread the bottleneck on a live call.
 *
 * @param writes how many lines have been written since the app started
 */
internal fun shouldTrimDiagnosticsLog(writes: Long): Boolean = writes % DiagnosticsLog.TRIM_EVERY_WRITES == 0L

/**
 * How many lines to keep when the diagnostics log has grown too large, or null to leave it alone.
 *
 * The newest lines are the ones kept, because the log is read to explain the call that just
 * happened. The number kept must be smaller than the number that triggers the trim, or the file
 * would be rewritten at the same size and grow without bound.
 *
 * @param lineCount how many lines the file currently has
 */
internal fun diagnosticsLogLinesToKeep(lineCount: Int): Int? =
    if (lineCount <= DiagnosticsLog.MAX_LINES) null else DiagnosticsLog.TRIM_TO_LINES