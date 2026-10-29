package com.ilkay.redmigateway.journal

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Large diagnostics from the iPhone (MetricKit reports, crash text, log tails), received in parts.
 *
 * Such a report is 20-60 KB, and one sealed frame above 16,384 chars is dropped before it is even
 * opened (frames above 64 KB close the socket with 1009). The iPhone therefore sends `client_diag`
 * parts of at most 6000 raw bytes and waits for each ack; this class stores the parts and joins
 * them into `ios-diag/<id>.<kind>.json` once the set is complete (docs/PROTOCOL.md "client_diag").
 *
 * Plain JVM on purpose (no Android types), so the validation, the joining and the folder cap can be
 * asserted on directly. Not thread-safe: IphoneDiagnostics runs every call on its one writer thread.
 *
 * @param dir the `ios-diag` folder; created on the first write
 * @param clock wall time in ms, for the age of unfinished parts
 */
class IosDiagStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One validated part, ready to be written. */
    class Part(val id: String, val kind: String, val part: Int, val parts: Int, val data: ByteArray)

    sealed class Parsed {
        class Valid(val part: Part) : Parsed()
        /** [error] is the stable code sent back in the ack. */
        class Invalid(val error: String) : Parsed()
    }

    /** What happened to one part; only [Failed] is reported to the iPhone as `success:false`. */
    sealed class Outcome {
        /** The part is on disk; [completed] is set when it was the one that finished the payload. */
        class Stored(val completed: Completed?) : Outcome()
        /** The payload was joined earlier: a resend after a lost ack, nothing written again. */
        object AlreadyComplete : Outcome()
        /** [error] is the stable code for the ack; [detail] is for the log only. */
        class Failed(val error: String, val detail: String? = null) : Outcome()
    }

    class Completed(val file: File, val bytes: Long, val parts: Int, val pruned: Int)

    companion object {
        const val MAX_PARTS = 64
        /** The iPhone sends at most 6000 raw bytes; the margin keeps a near miss from failing for ever. */
        const val MAX_PART_BYTES = 8 * 1024
        const val MAX_DIR_BYTES = 2L * 1024L * 1024L
        const val MAX_FILES = 50
        /**
         * Parts of a payload that was never finished. Generous, because the iPhone keeps a payload
         * until its last ack and may come back days later; the size cap still applies meanwhile.
         */
        const val PARTIAL_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        internal const val PARTIAL_DIR = "partial"

        /** The kinds the contract names; any other kind that fits [KIND] is stored as well. */
        val KNOWN_KINDS = setOf(
            "metrickit-crash", "metrickit-hang", "metrickit-cpu", "metrickit-disk",
            "metrickit-metric", "nsexception", "exit-unclean", "log-tail"
        )

        private val ID = Regex("^[A-Za-z0-9-]{8,64}$")
        // The kind becomes part of a file name. A newer iPhone may add kinds, and rejecting those
        // would make it retry the same payload for ever, so the shape is checked, not the list.
        private val KIND = Regex("^[a-z][a-z0-9-]{0,31}$")
        // Base64 of MAX_PART_BYTES bytes; a longer string cannot fit and is not decoded at all.
        private const val MAX_DATA_CHARS = ((MAX_PART_BYTES + 2) / 3) * 4

        /** Validates one `client_diag` frame; cheap enough for the socket thread. */
        fun parse(json: JSONObject): Parsed {
            val id = json.optString("id")
            if (!ID.matches(id)) return Parsed.Invalid("bad_id")
            val kind = json.optString("kind")
            if (!KIND.matches(kind)) return Parsed.Invalid("bad_kind")
            val parts = json.optInt("parts", 0)
            if (parts !in 1..MAX_PARTS) return Parsed.Invalid("bad_parts")
            val part = json.optInt("part", -1)
            if (part !in 0 until parts) return Parsed.Invalid("bad_part")
            if (json.optString("enc", "base64") != "base64") return Parsed.Invalid("bad_enc")
            val text = json.optString("data")
            if (text.length > MAX_DATA_CHARS) return Parsed.Invalid("too_large")
            val data = try {
                java.util.Base64.getDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                return Parsed.Invalid("bad_data")
            }
            if (data.size > MAX_PART_BYTES) return Parsed.Invalid("too_large")
            // A part always carries bytes: the iPhone cuts a report into ceil(size / 6000) parts and
            // does not send an empty report. An empty part therefore means the sender lost its data
            // (a failed encode, a missing field), and acking it would let the iPhone delete a report
            // that never reached the Redmi.
            if (data.isEmpty()) return Parsed.Invalid("bad_data")
            return Parsed.Valid(Part(id, kind, part, parts, data))
        }
    }

    /** The joined payload's file. */
    internal fun finalFile(id: String, kind: String) = File(dir, "$id.$kind.json")

    // The part count is in the folder name, so a payload re-chunked differently never mixes with
    // the parts of its earlier attempt; the stale folder ages out or falls to the cap.
    private fun partialDir(part: Part) = File(File(dir, PARTIAL_DIR), "${part.id}.${part.kind}.${part.parts}")

    /** Writes [part] (a repeat overwrites it) and joins the payload when every part is present. */
    fun put(part: Part): Outcome = try {
        val target = finalFile(part.id, part.kind)
        if (target.isFile) {
            Outcome.AlreadyComplete
        } else {
            val folder = partialDir(part)
            val newFolder = !folder.isDirectory
            if (!folder.isDirectory && !folder.mkdirs()) throw java.io.IOException("cannot create ${folder.name}")
            // Durable, because the ack that follows lets the iPhone delete its copy.
            writeAtomically(File(folder, "${part.part}.part"), part.data, durable = true)
            if (newFolder) unreportedPrunes += prune(keep = folder)
            val pieces = (0 until part.parts).map { File(folder, "$it.part") }
            when {
                pieces.all { it.isFile } -> Outcome.Stored(join(part, pieces, folder, target))
                // The iPhone sends the parts in order and waits for each ack, so the last one
                // arriving with gaps means earlier parts were lost here (the cap, the age limit or
                // cleared app data). A success would let the iPhone delete a payload that was never
                // stored; a failure keeps it on the iPhone so it can send it again from the start.
                part.part == part.parts - 1 -> Outcome.Failed(
                    "missing_parts",
                    "missing=" + pieces.indices.filter { !pieces[it].isFile }.take(10).joinToString(",")
                )
                else -> Outcome.Stored(null)
            }
        }
    } catch (error: Exception) {
        Outcome.Failed("io_error", "${error.javaClass.simpleName}: ${error.message}")
    }

    private fun join(part: Part, pieces: List<File>, folder: File, target: File): Completed {
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            pieces.forEach { piece -> piece.inputStream().use { it.copyTo(out) } }
            // On disk before the parts are deleted and the last part is acked.
            out.fd.sync()
        }
        moveAtomically(tmp, target)
        syncFolder(dir)
        folder.deleteRecursively()
        unreportedPrunes += prune(keep = target)
        val pruned = unreportedPrunes
        unreportedPrunes = 0
        return Completed(target, target.length(), part.parts, pruned)
    }

    /** Deletions made while parts arrived, reported with the next completed payload. */
    private var unreportedPrunes = 0

    private class Entry(val file: File, val bytes: Long, val modified: Long)

    /**
     * Keeps the folder within [MAX_FILES] entries and [MAX_DIR_BYTES], deleting the oldest first.
     * A joined payload is one entry and so is each unfinished payload's folder of parts.
     * Returns how many entries were deleted.
     */
    internal fun prune(keep: File? = null): Int {
        var deleted = 0
        // Left over from a write the process did not survive; nothing is mid-write here, because
        // every call runs on the one writer thread.
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".tmp") }?.forEach { it.delete() }
        val now = clock()
        val partialRoot = File(dir, PARTIAL_DIR)
        partialRoot.listFiles()?.filter { it.isDirectory && it != keep }?.forEach { folder ->
            if (now - newestModified(folder) > PARTIAL_MAX_AGE_MS && folder.deleteRecursively()) deleted++
        }
        val entries = ArrayList<Entry>()
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?.forEach { entries.add(Entry(it, it.length(), it.lastModified())) }
        partialRoot.listFiles()?.filter { it.isDirectory }
            ?.forEach { entries.add(Entry(it, sizeOf(it), newestModified(it))) }
        entries.sortBy { it.modified }
        var count = entries.size
        var bytes = entries.sumOf { it.bytes }
        for (entry in entries) {
            if (count <= MAX_FILES && bytes <= MAX_DIR_BYTES) break
            if (entry.file == keep) continue
            if (entry.file.deleteRecursively()) {
                deleted++
                count--
                bytes -= entry.bytes
            }
        }
        return deleted
    }

    private fun sizeOf(folder: File): Long = folder.listFiles()?.sumOf { it.length() } ?: 0L

    private fun newestModified(folder: File): Long =
        maxOf(folder.lastModified(), folder.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L)
}

/**
 * Writes [bytes] to [target] through a temporary file and a rename, so a reader (adb pull, the PC
 * script) never sees half a file and a crash mid-write leaves the previous version in place.
 *
 * [durable] also flushes the bytes and then the folder to the storage device, so a power cut right
 * after the call cannot leave an empty or missing file. It is for writes that are acked to the
 * iPhone; a file that is simply replaced again a minute later does not need it.
 */
internal fun writeAtomically(target: File, bytes: ByteArray, durable: Boolean = false) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    FileOutputStream(tmp).use { out ->
        out.write(bytes)
        if (durable) out.fd.sync()
    }
    moveAtomically(tmp, target)
    if (durable) target.parentFile?.let(::syncFolder)
}

/**
 * Flushes a folder's entries, so a rename into it survives a power cut. Best effort: Linux allows
 * it, but not every file system or platform does (the JVM tests on Windows refuse to open a folder),
 * and the file's own bytes were already synced.
 */
internal fun syncFolder(dir: File) {
    runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
}

internal fun moveAtomically(source: File, target: File) {
    // ATOMIC_MOVE is a rename(2) on the device, which replaces the target in one step.
    Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
