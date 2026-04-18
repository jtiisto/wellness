import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';
import { queries, submitQuery, getUserLocation, showNotification } from '../store.js';

const html = htm.bind(h);

// Feather-style line icons keyed by the `icon` field on each query (set
// server-side in analysis_queries.py). Unknown or missing names fall back
// to a neutral document glyph so user_queries without an icon still render.
const ICONS = {
    dumbbell: html`<path d="M6.5 6.5 17.5 17.5"></path><path d="m21 21-1-1"></path><path d="m3 3 1 1"></path><path d="m18 22 4-4"></path><path d="m2 6 4-4"></path><path d="m3 10 7-7"></path><path d="m14 21 7-7"></path>`,
    zap: html`<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon>`,
    calendar: html`<rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect><line x1="16" y1="2" x2="16" y2="6"></line><line x1="8" y1="2" x2="8" y2="6"></line><line x1="3" y1="10" x2="21" y2="10"></line>`,
    'heart-pulse': html`<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1L12 21l7.8-7.8 1-1a5.5 5.5 0 0 0 0-7.6z"></path><polyline points="3.5 12 7 12 9 8 13 16 15 12 20.5 12"></polyline>`,
    'trending-up': html`<polyline points="23 6 13.5 15.5 8.5 10.5 1 18"></polyline><polyline points="17 6 23 6 23 12"></polyline>`,
    document: html`<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline>`,
};

function QueryIcon({ name }) {
    const shape = ICONS[name] || ICONS.document;
    return html`
        <svg class="query-card-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="24" height="24" aria-hidden="true">
            ${shape}
        </svg>
    `;
}

async function handleQueryClick(query) {
    let location = null;
    if (query.accepts_location) {
        location = await getUserLocation();
        if (location) {
            showNotification({ type: 'info', title: 'Location', message: location, duration: 3000 });
        }
    }
    submitQuery(query.id, location);
}

export function QueryList() {
    const [items, setItems] = useState(queries.value);

    useEffect(() => {
        const dispose = effect(() => {
            setItems([...queries.value]);
        });
        return dispose;
    }, []);

    if (items.length === 0) {
        return html`
            <div class="empty-state">
                <div class="empty-state-text">No queries available</div>
            </div>
        `;
    }

    return html`
        <div class="query-grid">
            ${items.map(q => html`
                <button
                    key=${q.id}
                    class="query-card"
                    onClick=${() => handleQueryClick(q)}
                >
                    <${QueryIcon} name=${q.icon} />
                    <div class="query-card-label">${q.label}</div>
                    <div class="query-card-description">${q.description}</div>
                </button>
            `)}
        </div>
    `;
}
