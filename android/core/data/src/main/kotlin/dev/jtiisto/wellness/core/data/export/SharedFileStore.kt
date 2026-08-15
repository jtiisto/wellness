package dev.jtiisto.wellness.core.data.export

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The staging area behind every share action: `cache/shared/`, the one
 * directory the app's `FileProvider` is allowed to hand out.
 *
 * Everything written here has already left the app's control the moment the
 * chooser appears — the recipient may copy it anywhere — so the only thing this
 * can usefully manage is the local copy. It does two things about that:
 *
 * - **Distinct names.** Second precision in the filename, so two exports a
 *   minute apart do not overwrite each other while the first is still being
 *   read by whatever the user shared it to.
 * - **Bounded lifetime.** Every share sweeps out anything older than 24 hours.
 *   The cache directory is the system's to reclaim, but it does so on its own
 *   schedule and a full journal export is not a file to leave lying around
 *   waiting for that.
 *
 * The clock is local rather than UTC on purpose: these filenames are read by a
 * person who is choosing between two of them in a file picker, and "the one
 * from this morning" has to mean their morning.
 */
class SharedFileStore(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * A fresh file to write, with the directory swept and created. The file
     * itself is not created — the caller's stream does that.
     */
    fun prepare(name: String): File {
        cleanupExpired()
        root.mkdirs()
        return File(root, name)
    }

    /** Drop everything past its retention. Failures are ignored: this is a cache. */
    fun cleanupExpired() {
        val cutoff = now() - RETENTION_MS
        root.listFiles()?.forEach { file ->
            if (isExpired(file.lastModified(), cutoff)) file.delete()
        }
    }

    /**
     * Empty the directory outright — the server switch's last cleanup step.
     *
     * An export or a log dump taken against the previous server must not
     * outlive the switch to a new one: the file is named as if it described
     * "the app's data", and after the wipe it no longer does. Copies already
     * delivered to other apps are gone regardless; this is the one still here.
     */
    fun deleteAll() {
        root.listFiles()?.forEach { it.delete() }
    }

    /** `wellness-export-2026-08-09-143005.json`. */
    fun exportFileName(): String = "wellness-export-${stamp()}.json"

    /** `debug-log-2026-08-09-143005.txt`. */
    fun debugLogFileName(): String = "debug-log-${stamp()}.txt"

    private fun stamp(): String =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now()), zone).format(STAMP_FORMAT)

    companion object {
        /** The `FileProvider` path this maps to; see `file_paths.xml`. */
        const val DIRECTORY = "shared"

        const val RETENTION_MS = 24L * 60L * 60L * 1000L

        /** A file exactly at its retention age is already gone. */
        fun isExpired(lastModified: Long, cutoff: Long): Boolean = lastModified <= cutoff

        private val STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
    }
}
