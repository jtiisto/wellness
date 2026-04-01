// Wellness PWA - Unified Service Worker
// Caching strategy:
//   /api/*      -> network-only (LocalForage handles offline data)
//   esm.sh CDN  -> cache-first
//   app shell   -> network-first with cache fallback
//   navigation  -> offline fallback to cached /

const CACHE_VERSION = '$SERVER_VERSION$';
const CDN_CACHE = 'wellness-cdn-v1';
const B = '$BASE_PATH$';

// App shell assets to precache on install
const APP_SHELL_URLS = [
  B + '/',
  B + '/styles.css',
  B + '/fonts/Inter-Regular.woff2',
  B + '/fonts/Inter-Medium.woff2',
  B + '/fonts/Inter-SemiBold.woff2',
  B + '/manifest.json',
  B + '/version.json',
  // Shared
  B + '/js/app.js',
  B + '/js/shared/header.js',
  B + '/js/shared/notifications.js',
  B + '/js/shared/utils.js',
  B + '/js/shared/tools-menu.js',
  B + '/js/shared/sync-scheduler.js',
  B + '/js/shared/debug-log.js',
  B + '/js/shared/force-sync.js',
  B + '/js/shared/data-export.js',
  // Journal module
  B + '/js/journal/JournalView.js',
  B + '/js/journal/store.js',
  B + '/js/journal/utils.js',
  B + '/js/journal/components/ConfigScreen.js',
  B + '/js/journal/components/ConflictResolver.js',
  B + '/js/journal/components/Header.js',
  B + '/js/journal/components/TrackerItem.js',
  B + '/js/journal/components/TrackerList.js',
  // Coach module
  B + '/js/coach/CoachView.js',
  B + '/js/coach/store.js',
  B + '/js/coach/utils.js',
  B + '/js/coach/components/BlockView.js',
  B + '/js/coach/components/CalendarPicker.js',
  B + '/js/coach/components/CardioEntry.js',
  B + '/js/coach/components/ChecklistEntry.js',
  B + '/js/coach/components/DateSelector.js',
  B + '/js/coach/components/ExerciseItem.js',
  B + '/js/coach/components/SessionFeedback.js',
  B + '/js/coach/components/SetEntry.js',
  B + '/js/coach/components/WorkoutView.js',
  // Analysis module
  B + '/js/analysis/AnalysisView.js',
  B + '/js/analysis/store.js',
  B + '/js/analysis/utils.js',
  B + '/js/analysis/components/HistoryView.js',
  B + '/js/analysis/components/ProgressView.js',
  B + '/js/analysis/components/QueryList.js',
  B + '/js/analysis/components/ReportView.js',
];

// Third-party CDN dependencies to precache
const CDN_URLS = [
  'https://esm.sh/preact@10.19.3',
  'https://esm.sh/preact@10.19.3/hooks',
  'https://esm.sh/@preact/signals@1.2.1?deps=preact@10.19.3',
  'https://esm.sh/htm@3.1.1',
  'https://esm.sh/localforage@1.10.0',
  'https://esm.sh/marked@15.0.7',
];

// ---------------------------------------------------------------------------
// Install: precache app shell and CDN assets
// ---------------------------------------------------------------------------
self.addEventListener('install', (event) => {
  event.waitUntil(
    Promise.all([
      caches.open(CACHE_VERSION).then((cache) => cache.addAll(APP_SHELL_URLS)),
      caches.open(CDN_CACHE).then((cache) => cache.addAll(CDN_URLS)),
    ]).then(() => self.skipWaiting())
  );
});

// ---------------------------------------------------------------------------
// Activate: clean up old caches that no longer match current versions
// ---------------------------------------------------------------------------
self.addEventListener('activate', (event) => {
  const keepCaches = new Set([CACHE_VERSION, CDN_CACHE]);

  event.waitUntil(
    caches.keys().then((cacheNames) =>
      Promise.all(
        cacheNames
          .filter((name) => !keepCaches.has(name))
          .map((name) => caches.delete(name))
      )
    ).then(() => self.clients.claim())
  );
});

// ---------------------------------------------------------------------------
// Fetch: route requests to the appropriate caching strategy
// ---------------------------------------------------------------------------
self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // API requests: network-only (LocalForage handles offline data)
  if (url.pathname.startsWith(B + '/api/') || url.pathname.startsWith('/api/')) {
    return; // Let the browser handle it normally (network-only)
  }

  // CDN requests (esm.sh): cache-first
  if (url.hostname === 'esm.sh') {
    event.respondWith(cacheFirstCDN(request));
    return;
  }

  // App shell / same-origin assets: network-first with cache fallback
  if (url.origin === self.location.origin) {
    event.respondWith(networkFirstAppShell(request));
  }
});

// ---------------------------------------------------------------------------
// Strategy: cache-first for CDN assets
// ---------------------------------------------------------------------------
async function cacheFirstCDN(request) {
  const cache = await caches.open(CDN_CACHE);
  const cached = await cache.match(request);

  if (cached) {
    return cached;
  }

  // Cache miss: fetch from network, cache the response, and return it
  const response = await fetch(request);
  if (response.ok) {
    cache.put(request, response.clone());
  }
  return response;
}

// ---------------------------------------------------------------------------
// Strategy: network-first for app shell assets
// ---------------------------------------------------------------------------
async function networkFirstAppShell(request) {
  const cache = await caches.open(CACHE_VERSION);

  try {
    const response = await fetch(request);

    // Cache successful responses for offline use
    if (response.ok) {
      cache.put(request, response.clone());
    }

    return response;
  } catch (_err) {
    // Network failed: try the cache.
    // Strip query params for the cache lookup because the server may append
    // ?v=xxx for cache busting, but the SW should match by base path.
    const cacheUrl = new URL(request.url);
    cacheUrl.search = '';
    const cached = await cache.match(cacheUrl.href);

    if (cached) {
      return cached;
    }

    // Navigation requests that miss both network and cache: serve the cached
    // index page so the client-side router can handle the route offline.
    if (request.mode === 'navigate') {
      const fallback = await cache.match(B + '/');
      if (fallback) {
        return fallback;
      }

      // Last resort: a minimal offline page
      return new Response(
        '<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">' +
        '<meta name="viewport" content="width=device-width,initial-scale=1">' +
        '<title>Offline - Wellness</title>' +
        '<style>body{font-family:system-ui,sans-serif;display:flex;' +
        'align-items:center;justify-content:center;min-height:100vh;' +
        'margin:0;background:#1a1a2e;color:#e0e0e0;text-align:center}' +
        'h1{font-size:1.5rem;margin-bottom:.5rem}' +
        'p{color:#999;max-width:28ch}</style></head>' +
        '<body><div><h1>You are offline</h1>' +
        '<p>Check your connection and try again.</p></div></body></html>',
        {
          status: 503,
          headers: { 'Content-Type': 'text/html; charset=utf-8' },
        }
      );
    }

    // Non-navigation requests with no cache match: return a network error
    return new Response('Network error', { status: 503 });
  }
}
