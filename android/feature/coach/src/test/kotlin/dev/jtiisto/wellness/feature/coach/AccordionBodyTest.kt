package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.data.coach.SetColumn
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The accordion's collapse is the one place a stale widget can exist, so the
 * rules for it are pinned here rather than left to a device review that would
 * have to catch a tap inside a 300ms window.
 */
class AccordionBodyTest {

    private val live = EntryWidgetState.Sets(
        columns = listOf(SetColumn(key = "reps", label = "Reps")),
        rows = emptyList(),
        lastPerformanceHint = null,
    )
    private val retained = EntryWidgetState.Checklist(items = emptyList())

    @Test
    @DisplayName("expanded and editable: the live entry is drawn and can be edited")
    fun liveEntryIsInteractive() {
        val body = accordionBody(expanded = true, liveEntry = live, retainedEntry = retained, editable = true)
        assertSame(live, body.entry)
        assertTrue(body.interactive)
    }

    @Test
    @DisplayName("expanded on a read-only day: the live entry is drawn, but not editable")
    fun readOnlyDayIsNeverInteractive() {
        val body = accordionBody(expanded = true, liveEntry = live, retainedEntry = null, editable = false)
        assertSame(live, body.entry)
        assertFalse(body.interactive)
    }

    @Test
    @DisplayName("collapsing draws the retained copy, and it can never be touched")
    fun retainedCopyIsInert() {
        val body = accordionBody(expanded = false, liveEntry = null, retainedEntry = retained, editable = true)
        assertSame(retained, body.entry, "the collapse animates the last body rather than an empty box")
        assertFalse(body.interactive, "a tap during the collapse would commit against a value the store has dropped")
    }

    @Test
    @DisplayName("a live entry that goes null while expanded draws nothing — never the stale copy")
    fun expandedWithoutAnEntryShowsNothing() {
        val body = accordionBody(expanded = true, liveEntry = null, retainedEntry = retained, editable = true)
        assertNull(body.entry)
        assertFalse(body.interactive)
    }

    @Test
    @DisplayName("once the retained copy is dropped there is nothing left to draw")
    fun clearedRetentionDrawsNothing() {
        val body = accordionBody(expanded = false, liveEntry = null, retainedEntry = null, editable = true)
        assertNull(body.entry)
        assertFalse(body.interactive)
    }
}
