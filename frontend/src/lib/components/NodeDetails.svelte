<script>
  import { selectedNode } from '$lib/telemetry.svelte.js';
  import { num } from '$lib/format.js';

  const node = $derived(selectedNode());

  function bars(rssi) {
    if (rssi == null) return 0;
    if (rssi >= -55) return 4;
    if (rssi >= -65) return 3;
    if (rssi >= -75) return 2;
    if (rssi >= -85) return 1;
    return 0;
  }

  function rssiColor(rssi) {
    const strength = bars(rssi);
    return strength >= 3 ? 'var(--ch-rssi)' : strength === 2 ? 'var(--warn)' : 'var(--crit)';
  }

  function batteryColor(value) {
    if (value == null) return 'var(--text-faint)';
    return value > 40 ? 'var(--ch-rssi)' : value > 20 ? 'var(--warn)' : 'var(--crit)';
  }
</script>

{#snippet signal(rssi)}
  <span class="signal" style:--c={rssiColor(rssi)} aria-hidden="true">
    {#each [1, 2, 3, 4] as level}
      <span class="bar" class:lit={bars(rssi) >= level} style:height="{level * 3 + 2}px"></span>
    {/each}
  </span>
{/snippet}

{#snippet battery(value)}
  <span class="battery" style:--c={batteryColor(value)} aria-hidden="true">
    <span class="cell">
      <span class="level" style:width="{value == null ? 0 : Math.max(4, Math.min(100, value))}%"></span>
    </span>
    <span class="nub"></span>
  </span>
{/snippet}

<section class="panel">
  {#if node}
    <div class="row">
      <div class="identity">
        <span class="eyebrow">node details</span>
        <div class="content identity-row">
          <h2>{node.name}</h2>
          <span class="status" class:on={node.status === 'online'}>
            <span class="led"></span>{node.status}
          </span>
        </div>
      </div>

      <div class="grid">
        <div class="detail">
          <span class="eyebrow">signal</span>
          <span class="content value">
            {@render signal(node.rssi)}
            <span class="mono">{node.rssi ?? '—'}<small>dBm</small></span>
          </span>
        </div>
        <div class="detail">
          <span class="eyebrow">battery</span>
          <span class="content value">
            {@render battery(node.battery)}
            <span class="mono">{num(node.battery, 0)}%</span>
          </span>
        </div>
        <div class="detail">
          <span class="eyebrow">temperature</span>
          <span class="content reading mono temp">{num(node.latest.temperature, 1)}<small>°C</small></span>
        </div>
        <div class="detail">
          <span class="eyebrow">humidity</span>
          <span class="content reading mono humid">{num(node.latest.humidity, 0)}<small>%</small></span>
        </div>
        <div class="detail">
          <span class="eyebrow">co₂</span>
          <span class="content reading mono co2">{num(node.latest.co2, 0)}<small>ppm</small></span>
        </div>
        <div class="detail">
          <span class="eyebrow">light</span>
          <span class="content reading mono light">{num(node.latest.light, 0)}<small>lux</small></span>
        </div>
        <div class="detail">
          <span class="eyebrow">firmware</span>
          <span class="content reading mono">{node.firmwareVersion}</span>
        </div>
      </div>
    </div>
  {:else}
    <p class="empty mono">No node selected.</p>
  {/if}
</section>

<style>
  .panel {
    padding: 18px;
    overflow-x: auto;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--panel);
  }
  .identity {
    flex: 0 0 230px;
    padding: 14px 16px;
  }
  .row {
    display: flex;
    align-items: stretch;
    max-width: 1050px;
    gap: 50px;
  }
  h2 {
    color: var(--text);
    font-size: 17px;
    white-space: nowrap;
  }
  .status {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    color: var(--text-dim);
    font-family: var(--mono);
    font-size: 11.5px;
    letter-spacing: 0.05em;
    text-transform: uppercase;
  }
  .status.on {
    color: var(--ch-rssi);
  }
  .led {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--text-faint);
  }
  .status.on .led {
    background: var(--ch-rssi);
    box-shadow: 0 0 7px var(--ch-rssi);
  }
  .grid {
    display: grid;
    flex: 1;
    grid-template-columns:
        120px  /* signal */
        120px  /* battery */
        130px  /* temperature */
        120px  /* humidity */
        120px  /* CO₂ */
        120px  /* light */
        160px; /* firmware */
    gap: 1px;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--line);
  }
  .detail {
    min-width: 0;
    padding: 14px 16px;
    background: var(--bg-2);
  }
  .identity > .eyebrow,
  .detail > .eyebrow {
    display: block;
  }
  .content {
    min-height: 24px;
    margin-top: 9px;
  }
  .identity-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .value {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--text);
    font-size: 17px;
    font-variant-numeric: tabular-nums;
  }
  .reading {
    display: flex;
    align-items: center;
    color: var(--text);
    font-size: 17px;
    font-variant-numeric: tabular-nums;
  }
  .reading.temp {
    color: var(--ch-temp);
  }
  .reading.humid {
    color: var(--ch-humid);
  }
  .reading.co2 {
    color: var(--ch-co2);
  }
  .reading.light {
    color: var(--ch-light);
  }
  small {
    margin-left: 3px;
    color: var(--text-faint);
    font-size: 10px;
  }
  .signal {
    display: inline-flex;
    align-items: flex-end;
    gap: 2px;
    height: 16px;
  }
  .bar {
    width: 3px;
    border-radius: 1px;
    background: var(--line);
  }
  .bar.lit {
    background: var(--c);
  }
  .battery {
    display: inline-flex;
    align-items: center;
  }
  .cell {
    display: block;
    width: 26px;
    height: 13px;
    padding: 1.5px;
    border: 1.5px solid var(--c);
    border-radius: 3px;
  }
  .level {
    display: block;
    height: 100%;
    border-radius: 1px;
    background: var(--c);
  }
  .nub {
    width: 2px;
    height: 6px;
    margin-left: 1px;
    border-radius: 0 2px 2px 0;
    background: var(--c);
  }
  .empty {
    margin: 0;
    color: var(--text-faint);
    font-size: 12px;
  }
</style>
