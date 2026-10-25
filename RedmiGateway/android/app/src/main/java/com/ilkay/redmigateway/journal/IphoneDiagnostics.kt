package com.ilkay.redmigateway.journal

import android.content.Context
import android.util.Log
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

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "IphoneDiagnostics").apply { isDaemon = true }
    }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var file: File? = null
    private var appContext: Context? = null

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

    /** The newest [lines] lines, newest last; used by the settings screen and by support. */
    fun tail(lines: Int = 400): String {
        val target = file ?: return ""
        return runCatching { target.readLines().takeLast(lines).joinToString("\n") }
            .getOrElse { "" }
    }

    /** Where the file lives, for the settings screen and for support. */
    fun path(): String? = file?.absolutePath
}
