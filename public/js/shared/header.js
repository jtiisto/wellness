/**
 * Shared Header Component
 * Used by modules that have a sync indicator (journal, coach).
 * Each module passes its own signals/callbacks as props.
 */
import { h } from 'preact';
import htm from 'htm';

const html = htm.bind(h);

export function SyncIndicator({ status, syncing, conflictCount = 0 }) {
    const getTooltip = () => {
        if (syncing) return 'Syncing...';
        if (status === 'yellow') return `${conflictCount} conflict${conflictCount !== 1 ? 's' : ''} to resolve`;
        if (status === 'red') return 'Pending changes';
        if (status === 'green') return 'Synced';
        return 'Offline';
    };

    return html`
        <div
            class="sync-indicator ${syncing ? 'syncing' : ''} ${conflictCount > 0 ? 'has-conflicts' : ''}"
            title=${getTooltip()}
        >
            <div class="sync-dot ${status}"></div>
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
