package dev.jtiisto.wellness.core.data.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneId

/** Naming, retention, and the switch's clean-out of `cache/shared/`. */
class SharedFileStoreTest {

    @TempDir
    lateinit var root: File

    private val utc = ZoneId.of("UTC")

    private fun store(now: Long) = SharedFileStore(root = File(root, "shared"), now = { now }, zone = utc)

    @Test
    @DisplayName("the export filename carries the local date and time to the second")
    fun exportFileName() {
        assertEquals("wellness-export-2026-08-09-143005.json", store(INSTANT).exportFileName())
    }

    @Test
    @DisplayName("the log filename follows the same pattern with a .txt extension")
    fun debugLogFileName() {
        assertEquals("debug-log-2026-08-09-143005.txt", store(INSTANT).debugLogFileName())
    }

    @Test
    @DisplayName("second precision keeps two exports a few seconds apart from colliding")
    fun namesAreDistinctWithinAMinute() {
        // A minute-precision name would have the second share overwrite the
        // first while a recipient app was still reading it.
        assertFalse(store(INSTANT).exportFileName() == store(INSTANT + 1_000).exportFileName())
    }

    @Test
    @DisplayName("prepare creates the directory and returns a path inside it")
    fun prepareCreatesTheDirectory() {
        val file = store(INSTANT).prepare("thing.json")

        assertTrue(file.parentFile.isDirectory)
        assertEquals("shared", file.parentFile.name)
        assertFalse(file.exists(), "the file itself is the caller's stream to create")
    }

    @Test
    @DisplayName("preparing a share sweeps out anything older than 24 hours")
    fun cleanupOnPrepare() {
        val shared = File(root, "shared").apply { mkdirs() }
        val old = File(shared, "old.json").apply { writeText("x"); setLastModified(INSTANT - 25 * HOUR) }
        val recent = File(shared, "recent.json").apply { writeText("x"); setLastModified(INSTANT - 2 * HOUR) }

        store(INSTANT).prepare("new.json")

        assertFalse(old.exists(), "a day-old export is still a full copy of someone's journal")
        assertTrue(recent.exists())
    }

    @Test
    @DisplayName("a file exactly at the retention age is already gone")
    fun retentionBoundaryIsExclusive() {
        assertTrue(SharedFileStore.isExpired(lastModified = 100, cutoff = 100))
        assertFalse(SharedFileStore.isExpired(lastModified = 101, cutoff = 100))
    }

    @Test
    @DisplayName("deleteAll empties the directory — the switch's last step")
    fun deleteAllEmptiesTheDirectory() {
        val shared = File(root, "shared").apply { mkdirs() }
        File(shared, "export.json").writeText("previous server's data")
        File(shared, "debug-log.txt").writeText("previous server's traffic")

        store(INSTANT).deleteAll()

        // An export named "all your data" must not outlive the switch that made
        // it describe a different server.
        assertEquals(emptyList<String>(), shared.list()!!.toList())
    }

    @Test
    @DisplayName("cleanup on a directory that does not exist yet is a no-op, not a crash")
    fun cleanupWithoutDirectory() {
        store(INSTANT).cleanupExpired()
        store(INSTANT).deleteAll()
    }

    private companion object {
        const val HOUR = 60L * 60L * 1000L

        /** 2026-08-09T14:30:05Z as epoch millis. */
        const val INSTANT = 1_786_285_805_000L
    }
}
