import { h } from 'preact';
import htm from 'htm';
import { navigateTo, submitQuery } from '../store.js';
import { formatTimestamp, markdownToHtml } from '../utils.js';

const html = htm.bind(h);

export function ReportView({ report }) {
    if (!report) {
        return html`<div class="empty-state"><div class="empty-state-text">No report selected</div></div>`;
    }

    if (report.status === 'failed') {
        return html`
            <div class="report-view">
                <div class="report-header">
                    <button class="back-btn" onClick=${() => navigateTo('queries')}>← Back</button>
                </div>
                <div class="error-state">
                    <div>Query Failed</div>
                    <div class="error-state-message">${report.error_message || 'Unknown error'}</div>
                    ${report.query_id && html`
                        <button class="btn btn-primary mt-md" onClick=${() => submitQuery(report.query_id)}>
                            Try Again
                        </button>
                    `}
                </div>
            </div>
        `;
    }

    return html`
        <div class="report-view">
            <div class="report-header">
                <button class="back-btn" onClick=${() => navigateTo('queries')}>← Back</button>
                <div class="report-title">${report.query_label}</div>
            </div>
            <div class="report-meta">${formatTimestamp(report.completed_at || report.created_at)}</div>
            <div
                class="report-content"
                dangerouslySetInnerHTML=${{ __html: markdownToHtml(report.response_markdown) }}
            />
        </div>
    `;
}
