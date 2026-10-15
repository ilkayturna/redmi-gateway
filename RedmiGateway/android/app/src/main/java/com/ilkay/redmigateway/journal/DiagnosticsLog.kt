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
    private const val MAX_LINES = 4000
    private const val TRIM_TO_LINES = 2500
    private const val TRIM_EVERY_WRITES = 200L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DiagnosticsLog").apply { isDaemon = true }
    }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val writes = AtomicLong()

    @Volatile private var file: File? = null

    /** Safe to call more than once; the first context wins. */
    fun init(context: Context) {
        if (file != null) return
        file = runCatching {
            val dir = context.getExternalFilesDir("diagnostics") ?: context.filesDir
            File(dir, FILE_NAME)
        }.getOrNull()
    }

    /** Appends one line. Never blocks the caller and never throws. */
    fun record(tag: String, message: String) {
        val target = file ?: return
        val line = "${stamp.format(Date())} $tag: $message"
        runCatching {
            executor.execute {
                runCatching {
                    target.parentFile?.mkdirs()
                    target.appendText(line + "\n")
                    if (writes.incrementAndGet() % TRIM_EVERY_WRITES == 0L) trim(target)
                }.onFailure { Log.w(TAG, "Could not write diagnostics: ${it.message}") }
            }
        }
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
        if (all.size <= MAX_LINES) return
        target.writeText(all.takeLast(TRIM_TO_LINES).joinToString("\n") + "\n")
    }
}
