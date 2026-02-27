/**
 * Shared utility functions used across all modules
 */

/**
 * Generate a UUID v4
 * Falls back to manual generation for insecure contexts (HTTP on mobile)
 */
export function generateId() {
    if (crypto.randomUUID) {
        try {
            return crypto.randomUUID();
        } catch (e) {
            // Falls through to manual generation
        }
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = Math.random() * 16 | 0;
        const v = c === 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

/**
 * Get today's date as YYYY-MM-DD string in local timezone
 */
export function getToday() {
    return formatDateLocal(new Date());
}

/**
 * Format a Date object as YYYY-MM-DD string in local timezone
 */
export function formatDateLocal(date) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
}

/**
 * Parse a YYYY-MM-DD string as a local date (midnight in local timezone)
 */
export function parseLocalDate(dateStr) {
    const [year, month, day] = dateStr.split('-').map(Number);
    return new Date(year, month - 1, day);
}

/**
 * Get current UTC timestamp as ISO-8601 string
 */
export function getUtcNow() {
    return new Date().toISOString();
}

/**
 * Deep clone an object via JSON roundtrip
 */
export function deepClone(obj) {
    return JSON.parse(JSON.stringify(obj));
}
