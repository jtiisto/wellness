/**
 * Tools Menu - Slide-up modal with app utilities
 */
import { h } from 'preact';
import { useState } from 'preact/hooks';
import htm from 'htm';
import { downloadDebugLog } from './debug-log.js';
import { exportAllData } from './data-export.js';
import { forceSync } from './force-sync.js';
import { showNotification } from './notifications.js';

const html = htm.bind(h);

export function ToolsMenu({ isOpen, onClose }) {
    const [isForceSyncing, setIsForceSyncing] = useState(false);

    if (!isOpen) return null;

    function handleDownloadLog() {
        downloadDebugLog();
        onClose();
    }

    function handleExportData() {
        exportAllData();
        onClose();
    }

    async function handleForceSync() {
        if (!navigator.onLine) {
            showNotification({
                type: 'error',
                title: 'Offline',
                message: 'Force sync requires an internet connection.'
            });
            return;
        }

        if (!confirm('This will reconcile all data with the server. Continue?')) return;

        setIsForceSyncing(true);
        try {
            const results = await forceSync();

            const parts = [];
            if (results.coach?.success) {
                parts.push(`Coach: ${results.coach.uploaded} uploaded, ${results.coach.accepted} accepted`);
            } else if (results.coach) {
                parts.push(`Coach: failed`);
            }
            if (results.journal?.success) {
                parts.push(`Journal: ${results.journal.uploaded} uploaded, ${results.journal.accepted} accepted`);
            } else if (results.journal) {
                parts.push(`Journal: failed`);
            }

            const anySuccess = results.coach?.success || results.journal?.success;
            showNotification({
                type: anySuccess ? 'success' : 'error',
                title: 'Force Sync Complete',
                message: parts.join('. ') || 'No modules synced.',
                duration: 5000
            });
        } catch (error) {
            showNotification({
                type: 'error',
                title: 'Force Sync Failed',
                message: error.message
            });
        } finally {
            setIsForceSyncing(false);
            onClose();
        }
    }

    return html`
        <div class="modal-overlay" onClick=${onClose}>
            <div class="modal-content tools-menu" onClick=${e => e.stopPropagation()}>
                <div class="modal-header">
                    <span class="modal-title">Tools</span>
                    <button class="close-btn" onClick=${onClose}>
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="20" height="20">
                            <line x1="18" y1="6" x2="6" y2="18"/>
                            <line x1="6" y1="6" x2="18" y2="18"/>
                        </svg>
                    </button>
                </div>
                <div class="tools-list">
                    <button class="tools-item" onClick=${handleDownloadLog}>
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="20" height="20">
                            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                            <polyline points="7 10 12 15 17 10"/>
                            <line x1="12" y1="15" x2="12" y2="3"/>
                        </svg>
                        Save Debug Log
                    </button>
                    <button class="tools-item" onClick=${handleExportData}>
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="20" height="20">
                            <path d="M12 3v12m0 0l-4-4m4 4l4-4"/>
                            <path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/>
                        </svg>
                        Export All Data
                    </button>
                    <button class="tools-item" onClick=${handleForceSync} disabled=${isForceSyncing}>
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="20" height="20">
                            <path d="M21.5 2v6h-6"/>
                            <path d="M2.5 22v-6h6"/>
                            <path d="M2 11.5a10 10 0 0 1 18.8-4.3L21.5 8"/>
                            <path d="M22 12.5a10 10 0 0 1-18.8 4.3L2.5 16"/>
                        </svg>
                        ${isForceSyncing ? 'Syncing...' : 'Force Sync'}
                    </button>
                </div>
            </div>
        </div>
    `;
}
