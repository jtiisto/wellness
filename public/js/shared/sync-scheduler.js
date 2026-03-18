/**
 * SyncScheduler - Shared sync triggering logic for auto-sync
 * Each module (coach, journal) creates its own instance with its own syncFn.
 * Handles debounced uploads, periodic polling, retry with backoff,
 * and online/visibility event listeners.
 */
import { log as debugLog } from './debug-log.js';
import { showNotification } from './notifications.js';

export class SyncScheduler {
    /**
     * @param {Object} options
     * @param {string} options.name - Module name for logging ('coach', 'journal')
     * @param {Function} options.syncFn - The sync function to call. Returns { success, reason?, error? }
     * @param {Function} options.getIsSyncing - Returns current syncing state
     * @param {Function} options.getHasDirtyData - Returns whether there's dirty data to upload
     * @param {Function} [options.pollCheckFn] - Optional pre-poll check. Return true to proceed with sync.
     * @param {number} [options.uploadDebounceMs=2500] - Debounce delay for upload triggers
     * @param {number} [options.pollIntervalMs=30000] - Polling interval
     * @param {number} [options.baseRetryMs=5000] - Base retry delay
     * @param {number} [options.maxRetryMs=120000] - Max retry delay
     */
    constructor({ name, syncFn, getIsSyncing, getHasDirtyData, pollCheckFn = null,
                  uploadDebounceMs = 2500, pollIntervalMs = 30000,
                  baseRetryMs = 5000, maxRetryMs = 120000 }) {
        this._name = name;
        this._syncFn = syncFn;
        this._getIsSyncing = getIsSyncing;
        this._getHasDirtyData = getHasDirtyData;
        this._pollCheckFn = pollCheckFn;
        this._uploadDebounceMs = uploadDebounceMs;
        this._pollIntervalMs = pollIntervalMs;
        this._baseRetryMs = baseRetryMs;
        this._maxRetryMs = maxRetryMs;

        this._debounceTimer = null;
        this._pollTimer = null;
        this._retryTimer = null;
        this._retryAttempt = 0;
        this._pendingSync = false;
        this._started = false;

        this._onOnline = this._onOnline.bind(this);
        this._onOffline = this._onOffline.bind(this);
        this._onVisibilityChange = this._onVisibilityChange.bind(this);
    }

    /** Set up event listeners and start polling. Call after store init. */
    start() {
        if (this._started) return;
        this._started = true;
        window.addEventListener('online', this._onOnline);
        window.addEventListener('offline', this._onOffline);
        document.addEventListener('visibilitychange', this._onVisibilityChange);
        if (navigator.onLine) {
            this._startPolling();
        }
    }

    /** Teardown: clear all timers and remove listeners. */
    stop() {
        this._started = false;
        this._stopPolling();
        this._clearDebounce();
        this._clearRetry();
        window.removeEventListener('online', this._onOnline);
        window.removeEventListener('offline', this._onOffline);
        document.removeEventListener('visibilitychange', this._onVisibilityChange);
    }

    /** Called by mutation functions. Starts/resets the debounce timer. */
    scheduleUpload() {
        this._clearDebounce();
        this._debounceTimer = setTimeout(() => {
            this._debounceTimer = null;
            this._executeSync('debounce');
        }, this._uploadDebounceMs);
    }

    /** Immediate sync request. Clears pending debounce (full sync uploads dirty data). */
    requestSync() {
        this._clearDebounce();
        this._clearRetry();
        this._executeSync('request');
    }

    /** Reset retry state. Called after successful force sync. */
    resetRetry() {
        this._retryAttempt = 0;
        this._clearRetry();
    }

    // --- Internal ---

    async _executeSync(trigger) {
        if (!navigator.onLine) return;

        // If already syncing, remember we were asked so we follow up after
        if (this._getIsSyncing()) {
            this._pendingSync = true;
            return;
        }

        debugLog(`${this._name}-scheduler`, 'sync triggered', { trigger, retryAttempt: this._retryAttempt });

        try {
            const result = await this._syncFn();

            if (result.success || result.reason === 'conflicts') {
                // Success or conflicts (handled by journal UI) — reset retry
                this._retryAttempt = 0;
                this._clearRetry();
            } else if (result.reason === 'offline' || result.reason === 'already syncing') {
                // Not an error, just skip
                return;
            } else if (result.error) {
                // syncFn returned a failure with an error object
                this._handleError(result.error);
            } else {
                // Generic failure — schedule retry without toast
                this._scheduleRetry();
            }
        } catch (error) {
            this._handleError(error);
        } finally {
            // After sync completes, check if new dirty data accumulated during sync
            if (this._pendingSync || this._getHasDirtyData()) {
                this._pendingSync = false;
                this.scheduleUpload();
            }
        }
    }

    _handleError(error) {
        const errorType = this._classifyError(error);
        debugLog(`${this._name}-scheduler`, 'sync error', { errorType, message: error.message });

        if (errorType === 'server') {
            showNotification({
                type: 'error',
                title: 'Sync Failed',
                message: error.message,
                duration: 5000
            });
        }
        // Network errors: silent — indicator already pulsed during attempt
        this._scheduleRetry();
    }

    _classifyError(error) {
        // TypeError = network-level failure (DNS, offline, CORS)
        if (error instanceof TypeError) return 'network';
        if (error.name === 'AbortError') return 'network';
        if (!navigator.onLine) return 'network';
        return 'server';
    }

    _scheduleRetry() {
        this._clearRetry();
        const delay = Math.min(
            this._baseRetryMs * Math.pow(2, this._retryAttempt),
            this._maxRetryMs
        );
        this._retryAttempt++;
        debugLog(`${this._name}-scheduler`, 'retry scheduled', { delay, attempt: this._retryAttempt });
        this._retryTimer = setTimeout(() => {
            this._retryTimer = null;
            this._executeSync('retry');
        }, delay);
    }

    _startPolling() {
        if (this._pollTimer) return;
        this._pollTimer = setInterval(() => this._poll(), this._pollIntervalMs);
    }

    _stopPolling() {
        if (this._pollTimer) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
    }

    async _poll() {
        if (!navigator.onLine || this._getIsSyncing()) return;

        if (this._pollCheckFn) {
            try {
                const shouldSync = await this._pollCheckFn();
                if (!shouldSync) return;
            } catch {
                // Poll check failed — skip this cycle
                return;
            }
        }

        this._executeSync('poll');
    }

    _onOnline() {
        this.requestSync();
        this._startPolling();
    }

    _onOffline() {
        this._stopPolling();
        this._clearDebounce();
        this._clearRetry();
    }

    _onVisibilityChange() {
        if (document.visibilityState === 'visible' && navigator.onLine) {
            this.requestSync();
            this._startPolling();
        } else {
            this._stopPolling();
        }
    }

    _clearDebounce() {
        if (this._debounceTimer) {
            clearTimeout(this._debounceTimer);
            this._debounceTimer = null;
        }
    }

    _clearRetry() {
        if (this._retryTimer) {
            clearTimeout(this._retryTimer);
            this._retryTimer = null;
        }
    }
}
