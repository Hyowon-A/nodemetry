<script>
  import { store, selectNode, dashboardNodes, selectedDashboardNode } from '$lib/telemetry.svelte.js';
  import { timeAgo } from '$lib/format.js';

  const nodes = $derived(dashboardNodes());
  const selectedNodeId = $derived(selectedDashboardNode()?.nodeId ?? nodes[0]?.nodeId ?? null);
</script>

<section class="panel">
  <div class="head">
    <span class="eyebrow">node fleet</span>
    <span class="hint mono">select a node</span>
  </div>

  {#if nodes.length === 0}
    <p class="empty mono">No physical nodes found.</p>
  {:else}
    <div class="scroll">
      <table>
        <thead>
          <tr>
            <th>node</th>
            <th>status</th>
            <th>last seen</th>
          </tr>
        </thead>
        <tbody>
          {#each nodes as node (node.nodeId)}
            <tr
              class:offline={node.status !== 'online'}
              class:selected={selectedNodeId === node.nodeId}
              onclick={() => selectNode(node.nodeId)}
              tabindex="0"
              onkeydown={(event) =>
                (event.key === 'Enter' || event.key === ' ') &&
                (event.preventDefault(), selectNode(node.nodeId))}
            >
              <td>
                <div class="name">{node.name}</div>
                <div class="id mono">{node.nodeId}</div>
              </td>
              <td>
                <span class="status" class:on={node.status === 'online'}>
                  <span class="led"></span>{node.status}
                </span>
              </td>
              <td class="mono dim">{timeAgo(node.lastSeenAt, store.now)}</td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  {/if}
</section>

<style>
  .panel {
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    padding: 16px;
  }
  .head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 10px;
    margin-bottom: 12px;
  }
  .hint {
    color: var(--text-faint);
    font-size: 11px;
  }
  .scroll {
    overflow-x: auto;
  }
  .empty {
    margin: 0;
    color: var(--text-faint);
    font-size: 12.5px;
    padding: 10px 2px 2px;
  }
  table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13.5px;
  }
  th {
    padding: 0 12px 10px;
    border-bottom: 1px solid var(--line);
    color: var(--text-faint);
    font-family: var(--mono);
    font-size: 10.5px;
    font-weight: 600;
    letter-spacing: 0.12em;
    text-align: left;
    text-transform: uppercase;
    white-space: nowrap;
  }
  td {
    padding: 15px 12px;
    border-bottom: 1px solid var(--line-soft);
    vertical-align: middle;
    white-space: nowrap;
  }
  tbody tr {
    cursor: pointer;
    transition: background 0.15s;
  }
  tbody tr:hover {
    background: var(--bg-2);
  }
  tbody tr.selected {
    background: color-mix(in srgb, var(--ch-rssi) 10%, transparent);
    box-shadow: inset 3px 0 0 var(--ch-rssi);
  }
  tbody tr.offline {
    opacity: 0.5;
  }
  .name {
    color: var(--text);
    font-weight: 500;
  }
  .id {
    margin-top: 1px;
    color: var(--text-faint);
    font-size: 11px;
  }
  .dim {
    color: var(--text-dim);
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
  @media (max-width: 520px) {
    .hint {
      display: none;
    }
    th,
    td {
      padding-right: 9px;
      padding-left: 9px;
    }
  }
</style>
