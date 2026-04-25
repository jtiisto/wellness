/**
 * SupersetGroup - Wraps consecutive exercises that share a superset_group label.
 *
 * Renders a labeled container around its members so antagonist pairs / triplets
 * read as a single visual unit. Receives already-grouped children from BlockView
 * via the `groupExercises` helper in utils.js.
 */
import { h } from 'preact';
import htm from 'htm';

const html = htm.bind(h);

// Bare letter / number labels ("A", "B", "C2") get prefixed with "Superset"
// for display. Compound labels like "Triplet A" or "Pair B" already carry
// their own prefix and render as-is.
const BARE_LABEL_RE = /^[A-Za-z]\d*$/;

export function SupersetGroup({ label, children }) {
    const displayLabel = BARE_LABEL_RE.test(label) ? `Superset ${label}` : label;
    return html`
        <div class="superset-group" data-superset-group=${label}>
            <div class="superset-group__label">${displayLabel}</div>
            <div class="superset-group__members">
                ${children}
            </div>
        </div>
    `;
}
