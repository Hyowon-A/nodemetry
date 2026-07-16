/**
 * telemetry.svelte.js
 * ------------------------------------------------------------------
 * Reactive state for the Nodemetry dashboard + a mock MQTT feed that
 * makes the dashboard live out of the box.
 *
 * >>> THIS IS THE ONLY FILE YOU NEED TO TOUCH TO GO REAL <<<
 * Replace `startMockFeed()` with a connection to your Spring Boot
 * backend. The shapes below match the REST/WebSocket contract in the
 * project plan:
 *
 *   GET  /api/v1/nodes
 *   GET  /api/v1/nodes/{nodeId}/latest
 *   GET  /api/v1/nodes/{nodeId}/readings?from=&to=
 *   GET  /api/v1/metrics/ingestion
 *   WS   /ws/telemetry   (push: { type:'reading'|'status', ... })
 *
 * See applyReading()/applyStatus() — feed your WebSocket
 * messages into those and the whole UI updates.
 * ------------------------------------------------------------------
 */

const WINDOW = 48; // points kept per metric per node (rolling time window)
const TICK_MS = 1800; // mock publish cadence

/* ----------------------------- state ----------------------------- */

export const store = $state({
  connected: false,
  startedAt: 0,
  now: Date.now(),
  broker: "hivemq-cloud · nodemetry/+/telemetry",
  selectedNodeId: null,
  selectedRunIdsByNodeId: {},
  nodes: [],
  metrics: {
    messagesReceived: 0,
    messagesSaved: 0,
    duplicatesSkipped: 0,
    activeNodes: 0,
    offlineNodes: 0,
    avgProcessingMs: 0,
    throughput: 0,
  },
});

/** The currently selected node object (or first node). */
export function selectedNode() {
  return (
    store.nodes.find((n) => n.nodeId === store.selectedNodeId) ??
    store.nodes[0] ??
    null
  );
}

export function isVirtualNodeId(nodeId) {
  return nodeId?.startsWith("vnode-") ?? false;
}

export function dashboardNodes() {
  return store.nodes.filter((node) => !isVirtualNodeId(node.nodeId));
}

export function selectedDashboardNode() {
  const nodes = dashboardNodes();
  return (
    nodes.find((n) => n.nodeId === store.selectedNodeId) ?? nodes[0] ?? null
  );
}

export function selectNode(id) {
  store.selectedNodeId = id;
}

export function selectedRunIdForNode(nodeId) {
  return nodeId ? (store.selectedRunIdsByNodeId[nodeId] ?? null) : null;
}

export function selectedDashboardRunId() {
  return selectedRunIdForNode(selectedDashboardNode()?.nodeId);
}

export function selectNodeRun(nodeId, runId) {
  if (!nodeId) return;

  if (runId) {
    store.selectedRunIdsByNodeId = {
      ...store.selectedRunIdsByNodeId,
      [nodeId]: runId,
    };
    return;
  }

  const next = { ...store.selectedRunIdsByNodeId };
  delete next[nodeId];
  store.selectedRunIdsByNodeId = next;
}

/* ----------------------- live backend wiring ----------------------- */

let localPipeline = $state(true);

/** Toggle whether applyReading() also updates client-side mock pipeline metrics. */
export function setLocalPipeline(enabled) {
  localPipeline = enabled;
}

/** True once a real backend owns the pipeline (reactive, set by connectLive). */
export function isLivePipeline() {
  return !localPipeline;
}

export function setNodes(nodes) {
  for (const node of nodes) ensureNodeIngestion(node);
  store.nodes = nodes;
  store.selectedNodeId =
    nodes.find((node) => node.nodeId === store.selectedNodeId)?.nodeId ??
    nodes.find((node) => !isVirtualNodeId(node.nodeId))?.nodeId ??
    nodes[0]?.nodeId ??
    null;
  recountNodes();
}

export function setMetrics(metrics) {
  Object.assign(store.metrics, metrics);
}

export function setConnected(connected) {
  store.connected = connected;
}

/* --------------------------- seed fleet --------------------------- */

const SEED = [
  {
    nodeId: "living-room-01",
    name: "Living Room",
    location: "Flat A",
    fw: "0.4.1",
    base: { t: 22.4, h: 47, l: 320 },
    battery: 88,
    rssi: -58,
  },
  {
    nodeId: "bedroom-02",
    name: "Bedroom",
    location: "Flat A",
    fw: "0.4.1",
    base: { t: 21.1, h: 53 },
    battery: 71,
    rssi: -64,
  },
  {
    nodeId: "kitchen-03",
    name: "Kitchen",
    location: "Flat A",
    fw: "0.4.0",
    base: { t: 25.8, h: 41, l: 850 },
    battery: 54,
    rssi: -69,
    warmDrift: true,
  },
  {
    nodeId: "office-04",
    name: "Office",
    location: "Flat B",
    fw: "0.4.1",
    base: { t: 23.0, h: 45 },
    battery: 33,
    rssi: -78,
  },
  {
    nodeId: "garage-05",
    name: "Garage",
    location: "Flat B",
    fw: "0.3.9",
    base: { t: 17.5, h: 60 },
    battery: 9,
    rssi: -82,
    offline: true,
  },
];

export function emptyHistory() {
  return {
    t: [],
    temperature: [],
    temperatureRaw: [],
    humidity: [],
    humidityRaw: [],
    light: [],
    lightRaw: [],
  };
}

export function emptyLatest() {
  return { temperature: null, humidity: null, light: null };
}

export function emptyIngestionMetrics() {
  return {
    messagesReceived: 0,
    messagesSaved: 0,
    duplicatesSkipped: 0,
    avgProcessingMs: 0,
    throughput: 0,
    lastMessageAt: null,
  };
}

export function replaceNodeHistory(nodeId, history) {
  const node = store.nodes.find((n) => n.nodeId === nodeId);
  if (!node) return;
  node.history = history;
}

function seedNodes() {
  const now = Date.now();
  return SEED.map((s) => ({
    nodeId: s.nodeId,
    name: s.name,
    location: s.location,
    firmwareVersion: s.fw,
    status: s.offline ? "offline" : "online",
    battery: s.battery,
    rssi: s.rssi,
    lastSeenAt: s.offline ? now - 1000 * 60 * 4 : now,
    base: { ...s.base },
    warmDrift: !!s.warmDrift,
    latest: s.offline
      ? emptyLatest()
      : {
          temperature: s.base.t,
          humidity: s.base.h,
          light: s.base.l ?? null,
        },
    history: emptyHistory(),
    ingestion: emptyIngestionMetrics(),
  }));
}

/* --------------------------- utilities --------------------------- */

const rnd = (a, b) => Math.random() * (b - a) + a;
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

function pushPoint(arr, v, limit = WINDOW) {
  arr.push(v);
  if (limit !== null && arr.length > limit) arr.shift();
}

/* ----------------------- ingestion pipeline ----------------------- */
/* These mirror what the backend does, so a real WS feed can call them. */

const seenMessageIds = new Set();
const nodeMessageWindows = new Map();

function ensureNodeIngestion(node) {
  node.ingestion ??= emptyIngestionMetrics();
  return node.ingestion;
}

function nodeMessageWindow(nodeId) {
  if (!nodeMessageWindows.has(nodeId)) nodeMessageWindows.set(nodeId, []);
  return nodeMessageWindows.get(nodeId);
}

function refreshNodeThroughput(node, now) {
  const window = nodeMessageWindow(node.nodeId);
  while (window.length && now - window[0].now >= 5000) window.shift();

  const total = window.reduce((sum, item) => sum + item.n, 0);
  ensureNodeIngestion(node).throughput = +(total / 5).toFixed(1);
}

function noteNodeMessage(node, now) {
  const metrics = ensureNodeIngestion(node);
  metrics.messagesReceived++;
  metrics.lastMessageAt = now;
  nodeMessageWindow(node.nodeId).push({ now, n: 1 });
  refreshNodeThroughput(node, now);
}

/**
 * Overwrite a node's counters with a persisted row from
 * GET /api/v1/metrics/ingestion (the backend's physical_node_runs table).
 * A null row means the backend has nothing for the requested scope, so the
 * counters reset. Live WebSocket increments keep the numbers moving between
 * refreshes; the rolling 5s throughput stays client-side while messages are
 * flowing and falls back to the row's run-average when they are not.
 */
export function applyIngestionMetrics(nodeId, row, options = {}) {
  const node = store.nodes.find((n) => n.nodeId === nodeId);
  if (!node) return;
  const { preferPersistedThroughput = false } = options;
  const metrics = ensureNodeIngestion(node);

  if (!row) {
    metrics.messagesReceived = 0;
    metrics.messagesSaved = 0;
    metrics.duplicatesSkipped = 0;
    metrics.avgProcessingMs = 0;
    metrics.throughput = 0;
    metrics.lastMessageAt = null;
    return;
  }

  metrics.messagesReceived = row.messagesReceived ?? 0;
  metrics.messagesSaved = row.messagesSaved ?? 0;
  metrics.duplicatesSkipped = row.duplicatesSkipped ?? 0;
  metrics.avgProcessingMs = row.avgProcessingMs ?? 0;

  const serverLastMs = row.lastMessageAt ? Date.parse(row.lastMessageAt) : null;
  if (preferPersistedThroughput) {
    metrics.lastMessageAt = serverLastMs;
  } else if (serverLastMs && serverLastMs > (metrics.lastMessageAt ?? 0)) {
    metrics.lastMessageAt = serverLastMs;
  }
  if (preferPersistedThroughput || nodeMessageWindow(node.nodeId).length === 0) {
    metrics.throughput = row.throughput ?? 0;
  }
}

/** Apply one telemetry reading (the WebSocket "reading" event). */
export function applyReading(r) {
  if (!r?.nodeId) return;

  const receivedAt = r.receivedAt ?? r.measuredAt ?? Date.now();
  const measuredAt = r.measuredAt ?? receivedAt;
  let node = store.nodes.find((n) => n.nodeId === r.nodeId);

  // A node may start publishing after the initial REST bootstrap. Add it to
  // the reactive fleet instead of dropping its first and subsequent readings.
  if (!node) {
    node = {
      nodeId: r.nodeId,
      name: r.nodeId,
      location: "",
      firmwareVersion: r.firmwareVersion ?? "—",
      status: "online",
      battery: r.battery ?? null,
      rssi: r.rssi ?? null,
      lastSeenAt: receivedAt,
      latest: emptyLatest(),
      history: emptyHistory(),
      ingestion: emptyIngestionMetrics(),
    };
    store.nodes.push(node);
    store.selectedNodeId ??= node.nodeId;
  }

  // With a run selected, the node's ingestion panel shows that run only, so
  // live counters for other runs must not bleed into it (the fleet-wide
  // store.metrics keep counting everything).
  const selectedRunId = selectedRunIdForNode(node.nodeId);
  const runMatches = !selectedRunId || r.runId === selectedRunId;

  store.metrics.messagesReceived++;
  if (runMatches) noteNodeMessage(node, receivedAt);

  // duplicate handling via messageId (idempotent writes)
  if (r.messageId && seenMessageIds.has(r.messageId)) {
    store.metrics.duplicatesSkipped++;
    if (runMatches) ensureNodeIngestion(node).duplicatesSkipped++;
    return;
  }
  if (r.messageId) seenMessageIds.add(r.messageId);

  node.lastSeenAt = receivedAt;
  if (node.status !== "online") node.status = "online";
  if (r.battery !== undefined && r.battery !== null) node.battery = r.battery;
  if (r.rssi !== undefined && r.rssi !== null) node.rssi = r.rssi;
  if (r.firmwareVersion) node.firmwareVersion = r.firmwareVersion;
  if (r.runId) node.latestRunId = r.runId;

  node.latest = {
    temperature:
      r.temperatureFiltered ?? r.temperature ?? node.latest.temperature,
    humidity: r.humidityFiltered ?? r.humidity ?? node.latest.humidity,
    light: r.lightRaw ?? r.light ?? node.latest.light,
  };

  if (runMatches) {
    const historyLimit = selectedRunId ? null : WINDOW;
    pushPoint(node.history.t, measuredAt, historyLimit);
    pushPoint(node.history.temperature, node.latest.temperature, historyLimit);
    pushPoint(
      node.history.temperatureRaw,
      r.temperatureRaw ?? node.latest.temperature,
      historyLimit,
    );
    pushPoint(node.history.humidity, node.latest.humidity, historyLimit);
    pushPoint(node.history.humidityRaw, r.humidityRaw ?? node.latest.humidity, historyLimit);
    pushPoint(
      node.history.lightRaw,
      r.lightRaw ?? r.light ?? node.latest.light,
      historyLimit,
    );
    pushPoint(node.history.light, node.latest.light, historyLimit);
  }
  if (runMatches) ensureNodeIngestion(node).messagesSaved++;
  recountNodes();

  if (localPipeline) {
    store.metrics.messagesSaved++;
    // rolling average processing time (purely illustrative)
    const proc = rnd(1.4, 6.2);
    store.metrics.avgProcessingMs =
      store.metrics.avgProcessingMs * 0.9 + proc * 0.1;
    node.ingestion.avgProcessingMs =
      node.ingestion.avgProcessingMs * 0.9 + proc * 0.1;
  }
}

/** Apply a status change (the WebSocket "status" event / Last Will). */
export function applyStatus(nodeId, status, reason) {
  const node = store.nodes.find((n) => n.nodeId === nodeId);
  if (!node) return;
  node.status = status;
  if (status === "offline") {
    node.lastSeenAt = node.lastSeenAt || Date.now();
  }
  recountNodes();
}

/* ----------------------- mock MQTT generator ---------------------- */

let tickHandle = null;
let clockHandle = null;
let msgWindow = [];
let msgSeq = 0;

function makeReading(node) {
  const b = node.base;
  // Random walk around baseline; kitchen drifts warm to exercise chart scale.
  b.t = clamp(b.t + rnd(-0.25, node.warmDrift ? 0.4 : 0.25), 16, 31);
  b.h = clamp(b.h + rnd(-0.7, 0.7), 28, 70);
  if (b.l != null) b.l = clamp(b.l + rnd(-50, 50), 0, 100000);

  const tNoise = rnd(-0.5, 0.5);
  const hNoise = rnd(-0.9, 0.9);
  const lNoise = rnd(-30, 30);
  msgSeq++;

  return {
    messageId: `${node.nodeId}-${String(msgSeq).padStart(6, "0")}`,
    nodeId: node.nodeId,
    measuredAt: Date.now(),
    temperatureRaw: +(b.t + tNoise).toFixed(2),
    temperatureFiltered: +b.t.toFixed(2),
    humidityRaw: +(b.h + hNoise).toFixed(2),
    humidityFiltered: +b.h.toFixed(2),
    battery: +(node.battery - rnd(0, 0.05)).toFixed(2),
    rssi: Math.round(clamp(node.rssi + rnd(-3, 3), -92, -45)),
    firmwareVersion: node.firmwareVersion,
    lightRaw: b.l != null ? +clamp(b.l + lNoise, 0, 100000).toFixed(2) : null,
    light: b.l != null ? +clamp(b.l + lNoise, 0, 100000).toFixed(2) : null,
  };
}

function tick() {
  let countThisTick = 0;
  for (const node of store.nodes) {
    if (node.status !== "online") continue;
    const r = makeReading(node);
    applyReading(r);
    countThisTick++;

    // occasionally replay a duplicate to exercise messageId dedup
    if (Math.random() < 0.12) {
      applyReading(r);
      countThisTick++;
    }
  }

  // throughput = messages over a rolling 5s window
  const now = Date.now();
  msgWindow.push({ now, n: countThisTick });
  msgWindow = msgWindow.filter((m) => now - m.now < 5000);
  const total = msgWindow.reduce((a, m) => a + m.n, 0);
  store.metrics.throughput = +(total / 5).toFixed(1);

  recountNodes();
}

function recountNodes() {
  store.metrics.activeNodes = store.nodes.filter(
    (n) => n.status === "online",
  ).length;
  store.metrics.offlineNodes = store.nodes.filter(
    (n) => n.status !== "online",
  ).length;
}

/** Scheduled offline detection (mirrors the backend's @Scheduled sweep). */
function sweepOffline() {
  const now = Date.now();
  for (const node of store.nodes) {
    if (node.status === "online" && now - node.lastSeenAt > TICK_MS * 4) {
      applyStatus(node.nodeId, "offline", "no_telemetry");
    }
  }
}

/** Start the bundled mock feed. Returns a stop function. */
export function startMockFeed() {
  if (tickHandle) return stopFeed;
  store.nodes = seedNodes();
  store.selectedNodeId = store.nodes[0]?.nodeId ?? null;
  store.startedAt = Date.now();
  store.connected = true;
  recountNodes();

  // seed the rolling charts with a little history so they're not empty
  for (let i = 0; i < 14; i++) tick();

  tickHandle = setInterval(() => {
    sweepOffline();
    tick();
  }, TICK_MS);
  clockHandle = setInterval(() => {
    store.now = Date.now();
  }, 1000);
  return stopFeed;
}

export function stopFeed() {
  clearInterval(tickHandle);
  clearInterval(clockHandle);
  tickHandle = null;
  clockHandle = null;
  store.connected = false;
}

export const config = { WINDOW, TICK_MS };
