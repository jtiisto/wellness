/**
 * PillSelect — a compact pill trigger that opens the app's bottom sheet
 * (modal-overlay/modal-content, the tools-menu idiom) instead of the OS
 * native select dialog, which can't be themed and reads as foreign.
 *
 * Props:
 *   title:    sheet header ("Exercise", "Tracker")
 *   value:    currently selected option value
 *   options:  [{value, label}]
 *   onChange: (value) => void
 */
import { h } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import htm from 'htm';

const html = htm.bind(h);

export function PillSelect({ title, value, options, onChange }) {
    const [open, setOpen] = useState(false);
    const current = options.find(o => o.value === value);

    useEffect(() => {
        if (!open) return;
        const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
        document.addEventListener('keydown', onKey);
        return () => document.removeEventListener('keydown', onKey);
    }, [open]);

    return html`
        <button class="trends-picker" aria-haspopup="listbox" aria-expanded=${open}
                onClick=${() => setOpen(true)}>
            <span class="trends-picker-label">${current ? current.label : ''}</span>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round" width="14" height="14"
                 aria-hidden="true"><polyline points="6 9 12 15 18 9"/></svg>
        </button>
        ${open && html`
            <div class="modal-overlay" onClick=${() => setOpen(false)}>
                <div class="modal-content" role="listbox" aria-label=${title}
                     onClick=${e => e.stopPropagation()}>
                    <div class="modal-header">
                        <span class="modal-title">${title}</span>
                        <button class="close-btn" onClick=${() => setOpen(false)}>
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                                 stroke-linecap="round" stroke-linejoin="round" width="20" height="20">
                                <line x1="18" y1="6" x2="6" y2="18"/>
                                <line x1="6" y1="6" x2="18" y2="18"/>
                            </svg>
                        </button>
                    </div>
                    <div class="trends-picker-options">
                        ${options.map(o => html`
                            <button key=${o.value} role="option"
                                    aria-selected=${o.value === value}
                                    class="trends-picker-option ${o.value === value ? 'active' : ''}"
                                    onClick=${() => { onChange(o.value); setOpen(false); }}>
                                ${o.label}
                            </button>
                        `)}
                    </div>
                </div>
            </div>
        `}
    `;
}
