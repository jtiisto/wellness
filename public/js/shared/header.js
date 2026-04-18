/**
 * Shared Header Component
 * Used by modules that have a sync indicator (journal, coach).
 * Each module passes its own signals/callbacks as props.
 */
import { h } from 'preact';
import htm from 'htm';

const html = htm.bind(h);

export function SyncIndicator({ status, syncing, conflictCount = 0 }) {
    // Label is the primary signal on wider viewports — the dot handles the
    // narrow-viewport case via a media query that hides the text.
    const label = (() => {
        if (syncing) return 'Syncing\u2026';
        if (status === 'yellow') return `${conflictCount} conflict${conflictCount !== 1 ? 's' : ''}`;
        if (status === 'red') return 'Pending';
        if (status === 'green') return 'Synced';
        return 'Offline';
    })();

    return html`
        <div
            class="sync-indicator ${syncing ? 'syncing' : ''} ${conflictCount > 0 ? 'has-conflicts' : ''}"
            role="status"
            aria-label=${`Sync status: ${label}`}
        >
            <div class="sync-dot ${status}"></div>
            <span class="sync-indicator-label">${label}</span>
            ${conflictCount > 0 && html`
                <span class="conflict-badge">${conflictCount}</span>
            `}
        </div>
    `;
}

export function Header({ title, children }) {
    return html`
        <header class="header">
            <h1 class="header-title">${title}</h1>
            <div class="header-actions">
                ${children}
            </div>
        </header>
    `;
}
