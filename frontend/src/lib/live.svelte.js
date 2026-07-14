/**
 * live.svelte.js
 * ------------------------------------------------------------------
 * Connects the dashboard to the real Spring Boot backend.
 *
 *   REST   →  initial fleet + history (GET /api/v1/nodes, /readings)
 *   STOMP  →  live readings, pushed to /topic/readings
 *             (see WebSocketConfig.java + TelemetryService.java)
 *
 * The backend's WS endpoint speaks STOMP over a raw WebSocket
 * (no SockJS), so the client here is @stomp/stompjs, not the
 * plain WebSocket API.
 *
 * Configure with environment variables (see .env.example):
 *   PUBLIC_API_BASE   e.g. http://localhost:8080   (no trailing slash)
 *   PUBLIC_WS_URL     e.g. ws://localhost:8080/ws
 *
 * +page.svelte calls connectLive() instead of startMockFeed().
 * ------------------------------------------------------------------
 */
import { Client } from '@stomp/stompjs';
import { env } from '$env/dynamic/public';
import {
  store,
  config,
  applyReading,
  applyStatus,
  setNodes,
  setConnected,
  setLocalPipeline,
  setAckHandler,
  emptyHistory,
  emptyLatest,
  replaceNodeHistory,
  selectedRunIdForNode
} from '$lib/telemetry.svelte.js';

const API = (env.PUBLIC_API_BASE || 'http://localhost:8080').replace(/\/$/, '');
const WS_URL = env.PUBLIC_WS_URL || API.replace(/^http/, 'ws') + '/ws';

/** Normalise a timestamp (ISO string or epoch) to epoch ms. */
const ms = (t) => (typeof t === 'number' ? t : t ? Date.parse(t) : Date.now());

async function getJSON(path) {
  const res = await fetch(`${API}${path}`);
  if (!res.ok) throw new Error(`${path} → ${res.status}`);
  return res.json();
}

function mapNode(n) {
  return {
    nodeId: n.nodeId,
    name: n.name ?? n.nodeId,
    location: n.location ?? '',
    firmwareVersion: n.firmwareVersion ?? '—',
    status: n.status ?? 'offline',
    battery: n.battery ?? null,
    rssi: n.rssi ?? null,
    lastSeenAt: ms(n.lastSeenAt),
    latest: emptyLatest(),
    history: emptyHistory()
  };
}

function readingsPath(nodeId, runId) {
  const encodedNodeId = encodeURIComponent(nodeId);
  return runId
    ? `/api/v1/nodes/${encodedNodeId}/runs/${encodeURIComponent(runId)}/readings`
    : `/api/v1/nodes/${encodedNodeId}/readings`;
}

function readingTime(reading) {
  return ms(reading.measuredAt ?? reading.receivedAt);
}

function historyFromRows(rows) {
  const h = emptyHistory();
  for (const r of rows) {
    h.t.push(readingTime(r));
    h.temperature.push(r.temperatureFiltered ?? r.temperature ?? null);
    h.temperatureRaw.push(r.temperatureRaw ?? r.temperature ?? null);
    h.humidity.push(r.humidityFiltered ?? r.humidity ?? null);
    h.humidityRaw.push(r.humidityRaw ?? r.humidity ?? null);
    h.lightRaw.push(r.lightRaw ?? r.light ?? null);
    h.light.push(r.lightRaw ?? r.light ?? null);
  }
  return h;
}

export async function loadNodeHistory(nodeId, runId = null) {
  const rows = await getJSON(readingsPath(nodeId, runId));
  const recent = rows
    .sort((a, b) => readingTime(a) - readingTime(b))
    .slice(-config.WINDOW);

  if (selectedRunIdForNode(nodeId) !== (runId ?? null)) return 0;

  replaceNodeHistory(nodeId, historyFromRows(recent));
  return recent.length;
}

async function seedHistory(node) {
  try {
    const count = await loadNodeHistory(node.nodeId);
    const h = node.history;
    const lastIndex = count - 1;
    const lastRow =
      lastIndex >= 0
        ? {
            temperature: h.temperature[lastIndex],
            humidity: h.humidity[lastIndex],
            light: h.light[lastIndex]
          }
        : null;
    if (lastRow) {
      const prev = node.latest;
      node.latest = {
        temperature: lastRow.temperature ?? prev.temperature,
        humidity: lastRow.humidity ?? prev.humidity,
        light: lastRow.light ?? prev.light
      };
    }
  } catch (e) {
    console.warn(`[nodemetry] history seed failed for ${node.nodeId}:`, e.message);
  }
}

let stomp = null;
let alive = false;
let clockTimer = null;

/** Bootstrap from REST, then open the live STOMP connection. Returns nothing. */
export async function connectLive() {
  alive = true;
  setLocalPipeline(false); // backend owns persistence; we just mirror readings
  setAckHandler(null);

  store.startedAt = Date.now();
  clearInterval(clockTimer);
  clockTimer = setInterval(() => {
    store.now = Date.now();
  }, 1000);

  // 1) initial state over REST
  try {
    const nodes = (await getJSON('/api/v1/nodes')).map(mapNode);
    setNodes(nodes);
    await Promise.all(nodes.map(seedHistory));
  } catch (e) {
    console.error('[nodemetry] REST bootstrap failed — is PUBLIC_API_BASE correct?', e.message);
  }

  // 2) live stream — STOMP over WebSocket
  openStomp();
}

function openStomp() {
  if (!alive) return;

  const client = new Client({
    brokerURL: WS_URL,
    reconnectDelay: 5000, // built-in auto-reconnect, capped retry cadence
    onConnect: () => {
      setConnected(true);
      client.subscribe('/topic/readings', (message) => {
        try {
          const r = JSON.parse(message.body);
          applyReading({
            messageId: r.messageId,
            runId: r.runId,
            nodeId: r.nodeId,
            measuredAt: ms(r.measuredAt ?? r.receivedAt),
            temperatureRaw: r.temperatureRaw,
            temperatureFiltered: r.temperatureFiltered,
            humidityRaw: r.humidityRaw,
            humidityFiltered: r.humidityFiltered,
            lightRaw: r.lightRaw ?? r.light,
            light: r.light,
            battery: r.battery,
            rssi: r.rssi,
            firmwareVersion: r.firmwareVersion
          });
        } catch (error) {
          console.error('[nodemetry] invalid STOMP reading:', error, message.body);
        }
      });

      client.subscribe('/topic/nodes/status', (message) => {
        try {
          const { nodeId, status } = JSON.parse(message.body);
          applyStatus(nodeId, status);
        } catch (error) {
          console.error('[nodemetry] invalid status message:', error, message.body);
        }
      });
    },
    onWebSocketClose: () => setConnected(false),
    onWebSocketError: (event) =>
      console.error('[nodemetry] WebSocket connection failed:', WS_URL, event),
    onStompError: (frame) =>
      console.error('[nodemetry] STOMP error:', frame.headers?.message, frame.body)
  });

  stomp = client;
  client.activate();
}

/** Tear down the live connection (returned to onMount for cleanup). */
export function disconnectLive() {
  alive = false;
  clearInterval(clockTimer);
  clockTimer = null;
  setConnected(false);
  stomp?.deactivate();
  stomp = null;
}
