/**
 * Trends Store — signals + offline cache for the read-only aggregates module.
 *
 * No sync, no dirty tracking: trends only ever GETs server aggregates. The
 * cache is network-first write-through (the Analysis report-cache pattern)
 * upgraded with a `fetchedAt` stamp so the UI can badge stale offline data:
 * on success the payload is cached and staleness cleared; on a network error
 * the cached payload is served and its age surfaces via the `staleness`
 * signal (rendered by components/StaleBadge.js).
 */
import { signal } from '@preact/signals';
import localforage from 'localforage';
import { isNetworkError } from '../shared/utils.js';

const API_BASE = '/wellness/api/trends';

const cache = localforage.createInstance({
    name: 'TrendsApp',
    storeName: 'trends_cache',
});

// ==================== Signals ====================

// Shared time-range selector: '4w' | '12w' | '6m' | 'all' (persisted).
export const range = signal(localStorage.getItem('trends_range') || '12w');

// Active sub-screen: 'overview' | 'strength' | 'cardio' | 'journal'.
export const activeScreen = signal(localStorage.getItem('trends_screen') || 'overview');

// cacheKey -> fetchedAt (ms). Set ONLY when a cached payload is served
// because the network failed; cleared on any fresh fetch of that key.
export const staleness = signal({});

export const isLoading = signal(false);

export function setRange(value) {
    range.value = value;
    localStorage.setItem('trends_range', value);
}

export function setActiveScreen(value) {
    activeScreen.value = value;
    localStorage.setItem('trends_screen', value);
}

// ==================== Fetch-through cache ====================

/**
 * Network-first fetch with offline fallback. `path` is relative to the trends
 * API base (e.g. `/strength/exercises?start=...`). Returns the payload;
 * throws the network error only when there is no cached copy either.
 */
export async function fetchCached(cacheKey, path) {
    try {
        const res = await fetch(`${API_BASE}${path}`, { cache: 'no-store' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const payload = await res.json();
        await cache.setItem(cacheKey, { payload, fetchedAt: Date.now() });
        if (staleness.value[cacheKey]) {
            const next = { ...staleness.value };
            delete next[cacheKey];
            staleness.value = next;
        }
        return payload;
    } catch (error) {
        if (isNetworkError(error) || error.message?.startsWith('HTTP 5')) {
            const cached = await cache.getItem(cacheKey);
            if (cached) {
                staleness.value = { ...staleness.value, [cacheKey]: cached.fetchedAt };
                return cached.payload;
            }
        }
        throw error;
    }
}

// ==================== Initialization ====================

let _initPromise = null;

/** Idempotent per-session init (the shared `_initPromise` pattern). Trends
 *  has nothing to warm eagerly — screens fetch on view — but the hook exists
 *  so the shell's conventions hold and future prefetching has a home. */
export function initializeStore() {
    if (!_initPromise) _initPromise = Promise.resolve();
    return _initPromise;
}
