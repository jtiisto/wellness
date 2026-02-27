import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';
import { reportHistory, loadHistory, loadReport, deleteReport } from '../store.js';
import { formatTimestamp } from '../utils.js';

const html = htm.bind(h);

export function HistoryView() {
    const [items, setItems] = useState(reportHistory.value);

    useEffect(() => {
        const dispose = effect(() => {
            setItems([...reportHistory.value]);
        });
        return dispose;
    }, []);

    useEffect(() => { loadHistory(); }, []);

    if (items.length === 0) {
        return html`
            <div class="empty-state">
                <div class="empty-state-text">No reports yet</div>
            </div>
        `;
    }

    const handleDelete = (e, id) => {
        e.stopPropagation();
        if (confirm('Delete this report?')) {
            deleteReport(id);
        }
    };

    return html`
        <div class="history-list">
            ${items.map(r => html`
                <div key=${r.id} class="history-item" onClick=${() => loadReport(r.id)}>
                    <div class="history-item-content">
                        <div class="history-item-label">${r.query_label}</div>
                        <div class="history-item-date">${formatTimestamp(r.created_at)}</div>
                    </div>
                    <span class="history-item-status ${r.status}">${r.status}</span>
                    <button
                        class="history-item-delete"
                        onClick=${(e) => handleDelete(e, r.id)}
                    >✕</button>
                </div>
            `)}
        </div>
    `;
}
