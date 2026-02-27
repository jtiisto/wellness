/**
 * ConflictResolver Component - UI for resolving sync conflicts
 */
import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';
import {
    pendingConflicts,
    resolveConflict,
    resolveAllConflicts,
    currentView,
    trackerConfig
} from '../store.js';

const html = htm.bind(h);

function formatValue(value, type) {
    if (value === null || value === undefined) {
        return '-';
    }
    if (typeof value === 'boolean') {
        return value ? 'Yes' : 'No';
    }
    return String(value);
}

function getTrackerName(trackerId) {
    const tracker = trackerConfig.value.find(t => t.id === trackerId);
    return tracker?.name || trackerId.slice(0, 8) + '...';
}

function ConflictItem({ conflict, onResolve }) {
    const [resolving, setResolving] = useState(false);

    const handleResolve = async (resolution) => {
        setResolving(true);
        await onResolve(conflict, resolution);
        setResolving(false);
    };

    const isTracker = conflict.type === 'tracker';
    const title = isTracker
        ? `Tracker: ${conflict.local?.name || conflict.server?.name}`
        : `Entry: ${conflict.date} - ${getTrackerName(conflict.trackerId)}`;

    return html`
        <div class="conflict-item">
            <div class="conflict-header">
                <span class="conflict-type ${conflict.type}">${isTracker ? 'Config' : 'Entry'}</span>
                <h3 class="conflict-title">${title}</h3>
            </div>

            <div class="conflict-comparison">
                <div class="conflict-version local">
                    <div class="version-header">
                        <span class="version-label">Your Version</span>
                        <span class="version-badge">Local</span>
                    </div>
                    <div class="version-content">
                        ${isTracker ? html`
                            <div class="field-row">
                                <span class="field-label">Name:</span>
                                <span class="field-value">${conflict.local?.name || '-'}</span>
                            </div>
                            <div class="field-row">
                                <span class="field-label">Category:</span>
                                <span class="field-value">${conflict.local?.category || '-'}</span>
                            </div>
                            <div class="field-row">
                                <span class="field-label">Type:</span>
                                <span class="field-value">${conflict.local?.type || '-'}</span>
                            </div>
                        ` : html`
                            <div class="field-row">
                                <span class="field-label">Value:</span>
                                <span class="field-value">${formatValue(conflict.local?.value)}</span>
                            </div>
                            <div class="field-row">
                                <span class="field-label">Completed:</span>
                                <span class="field-value">${formatValue(conflict.local?.completed)}</span>
                            </div>
                        `}
                    </div>
                    <button
                        class="btn btn-primary btn-block"
                        onClick=${() => handleResolve('client')}
                        disabled=${resolving}
                    >
                        ${resolving ? 'Saving...' : 'Keep Mine'}
                    </button>
                </div>

                <div class="conflict-divider">
                    <span>vs</span>
                </div>

                <div class="conflict-version server">
                    <div class="version-header">
                        <span class="version-label">Other Device</span>
                        <span class="version-badge server">Server</span>
                    </div>
                    <div class="version-content">
                        ${isTracker ? html`
                            <div class="field-row">
                                <span class="field-label">Name:</span>
                                <span class="field-value">${conflict.server?.name || '-'}</span>
                            </div>
                            <div class="field-row">
                                <span class="field-label">Category:</span>
                                <span class="field-value">${conflict.server?.category || '-'}</span>
                            </div>
                            <div class="field-row">
                                <span class="field-label">Type:</span>
                                <span class="field-value">${conflict.server?.type || '-'}</span>
                            </div>
                        ` : html`
                            <div class="field-row">
                                <span class="field-label">Value:</span>
                                <span class="field-value">${formatValue(conflict.server?.value)}</span>
                            </div>
                            <div class="field-row">
                                <span class="field-label">Completed:</span>
                                <span class="field-value">${formatValue(conflict.server?.completed)}</span>
                            </div>
                        `}
                    </div>
                    <button
                        class="btn btn-secondary btn-block"
                        onClick=${() => handleResolve('server')}
                        disabled=${resolving}
                    >
                        ${resolving ? 'Saving...' : 'Use Theirs'}
                    </button>
                </div>
            </div>
        </div>
    `;
}

export function ConflictResolver() {
    const [conflicts, setConflicts] = useState(pendingConflicts.value);
    const [resolvingAll, setResolvingAll] = useState(false);

    useEffect(() => {
        const dispose = effect(() => {
            setConflicts([...pendingConflicts.value]);
        });
        return dispose;
    }, []);

    const handleResolve = async (conflict, resolution) => {
        await resolveConflict(conflict, resolution);
    };

    const handleResolveAll = async (resolution) => {
        setResolvingAll(true);
        await resolveAllConflicts(resolution);
        setResolvingAll(false);
    };

    const handleBack = () => {
        currentView.value = 'home';
    };

    if (conflicts.length === 0) {
        return html`
            <div class="conflict-screen">
                <div class="conflict-screen-header">
                    <button class="icon-btn" onClick=${handleBack}>\u2190</button>
                    <h2>Sync Conflicts</h2>
                </div>
                <div class="empty-state">
                    <div class="empty-state-icon">\u2705</div>
                    <p>No conflicts to resolve!</p>
                    <button class="btn btn-primary" onClick=${handleBack}>
                        Back to Journal
                    </button>
                </div>
            </div>
        `;
    }

    return html`
        <div class="conflict-screen">
            <div class="conflict-screen-header">
                <button class="icon-btn" onClick=${handleBack}>\u2190</button>
                <h2>Sync Conflicts</h2>
            </div>

            <div class="conflict-intro">
                <p class="conflict-intro-text">
                    Changes were made on another device while you were offline.
                    Choose which version to keep for each conflict.
                </p>
                <div class="conflict-bulk-actions">
                    <button
                        class="btn btn-secondary"
                        onClick=${() => handleResolveAll('client')}
                        disabled=${resolvingAll}
                    >
                        Keep All Mine
                    </button>
                    <button
                        class="btn btn-secondary"
                        onClick=${() => handleResolveAll('server')}
                        disabled=${resolvingAll}
                    >
                        Use All Theirs
                    </button>
                </div>
            </div>

            <div class="conflict-list">
                ${conflicts.map(conflict => html`
                    <${ConflictItem}
                        key=${conflict.id}
                        conflict=${conflict}
                        onResolve=${handleResolve}
                    />
                `)}
            </div>
        </div>
    `;
}
