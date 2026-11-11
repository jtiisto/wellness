package dev.jtiisto.wellness.feature.journal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Commit a numeric field's pending text when its row leaves composition.
 *
 * The field writes on focus loss or IME Done, but a row can be disposed with
 * the keyboard still up — the category header collapses it, the list scrolls
 * it off, the tab changes — and Compose's focus invalidation skips detached
 * nodes, so the focus-loss event the commit hung on never arrives. The typed
 * number was silently dropped (device-found, 2026-10-29). `onDispose` is the
 * one guarantee that does fire for every way out, so the commit hangs on it
 * too.
 *
 * [text] is read at disposal, not copied at composition. The checkbox and
 * the blur both hand the typed text to a write and then reset it to the seed
 * in the same event; a row removed before it recomposes would otherwise
 * dispose with the pre-reset text still cached and write it a second time,
 * stamp and all (Codex review, 2026-11-11). [seededText] and [commit] can stay
 * composition-cached through `rememberUpdatedState`: the reset writes exactly
 * the seed of the composition it ran in, which is the one cached here, so the
 * live text compares equal and [pendingNumericInput] finds nothing pending.
 * Without `rememberUpdatedState` a lambda created at first composition would
 * see the seed and commit the row was born with.
 *
 * UI-free on purpose: this is the one piece of the field that is about
 * lifecycle rather than layout, and keeping `compose.ui` out of it is what lets
 * `CommitOnDisposeTest` drive it through a bare `Recomposer` on the JVM — no
 * device, no Robolectric — the project's rule that UI stays JVM-logic-tested,
 * extended to the one effect that is a lifecycle promise.
 *
 * @param key the row's identity; the effect is re-armed only when it changes.
 */
@Composable
fun CommitOnDispose(key: Any?, text: () -> String, seededText: String, commit: (String) -> Unit) {
    val latestText by rememberUpdatedState(text)
    val latestSeeded by rememberUpdatedState(seededText)
    val latestCommit by rememberUpdatedState(commit)
    DisposableEffect(key) {
        onDispose {
            pendingNumericInput(latestText(), latestSeeded)?.let { latestCommit(it) }
        }
    }
}
