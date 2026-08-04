# Nodemetry Frontend

SvelteKit dashboard for the Nodemetry MQTT telemetry platform. It bootstraps
node state and recent history over REST, streams live readings over STOMP, and
renders physical-node telemetry, node health, run history, and ingestion metrics.

## Requirements

- Node 20, pinned in `.nvmrc`
- npm
- Running backend API for live data

## Install, Run, and Build

```bash
npm install
npm run dev
```

The dev server runs at `http://localhost:5173`.

Production build:

```bash
npm run build
npm run preview
```

## Environment Variables

Browser-facing values go in `frontend/.env`; use `.env.example` as the
template:

```text
PUBLIC_API_BASE=http://localhost:8080
PUBLIC_WS_URL=ws://localhost:8080/ws
PUBLIC_INCLUDE_VNODES=true
PUBLIC_USE_MOCK=false
```

`PUBLIC_USE_MOCK=false` connects the dashboard to the live backend; when it is
omitted or set to another value, the dashboard uses its local mock feed.
`PUBLIC_INCLUDE_VNODES` is optional and mainly useful in development. Do not put
secrets in `PUBLIC_` variables because they are bundled into browser code.

## Main Routes

```text
/              Physical-node dashboard
/load-tester   Load-test topology and run results
/api/simulator Dev-only simulator control endpoint
```

`/api/simulator` returns 404 in production builds.

## Physical-Node Dashboard Behavior

The dashboard filters `vnode-*` load-test nodes out of the main live view by
default. It shows:

- node fleet status and latest readings
- selected-node details
- temperature and humidity raw-vs-filtered charts
- light chart
- per-node run selector
- persisted ingestion metrics from the backend

When a run is selected for a node, charts use the run-scoped history endpoint.

## Load-Test Results Page Behavior

`/load-tester` is available in production as a read-only view. It shows virtual
node topology and run history.

In development, the page also shows simulator controls. Those controls call
`/api/simulator`, which spawns the Python simulator from `../simulator`, waits
for MQTT warmup, registers a backend run, then releases telemetry. This keeps
connection warmup out of measured run duration.

## WebSocket/STOMP Integration

The live integration lives in `src/lib/live.svelte.js`.

Startup flow:

1. Fetch `GET /api/v1/nodes`.
2. Fetch recent readings for the selected node.
3. Connect to STOMP at `PUBLIC_WS_URL`.
4. Subscribe to:
   - `/topic/readings`
   - `/topic/nodes/status`

Incoming messages update the reactive state in `src/lib/telemetry.svelte.js`.

## Recorded and Demo Data Behavior

The frontend contains a local mock pipeline used when the live backend is not
driving the store. In normal operation `connectLive()` disables that local
pipeline and treats the backend as the source of truth. The load-test page can
display persisted run results from the backend even when simulator controls are
not available.

## Production Deployment Notes

- Set `PUBLIC_API_BASE` to the deployed backend HTTP base URL.
- Set `PUBLIC_WS_URL` to the deployed backend WebSocket URL.
- Choose and configure an explicit SvelteKit adapter if `adapter-auto` cannot
  detect the deployment platform.
- Keep `/api/simulator` production-disabled; it is intentionally dev-only.
- The backend production HTTP API is read-only by default, so deployed frontend
  code should not rely on simulator start/stop writes.

## Project Layout

```text
src/
├── app.html
├── app.css
├── lib/
│   ├── telemetry.svelte.js
│   ├── live.svelte.js
│   ├── runs.svelte.js
│   ├── format.js
│   └── components/
└── routes/
    ├── +layout.svelte
    ├── +page.svelte
    ├── load-tester/+page.svelte
    └── api/simulator/+server.js
```
