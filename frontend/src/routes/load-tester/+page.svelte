<script>
  import { dev } from '$app/environment';
  import { onMount } from 'svelte';
  import TopBar from '$lib/components/TopBar.svelte';
  import RunsPanel from '$lib/components/RunsPanel.svelte';
  import VNodeTopology from '$lib/components/VNodeTopology.svelte';
  import { store } from '$lib/telemetry.svelte.js';
  import { num, timeAgo } from '$lib/format.js';

  /**
   * @typedef {Object} VNode
   * @property {string} nodeId
   * @property {string} [name]
   * @property {string} status
   * @property {number} [lastSeenAt]
   * @property {number|null} [rssi]
   * @property {number|null} [battery]
   * @property {string} [firmwareVersion]
   * @property {{ temperature?: number|null, humidity?: number|null, light?: number|null }} [latest]
   * @property {{ lastMessageAt?: number|null, throughput?: number }} [ingestion]
   *
   * @typedef {Object} LastExit
   * @property {number|null} code
   * @property {string|null} signal
   * @property {number} at
   * @property {string} [error]
   *
   * @typedef {Object} SimulatorOptions
   * @property {number} nodes
   * @property {number} interval
   * @property {number} duration
   * @property {number} qos
   * @property {boolean} shared
   * @property {number} connections
   * @property {number} duplicateRate
   *
   * @typedef {Object} SimulatorStatus
   * @property {boolean} [running]
   * @property {boolean} [draining]
   * @property {number|null} [startedAt]
   * @property {string|null} [currentRunId]
   * @property {string} [logTail]
   * @property {LastExit|null} [lastExit]
   * @property {SimulatorOptions} [options]
   *
   * @typedef {Object} TesterState
   * @property {boolean} running
   * @property {boolean} draining
   * @property {boolean} busy
   * @property {string} error
   * @property {number|null} startedAt
   * @property {string|null} currentRunId
   * @property {string} logTail
   * @property {LastExit|null} lastExit
   * @property {SimulatorOptions} options
   */

  /** @type {SimulatorOptions} */
  const defaultOptions = {
    nodes: 10,
    interval: 10,
    duration: 0,
    qos: 1,
    shared: false,
    connections: 5,
    duplicateRate: 0
  };

  /** @type {TesterState} */
  let tester = $state({
    running: false,
    draining: false,
    busy: false,
    error: '',
    startedAt: null,
    currentRunId: null,
    logTail: '',
    lastExit: null,
    options: { ...defaultOptions }
  });
  let simulatorOptionsLoaded = false;

  /** @param {VNode} node */
  function isVirtualNode(node) {
    return node.nodeId?.startsWith('vnode-');
  }

  /** @param {VNode} a @param {VNode} b */
  function compareNodeIds(a, b) {
    return a.nodeId.localeCompare(b.nodeId, undefined, { numeric: true });
  }

  /** @param {Array<number|null|undefined>} values */
  function avg(values) {
    const real = /** @type {number[]} */ (
      values.filter((value) => value !== null && value !== undefined && !Number.isNaN(value))
    );
    if (!real.length) return null;
    return real.reduce((total, value) => total + value, 0) / real.length;
  }

  /** @param {number} value */
  function pct(value) {
    return `${num(value * 100, 0)}%`;
  }

  /** @param {string|null} runId */
  function runIdLabel(runId) {
    return runId ? runId.slice(0, 8) : 'none';
  }

  /** @param {number} ms */
  function durationLabel(ms) {
    return `${Math.floor((ms || 0) / 1000)}s`;
  }

  const virtualNodes = /** @type {VNode[]} */ ($derived(store.nodes.filter(isVirtualNode).sort(compareNodeIds)));
  const onlineVirtualNodes = $derived(virtualNodes.filter((node) => node.status === 'online').length);
  const offlineVirtualNodes = $derived(virtualNodes.length - onlineVirtualNodes);
  const lastVirtualSeenAt = $derived(Math.max(0, ...virtualNodes.map((node) => node.lastSeenAt || 0)));
  const avgVirtualRssi = $derived(avg(virtualNodes.map((node) => node.rssi)));
  const avgVirtualBattery = $derived(avg(virtualNodes.map((node) => node.battery)));
  const expectedRate = $derived(
    tester.options.interval > 0 ? tester.options.nodes / tester.options.interval : 0
  );
  // Sum the live per-node throughput (the global store.metrics.throughput is
  // only computed by the mock feed, so it stays 0 against the real backend).
  // Skip nodes with no message in the last 5s so a stopped run reads 0.
  const observedRate = $derived(
    virtualNodes.reduce((total, node) => {
      const ingestion = node.ingestion;
      if (!ingestion?.lastMessageAt || store.now - ingestion.lastMessageAt > 5000) return total;
      return total + (ingestion.throughput ?? 0);
    }, 0)
  );
  const runAge = $derived(tester.startedAt ? store.now - tester.startedAt : 0);
  const summaryCards = $derived([
    { label: 'vnode matches', value: virtualNodes.length.toLocaleString() },
    { label: 'online', value: onlineVirtualNodes.toLocaleString(), accent: onlineVirtualNodes > 0 },
    { label: 'offline', value: offlineVirtualNodes.toLocaleString(), warn: offlineVirtualNodes > 0 },
    { label: 'last vnode seen', value: lastVirtualSeenAt ? timeAgo(lastVirtualSeenAt, store.now) : 'none' },
    { label: 'avg rssi', value: avgVirtualRssi == null ? 'none' : `${num(avgVirtualRssi, 0)} dBm` },
    { label: 'avg battery', value: avgVirtualBattery == null ? 'none' : `${num(avgVirtualBattery, 0)}%` },
    ...(dev ? [{ label: 'target rate', value: `${num(expectedRate, 1)} msg/s`, accent: tester.running }] : []),
    { label: 'observed rate', value: `${num(observedRate, 1)} msg/s`, accent: observedRate > 0 }
  ]);

  /** @param {SimulatorStatus} data */
  function applySimulatorStatus(data = {}) {
    tester.running = Boolean(data.running);
    tester.draining = Boolean(data.draining);
    tester.startedAt = data.startedAt ?? null;
    tester.currentRunId = data.currentRunId ?? null;
    tester.logTail = data.logTail ?? '';
    tester.lastExit = data.lastExit ?? null;
    tester.error = data.lastExit?.error ?? '';

    if (data.options && (!simulatorOptionsLoaded || data.running)) {
      Object.assign(tester.options, data.options);
      simulatorOptionsLoaded = true;
    }
  }

  async function refreshSimulator() {
    try {
      const res = await fetch('/api/simulator');
      if (!res.ok) return;
      applySimulatorStatus(await res.json());
    } catch {
      tester.running = false;
    }
  }

  async function toggleSimulator() {
    tester.busy = true;
    tester.error = '';

    try {
      const res = await fetch('/api/simulator', {
        method: tester.running ? 'DELETE' : 'POST',
        headers: tester.running ? undefined : { 'content-type': 'application/json' },
        body: tester.running ? undefined : JSON.stringify(tester.options)
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error ?? `Simulator request failed (${res.status})`);
      applySimulatorStatus(data);
      setTimeout(refreshSimulator, 800);
    } catch (error) {
      tester.error = error instanceof Error ? error.message : String(error);
    } finally {
      tester.busy = false;
    }
  }

  onMount(() => {
    if (!dev) return;
    refreshSimulator();
    const timer = setInterval(refreshSimulator, 3000);
    return () => clearInterval(timer);
  });
</script>

<svelte:head>
  <title>Nodemetry · load tester</title>
</svelte:head>

<main class="app">
  <TopBar />

  <section class="page-head">
    <div>
      <span class="eyebrow">load tester</span>
      <h2>Virtual node experiment</h2>
      <p class="mono">
        {virtualNodes.length.toLocaleString()} vnode-* node{virtualNodes.length === 1 ? '' : 's'} tracked
        <span class="sep">/</span>
        {dev ? `run ${runIdLabel(tester.currentRunId)}` : 'read-only view'}
      </p>
    </div>
    <a class="return-link mono" href="/">DASHBOARD</a>
  </section>

  <div class="tester-grid" class:viewer-only={!dev}>
    {#if dev}
      <section class="panel controls-panel">
        <div class="head">
          <span class="eyebrow">experiment controls</span>
          <span class="status mono" class:on={tester.running}>
            <span class="pip" class:live={tester.running}></span>
            {tester.running ? `RUNNING ${durationLabel(runAge)}` : tester.draining ? 'DRAINING' : 'IDLE'}
          </span>
        </div>

        <form class="control-grid" onsubmit={(event) => event.preventDefault()}>
          <label>
            <span class="eyebrow">nodes</span>
            <input
              class="mono"
              type="number"
              min="1"
              max="5000"
              step="1"
              disabled={tester.running || tester.draining || tester.busy}
              bind:value={tester.options.nodes}
            />
          </label>
          <label>
            <span class="eyebrow">interval sec</span>
            <input
              class="mono"
              type="number"
              min="1"
              max="3600"
              step="0.5"
              disabled={tester.running || tester.draining || tester.busy}
              bind:value={tester.options.interval}
            />
          </label>
          <label>
            <span class="eyebrow">duration sec</span>
            <input
              class="mono"
              type="number"
              min="0"
              max="86400"
              step="1"
              disabled={tester.running || tester.draining || tester.busy}
              bind:value={tester.options.duration}
            />
          </label>
          <label>
            <span class="eyebrow">qos</span>
            <select class="mono" disabled={tester.running || tester.draining || tester.busy} bind:value={tester.options.qos}>
              <option value={0}>0</option>
              <option value={1}>1</option>
              <option value={2}>2</option>
            </select>
          </label>
          <label>
            <span class="eyebrow">connections</span>
            <input
              class="mono"
              type="number"
              min="1"
              max="200"
              step="1"
              disabled={!tester.options.shared || tester.running || tester.draining || tester.busy}
              bind:value={tester.options.connections}
            />
          </label>
          <label>
            <span class="eyebrow">duplicate rate</span>
            <input
              class="mono"
              type="number"
              min="0"
              max="1"
              step="0.01"
              disabled={tester.running || tester.draining || tester.busy}
              bind:value={tester.options.duplicateRate}
            />
          </label>
          <label class="toggle-row">
            <span class="eyebrow">shared</span>
            <input type="checkbox" disabled={tester.running || tester.draining || tester.busy} bind:checked={tester.options.shared} />
          </label>
        </form>

        <div class="actions">
          <button class="action" class:on={tester.running} type="button" disabled={tester.busy || tester.draining} onclick={toggleSimulator}>
            <span class="pip" class:live={tester.running}></span>
            {tester.busy ? 'WORKING' : tester.draining ? 'DRAINING' : tester.running ? 'STOP TEST' : 'START TEST'}
          </button>
          <button class="secondary" type="button" disabled={tester.busy} onclick={refreshSimulator}>REFRESH</button>
        </div>

        {#if tester.error}
          <p class="error mono">{tester.error}</p>
        {/if}
        {#if tester.lastExit && !tester.error}
          <p class="exit mono">last exit: code {tester.lastExit.code ?? 'none'} / signal {tester.lastExit.signal ?? 'none'}</p>
        {/if}
        {#if tester.logTail}
          <pre class="log mono">{tester.logTail}</pre>
        {/if}
      </section>
    {/if}

    <section class="panel summary-panel">
      <div class="head">
        <span class="eyebrow">experiment snapshot</span>
        <span class="hint mono">{dev ? `duplicate target ${pct(tester.options.duplicateRate)}` : 'read only'}</span>
      </div>

      <div class="cards">
        {#each summaryCards as card (card.label)}
          <div class="cell">
            <span class="eyebrow">{card.label}</span>
            <span class="value mono" class:accent={card.accent} class:warn={card.warn}>{card.value}</span>
          </div>
        {/each}
      </div>
    </section>
  </div>

  <VNodeTopology
    nodes={virtualNodes}
    now={store.now}
    running={tester.running}
    expectedRate={expectedRate}
    observedRate={observedRate}
    showTarget={dev}
  />

  <div class="panel-bare"><RunsPanel /></div>
</main>

<style>
  .app {
    max-width: 1680px;
    margin: 0 auto;
    padding: 22px;
    display: flex;
    flex-direction: column;
    gap: var(--gap);
  }
  .page-head,
  .panel {
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: var(--radius);
  }
  .page-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 18px;
    padding: 18px 20px;
  }
  h2 {
    margin-top: 4px;
    font-size: 24px;
    line-height: 1.1;
  }
  .page-head p {
    margin: 6px 0 0;
    color: var(--text-dim);
    font-size: 12px;
  }
  .sep {
    margin: 0 8px;
    color: var(--text-faint);
  }
  .return-link,
  .action,
  .secondary {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-height: 38px;
    padding: 9px 15px;
    color: var(--text-dim);
    background: var(--bg-2);
    border: 1px solid var(--line);
    border-radius: 999px;
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.12em;
    text-decoration: none;
    white-space: nowrap;
  }
  .return-link:hover,
  .action:hover,
  .secondary:hover {
    border-color: var(--text-dim);
  }
  .tester-grid {
    display: grid;
    grid-template-columns: minmax(360px, 0.9fr) minmax(0, 1.3fr);
    gap: var(--gap);
    align-items: start;
  }
  .tester-grid.viewer-only {
    grid-template-columns: minmax(0, 1fr);
  }
  .panel {
    padding: 16px;
  }
  .head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 13px;
  }
  .hint {
    color: var(--text-faint);
    font-size: 11px;
  }
  .control-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }
  label {
    display: grid;
    gap: 6px;
    min-width: 0;
  }
  input,
  select {
    width: 100%;
    min-width: 0;
    padding: 8px 9px;
    color: var(--text);
    background: var(--bg-2);
    border: 1px solid var(--line);
    border-radius: 6px;
  }
  input:disabled,
  select:disabled {
    opacity: 0.55;
  }
  .toggle-row {
    align-content: end;
  }
  .toggle-row input {
    width: 18px;
    height: 18px;
    accent-color: var(--live);
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-top: 14px;
  }
  .action.on {
    color: var(--live);
    border-color: color-mix(in srgb, var(--live) 45%, var(--line));
  }
  .action:disabled,
  .secondary:disabled {
    cursor: wait;
    opacity: 0.7;
  }
  .cards {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 1px;
    overflow: hidden;
    background: var(--line);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
  }
  .cell {
    display: grid;
    gap: 7px;
    min-height: 78px;
    padding: 13px;
    background: var(--bg-2);
  }
  .value {
    color: var(--text);
    font-size: clamp(16px, 1.3vw, 22px);
    font-weight: 600;
    line-height: 1;
    font-variant-numeric: tabular-nums;
  }
  .value.accent {
    color: var(--ch-rssi);
  }
  .value.warn {
    color: var(--warn);
  }
  .status {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    color: var(--text-dim);
    font-size: 11.5px;
    text-transform: uppercase;
  }
  .status.on {
    color: var(--ch-rssi);
  }
  .pip {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--text-faint);
  }
  .pip.live {
    background: var(--live);
    box-shadow: 0 0 8px var(--live);
    animation: blink 1.8s var(--ease) infinite;
  }
  .error,
  .exit {
    margin: 12px 0 0;
    font-size: 11px;
    line-height: 1.4;
  }
  .error {
    color: var(--crit);
  }
  .exit {
    color: var(--text-faint);
  }
  .log {
    max-height: 150px;
    margin: 12px 0 0;
    padding: 10px;
    overflow: auto;
    color: var(--text-dim);
    background: var(--bg-2);
    border: 1px solid var(--line-soft);
    border-radius: 6px;
    font-size: 11px;
    line-height: 1.5;
    white-space: pre-wrap;
  }
  @keyframes blink {
    0%,
    100% {
      opacity: 1;
    }
    50% {
      opacity: 0.4;
    }
  }
  @media (max-width: 1180px) {
    .tester-grid {
      grid-template-columns: 1fr;
    }
    .cards {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
  @media (max-width: 760px) {
    .app {
      padding: 14px;
    }
    .page-head {
      align-items: flex-start;
      flex-direction: column;
    }
    .control-grid,
    .cards {
      grid-template-columns: 1fr;
    }
  }
</style>
