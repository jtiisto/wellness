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
} from '../store.js';
import { SyncIndicator } from '../../shared/header.js';

const html = htm.bind(h);

export function Header() {
    const [view, setView] = useState(currentView.value);
    const [status, setStatus] = useState(syncStatus.value);
    const [syncing, setSyncing] = useState(isSyncing.value);

    useEffect(() => {
        const dispose = effect(() => {
            setView(currentView.value);
            setStatus(syncStatus.value);
            setSyncing(isSyncing.value);
        });
        return dispose;
    }, []);

    const handleConfigClick = () => {
        currentView.value = currentView.value === 'home' ? 'config' : 'home';
    };

    const getTitle = () => {
        switch (view) {
            case 'config': return 'Settings';
            default: return 'Journal';
        }
    };

    return html`
        <header class="header">
            <h1 class="header-title">
                ${getTitle()}
            </h1>
            <div class="header-actions">
                <${SyncIndicator}
                    status=${status}
                    syncing=${syncing}
                />
                <button
                    class="icon-btn"
                    onClick=${handleConfigClick}
                    aria-label=${view === 'home' ? 'Settings' : 'Back'}
                >
                    ${view === 'home' ? '⚙' : '←'}
                </button>
            </div>
        </header>
    `;
}
