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
 * Export all PWA data as a JSON file download.
 */
export async function exportAllData() {
    try {
        const [
            trackerConfig, dailyLogs, journalMeta, journalClientId, expandedCategories,
            workoutPlans, workoutLogs, coachMeta, coachClientId
        ] = await Promise.all([
            journalStore.getItem('tracker_config'),
            journalStore.getItem('daily_logs'),
            journalStore.getItem('app_metadata'),
            journalStore.getItem('client_id'),
            journalStore.getItem('expanded_categories'),
            coachStore.getItem('workout_plans'),
            coachStore.getItem('workout_logs'),
            coachStore.getItem('coach_metadata'),
            coachStore.getItem('coach_client_id'),
        ]);

        const data = {
            _export: {
                version: 1,
                exportedAt: new Date().toISOString(),
                userAgent: navigator.userAgent
            },
            journal: {
                clientId: journalClientId,
                trackerConfig: trackerConfig,
                dailyLogs: dailyLogs,
                syncMetadata: journalMeta,
                expandedCategories: expandedCategories
            },
            coach: {
                clientId: coachClientId,
                workoutPlans: workoutPlans,
                workoutLogs: workoutLogs,
                syncMetadata: coachMeta
            },
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
