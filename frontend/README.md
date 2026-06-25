# Nodemetry — Live Signal Monitor

A SvelteKit dashboard for the **Nodemetry** low-power MQTT environmental
telemetry platform. It presents the fleet as an instrument console: each
sensor metric is its own oscilloscope channel, with live readouts, a node
status table, time-series charts, a raw-vs-filtered (signal/noise) trace,
threshold alerts, and backend ingestion metrics.

It ships with a **client-side mock MQTT feed** so it runs and animates
immediately — no broker or backend required to see it working.

## Run it

```bash
npm install
npm run dev      # http://localhost:5173
```

```bash
npm run build    # production build
npm run preview  # preview the build
```

Requires Node 18+.

## What's on screen

- **Top bar** — broker subscription, live/paused state (the LIVE button
  pauses the feed), node uptime, and clock.
- **Overview strip** — active nodes versus total nodes.
- **Charts** — temperature, humidity, and CO₂ for the selected node,
  plus the signature **raw vs filtered** temperature trace (faint jittery raw
  overlaid with the clean filtered signal).
- **Alerts** — threshold and offline alerts (CO₂ > 1000 ppm, temp > 28 °C,
  humidity < 35 %, battery < 20 %, RSSI < −75 dBm, node offline) with an
  acknowledge action.
- **Ingestion metrics** — messages received/saved, duplicates skipped,
  throughput, active/offline counts, alerts created, avg processing time.
- **Node fleet table** — status, last seen, RSSI signal bars, battery cell,
  latest readings, firmware. **Click a row to drive the charts.**

## Connecting your real backend

Everything that produces data lives in one file:

```
src/lib/telemetry.svelte.js
```

The reactive `store` and the ingestion helpers (`applyReading`,
`applyStatus`, `acknowledgeAlert`) already match the project plan's
contract. To go real, replace `startMockFeed()` with code that:

1. Loads the fleet and recent history over REST, e.g.

   ```js
   const nodes = await fetch('/api/v1/nodes').then((r) => r.json());
   // map each into the node shape, then: store.nodes = mapped;
   ```

2. Opens a WebSocket and pipes pushes into the same helpers the mock uses:

   ```js
   const ws = new WebSocket('wss://your-host/ws/telemetry');
   ws.onopen = () => (store.connected = true);
   ws.onmessage = (e) => {
     const msg = JSON.parse(e.data);
     if (msg.type === 'reading') applyReading(msg);        // dedup + store + alerts
     else if (msg.type === 'status') applyStatus(msg.nodeId, msg.status, msg.reason);
   };
   ```

`applyReading()` already does messageId de-duplication, history windowing,
and threshold alert evaluation — the same logic your Spring Boot subscriber
performs — so the UI behaves identically whether driven by the mock or a
live broker.

Reading payload fields consumed by the UI (all optional except `nodeId`):

```
messageId, nodeId, measuredAt,
temperatureRaw, temperatureFiltered, humidityRaw, humidityFiltered,
co2, battery, rssi, firmwareVersion
```

Acknowledging an alert should also call your backend:

```
PATCH /api/v1/alerts/{alertId}/acknowledge
```

## Project layout

```
src/
├── app.html                 fonts + shell
├── app.css                  theme tokens, scope-grid atmosphere
├── lib/
│   ├── telemetry.svelte.js   ← state + mock feed (the integration seam)
│   ├── format.js             time / number helpers
│   └── components/
│       ├── TopBar.svelte
│       ├── OverviewStrip.svelte
│       ├── SignalChart.svelte    reusable scope chart
│       ├── AlertsPanel.svelte
│       ├── IngestionMetrics.svelte
│       └── NodeTable.svelte
└── routes/
    ├── +layout.svelte
    └── +page.svelte          dashboard composition
```

## Notes

- Built with Svelte 5 (runes) + SvelteKit 2. No charting dependency — the
  charts are hand-rolled SVG so they stay portable and on-theme.
- Respects `prefers-reduced-motion`; tables and controls are keyboard
  accessible.
- Fonts: IBM Plex Mono (readouts/labels) + IBM Plex Sans (body), loaded from
  Google Fonts.
