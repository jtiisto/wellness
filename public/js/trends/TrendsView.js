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
import { StrengthScreen } from './components/StrengthScreen.js';
import { CardioScreen } from './components/CardioScreen.js';
import { JournalScreen } from './components/JournalScreen.js';
import { OverviewScreen } from './components/OverviewScreen.js';
import { HealthScreen } from './components/HealthScreen.js';

const html = htm.bind(h);

const SCREENS = [
    { id: 'overview', label: 'Overview' },
    { id: 'strength', label: 'Strength' },
    { id: 'cardio', label: 'Cardio' },
    { id: 'journal', label: 'Journal' },
    { id: 'health', label: 'Health' },
];

// Screens land phase by phase; unshipped ones render the stub.
const SCREEN_COMPONENTS = {
    overview: OverviewScreen,
    strength: StrengthScreen,
    cardio: CardioScreen,
    journal: JournalScreen,
    health: HealthScreen,
};

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
                ${SCREEN_COMPONENTS[screen]
                    ? h(SCREEN_COMPONENTS[screen], {})
                    : html`<${StubScreen} label=${SCREENS.find(s => s.id === screen)?.label || 'Trends'} />`}
            </main>
        </div>
    `;
}
