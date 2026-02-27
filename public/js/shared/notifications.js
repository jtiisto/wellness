/**
 * Shared Notifications - Toast notification system used by all modules
 */
import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { signal, effect } from '@preact/signals';
import htm from 'htm';

const html = htm.bind(h);

// ==================== State ====================

export const notifications = signal([]);

let notificationId = 0;

export function showNotification({ type = 'info', title, message, action = null, duration = 5000 }) {
    const id = ++notificationId;
    const notification = { id, type, title, message, action, createdAt: Date.now() };

    notifications.value = [...notifications.value, notification];

    if (duration > 0) {
        setTimeout(() => {
            dismissNotification(id);
        }, duration);
    }

    return id;
}

export function dismissNotification(id) {
    notifications.value = notifications.value.filter(n => n.id !== id);
}

// ==================== Components ====================

const ICONS = {
    info: '\u2139\uFE0F',
    success: '\u2705',
    warning: '\u26A0\uFE0F',
    error: '\u274C'
};

function NotificationItem({ notification, onDismiss }) {
    const { id, type, title, message, action } = notification;

    const handleAction = () => {
        if (action?.handler) {
            action.handler();
        }
        onDismiss(id);
    };

    return html`
        <div class="notification notification-${type}">
            <div class="notification-icon">${ICONS[type] || ICONS.info}</div>
            <div class="notification-content">
                <div class="notification-title">${title}</div>
                ${message && html`<div class="notification-message">${message}</div>`}
            </div>
            <div class="notification-actions">
                ${action && html`
                    <button class="notification-action-btn" onClick=${handleAction}>
                        ${action.label}
                    </button>
                `}
                <button class="notification-close" onClick=${() => onDismiss(id)}>
                    \u2715
                </button>
            </div>
        </div>
    `;
}

export function Notifications() {
    const [items, setItems] = useState(notifications.value);

    useEffect(() => {
        const dispose = effect(() => {
            setItems([...notifications.value]);
        });
        return dispose;
    }, []);

    if (items.length === 0) {
        return null;
    }

    return html`
        <div class="notifications-container">
            ${items.map(notification => html`
                <${NotificationItem}
                    key=${notification.id}
                    notification=${notification}
                    onDismiss=${dismissNotification}
                />
            `)}
        </div>
    `;
}
