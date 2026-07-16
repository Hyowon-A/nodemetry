# Nodemetry — Live Signal Monitor (dashboard)

The SvelteKit dashboard for the **Nodemetry** MQTT telemetry platform. It
connects to the Spring Boot backend, bootstraps the fleet + recent history over
REST, and streams live readings over STOMP. It presents the fleet as an
instrument console: each sensor metric is its own oscilloscope channel, with
live readouts, a node status table, time-series charts, raw-vs-filtered
temperature/humidity traces, and ingestion metrics.

## Run it

```bash
npm install
npm run dev      # http://localhost:5173
```

```bash
npm run build    # production build
npm run preview  # preview the production build
```

Requires Node 20 (pinned in `.nvmrc`).

## Live backend

The dashboard expects the backend to be reachable. Configure the browser-facing
REST and WebSocket endpoints in `.env`:

```
PUBLIC_API_BASE=http://localhost:8080
PUBLIC_WS_URL=ws://localhost:8080/ws
# Optional dev-only load-test node visibility:
# PUBLIC_INCLUDE_VNODES=true
```

> `PUBLIC_`-prefixed variables are bundled into the browser build — keep secrets
> out of them.

The live integration lives in **`src/lib/live.svelte.js`**:

1. **REST bootstrap** — `GET /api/v1/nodes`, filtering out `vnode-*` records by
   default, then `GET /api/v1/nodes/{id}/readings` for the selected dashboard
   node. Set `PUBLIC_INCLUDE_VNODES=true` in development to include load-test
   virtual nodes in the live store.
2. **Live stream** — STOMP over a raw WebSocket via `@stomp/stompjs` (the backend
   speaks STOMP without SockJS), subscribing to `/topic/readings` and
   `/topic/nodes/status`.

Incoming live readings and node-status events flow through the
`applyReading()` / `applyStatus()` helpers in `src/lib/telemetry.svelte.js`.
`applyReading()` handles `messageId` de-duplication, history windowing, and
live ingestion metric updates.

Reading fields consumed by the UI (all optional except `nodeId`):

```
messageId, nodeId, runId, measuredAt / receivedAt,
temperature, humidity, light, battery, rssi, firmwareVersion
```

## What's on screen

- **Top bar** — broker subscription, backend connection state, node uptime,
  clock, and context navigation between the dashboard and load tester.
- **Overview strip** — active nodes versus total nodes.
- **Charts** — temperature, humidity, and raw light for the selected node,
  with raw-vs-filtered traces for temperature and humidity.
- **Ingestion metrics** — messages received/saved, duplicates skipped, throughput,
  node status, history size, and avg processing time.
- **Node fleet table** — status, last seen, RSSI signal bars, battery cell, latest
  readings, firmware. **Click a row to drive the charts.**
- **Node details / run selector** — per-node history, with a selector to scope the
  charts to a specific load-test run.

## Load tester

`/load-tester` shows the virtual-node topology and run history. The page is
available in production as a read-only view.

In development, the page also shows experiment controls and a snapshot panel.
The controls drive the Python simulator in `../simulator` through
`src/routes/api/simulator/+server.js` to generate load against the backend and
record test runs (start/stop, node count, interval, QoS, duplicate rate). The
control and snapshot panels are hidden in production, and `/api/simulator` still
returns **404** in any production build so nothing shippable can spawn simulator
processes.

Run-history metrics use a warmup window: the simulator connects all configured
MQTT clients first, then the frontend registers the backend run and releases
telemetry. That keeps connection ramp time out of `duration`, expected message
count, delivery %, and throughput, so those metrics describe the measured steady
load rather than startup connection delay.

## Project layout

```
src/
├── app.html                    fonts + shell
├── app.css                     theme tokens, scope-grid atmosphere
├── lib/
│   ├── telemetry.svelte.js     ← reactive dashboard state + apply* helpers
│   ├── live.svelte.js          ← backend REST bootstrap + STOMP stream
│   ├── runs.svelte.js          load-test run state
│   ├── format.js               time / number helpers
│   └── components/
│       ├── TopBar.svelte
│       ├── OverviewStrip.svelte
│       ├── SignalChart.svelte      reusable scope chart
│       ├── IngestionMetrics.svelte
│       ├── NodeTable.svelte
│       ├── NodeDetails.svelte
│       ├── NodeRunSelector.svelte
│       ├── RunsPanel.svelte
│       └── VNodeTopology.svelte
└── routes/
    ├── +layout.svelte          starts the backend connection
    ├── +page.svelte            dashboard composition
    ├── load-tester/
    │   └── +page.svelte        read-only prod view + dev simulator controls
    └── api/
        └── simulator/
            └── +server.js      spawns/stops the simulator (dev only)
```

## Notes

- Built with Svelte 5 (runes) + SvelteKit 2. No charting dependency — the charts
  are hand-rolled SVG so they stay portable and on-theme.
- Respects `prefers-reduced-motion`; tables and controls are keyboard accessible.
- Fonts: IBM Plex Mono (readouts/labels) + IBM Plex Sans (body), from Google Fonts.
