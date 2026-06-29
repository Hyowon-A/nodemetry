<script>
  import { onMount } from 'svelte';
  import { store, toggleFeed } from '$lib/telemetry.svelte.js';
  import { clock, uptime } from '$lib/format.js';

  let simulator = $state({
    running: false,
    busy: false,
    error: '',
    panelOpen: false,
    options: {
      nodes: 10,
      interval: 10,
      duration: 0,
      qos: 1,
      shared: false,
      connections: 5,
      duplicateRate: 0
    }
  });
  let simulatorOptionsLoaded = false;

  function applySimulatorStatus(data) {
    simulator.running = data.running;
    simulator.error = data.lastExit?.error ?? '';

    if (data.options && (!simulatorOptionsLoaded || data.running)) {
      Object.assign(simulator.options, data.options);
      simulatorOptionsLoaded = true;
    }
  }

  async function refreshSimulator() {
    try {
      const res = await fetch('/api/simulator');
      if (!res.ok) return;
      const data = await res.json();
      applySimulatorStatus(data);
    } catch {
      simulator.running = false;
    }
  }

  async function toggleSimulator() {
    simulator.busy = true;
    simulator.error = '';

    try {
      const res = await fetch('/api/simulator', {
        method: simulator.running ? 'DELETE' : 'POST',
        headers: simulator.running ? undefined : { 'content-type': 'application/json' },
        body: simulator.running ? undefined : JSON.stringify(simulator.options)
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error ?? `Simulator request failed (${res.status})`);
      applySimulatorStatus(data);
      setTimeout(refreshSimulator, 800);
    } catch (error) {
      simulator.error = error.message;
    } finally {
      simulator.busy = false;
    }
  }

  onMount(() => {
    refreshSimulator();
    const timer = setInterval(refreshSimulator, 3000);
    return () => clearInterval(timer);
  });
</script>

<header class="bar">
  <div class="brand">
    <svg class="glyph" viewBox="0 0 32 22" aria-hidden="true">
      <path
        d="M2 16 L9 16 L12 4 L16 20 L19 9 L22 16 L30 16"
        fill="none"
        stroke="var(--ch-rssi)"
        stroke-width="2"
        stroke-linejoin="round"
        stroke-linecap="round"
      />
    </svg>
    <div class="title">
      <h1>NODEMETRY</h1>
      <span class="eyebrow">live signal monitor</span>
    </div>
  </div>

  <div class="broker mono" title="MQTT subscription">
    <span class="dot" class:live={store.connected}></span>
    {store.broker}
  </div>

  <div class="meta">
    <div class="stat">
      <span class="eyebrow">uptime</span>
      <span class="mono val">{uptime(store.now - store.startedAt)}</span>
    </div>
    <div class="stat">
      <span class="eyebrow">node time</span>
      <span class="mono val">{clock(store.now)}</span>
    </div>
    <div class="sim-control">
      <button
        class="feed"
        class:on={simulator.panelOpen}
        disabled={simulator.busy}
        title="Simulator options"
        onclick={() => (simulator.panelOpen = !simulator.panelOpen)}
      >
        SIM CFG
      </button>
      <button
        class="feed sim"
        class:on={simulator.running}
        disabled={simulator.busy}
        title={simulator.error || 'Run Python simulator'}
        onclick={toggleSimulator}
      >
        <span class="pip" class:live={simulator.running}></span>
        {simulator.busy ? '...' : simulator.running ? 'SIM ON' : 'RUN SIM'}
      </button>

      {#if simulator.panelOpen}
        <form class="sim-panel" onsubmit={(event) => event.preventDefault()}>
          <label>
            <span class="eyebrow">nodes</span>
            <input
              class="mono"
              type="number"
              min="1"
              max="5000"
              step="1"
              disabled={simulator.running || simulator.busy}
              bind:value={simulator.options.nodes}
            />
          </label>
          <label>
            <span class="eyebrow">interval</span>
            <input
              class="mono"
              type="number"
              min="1"
              max="3600"
              step="0.5"
              disabled={simulator.running || simulator.busy}
              bind:value={simulator.options.interval}
            />
          </label>
          <label>
            <span class="eyebrow">duration</span>
            <input
              class="mono"
              type="number"
              min="0"
              max="86400"
              step="1"
              disabled={simulator.running || simulator.busy}
              bind:value={simulator.options.duration}
            />
          </label>
          <label>
            <span class="eyebrow">qos</span>
            <select class="mono" disabled={simulator.running || simulator.busy} bind:value={simulator.options.qos}>
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
              disabled={!simulator.options.shared || simulator.running || simulator.busy}
              bind:value={simulator.options.connections}
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
              disabled={simulator.running || simulator.busy}
              bind:value={simulator.options.duplicateRate}
            />
          </label>
          <label class="toggle-row">
            <span class="eyebrow">shared</span>
            <input
              type="checkbox"
              disabled={simulator.running || simulator.busy}
              bind:checked={simulator.options.shared}
            />
          </label>
          {#if simulator.error}
            <p class="sim-error mono">{simulator.error}</p>
          {/if}
        </form>
      {/if}
    </div>
    <button class="feed" class:on={store.connected} onclick={toggleFeed}>
      <span class="pip" class:live={store.connected}></span>
      {store.connected ? 'LIVE' : 'PAUSED'}
    </button>
  </div>
</header>

<style>
  .bar {
    display: flex;
    align-items: center;
    gap: 24px;
    padding: 16px 22px;
    background: linear-gradient(180deg, var(--panel-2), var(--panel));
    border: 1px solid var(--line);
    border-radius: var(--radius);
    flex-wrap: wrap;
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 13px;
  }
  .glyph {
    width: 38px;
    height: 26px;
    filter: drop-shadow(0 0 6px rgba(87, 224, 138, 0.5));
  }
  .title h1 {
    font-family: var(--mono);
    font-size: 19px;
    letter-spacing: 0.16em;
    line-height: 1;
  }
  .title .eyebrow {
    display: block;
    margin-top: 3px;
  }
  .broker {
    display: flex;
    align-items: center;
    gap: 9px;
    font-size: 12px;
    color: var(--text-dim);
    padding: 7px 13px;
    background: var(--bg-2);
    border: 1px solid var(--line);
    border-radius: 999px;
    margin-right: auto;
  }
  .dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--text-faint);
  }
  .dot.live {
    background: var(--live);
    box-shadow: 0 0 8px var(--live);
    animation: blink 1.8s var(--ease) infinite;
  }
  .meta {
    display: flex;
    align-items: center;
    gap: 22px;
  }
  .sim-control {
    position: relative;
    display: inline-flex;
    gap: 8px;
  }
  .stat {
    display: flex;
    flex-direction: column;
    gap: 2px;
    text-align: right;
  }
  .val {
    font-size: 15px;
    font-weight: 500;
    color: var(--text);
    font-variant-numeric: tabular-nums;
  }
  .feed {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.12em;
    padding: 9px 15px;
    border-radius: 999px;
    border: 1px solid var(--line);
    background: var(--bg-2);
    color: var(--text-dim);
    transition: border-color 0.2s, color 0.2s;
  }
  .feed.on {
    color: var(--live);
    border-color: color-mix(in srgb, var(--live) 45%, var(--line));
  }
  .feed:hover {
    border-color: var(--text-dim);
  }
  .feed:disabled {
    cursor: wait;
    opacity: 0.7;
  }
  .sim-panel {
    position: absolute;
    top: calc(100% + 10px);
    right: 0;
    z-index: 20;
    width: min(360px, calc(100vw - 28px));
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
    padding: 14px;
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    box-shadow: 0 16px 40px rgba(0, 0, 0, 0.35);
  }
  .sim-panel label {
    display: grid;
    gap: 6px;
    min-width: 0;
  }
  .sim-panel input,
  .sim-panel select {
    min-width: 0;
    width: 100%;
    padding: 8px 9px;
    color: var(--text);
    background: var(--bg-2);
    border: 1px solid var(--line);
    border-radius: 6px;
  }
  .sim-panel input:disabled,
  .sim-panel select:disabled {
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
  .sim-error {
    grid-column: 1 / -1;
    margin: 0;
    color: var(--crit);
    font-size: 11px;
    line-height: 1.4;
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
  @keyframes blink {
    0%,
    100% {
      opacity: 1;
    }
    50% {
      opacity: 0.4;
    }
  }
  @media (max-width: 640px) {
    .broker {
      order: 3;
      margin-right: 0;
      width: 100%;
    }
    .meta {
      gap: 16px;
    }
  }
</style>
