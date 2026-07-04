<script>
  import { store, acknowledgeAlert, selectedDashboardNode } from '$lib/telemetry.svelte.js';
  import { timeAgo } from '$lib/format.js';

  const node = $derived(selectedDashboardNode());
  const active = $derived(
    node ? store.alerts.filter((a) => !a.acknowledged && a.nodeId === node.nodeId) : []
  );
  const symbol = { critical: '✕', warning: '!', info: 'i' };
</script>

<section class="panel">
  <div class="head">
    <span class="eyebrow">active alerts</span>
    <span class="meta">
      <span class="node-label mono">{node?.nodeId ?? 'no node'}</span>
      <span class="count mono" class:hot={active.length > 0}>{active.length}</span>
    </span>
  </div>

  <div class="list" role="list">
    {#if !node}
      <p class="empty mono">No physical node selected.</p>
    {:else if active.length === 0}
      <p class="empty mono">All channels nominal for this node.</p>
    {/if}
    {#each active as a (a.id)}
      <article class="alert {a.severity}" role="listitem">
        <span class="badge" aria-hidden="true">{symbol[a.severity]}</span>
        <div class="body">
          <div class="top">
            <span class="node mono">{a.nodeId}</span>
            <span class="time mono">{timeAgo(a.createdAt, store.now)}</span>
          </div>
          <p class="msg">{a.message}</p>
        </div>
        <button class="ack mono" onclick={() => acknowledgeAlert(a.id)} aria-label="Acknowledge alert">
          ack
        </button>
      </article>
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
    gap: 12px;
    min-height: 0;
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .meta {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }
  .node-label {
    max-width: 180px;
    overflow: hidden;
    color: var(--text-faint);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .count {
    font-size: 13px;
    font-weight: 600;
    color: var(--text-dim);
    background: var(--bg-2);
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 1px 9px;
  }
  .count.hot {
    color: var(--crit);
    border-color: color-mix(in srgb, var(--crit) 45%, var(--line));
  }
  .list {
    display: flex;
    flex-direction: column;
    gap: 9px;
    overflow-y: auto;
    max-height: 320px;
  }
  .empty {
    font-size: 12.5px;
    color: var(--text-faint);
    padding: 14px 4px;
    margin: 0;
  }
  .alert {
    display: grid;
    grid-template-columns: auto 1fr auto;
    align-items: center;
    gap: 11px;
    padding: 10px 11px;
    border-radius: var(--radius-sm);
    background: var(--bg-2);
    border: 1px solid var(--line);
    border-left-width: 3px;
  }
  .alert.critical {
    border-left-color: var(--crit);
  }
  .alert.warning {
    border-left-color: var(--warn);
  }
  .alert.info {
    border-left-color: var(--ch-humid);
  }
  .badge {
    width: 22px;
    height: 22px;
    display: grid;
    place-items: center;
    border-radius: 6px;
    font-family: var(--mono);
    font-size: 12px;
    font-weight: 700;
    color: var(--bg);
  }
  .critical .badge {
    background: var(--crit);
  }
  .warning .badge {
    background: var(--warn);
  }
  .info .badge {
    background: var(--ch-humid);
  }
  .top {
    display: flex;
    justify-content: space-between;
    gap: 8px;
  }
  .node {
    font-size: 11.5px;
    color: var(--text-dim);
  }
  .time {
    font-size: 11px;
    color: var(--text-faint);
  }
  .msg {
    margin: 2px 0 0;
    font-size: 13px;
    color: var(--text);
    line-height: 1.35;
  }
  .ack {
    align-self: stretch;
    padding: 0 11px;
    font-size: 11px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--text-dim);
    border: 1px solid var(--line);
    border-radius: 6px;
    background: var(--panel);
    transition: color 0.15s, border-color 0.15s;
  }
  .ack:hover {
    color: var(--text);
    border-color: var(--text-dim);
  }
</style>
