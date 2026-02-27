/**
 * JournalView - Module entry component for the Journal module
 * Wraps the journal's component tree and initializes the store on mount.
 */
import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';
import { currentView, initializeStore, triggerSync } from './store.js';
import { Header } from './components/Header.js';
import { TrackerList } from './components/TrackerList.js';
import { ConfigScreen } from './components/ConfigScreen.js';
import { ConflictResolver } from './components/ConflictResolver.js';

const html = htm.bind(h);

export default function JournalView() {
    const [loading, setLoading] = useState(true);
    const [view, setView] = useState('home');

    useEffect(() => {
        const dispose = effect(() => {
            setView(currentView.value);
        });
        return dispose;
    }, []);

    useEffect(() => {
        initializeStore()
            .then(() => {
                setLoading(false);
                triggerSync();
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

    return html`
        <div class="journal">
            <${Header} />
            ${view === 'home' && html`<${TrackerList} />`}
            ${view === 'config' && html`<${ConfigScreen} />`}
            ${view === 'conflicts' && html`<${ConflictResolver} />`}
        </div>
    `;
}
