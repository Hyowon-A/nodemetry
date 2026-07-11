import { json } from '@sveltejs/kit';
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';

const simulatorDir = resolve(process.cwd(), '../simulator');
const scriptPath = resolve(simulatorDir, 'simulator.py');
const venvPython = resolve(simulatorDir, 'venv/bin/python');
const defaultOptions = {
  nodes: 10,
  interval: 10,
  duration: 0,
  qos: 1,
  shared: false,
  connections: 5,
  duplicateRate: 0
};

const API_BASE = process.env.PUBLIC_API_BASE || 'http://localhost:8080';
const STOP_TIMEOUT_MS = 15000;

let processHandle = null;
let startedAt = null;
let lastExit = null;
let logTail = '';
let currentOptions = { ...defaultOptions };
let currentRunId = null;
let endingRunId = null;
let endPromise = null;

function isRunning() {
  return processHandle !== null && processHandle.exitCode === null && processHandle.signalCode === null;
}

function appendLog(chunk) {
  logTail = `${logTail}${chunk}`.slice(-4000);
}

function status() {
  return {
    running: isRunning(),
    draining: Boolean(endingRunId),
    startedAt,
    lastExit,
    logTail,
    options: currentOptions,
    currentRunId: currentRunId ?? endingRunId
  };
}

function waitForExit(child, timeoutMs) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return Promise.resolve(true);

  return new Promise((resolve) => {
    const timeout = setTimeout(() => {
      cleanup();
      resolve(false);
    }, timeoutMs);

    function cleanup() {
      clearTimeout(timeout);
      child.off('exit', onExit);
      child.off('error', onError);
    }

    function onExit() {
      cleanup();
      resolve(true);
    }

    function onError() {
      cleanup();
      resolve(true);
    }

    child.once('exit', onExit);
    child.once('error', onError);
  });
}

function parseSimulatorStats(log) {
  let stats = null;
  const finalPattern = /Done\. total_(?:received|queued)=(\d+) duplicates_(?:received|queued)=(\d+) failures=(\d+)/g;
  const livePattern = /(?:received|queued)=(\d+) duplicate_(?:received|queued)=(\d+) failures=(\d+)/g;

  for (const match of log.matchAll(finalPattern)) {
    stats = {
      received: Number(match[1]),
      duplicateReceived: Number(match[2]),
      failures: Number(match[3])
    };
  }

  if (stats) return stats;

  for (const match of log.matchAll(livePattern)) {
    stats = {
      received: Number(match[1]),
      duplicateReceived: Number(match[2]),
      failures: Number(match[3])
    };
  }

  return stats;
}

function endRunPayload() {
  const stats = parseSimulatorStats(logTail);
  return {
    endedAtEpochMs: lastExit?.at ?? Date.now(),
    ...(stats ? { totalReceived: stats.received } : {})
  };
}

async function endCurrentRun() {
  if (endPromise) return endPromise;
  if (!currentRunId) return null;

  const runId = currentRunId;
  endingRunId = runId;

  endPromise = (async () => {
    try {
      await fetch(`${API_BASE}/api/v1/runs/${runId}/end`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(endRunPayload())
      });
    } catch (e) {
      console.warn('[simulator] failed to end run:', e.message);
    } finally {
      if (currentRunId === runId) currentRunId = null;
      if (endingRunId === runId) endingRunId = null;
      endPromise = null;
    }
  })();

  return endPromise;
}

export function GET() {
  return json(status());
}

function numberOption(value, fallback, min, max) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, n));
}

function intOption(value, fallback, min, max) {
  return Math.round(numberOption(value, fallback, min, max));
}

function normalizeOptions(input = {}) {
  const qos = intOption(input.qos, defaultOptions.qos, 0, 2);
  return {
    nodes: intOption(input.nodes, defaultOptions.nodes, 1, 5000),
    interval: numberOption(input.interval, defaultOptions.interval, 1, 3600),
    duration: numberOption(input.duration, defaultOptions.duration, 0, 86400),
    qos: [0, 1, 2].includes(qos) ? qos : defaultOptions.qos,
    shared: Boolean(input.shared),
    connections: intOption(input.connections, defaultOptions.connections, 1, 200),
    duplicateRate: numberOption(input.duplicateRate, defaultOptions.duplicateRate, 0, 1)
  };
}

function argsForOptions(options, runId) {
  const args = [
    scriptPath,
    '--nodes',
    String(options.nodes),
    '--interval',
    String(options.interval),
    '--duration',
    String(options.duration),
    '--qos',
    String(options.qos),
    '--duplicate-rate',
    String(options.duplicateRate),
    '--run-id',
    runId
  ];

  if (options.shared) {
    args.push('--shared', '--connections', String(options.connections));
  }

  return args;
}

export async function POST({ request }) {
  if (isRunning() || endingRunId) return json(status());

  if (!existsSync(scriptPath)) {
    return json({ error: `Simulator script not found at ${scriptPath}` }, { status: 404 });
  }

  const body = await request.json().catch(() => ({}));
  currentOptions = normalizeOptions(body);

  // Generate the run id up front and hand it to the simulator so the run we
  // register below and the runId stamped on every published reading match.
  const runId = crypto.randomUUID();

  const labelParts = [
    `QoS ${currentOptions.qos}`,
    `${currentOptions.nodes} nodes`,
    `${currentOptions.interval}s interval`
  ];
  if (currentOptions.duration > 0) labelParts.push(`${currentOptions.duration}s duration`);
  const label = labelParts.join(' · ');

  // Register the run *before* spawning the simulator: the row must exist by the
  // time the first reading lands, or backend per-run counters have no target.
  // If registration fails, don't start an untracked load test.
  try {
    const res = await fetch(`${API_BASE}/api/v1/runs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        runId,
        label,
        qos: currentOptions.qos,
        nodeCount: currentOptions.nodes,
        intervalSec: currentOptions.interval,
        duplicateRate: currentOptions.duplicateRate
      })
    });
    if (!res.ok) {
      return json({ error: `Failed to register run (${res.status})` }, { status: 502 });
    }
  } catch (e) {
    return json({ error: `Failed to register run: ${e.message}` }, { status: 502 });
  }

  currentRunId = runId;

  const python = existsSync(venvPython) ? venvPython : 'python3';
  const child = spawn(python, argsForOptions(currentOptions, runId), {
    cwd: simulatorDir,
    env: process.env,
    stdio: ['ignore', 'pipe', 'pipe']
  });

  processHandle = child;
  startedAt = Date.now();
  lastExit = null;
  logTail = '';

  child.stdout.setEncoding('utf8');
  child.stderr.setEncoding('utf8');
  child.stdout.on('data', appendLog);
  child.stderr.on('data', appendLog);
  child.on('exit', (code, signal) => {
    lastExit = { code, signal, at: Date.now() };
    if (processHandle === child) processHandle = null;
    void endCurrentRun();
  });
  child.on('error', (error) => {
    appendLog(`${error.message}\n`);
    lastExit = { code: null, signal: null, at: Date.now(), error: error.message };
    if (processHandle === child) processHandle = null;
    // The process never started, so close the run we just registered instead of
    // leaving it open (endedAt null) and blocking the next run's counters.
    void endCurrentRun();
  });

  return json(status(), { status: 201 });
}

export async function DELETE() {
  if (!isRunning()) {
    await endCurrentRun();
    return json(status());
  }

  const child = processHandle;
  child.kill('SIGINT');
  const exited = await waitForExit(child, STOP_TIMEOUT_MS);
  if (exited) await endCurrentRun();

  return json({ ...status(), stopping: !exited });
}
