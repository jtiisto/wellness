import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import htm from 'htm';
import { elapsedTime } from '../utils.js';

const html = htm.bind(h);

export function ProgressView({ report }) {
    const [elapsed, setElapsed] = useState('0s');

    useEffect(() => {
        if (!report || !report.created_at) return;
        const update = () => setElapsed(elapsedTime(report.created_at));
        update();
        const timer = setInterval(update, 1000);
        return () => clearInterval(timer);
    }, [report && report.created_at]);

    const label = report && report.query_label ? report.query_label : 'Running query...';

    return html`
        <div class="progress-view">
            <div class="progress-spinner"></div>
            <div class="progress-text">${label}</div>
            <div class="progress-elapsed">${elapsed}</div>
        </div>
    `;
}
