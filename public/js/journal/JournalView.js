/**
 * JournalView - Module entry component for the Journal module
 * Wraps the journal's component tree and initializes the store on mount.
 */
import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';
import { currentView, initializeStore, initError, discardLocalAndContinue } from './store.js';
import { Header } from './components/Header.js';
import { TrackerList } from './components/TrackerList.js';
import { ConfigScreen } from './components/ConfigScreen.js';

const html = htm.bind(h);

export default function JournalView() {
    const [loading, setLoading] = useState(true);
    const [view, setView] = useState('home');
    const [error, setError] = useState(initError.value);

    useEffect(() => {
        const dispose = effect(() => {
            setView(currentView.value);
            setError(initError.value);
        });
        return dispose;
    }, []);

    useEffect(() => {
        initializeStore()
            .then(() => {
                setLoading(false);
            })
            .catch(err => {
                console.error('Journal init error:', err);
                setLoading(false);
            });
    }, []);

    if (loading) {
        return html`
            <div class="journal">
                <div class="empty-state">
                    <div class="loading-spinner"></div>
                    <p>Loading...</p>
                </div>
            </div>
        `;
    }

    if (error) {
        const handleDiscard = () => {
            if (window.confirm(`Discard ${error.dirtyCount} unsynced change${error.dirtyCount === 1 ? '' : 's'} and continue? This cannot be undone.`)) {
                discardLocalAndContinue();
            }
        };
        return html`
            <div class="journal">
                <div class="empty-state" role="alert">
                    <div class="empty-state-icon">⚠</div>
                    <h2>Storage upgrade required</h2>
                    <p>${error.message}</p>
                    ${error.recoverable && html`
                        <button type="button" class="btn btn-danger" onClick=${handleDiscard}>
                            Discard local changes and continue
                        </button>
                    `}
                </div>
            </div>
        `;
    }

    return html`
        <div class="journal">
            <${Header} />
            ${view === 'home' && html`<${TrackerList} />`}
            ${view === 'config' && html`<${ConfigScreen} />`}
        </div>
    `;
}
