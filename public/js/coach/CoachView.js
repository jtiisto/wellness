/**
 * CoachView - Module entry component for the Coach module
 * Lazy-loaded by the shell. Wraps the coach component tree and
 * initializes the store on mount.
 */
import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';

import {
    isLoading,
    selectedDate,
    workoutPlans,
    workoutLogs,
    syncStatus,
    isSyncing,
    initializeStore
} from './store.js';
import { getToday } from '../shared/utils.js';
import { Header, SyncIndicator } from '../shared/header.js';
import { CalendarPicker } from './components/CalendarPicker.js';
import { WorkoutView } from './components/WorkoutView.js';

const html = htm.bind(h);

export default function CoachView() {
    const [loading, setLoading] = useState(true);
    const [date, setDate] = useState(selectedDate.value);
    const [plans, setPlans] = useState({});
    const [logs, setLogs] = useState({});
    const [status, setStatus] = useState('gray');
    const [syncing, setSyncing] = useState(false);

    // Subscribe to signals
    useEffect(() => {
        const dispose = effect(() => {
            setLoading(isLoading.value);
            setDate(selectedDate.value);
            setPlans({ ...workoutPlans.value });
            setLogs({ ...workoutLogs.value });
            setStatus(syncStatus.value);
            setSyncing(isSyncing.value);
        });
        return dispose;
    }, []);

    // Initialize store on mount (initializeStore already triggers sync)
    useEffect(() => {
        initializeStore().catch(err => {
            console.error('Coach init error:', err);
        });
    }, []);

    const currentPlan = plans[date] || null;
    const currentLog = logs[date] || null;
    const isEditable = date === getToday();

    return html`
        <div class="coach">
            <${Header} title="Coach">
                <${SyncIndicator}
                    status=${status}
                    syncing=${syncing}
                />
            <//>
            <${CalendarPicker} plans=${plans} logs=${logs} />
            <main class="main-content">
                ${loading ? html`
                    <div class="loading">
                        <div class="loading-spinner"></div>
                        <span>Loading...</span>
                    </div>
                ` : html`
                    <${WorkoutView}
                        date=${date}
                        plan=${currentPlan}
                        log=${currentLog}
                        isEditable=${isEditable}
                    />
                `}
            </main>
        </div>
    `;
}
