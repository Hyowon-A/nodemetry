<script>
  import { store, selectedDashboardNode } from '$lib/telemetry.svelte.js';
  import { num } from '$lib/format.js';

  const node = $derived(selectedDashboardNode());
  const metrics = $derived(node?.ingestion ?? {});
  const activeAlerts = $derived(
    node ? store.alerts.filter((alert) => !alert.acknowledged && alert.nodeId === node.nodeId).length : 0
  );
  const throughput = $derived(
    node?.ingestion?.lastMessageAt && store.now - node.ingestion.lastMessageAt <= 5000
      ? node.ingestion.throughput
      : 0
  );
  const items = $derived([
    { k: 'received', v: (metrics.messagesReceived ?? 0).toLocaleString() },
    { k: 'saved', v: (metrics.messagesSaved ?? 0).toLocaleString() },
    {
      k: 'dupes skipped',
      v: (metrics.duplicatesSkipped ?? 0).toLocaleString(),
      warn: (metrics.duplicatesSkipped ?? 0) > 0
    },
    { k: 'throughput', v: num(throughput, 1), unit: 'msg/s', accent: throughput > 0 },
    { k: 'status', v: node?.status ?? 'none', accent: node?.status === 'online', warn: node?.status === 'offline' },
    { k: 'history', v: (node?.history?.t.length ?? 0).toLocaleString() },
    { k: 'alerts', v: activeAlerts, warn: activeAlerts > 0 },
    { k: 'avg proc', v: num(metrics.avgProcessingMs ?? 0, 1), unit: 'ms' }
  ]);
</script>

<section class="panel">
  <div class="head">
    <span class="eyebrow">ingestion metrics</span>
    <span class="hint mono">{node?.nodeId ?? 'no node'}</span>
  </div>
  <div class="grid">
    {#each items as it (it.k)}
      <div class="cell">
        <span class="k eyebrow">{it.k}</span>
        <span class="v mono" class:accent={it.accent} class:warn={it.warn}>
          {it.v}{#if it.unit}<span class="u">{it.unit}</span>{/if}
        </span>
      </div>
    {/each}
  </div>
</section>

<style>
  .panel {
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    padding: 16px;
    display: flex;
    flex-direction: column;
    gap: 13px;
  }
  .head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 10px;
  }
  .hint {
    color: var(--text-faint);
    font-size: 11px;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 1px;
    background: var(--line);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    overflow: hidden;
  }
  .cell {
    display: flex;
    flex-direction: column;
    gap: 5px;
    padding: 12px 13px;
    background: var(--bg-2);
  }
  .v {
    font-size: 19px;
    font-weight: 600;
    color: var(--text);
    line-height: 1;
    font-variant-numeric: tabular-nums;
  }
  .v.accent {
    color: var(--ch-rssi);
  }
  .v.warn {
    color: var(--warn);
  }
  .u {
    font-size: 10.5px;
    color: var(--text-dim);
    margin-left: 3px;
    font-weight: 500;
  }
</style>
