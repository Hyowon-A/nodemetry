<script>
  import { selectedNode, store } from '$lib/telemetry.svelte.js';
  import { num, timeAgo } from '$lib/format.js';

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

  function nodeAccent(n) {
    if (!n) return 'var(--text-faint)';
    return n.status === 'online' ? rssiColor(n.rssi) : 'var(--crit)';
  }

  function nodeSubtitle(n) {
    return [n.location, n.nodeId].filter((value) => value && value !== n.name).join(' / ');
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

<section
  class="panel"
  style:--node-color={nodeAccent(node)}
>
  {#if node}
    {@const subtitle = nodeSubtitle(node)}
    <div class="row">
      <div class="identity">
        <div class="identity-top">
          <span class="eyebrow">node details</span>
          <span class="status" class:on={node.status === 'online'}>
            <span class="led"></span>{node.status}
          </span>
        </div>

        <div class="identity-main">
          <div>
            <h2>{node.name}</h2>
            {#if subtitle}
              <p class="subtitle mono">{subtitle}</p>
            {/if}
          </div>
        </div>
      </div>

      <div class="grid">
        <div class="detail last-seen-card">
          <span class="eyebrow">last seen</span>
          <span class="content metric-value mono last-seen-value">{timeAgo(node.lastSeenAt, store.now)}</span>
        </div>
        <div class="detail" style:--accent={rssiColor(node.rssi)}>
          <span class="eyebrow">signal</span>
          <span class="content metric">
            {@render signal(node.rssi)}
            <span class="metric-value mono">
              {node.rssi ?? '—'}{#if node.rssi != null}<small>dBm</small>{/if}
            </span>
          </span>
        </div>
        <div class="detail" style:--accent={batteryColor(node.battery)}>
          <span class="eyebrow">battery</span>
          <span class="content metric">
            {@render battery(node.battery)}
            <span class="metric-value mono">
              {num(node.battery, 0)}{#if node.battery != null}<small>%</small>{/if}
            </span>
          </span>
        </div>
        <div class="detail firmware-card">
          <span class="eyebrow">firmware</span>
          <span class="content metric-value mono">{node.firmwareVersion}</span>
        </div>
      </div>
    </div>
  {:else}
    <p class="empty mono">No node selected.</p>
  {/if}
</section>

<style>
  .panel {
    padding: 14px;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background:
      radial-gradient(80% 120% at 12% 0%, color-mix(in srgb, var(--node-color) 10%, transparent), transparent 58%),
      linear-gradient(180deg, color-mix(in srgb, var(--panel-2) 70%, transparent), var(--panel));
  }
  .row {
    display: grid;
    grid-template-columns: minmax(260px, 0.82fr) minmax(0, 1.8fr);
    align-items: stretch;
    gap: 12px;
  }
  .identity {
    display: grid;
    grid-template-rows: 24px minmax(0, 1fr);
    min-width: 0;
    min-height: 122px;
    gap: 10px;
    padding: 18px;
    border: 1px solid color-mix(in srgb, var(--node-color) 26%, var(--line));
    border-radius: var(--radius-sm);
    background:
      linear-gradient(135deg, color-mix(in srgb, var(--node-color) 8%, transparent), transparent 58%),
      var(--bg-2);
  }
  .eyebrow {
    letter-spacing: 0;
  }
  .identity-top,
  .identity-main {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .identity-top {
    align-self: start;
  }
  .identity-main {
    align-self: center;
  }
  h2 {
    color: var(--text);
    font-size: 24px;
    line-height: 1.05;
    white-space: nowrap;
  }
  .subtitle {
    margin: 7px 0 0;
    color: var(--text-faint);
    font-size: 11px;
  }
  .status {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    color: var(--text-dim);
    font-family: var(--mono);
    font-size: 11.5px;
    letter-spacing: 0;
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
    grid-template-columns: repeat(4, minmax(140px, 1fr));
    gap: 12px;
    min-width: 0;
  }
  .detail {
    position: relative;
    display: grid;
    grid-template-rows: 24px minmax(0, 1fr);
    min-width: 0;
    min-height: 122px;
    gap: 10px;
    padding: 18px;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background:
      linear-gradient(180deg, color-mix(in srgb, var(--accent) 9%, transparent), transparent 62%),
      var(--bg-2);
  }
  .detail::before {
    content: '';
    position: absolute;
    inset: 0 auto 0 0;
    width: 3px;
    background: var(--accent);
    box-shadow: 0 0 12px var(--accent);
  }
  .firmware-card {
    --accent: var(--text-dim);
  }
  .last-seen-card {
    --accent: var(--ch-rssi);
  }
  .detail > .eyebrow {
    display: block;
    align-self: start;
  }
  .content {
    min-height: 42px;
    margin-top: 0;
    align-self: center;
  }
  .metric {
    display: grid;
    grid-template-columns: 44px minmax(0, max-content);
    align-items: center;
    color: var(--text);
    font-variant-numeric: tabular-nums;
  }
  .metric-value {
    display: flex;
    align-items: center;
    color: var(--text);
    font-size: 23px;
    line-height: 1;
    font-variant-numeric: tabular-nums;
  }
  .last-seen-value {
    font-size: clamp(15px, 1.2vw, 20px);
    white-space: nowrap;
  }
  small {
    margin-left: 3px;
    color: var(--text-faint);
    font-size: 10px;
  }
  .signal {
    display: inline-flex;
    align-items: flex-end;
    justify-content: flex-start;
    gap: 2px;
    width: 44px;
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
    justify-content: flex-start;
    width: 44px;
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
  @media (max-width: 900px) {
    .row {
      grid-template-columns: 1fr;
    }
    .grid {
      grid-template-columns: repeat(2, minmax(140px, 1fr));
    }
  }
  @media (max-width: 640px) {
    .grid {
      grid-template-columns: 1fr;
    }
    .identity-main,
    .identity-top {
      align-items: flex-start;
      flex-direction: column;
    }
    h2 {
      white-space: normal;
    }
  }
</style>
