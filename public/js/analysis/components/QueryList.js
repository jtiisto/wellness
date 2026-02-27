import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';
import { queries, submitQuery, getUserLocation, showNotification } from '../store.js';

const html = htm.bind(h);

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
                    <div class="query-card-label">${q.label}</div>
                    <div class="query-card-description">${q.description}</div>
                </button>
            `)}
        </div>
    `;
}
