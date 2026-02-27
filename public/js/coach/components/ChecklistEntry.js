/**
 * Checklist Entry Component - For warmup/checklist exercises
 */
import { h } from 'preact';
import htm from 'htm';

import { updateLog } from '../store.js';

const html = htm.bind(h);

export function ChecklistEntry({ date, exerciseId, items, completedItems, isEditable = true }) {
    const handleItemToggle = (item) => {
        if (!isEditable) return;
        let newCompleted;
        if (completedItems.includes(item)) {
            newCompleted = completedItems.filter(i => i !== item);
        } else {
            newCompleted = [...completedItems, item];
        }
        updateLog(date, exerciseId, { completed_items: newCompleted });
    };

    return html`
        <div class="checklist-container">
            ${items.map(item => {
                const isChecked = completedItems.includes(item);
                return html`
                    <div class="checklist-item ${isChecked ? 'completed' : ''}" key=${item}>
                        <input
                            type="checkbox"
                            id=${`${exerciseId}-${item}`}
                            checked=${isChecked}
                            onChange=${() => handleItemToggle(item)}
                            disabled=${!isEditable}
                        />
                        <label for=${`${exerciseId}-${item}`}>${item}</label>
                    </div>
                `;
            })}
        </div>
    `;
}
