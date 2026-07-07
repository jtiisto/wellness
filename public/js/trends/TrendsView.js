/**
 * Trends View — module entry. Read-only charts over coach/journal/Garmin
 * aggregates: deterministic "what happened" at a glance (the interpretive
 * "what does it mean" lives in interactive LLM sessions, not here).
 *
 * Sub-screens land phase by phase; until a screen ships it renders a stub.
 */
import { h } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';

import { Header } from '../shared/header.js';
import { activeScreen, setActiveScreen, initializeStore } from './store.js';

const html = htm.bind(h);

const SCREENS = [
    { id: 'overview', label: 'Overview' },
    { id: 'strength', label: 'Strength' },
    { id: 'cardio', label: 'Cardio' },
    { id: 'journal', label: 'Journal' },
];

function StubScreen({ label }) {
    return html`
        <div class="empty-state">
            <div class="empty-state-icon">📈</div>
            <p class="empty-state-text">${label} charts coming soon</p>
        </div>
    `;
}

export default function TrendsView() {
    const [screen, setScreen] = useState(activeScreen.value);

    useEffect(() => {
        const dispose = effect(() => setScreen(activeScreen.value));
        return dispose;
    }, []);

    useEffect(() => {
        initializeStore().catch(err => console.error('Trends init error:', err));
    }, []);

    return html`
        <div class="trends">
            <${Header} title="Trends" />
            <div class="trends-tabs" role="tablist">
                ${SCREENS.map(s => html`
                    <button
                        key=${s.id}
                        class="trends-tab ${screen === s.id ? 'active' : ''}"
                        role="tab"
                        aria-selected=${screen === s.id}
                        onClick=${() => setActiveScreen(s.id)}
                    >${s.label}</button>
                `)}
            </div>
            <main class="main-content trends-main">
                <${StubScreen} label=${SCREENS.find(s => s.id === screen)?.label || 'Trends'} />
            </main>
        </div>
    `;
}
