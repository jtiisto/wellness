/**
 * Force Sync - Full bidirectional reconciliation orchestrator
 * Calls forceSync() in both coach and journal stores via dynamic imports
 */
export async function forceSync() {
    const results = { coach: null, journal: null };

    try {
        const { forceSync } = await import('../coach/store.js');
        results.coach = await forceSync();
    } catch (e) {
        results.coach = { success: false, error: e.message };
    }

    try {
        const { forceSync } = await import('../journal/store.js');
        results.journal = await forceSync();
    } catch (e) {
        results.journal = { success: false, error: e.message };
    }

    return results;
}
