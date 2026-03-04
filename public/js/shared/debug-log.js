/**
 * Debug Logger - Ring buffer in LocalForage for diagnosing sync issues
 * Entries auto-prune after 1 hour, capped at 500 entries
 */
import localforage from 'localforage';

const store = localforage.createInstance({
    name: 'DebugLog',
    storeName: 'debug_log'
});

const MAX_ENTRIES = 500;
const TTL_MS = 60 * 60 * 1000; // 1 hour

/**
 * Log a debug entry. Fire-and-forget — callers should NOT await this.
 */
export async function log(category, message, data) {
    try {
        const now = Date.now();
        let entries = (await store.getItem('entries')) || [];

        // Prune entries older than 1 hour
        entries = entries.filter(e => (now - e.ts) < TTL_MS);

        entries.push({
            ts: now,
            cat: category,
            msg: message,
            ...(data !== undefined && { data })
        });

        // Cap at MAX_ENTRIES (keep most recent)
        if (entries.length > MAX_ENTRIES) {
            entries = entries.slice(entries.length - MAX_ENTRIES);
        }

        await store.setItem('entries', entries);
    } catch {
        // Never throw — debug logging must not break the app
    }
}

/**
 * Get all debug log entries.
 */
export async function getDebugLog() {
    try {
        return (await store.getItem('entries')) || [];
    } catch {
        return [];
    }
}

/**
 * Download debug log as a text file.
 * Format: ISO_TIMESTAMP [category] message {data}
 */
export async function downloadDebugLog() {
    const entries = await getDebugLog();

    const lines = entries.map(e => {
        const ts = new Date(e.ts).toISOString();
        const data = e.data !== undefined ? ' ' + JSON.stringify(e.data) : '';
        return `${ts} [${e.cat}] ${e.msg}${data}`;
    });

    const text = lines.join('\n');
    const now = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    const filename = `debug-log-${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}.txt`;

    const blob = new Blob([text], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
}
