/**
 * Data Export - Downloads all PWA data (journal, coach, app state) as JSON.
 * Excludes the debug log (which has its own export).
 */
import localforage from 'localforage';

const journalStore = localforage.createInstance({
    name: 'JournalApp',
    storeName: 'journal_data'
});

const coachStore = localforage.createInstance({
    name: 'CoachApp',
    storeName: 'coach_data'
});

/**
 * Dump every key/value pair in a LocalForage instance.
 *
 * Generic by construction: a key added to a store later is exported
 * automatically. The previous hand-maintained key list had already drifted —
 * it silently omitted `tracker_value_updated_times` and `app_schema_version`,
 * so a "full" export wasn't.
 */
async function dumpInstance(store) {
    const out = {};
    await store.iterate((value, key) => {
        out[key] = value;
    });
    return out;
}

/**
 * Export all PWA data as a JSON file download.
 */
export async function exportAllData() {
    try {
        const [journal, coach] = await Promise.all([
            dumpInstance(journalStore),
            dumpInstance(coachStore),
        ]);

        const data = {
            _export: {
                // version 2: modules are raw key->value dumps of their
                // LocalForage instances (keys as stored), not a curated shape.
                version: 2,
                exportedAt: new Date().toISOString(),
                userAgent: navigator.userAgent
            },
            journal,
            coach,
            app: {
                activeModule: localStorage.getItem('wellness_active_module')
            }
        };

        const json = JSON.stringify(data, null, 2);
        const now = new Date();
        const pad = (n) => String(n).padStart(2, '0');
        const filename = `wellness-export-${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}.json`;

        const blob = new Blob([json], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        a.click();
        URL.revokeObjectURL(url);

        return { success: true };
    } catch (error) {
        return { success: false, error: error.message };
    }
}
