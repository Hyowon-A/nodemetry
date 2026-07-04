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
 *   GET  /api/v1/alerts
 *   PATCH /api/v1/alerts/{alertId}/acknowledge
 *   GET  /api/v1/metrics/ingestion
 *   WS   /ws/telemetry   (push: { type:'reading'|'status'|'alert', ... })
 *
 * See applyReading()/applyStatus()/pushAlert() — feed your WebSocket
 * messages into those and the whole UI updates.
 * ------------------------------------------------------------------
 */

const WINDOW = 48; // points kept per metric per node (rolling time window)
const TICK_MS = 1800; // mock publish cadence

/** Thresholds for alert rules (from the plan). */
const RULES = {
  co2Max: 1000,
  tempMax: 28,
  humidityMin: 35,
  batteryMin: 20,
  rssiMin: -75,
  lightMin: 50   // lux — below this suggests a covered sensor or near-dark environment
};

/* ----------------------------- state ----------------------------- */

export const store = $state({
  connected: false,
  startedAt: 0,
  now: Date.now(),
  broker: 'hivemq-cloud · nodemetry/+/telemetry',
  selectedNodeId: null,
  nodes: [],
  alerts: [],
  metrics: {
    messagesReceived: 0,
    messagesSaved: 0,
    duplicatesSkipped: 0,
    activeNodes: 0,
    offlineNodes: 0,
    alertsCreated: 0,
    avgProcessingMs: 0,
    throughput: 0
  }
});

/** The currently selected node object (or first node). */
export function selectedNode() {
  return (
    store.nodes.find((n) => n.nodeId === store.selectedNodeId) ?? store.nodes[0] ?? null
  );
}

export function isVirtualNodeId(nodeId) {
  return nodeId?.startsWith('vnode-') ?? false;
}

export function dashboardNodes() {
  return store.nodes.filter((node) => !isVirtualNodeId(node.nodeId));
}

export function selectedDashboardNode() {
  const nodes = dashboardNodes();
  return nodes.find((n) => n.nodeId === store.selectedNodeId) ?? nodes[0] ?? null;
}

export function selectNode(id) {
  store.selectedNodeId = id;
}

export function acknowledgeAlert(id) {
  const a = store.alerts.find((x) => x.id === id);
  if (a) a.acknowledged = true;
  if (ackHandler) ackHandler(id);
}

/* ----------------------- live backend wiring ----------------------- */

let localPipeline = true;
let ackHandler = null;

/** Toggle whether applyReading/applyStatus also run local alert evaluation. */
export function setLocalPipeline(enabled) {
  localPipeline = enabled;
}

/** Register a handler invoked when the user acknowledges an alert (e.g. PATCH to backend). */
export function setAckHandler(fn) {
  ackHandler = fn;
}

export function setNodes(nodes) {
  for (const node of nodes) ensureNodeIngestion(node);
  store.nodes = nodes;
  store.selectedNodeId = nodes[0]?.nodeId ?? null;
  recountNodes();
}

export function setAlerts(alerts) {
  store.alerts = alerts;
  for (const a of alerts) activeAlertKeys.add(`${a.nodeId}:${a.type}`);
}

export function setMetrics(metrics) {
  Object.assign(store.metrics, metrics);
}

export function setConnected(connected) {
  store.connected = connected;
}

/* --------------------------- seed fleet --------------------------- */

const SEED = [
  { nodeId: 'living-room-01', name: 'Living Room', location: 'Flat A', fw: '0.4.1', base: { t: 22.4, h: 47, c: 610, l: 320 }, battery: 88, rssi: -58 },
  { nodeId: 'bedroom-02', name: 'Bedroom', location: 'Flat A', fw: '0.4.1', base: { t: 21.1, h: 53, c: 540 }, battery: 71, rssi: -64 },
  { nodeId: 'kitchen-03', name: 'Kitchen', location: 'Flat A', fw: '0.4.0', base: { t: 25.8, h: 41, c: 820, l: 850 }, battery: 54, rssi: -69, warmDrift: true },
  { nodeId: 'office-04', name: 'Office', location: 'Flat B', fw: '0.4.1', base: { t: 23.0, h: 45, c: 660 }, battery: 33, rssi: -78 },
  { nodeId: 'garage-05', name: 'Garage', location: 'Flat B', fw: '0.3.9', base: { t: 17.5, h: 60, c: 470 }, battery: 9, rssi: -82, offline: true }
];

export function emptyHistory() {
  return { t: [], temperature: [], temperatureRaw: [], humidity: [], humidityRaw: [], co2: [], light: [], lightRaw: [] };
}

export function emptyLatest() {
  return { temperature: null, humidity: null, co2: null, light: null };
}

export function emptyIngestionMetrics() {
  return {
    messagesReceived: 0,
    messagesSaved: 0,
    duplicatesSkipped: 0,
    alertsCreated: 0,
    avgProcessingMs: 0,
    throughput: 0,
    lastMessageAt: null
  };
}

function seedNodes() {
  const now = Date.now();
  return SEED.map((s) => ({
    nodeId: s.nodeId,
    name: s.name,
    location: s.location,
    firmwareVersion: s.fw,
    status: s.offline ? 'offline' : 'online',
    battery: s.battery,
    rssi: s.rssi,
    lastSeenAt: s.offline ? now - 1000 * 60 * 4 : now,
    base: { ...s.base },
    warmDrift: !!s.warmDrift,
    latest: s.offline
      ? emptyLatest()
      : { temperature: s.base.t, humidity: s.base.h, co2: s.base.c, light: s.base.l ?? null },
    history: emptyHistory(),
    ingestion: emptyIngestionMetrics()
  }));
}

/* --------------------------- utilities --------------------------- */

const rnd = (a, b) => Math.random() * (b - a) + a;
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

function pushPoint(arr, v) {
  arr.push(v);
  if (arr.length > WINDOW) arr.shift();
}

/* ----------------------- ingestion pipeline ----------------------- */
/* These mirror what the backend does, so a real WS feed can call them. */

const seenMessageIds = new Set();
const activeAlertKeys = new Set();
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

function noteNodeAlert(nodeId) {
  const node = store.nodes.find((n) => n.nodeId === nodeId);
  if (node) ensureNodeIngestion(node).alertsCreated++;
}

/** Apply one telemetry reading (the WebSocket "reading" event). */
export function applyReading(r) {
  if (!r?.nodeId) return;

  const t = r.measuredAt ?? Date.now();
  let node = store.nodes.find((n) => n.nodeId === r.nodeId);

  // A node may start publishing after the initial REST bootstrap. Add it to
  // the reactive fleet instead of dropping its first and subsequent readings.
  if (!node) {
    node = {
      nodeId: r.nodeId,
      name: r.nodeId,
      location: '',
      firmwareVersion: r.firmwareVersion ?? '—',
      status: 'online',
      battery: r.battery ?? null,
      rssi: r.rssi ?? null,
      lastSeenAt: t,
      latest: emptyLatest(),
      history: emptyHistory(),
      ingestion: emptyIngestionMetrics()
    };
    store.nodes.push(node);
    store.selectedNodeId ??= node.nodeId;
  }

  store.metrics.messagesReceived++;
  noteNodeMessage(node, t);

  // duplicate handling via messageId (idempotent writes)
  if (r.messageId && seenMessageIds.has(r.messageId)) {
    store.metrics.duplicatesSkipped++;
    ensureNodeIngestion(node).duplicatesSkipped++;
    return;
  }
  if (r.messageId) seenMessageIds.add(r.messageId);

  node.lastSeenAt = t;
  if (node.status !== 'online') node.status = 'online';
  if (r.battery !== undefined && r.battery !== null) node.battery = r.battery;
  if (r.rssi !== undefined && r.rssi !== null) node.rssi = r.rssi;
  if (r.firmwareVersion) node.firmwareVersion = r.firmwareVersion;

  node.latest = {
    temperature: r.temperatureFiltered ?? r.temperature ?? node.latest.temperature,
    humidity: r.humidityFiltered ?? r.humidity ?? node.latest.humidity,
    co2: r.co2 ?? node.latest.co2,
    light: r.lightFiltered ?? r.light ?? node.latest.light
  };

  pushPoint(node.history.t, t);
  pushPoint(node.history.temperature, node.latest.temperature);
  pushPoint(node.history.temperatureRaw, r.temperatureRaw ?? node.latest.temperature);
  pushPoint(node.history.humidity, node.latest.humidity);
  pushPoint(node.history.humidityRaw, r.humidityRaw ?? node.latest.humidity);
  pushPoint(node.history.co2, node.latest.co2);
  pushPoint(node.history.lightRaw, r.lightRaw ?? r.light ?? node.latest.light);
  pushPoint(node.history.light, node.latest.light);
  ensureNodeIngestion(node).messagesSaved++;
  recountNodes();

  if (localPipeline) {
    store.metrics.messagesSaved++;
    // rolling average processing time (purely illustrative)
    const proc = rnd(1.4, 6.2);
    store.metrics.avgProcessingMs = store.metrics.avgProcessingMs * 0.9 + proc * 0.1;
    node.ingestion.avgProcessingMs = node.ingestion.avgProcessingMs * 0.9 + proc * 0.1;
    evaluateAlerts(node);
  }
}

/** Apply a status change (the WebSocket "status" event / Last Will). */
export function applyStatus(nodeId, status, reason) {
  const node = store.nodes.find((n) => n.nodeId === nodeId);
  if (!node) return;
  const was = node.status;
  node.status = status;
  if (status === 'offline') {
    node.lastSeenAt = node.lastSeenAt || Date.now();
    if (was === 'online') {
      pushAlert(node.nodeId, 'offline', 'critical', `Node went offline${reason ? ` (${reason})` : ''}`);
    }
  } else {
    activeAlertKeys.delete(`${nodeId}:offline`);
  }
  recountNodes();
}

/** Apply a backend-pushed alert (the WebSocket "alert" event) directly into the store. */
export function applyAlert(alert) {
  const key = `${alert.nodeId}:${alert.type}`;
  activeAlertKeys.add(key);
  store.alerts.unshift(alert);
  if (store.alerts.length > 40) store.alerts.pop();
  store.metrics.alertsCreated++;
  noteNodeAlert(alert.nodeId);
}

function pushAlert(nodeId, type, severity, message) {
  const key = `${nodeId}:${type}`;
  if (activeAlertKeys.has(key)) return;
  activeAlertKeys.add(key);
  store.alerts.unshift({
    id: `${nodeId}-${type}-${Date.now()}`,
    nodeId,
    type,
    severity,
    message,
    acknowledged: false,
    createdAt: Date.now()
  });
  if (store.alerts.length > 40) store.alerts.pop();
  store.metrics.alertsCreated++;
  noteNodeAlert(nodeId);
}

function clearAlert(nodeId, type) {
  activeAlertKeys.delete(`${nodeId}:${type}`);
}

function evaluateAlerts(node) {
  const l = node.latest;
  const check = (cond, type, severity, msg) =>
    cond ? pushAlert(node.nodeId, type, severity, msg) : clearAlert(node.nodeId, type);

  check(l.co2 != null && l.co2 > RULES.co2Max, 'co2', 'critical', `CO₂ ${Math.round(l.co2)} ppm exceeds ${RULES.co2Max}`);
  check(l.temperature != null && l.temperature > RULES.tempMax, 'temp', 'warning', `Temperature ${l.temperature.toFixed(1)}°C above ${RULES.tempMax}°C`);
  check(l.humidity != null && l.humidity < RULES.humidityMin, 'humidity', 'warning', `Humidity ${l.humidity.toFixed(0)}% below ${RULES.humidityMin}%`);
  check(node.battery != null && node.battery < RULES.batteryMin, 'battery', 'warning', `Battery ${Math.round(node.battery)}% — replace soon`);
  check(node.rssi != null && node.rssi < RULES.rssiMin, 'rssi', 'info', `Weak signal ${Math.round(node.rssi)} dBm`);
  check(l.light != null && l.light < RULES.lightMin, 'light', 'warning', `Light ${Math.round(l.light)} lux below ${RULES.lightMin}`);
}

/* ----------------------- mock MQTT generator ---------------------- */

let tickHandle = null;
let clockHandle = null;
let msgWindow = [];
let msgSeq = 0;

function makeReading(node) {
  const b = node.base;
  // random walk around baseline; kitchen drifts warm to demo an alert
  b.t = clamp(b.t + rnd(-0.25, node.warmDrift ? 0.4 : 0.25), 16, 31);
  b.h = clamp(b.h + rnd(-0.7, 0.7), 28, 70);
  b.c = clamp(b.c + rnd(-30, node.nodeId === 'kitchen-03' ? 55 : 30), 400, 1300);
  if (b.l != null) b.l = clamp(b.l + rnd(-50, 50), 0, 100000);

  const tNoise = rnd(-0.5, 0.5);
  const hNoise = rnd(-0.9, 0.9);
  const lNoise = rnd(-30, 30);
  msgSeq++;

  return {
    messageId: `${node.nodeId}-${String(msgSeq).padStart(6, '0')}`,
    nodeId: node.nodeId,
    measuredAt: Date.now(),
    temperatureRaw: +(b.t + tNoise).toFixed(2),
    temperatureFiltered: +b.t.toFixed(2),
    humidityRaw: +(b.h + hNoise).toFixed(2),
    humidityFiltered: +b.h.toFixed(2),
    co2: Math.round(b.c),
    battery: +(node.battery - rnd(0, 0.05)).toFixed(2),
    rssi: Math.round(clamp(node.rssi + rnd(-3, 3), -92, -45)),
    firmwareVersion: node.firmwareVersion,
    lightRaw: b.l != null ? Math.round(clamp(b.l + lNoise, 0, 100000)) : null,
    lightFiltered: b.l != null ? Math.round(b.l) : null,
    light: b.l != null ? Math.round(b.l) : null
  };
}

function tick() {
  let countThisTick = 0;
  for (const node of store.nodes) {
    if (node.status !== 'online') continue;
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
  store.metrics.activeNodes = store.nodes.filter((n) => n.status === 'online').length;
  store.metrics.offlineNodes = store.nodes.filter((n) => n.status !== 'online').length;
}

/** Scheduled offline detection (mirrors the backend's @Scheduled sweep). */
function sweepOffline() {
  const now = Date.now();
  for (const node of store.nodes) {
    if (node.status === 'online' && now - node.lastSeenAt > TICK_MS * 4) {
      applyStatus(node.nodeId, 'offline', 'no_telemetry');
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

/** Pause / resume button in the top bar. */
export function toggleFeed() {
  if (tickHandle) stopFeed();
  else {
    store.connected = true;
    store.startedAt = store.startedAt || Date.now();
    tickHandle = setInterval(() => {
      sweepOffline();
      tick();
    }, TICK_MS);
    clockHandle = setInterval(() => {
      store.now = Date.now();
    }, 1000);
  }
}

export const config = { WINDOW, TICK_MS, RULES };
