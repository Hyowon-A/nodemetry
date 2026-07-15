import { json, error } from '@sveltejs/kit';
import { dev } from '$app/environment';
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
const RUN_ID_TIMEOUT_MS = 5000;
const WARMUP_READY_TIMEOUT_MS = 120000;

let processHandle = null;
let startedAt = null;
let warming = false;
let lastExit = null;
let logTail = '';
let currentOptions = { ...defaultOptions };
let currentRunId = null;
let registeredRunId = null;
let endingRunId = null;
let endPromise = null;

function isChildRunning(child) {
  return child !== null && child.exitCode === null && child.signalCode === null;
}

function isRunning() {
  return isChildRunning(processHandle);
}

function appendLog(chunk) {
  logTail = `${logTail}${chunk}`.slice(-4000);
}

function status() {
  return {
    running: isRunning(),
    warming: warming && isRunning(),
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
  if (!registeredRunId) {
    if (!isRunning()) {
      currentRunId = null;
      warming = false;
      startedAt = null;
    }
    return null;
  }

  const runId = registeredRunId;
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
      if (registeredRunId === runId) registeredRunId = null;
      if (endingRunId === runId) endingRunId = null;
      warming = false;
      startedAt = null;
      endPromise = null;
    }
  })();

  return endPromise;
}

export function GET() {
  if (!dev) throw error(404, 'Not found');
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
    runId,
    '--start-gate-stdin'
  ];

  if (options.shared) {
    args.push('--shared', '--connections', String(options.connections));
  }

  return args;
}

function pctLabel(value) {
  const pct = value * 100;
  if (pct === 0) return '0%';
  if (pct < 1) return `${pct.toFixed(2).replace(/\.?0+$/, '')}%`;
  if (pct < 10) return `${pct.toFixed(1).replace(/\.?0+$/, '')}%`;
  return `${Math.round(pct)}%`;
}

function runIdFromSimulator(python) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      python,
      ['-c', 'import simulator; print(simulator.default_run_id())'],
      {
        cwd: simulatorDir,
        env: process.env,
        stdio: ['ignore', 'pipe', 'pipe']
      }
    );

    let stdout = '';
    let stderr = '';
    const timeout = setTimeout(() => {
      child.kill('SIGTERM');
      reject(new Error('timed out waiting for simulator run id'));
    }, RUN_ID_TIMEOUT_MS);

    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.on('error', (error) => {
      clearTimeout(timeout);
      reject(error);
    });
    child.on('exit', (code, signal) => {
      clearTimeout(timeout);
      if (code !== 0) {
        const detail = stderr.trim() || signal || `exit code ${code}`;
        reject(new Error(detail));
        return;
      }

      const runId = stdout.trim();
      if (!runId) {
        reject(new Error('simulator returned an empty run id'));
        return;
      }
      resolve(runId);
    });
  });
}

function waitForWarmupReady(child) {
  return new Promise((resolve, reject) => {
    let buffer = '';
    const timeout = setTimeout(() => {
      cleanup();
      reject(new Error('timed out waiting for simulator warmup'));
    }, WARMUP_READY_TIMEOUT_MS);

    function cleanup() {
      clearTimeout(timeout);
      child.stdout.off('data', onData);
      child.stderr.off('data', onData);
      child.off('exit', onExit);
      child.off('error', onError);
    }

    function onData(chunk) {
      buffer = `${buffer}${chunk}`.slice(-2000);
      const ready = buffer.match(/READY warmup_connected=(\d+)\/(\d+)/);
      if (ready) {
        cleanup();
        resolve({ connected: Number(ready[1]), expected: Number(ready[2]) });
        return;
      }

      const failed = buffer.match(/WARMUP_FAILED warmup_connected=(\d+)\/(\d+) failures=(\d+)/);
      if (failed) {
        cleanup();
        reject(
          new Error(
            `simulator warmup failed: ${failed[1]}/${failed[2]} connections ready, ${failed[3]} failed`
          )
        );
      }
    }

    function onExit(code, signal) {
      cleanup();
      reject(new Error(`simulator exited during warmup (${signal ?? `code ${code}`})`));
    }

    function onError(error) {
      cleanup();
      reject(error);
    }

    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.once('exit', onExit);
    child.once('error', onError);
  });
}

async function registerRun(runId, label) {
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
    throw new Error(`Failed to register run (${res.status})`);
  }
}

async function beginMeasurementAfterWarmup(child, runId, label) {
  try {
    await waitForWarmupReady(child);
    if (processHandle !== child || !isChildRunning(child)) return;

    await registerRun(runId, label);
    registeredRunId = runId;
    if (!warming || processHandle !== child || !isChildRunning(child)) {
      await endCurrentRun();
      return;
    }

    startedAt = Date.now();
    warming = false;
    child.stdin.write('start\n');
  } catch (e) {
    if (processHandle !== child || !warming) return;

    appendLog(`warmup failed: ${e.message}\n`);
    lastExit = { code: null, signal: null, at: Date.now(), error: e.message };
    warming = false;
    if (isChildRunning(child)) {
      child.kill('SIGINT');
    }
  }
}

export async function POST({ request }) {
  if (!dev) throw error(404, 'Not found');
  if (isRunning() || endingRunId) return json(status());

  if (!existsSync(scriptPath)) {
    return json({ error: `Simulator script not found at ${scriptPath}` }, { status: 404 });
  }

  const body = await request.json().catch(() => ({}));
  currentOptions = normalizeOptions(body);

  const python = existsSync(venvPython) ? venvPython : 'python3';

  // The simulator owns the run-id format. Ask it for the id before registering
  // the backend row so counters have a target by the time telemetry starts.
  let runId;
  try {
    runId = await runIdFromSimulator(python);
  } catch (e) {
    return json({ error: `Failed to get run id from simulator: ${e.message}` }, { status: 502 });
  }

  const labelParts = [
    currentOptions.shared ? `shared · ${currentOptions.connections} connections` : 'dedicated',
    `${currentOptions.interval}s interval`,
    `${pctLabel(currentOptions.duplicateRate)} duplicate rate`
  ];
  const label = labelParts.join(' · ');

  currentRunId = runId;
  registeredRunId = null;
  warming = true;

  const child = spawn(python, argsForOptions(currentOptions, runId), {
    cwd: simulatorDir,
    env: process.env,
    stdio: ['pipe', 'pipe', 'pipe']
  });

  processHandle = child;
  startedAt = null;
  lastExit = null;
  logTail = '';

  child.stdout.setEncoding('utf8');
  child.stderr.setEncoding('utf8');
  child.stdout.on('data', appendLog);
  child.stderr.on('data', appendLog);
  child.on('exit', (code, signal) => {
    lastExit = { code, signal, at: Date.now(), ...(lastExit?.error ? { error: lastExit.error } : {}) };
    if (processHandle === child) processHandle = null;
    warming = false;
    void endCurrentRun();
  });
  child.on('error', (error) => {
    appendLog(`${error.message}\n`);
    lastExit = { code: null, signal: null, at: Date.now(), error: error.message };
    if (processHandle === child) processHandle = null;
    warming = false;
    // If warmup had already registered the run, close it instead of leaving an
    // open row (endedAt null) that blocks the next run's counters.
    void endCurrentRun();
  });

  void beginMeasurementAfterWarmup(child, runId, label);

  return json(status(), { status: 202 });
}

export async function DELETE() {
  if (!dev) throw error(404, 'Not found');
  if (!isRunning()) {
    await endCurrentRun();
    return json(status());
  }

  const child = processHandle;
  warming = false;
  child.kill('SIGINT');
  const exited = await waitForExit(child, STOP_TIMEOUT_MS);
  if (exited) await endCurrentRun();

  return json({ ...status(), stopping: !exited });
}
