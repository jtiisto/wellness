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
 * `rememberUpdatedState` is what makes a lambda created at first composition
 * see [text], [seededText] and [commit] as they stand at disposal; without it
 * the effect would commit the empty field the row was born with.
 * [pendingNumericInput] keeps a field the blur already committed — the blur
 * re-seeds the text — from writing the same number twice.
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
fun CommitOnDispose(key: Any?, text: String, seededText: String, commit: (String) -> Unit) {
    val latestText by rememberUpdatedState(text)
    val latestSeeded by rememberUpdatedState(seededText)
    val latestCommit by rememberUpdatedState(commit)
    DisposableEffect(key) {
        onDispose {
            pendingNumericInput(latestText, latestSeeded)?.let { latestCommit(it) }
        }
    }
}
