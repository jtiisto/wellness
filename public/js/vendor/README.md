# Vendored runtime libraries

These are the app's six runtime dependencies, vendored from [esm.sh](https://esm.sh)
so the offline-first PWA has **no hard runtime dependency on a third-party CDN**.
The import map in `public/index.html` points the
bare specifiers at these same-origin files; the service worker precaches them with
the rest of the app shell.

All six are MIT-licensed.

## Files and their source URLs

| File | Specifier | esm.sh source |
|------|-----------|---------------|
| `preact.js` | `preact` | `https://esm.sh/preact@10.19.3?bundle` |
| `preact-hooks.js` | `preact/hooks` | `https://esm.sh/preact@10.19.3/hooks?external=preact` |
| `preact-signals.js` | `@preact/signals` | `https://esm.sh/@preact/signals@1.2.1?external=preact&bundle` |
| `htm.js` | `htm` | `https://esm.sh/htm@3.1.1?bundle` |
| `localforage.js` | `localforage` | `https://esm.sh/localforage@1.10.0?bundle` |
| `marked.js` | `marked` | `https://esm.sh/marked@15.0.7?bundle` |

`?external=preact` keeps `preact`/`preact/hooks` as **bare** imports inside
`preact-hooks.js` and `preact-signals.js`, so the import map resolves them back to the
single vendored `preact.js` — preserving the one-preact-instance invariant that hooks
and signals require. The leaf libraries (`htm`, `localforage`, `marked`) are fully
self-contained `?bundle` builds with no imports.

## Regenerating / upgrading

For each row, fetch the esm.sh URL, follow the one-line `export * from "/<path>"`
facade it returns, download that real build file, and strip the trailing
`//# sourceMappingURL=` comment (the `.map` is not vendored). To bump a version,
change it in the URL above and in `public/index.html`'s import map, then re-fetch.
Keep `preact`'s version identical across the preact / hooks / signals rows.
