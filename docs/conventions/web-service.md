# Python web services with a PWA

A FastAPI server with an offline-capable PWA, self-hosted on the tailnet. Imported by
every project that serves one.

## Stack
- **Server:** FastAPI on Uvicorn, Python 3.11+, SQLite. Databases and other runtime
  data live under a gitignored `data/`; so do `venv/`, the pid file and logs.
- **PWA:** served from `public/` with **no build step**: Preact, Preact Signals and
  HTM as ESM, LocalForage (IndexedDB) for offline storage, a service worker and a
  manifest. Mobile-first dark theme; new apps match the existing apps' visual design.
- **Tests:** pytest (unit, API, integration), Playwright for E2E in the browser, httpx
  for the async test client. The pre-push hook runs the full suite with
  `--cov-fail-under=<N>`; N is a project fact.

## Serving
- `bin/server.sh start|stop|restart|status|logs` controls the server. Each service
  has its own ports; a new service takes the next free block and is added here:

  | Service | Production | Test (`server.sh --test`) | Path |
  |---|---|---|---|
  | wellness | 9000 | 9001 | `/wellness` |
  | share | 9100 | 9101 | `/share` |
- Each app lives under its own path prefix (`/<app>`). Tailscale serves it:
  `tailscale serve --https 9443 --set-path /<app> --bg http://localhost:<port>`.
  Frontend paths carry the prefix; backend routes stay at the root (`/api/…`,
  `/ws`). A `StripPrefixMiddleware` strips the prefix, so the same server works
  behind Tailscale (which strips it already) and when reached directly.
- Tailscale is the only auth layer. The server binds `0.0.0.0` and refuses clients
  outside loopback and the tailnet ranges (403 for HTTP, close before accept for
  WebSocket), with an env var of CIDRs to replace the defaults. CORS is off by
  default, with an env var allowlisting origins.

## Configuration
Claude Code's Bash tool is non-interactive and never sees `.bashrc` exports. A
setting a tool or skill needs at run time goes in a config file
(`~/.config/<app>/…`, honouring `$XDG_CONFIG_HOME`), not only in an env var.
