<script>
  import { store } from '$lib/telemetry.svelte.js';
  import { num } from '$lib/format.js';

  const m = $derived(store.metrics);
  const items = $derived([
    { k: 'received', v: m.messagesReceived.toLocaleString() },
    { k: 'saved', v: m.messagesSaved.toLocaleString() },
    { k: 'dupes skipped', v: m.duplicatesSkipped.toLocaleString(), warn: m.duplicatesSkipped > 0 },
    { k: 'throughput', v: num(m.throughput, 1), unit: 'msg/s', accent: true },
    { k: 'active', v: m.activeNodes },
    { k: 'offline', v: m.offlineNodes, warn: m.offlineNodes > 0 },
    { k: 'alerts', v: m.alertsCreated },
    { k: 'avg proc', v: num(m.avgProcessingMs, 1), unit: 'ms' }
  ]);
</script>

<section class="panel">
  <span class="eyebrow">ingestion metrics</span>
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
