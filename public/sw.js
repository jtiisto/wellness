// Wellness PWA - Unified Service Worker
// Caching strategy:
//   /api/*      -> network-only (LocalForage handles offline data)
//   app shell   -> network-first with cache fallback (runtime libs are vendored
//                  same-origin under js/vendor/, so there is no CDN to cache)
//   navigation  -> offline fallback to cached /

const CACHE_VERSION = '$SERVER_VERSION$';
const B = '$BASE_PATH$';

// App shell assets to precache on install. The server injects this list by
// walking public/ at serve time (see _app_shell_urls in src/server.py), so every
// JS module — newly added components and the vendored libs under js/vendor/
// included — is precached automatically. No hand-maintained list to drift out of
// sync (which previously dropped coach/last-performance.js, breaking Coach
// offline after every deploy).
const APP_SHELL_URLS = $APP_SHELL_URLS$;

// ---------------------------------------------------------------------------
// Install: precache app shell and CDN assets
// ---------------------------------------------------------------------------
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_VERSION)
      .then((cache) => cache.addAll(APP_SHELL_URLS))
      .then(() => self.skipWaiting())
  );
});

// ---------------------------------------------------------------------------
// Activate: clean up old caches that no longer match current versions
// ---------------------------------------------------------------------------
self.addEventListener('activate', (event) => {
  const keepCaches = new Set([CACHE_VERSION]);

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

  // App shell / same-origin assets: network-first with cache fallback
  if (url.origin === self.location.origin) {
    event.respondWith(networkFirstAppShell(request));
  }
});

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
