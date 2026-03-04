/**
 * Journal Header Component
 */
import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';
import {
    currentView,
    syncStatus,
    isSyncing,
    pendingConflicts
} from '../store.js';

const html = htm.bind(h);

export function Header() {
    const [view, setView] = useState(currentView.value);
    const [status, setStatus] = useState(syncStatus.value);
    const [syncing, setSyncing] = useState(isSyncing.value);
    const [conflictCount, setConflictCount] = useState(pendingConflicts.value.length);

    useEffect(() => {
        const dispose = effect(() => {
            setView(currentView.value);
            setStatus(syncStatus.value);
            setSyncing(isSyncing.value);
            setConflictCount(pendingConflicts.value.length);
        });
        return dispose;
    }, []);

    const handleConfigClick = () => {
        if (view === 'conflicts') {
            currentView.value = 'home';
        } else {
            currentView.value = currentView.value === 'home' ? 'config' : 'home';
        }
    };

    const getTitle = () => {
        switch (view) {
            case 'config': return 'Settings';
            case 'conflicts': return 'Conflicts';
            default: return 'Journal';
        }
    };

    const getSyncTooltip = () => {
        if (syncing) return 'Syncing...';
        if (status === 'yellow') return `${conflictCount} conflict${conflictCount !== 1 ? 's' : ''} to resolve`;
        if (status === 'red') return 'Pending changes';
        if (status === 'green') return 'Synced';
        return 'Offline';
    };

    return html`
        <header class="header">
            <h1 class="header-title">
                ${getTitle()}
            </h1>
            <div class="header-actions">
                <div
                    class="sync-indicator ${syncing ? 'syncing' : ''} ${status === 'yellow' ? 'has-conflicts' : ''}"
                    title=${getSyncTooltip()}
                >
                    <div class="sync-dot ${status}"></div>
                    ${conflictCount > 0 && html`
                        <span class="conflict-badge">${conflictCount}</span>
                    `}
                </div>
                <button
                    class="icon-btn"
                    onClick=${handleConfigClick}
                    title=${view === 'home' ? 'Settings' : 'Back'}
                >
                    ${view === 'home' ? '\u2699' : '\u2190'}
                </button>
            </div>
        </header>
    `;
}
