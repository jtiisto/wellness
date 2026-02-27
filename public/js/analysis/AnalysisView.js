import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';
import { isLoading, currentView, activeReport, initializeStore, navigateTo } from './store.js';
import { QueryList } from './components/QueryList.js';
import { ProgressView } from './components/ProgressView.js';
import { ReportView } from './components/ReportView.js';
import { HistoryView } from './components/HistoryView.js';

const html = htm.bind(h);

function AnalysisHeader() {
    const [view, setView] = useState(currentView.value);

    useEffect(() => {
        const dispose = effect(() => {
            setView(currentView.value);
        });
        return dispose;
    }, []);

    const isNewActive = view === 'queries' || view === 'loading' || view === 'report';

    return html`
        <header class="analysis-header">
            <div class="analysis-header-title">Analysis</div>
            <div class="analysis-tab-bar">
                <button
                    class="analysis-tab-btn ${isNewActive ? 'active' : ''}"
                    onClick=${() => navigateTo('queries')}
                >New</button>
                <button
                    class="analysis-tab-btn ${view === 'history' ? 'active' : ''}"
                    onClick=${() => navigateTo('history')}
                >History</button>
            </div>
        </header>
    `;
}

export default function AnalysisView() {
    const [loading, setLoading] = useState(true);
    const [view, setView] = useState('queries');
    const [report, setReport] = useState(null);

    useEffect(() => effect(() => {
        setLoading(isLoading.value);
        setView(currentView.value);
        setReport(activeReport.value);
    }), []);

    useEffect(() => { initializeStore(); }, []);

    if (loading) {
        return html`
            <div class="analysis">
                <${AnalysisHeader}/>
                <div class="loading">
                    <div class="loading-spinner"></div>
                    <span>Loading...</span>
                </div>
            </div>
        `;
    }

    const content = {
        queries: html`<${QueryList}/>`,
        loading: html`<${ProgressView} report=${report}/>`,
        report: html`<${ReportView} report=${report}/>`,
        history: html`<${HistoryView}/>`
    }[view] || html`<${QueryList}/>`;

    return html`
        <div class="analysis">
            <${AnalysisHeader}/>
            <main class="analysis-main-content">${content}</main>
        </div>
    `;
}
