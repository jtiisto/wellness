/**
 * Settings Menu - Slide-up modal with app utilities
 */
import { h } from 'preact';
import htm from 'htm';
import { downloadDebugLog } from './debug-log.js';

const html = htm.bind(h);

export function SettingsMenu({ isOpen, onClose }) {
    if (!isOpen) return null;

    function handleDownloadLog() {
        downloadDebugLog();
        onClose();
    }

    return html`
        <div class="modal-overlay" onClick=${onClose}>
            <div class="modal-content settings-menu" onClick=${e => e.stopPropagation()}>
                <div class="modal-header">
                    <span class="modal-title">Settings</span>
                    <button class="close-btn" onClick=${onClose}>
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="20" height="20">
                            <line x1="18" y1="6" x2="6" y2="18"/>
                            <line x1="6" y1="6" x2="18" y2="18"/>
                        </svg>
                    </button>
                </div>
                <div class="settings-list">
                    <button class="settings-item" onClick=${handleDownloadLog}>
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="20" height="20">
                            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                            <polyline points="7 10 12 15 17 10"/>
                            <line x1="12" y1="15" x2="12" y2="3"/>
                        </svg>
                        Save Debug Log
                    </button>
                </div>
            </div>
        </div>
    `;
}
