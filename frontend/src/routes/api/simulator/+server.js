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

let processHandle = null;
let startedAt = null;
let lastExit = null;
let logTail = '';
let currentOptions = { ...defaultOptions };
let currentRunId = null;

function isRunning() {
  return processHandle !== null && processHandle.exitCode === null && !processHandle.killed;
}

function appendLog(chunk) {
  logTail = `${logTail}${chunk}`.slice(-4000);
}

function status() {
  return {
    running: isRunning(),
    startedAt,
    lastExit,
    logTail,
    options: currentOptions,
    currentRunId
  };
}

async function endCurrentRun() {
  if (!currentRunId) return;

  const runId = currentRunId;
  currentRunId = null;

  try {
    await fetch(`${API_BASE}/api/v1/runs/${runId}/end`, { method: 'PATCH' });
  } catch (e) {
    console.warn('[simulator] failed to end run:', e.message);
  }
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

function argsForOptions(options) {
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
    String(options.duplicateRate)
  ];

  if (options.shared) {
    args.push('--shared', '--connections', String(options.connections));
  }

  return args;
}

export async function POST({ request }) {
  if (isRunning()) return json(status());

  if (!existsSync(scriptPath)) {
    return json({ error: `Simulator script not found at ${scriptPath}` }, { status: 404 });
  }

  const body = await request.json().catch(() => ({}));
  currentOptions = normalizeOptions(body);

  const python = existsSync(venvPython) ? venvPython : 'python3';
  const child = spawn(python, argsForOptions(currentOptions), {
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
  });

  currentRunId = crypto.randomUUID();
  const labelParts = [
    `QoS ${currentOptions.qos}`,
    `${currentOptions.nodes} nodes`,
    `${currentOptions.interval}s interval`
  ];
  if (currentOptions.duration > 0) labelParts.push(`${currentOptions.duration}s duration`);
  const label = labelParts.join(' · ');
  try {
    await fetch(`${API_BASE}/api/v1/runs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        runId: currentRunId,
        label,
        qos: currentOptions.qos,
        nodeCount: currentOptions.nodes,
        intervalSec: currentOptions.interval,
        duplicateRate: currentOptions.duplicateRate
      })
    });
  } catch (e) {
    console.warn('[simulator] failed to register run:', e.message);
  }

  return json(status(), { status: 201 });
}

export async function DELETE() {
  if (!isRunning()) {
    await endCurrentRun();
    return json(status());
  }

  processHandle.kill('SIGINT');
  await endCurrentRun();

  return json({ ...status(), stopping: true });
}
