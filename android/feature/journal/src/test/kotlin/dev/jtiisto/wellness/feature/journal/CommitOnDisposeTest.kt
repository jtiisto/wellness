package dev.jtiisto.wellness.feature.journal

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Drives [CommitOnDispose] through a bare `Recomposer` — no UI, no device.
 *
 * The composition is a plain node tree with a no-op applier; the only thing
 * exercised is the runtime's lifecycle: state changes, recomposition under a
 * hand-pumped frame clock, and a composable leaving the tree. That is exactly
 * the surface the fix lives on, so it is the surface the test pins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommitOnDisposeTest {

    private object UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    /** One composition under test, with the frame clock in the test's hands. */
    private class Harness(val scope: TestScope, val clock: BroadcastFrameClock, val composition: Composition) {
        private var frame = 0L

        /** Flush snapshot writes, let the recomposer wake, pump a frame, settle. */
        fun recompose() {
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
            clock.sendFrame(frame++)
            scope.runCurrent()
        }
    }

    private fun compositionTest(block: Harness.() -> Unit) = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        // The frame clock rides on the runner's context: that is where the
        // recomposer looks for it, and the default would reach for Android's
        // Choreographer.
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val composition = Composition(UnitApplier, recomposer)
        try {
            Harness(this, clock, composition).block()
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.cancel()
        }
    }

    @Test
    @DisplayName("typed text commits once when the row leaves composition")
    fun typedTextCommitsOnRemoval() = compositionTest {
        var present by mutableStateOf(true)
        var text by mutableStateOf("")
        val committed = mutableListOf<String>()

        composition.setContent {
            if (present) CommitOnDispose(key = "protein", text = text, seededText = "") { committed += it }
        }
        text = "125"
        recompose()
        assertEquals(emptyList<String>(), committed, "nothing is written while the row is on screen")

        present = false
        recompose()
        assertEquals(listOf("125"), committed, "the effect saw the text as typed, not the empty field it was born with")
    }

    @Test
    @DisplayName("an untouched field writes nothing when its row leaves")
    fun untouchedFieldStaysSilent() = compositionTest {
        var present by mutableStateOf(true)
        val committed = mutableListOf<String>()

        composition.setContent {
            if (present) CommitOnDispose(key = "protein", text = "7", seededText = "7") { committed += it }
        }
        present = false
        recompose()
        assertEquals(emptyList<String>(), committed)
    }

    @Test
    @DisplayName("a field the blur already committed and re-seeded does not write twice")
    fun blurThenDisposeWritesOnce() = compositionTest {
        var present by mutableStateOf(true)
        var text by mutableStateOf("7")
        val committed = mutableListOf<String>()

        composition.setContent {
            if (present) CommitOnDispose(key = "protein", text = text, seededText = "7") { committed += it }
        }
        text = "125"
        recompose()
        // The blur's commit writes and then re-seeds the local text.
        text = "7"
        recompose()

        present = false
        recompose()
        assertEquals(emptyList<String>(), committed, "the dispose path found nothing pending")
    }

    @Test
    @DisplayName("disposing the whole composition — the tab switch — commits too")
    fun compositionDisposeCommits() = compositionTest {
        var text by mutableStateOf("")
        val committed = mutableListOf<String>()

        composition.setContent {
            CommitOnDispose(key = "protein", text = text, seededText = "") { committed += it }
        }
        text = "90"
        recompose()

        composition.dispose()
        assertEquals(listOf("90"), committed)
    }

    @Test
    @DisplayName("the commit lambda in force at disposal is the one called")
    fun latestCommitLambdaWins() = compositionTest {
        var present by mutableStateOf(true)
        var target by mutableStateOf("first")
        val committed = mutableListOf<String>()

        composition.setContent {
            // Recreated every recomposition, as the screen's lambda is — it
            // captures the row as it stands, so the stale one must never run.
            val label = target
            if (present) CommitOnDispose(key = "protein", text = "125", seededText = "") { committed += "$label:$it" }
        }
        target = "second"
        recompose()

        present = false
        recompose()
        assertEquals(listOf("second:125"), committed)
    }
}
