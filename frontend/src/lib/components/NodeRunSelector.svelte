<script>
  import { onMount, untrack } from 'svelte';
  import { env } from '$env/dynamic/public';
  import {
    selectedDashboardNode,
    selectedRunIdForNode,
    selectNodeRun
  } from '$lib/telemetry.svelte.js';
  import { loadNodeHistory } from '$lib/live.svelte.js';
  import { fetchNodeRunIds, runStore, shortRunId } from '$lib/runs.svelte.js';

  const liveBackend = env.PUBLIC_USE_MOCK === 'false';
  const node = $derived(selectedDashboardNode());
  const selectedRunId = $derived(selectedRunIdForNode(node?.nodeId));
  const runIds = $derived(node ? (runStore.nodeRunIdsByNodeId[node.nodeId] ?? []) : []);
  const runsLoading = $derived(node ? !!runStore.nodeLoadingByNodeId[node.nodeId] : false);
  const runsError = $derived(node ? (runStore.nodeErrorsByNodeId[node.nodeId] ?? '') : '');

  let timer;
  let historyLoading = $state(false);
  let historyError = $state('');
  let historyCount = $state(null);
  let lastHistoryKey = '';
  let lastRunIdsNodeId = '';
  let historyRequest = 0;

  onMount(() => {
    if (!liveBackend) return;

    timer = setInterval(() => {
      if (node) fetchNodeRunIds(node.nodeId);
    }, 30000);
    return () => clearInterval(timer);
  });

  async function refreshHistory(nodeId, runId) {
    const request = ++historyRequest;
    historyLoading = true;
    historyError = '';

    try {
      const count = await loadNodeHistory(nodeId, runId);
      if (request !== historyRequest) return;
      historyCount = count;
    } catch (error) {
      if (request !== historyRequest) return;
      historyError = error.message;
      historyCount = null;
    } finally {
      if (request === historyRequest) historyLoading = false;
    }
  }

  function handleRunChange(event) {
    if (!node) return;
    const runId = event.currentTarget.value || null;
    const key = `${node.nodeId}:${runId ?? ''}`;
    selectNodeRun(node.nodeId, runId);
    lastHistoryKey = key;
    void refreshHistory(node.nodeId, runId);
  }

  function optionLabel(runId) {
    return runId;
  }

  $effect(() => {
    if (!liveBackend || !node) return;

    const key = `${node.nodeId}:${selectedRunId ?? ''}`;
    if (!selectedRunId) {
      if (key !== lastHistoryKey) {
        historyRequest++;
        historyLoading = false;
        historyError = '';
        historyCount = null;
        lastHistoryKey = key;
      }
      return;
    }

    if (key === lastHistoryKey) return;
    lastHistoryKey = key;
    void refreshHistory(node.nodeId, selectedRunId);
  });

  $effect(() => {
    if (!liveBackend || !node) return;
    const nodeId = node.nodeId;
    if (nodeId === lastRunIdsNodeId) return;
    lastRunIdsNodeId = nodeId;
    untrack(() => {
      void fetchNodeRunIds(nodeId);
    });
  });

  $effect(() => {
    if (!node || !selectedRunId || runIds.length === 0 || runIds.includes(selectedRunId)) return;
    selectNodeRun(node.nodeId, null);
    lastHistoryKey = `${node.nodeId}:`;
    void refreshHistory(node.nodeId, null);
  });
</script>

<section class="panel">
  <div class="head">
    <div>
      <span class="eyebrow">run id</span>
      <span class="node mono">{node?.nodeId ?? 'no node'}</span>
    </div>

    <span class="hint mono">
      {#if !liveBackend}
        mock feed
      {:else if runsLoading && runIds.length === 0}
        loading run ids
      {:else if historyLoading}
        loading readings
      {:else if historyError}
        {historyError}
      {:else if runsError && runIds.length === 0}
        {runsError}
      {:else if selectedRunId && historyCount === 0}
        no readings for node
      {:else if selectedRunId}
        {historyCount ?? 0} point{historyCount === 1 ? '' : 's'}
      {:else}
        {runIds.length} run id{runIds.length === 1 ? '' : 's'}
      {/if}
    </span>
  </div>

  <label class="control">
    <span class="label mono">selected run</span>
    <span class="select-wrap">
      <select
        value={selectedRunId ?? ''}
        disabled={!liveBackend || !node || runIds.length === 0}
        aria-label="Select run id for {node?.nodeId ?? 'current node'}"
        onchange={handleRunChange}
      >
        <option value="">Live / recent readings</option>
        {#each runIds as runId (runId)}
          <option value={runId}>{optionLabel(runId)}</option>
        {/each}
      </select>
    </span>
  </label>

  {#if selectedRunId}
    <p class="selected mono">showing {shortRunId(selectedRunId)} for this node</p>
  {/if}
</section>

<style>
  .panel {
    display: grid;
    gap: 12px;
    padding: 14px 16px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background:
      linear-gradient(135deg, color-mix(in srgb, var(--ch-rssi) 6%, transparent), transparent 52%),
      var(--panel);
  }
  .head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
    min-width: 0;
  }
  .head > div {
    display: flex;
    align-items: baseline;
    gap: 10px;
    min-width: 0;
  }
  .node,
  .hint {
    color: var(--text-faint);
    font-size: 11px;
  }
  .node,
  .hint,
  .selected {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .control {
    display: grid;
    grid-template-columns: max-content minmax(0, 1fr);
    align-items: center;
    gap: 12px;
  }
  .label {
    color: var(--text-dim);
    font-size: 11px;
    text-transform: uppercase;
  }
  .select-wrap {
    position: relative;
    min-width: 0;
  }
  .select-wrap::after {
    content: '';
    position: absolute;
    top: 50%;
    right: 12px;
    width: 7px;
    height: 7px;
    pointer-events: none;
    border-right: 1px solid var(--text-dim);
    border-bottom: 1px solid var(--text-dim);
    transform: translateY(-65%) rotate(45deg);
  }
  select {
    width: 100%;
    min-width: 0;
    height: 38px;
    padding: 0 34px 0 12px;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    appearance: none;
    background: var(--bg-2);
    color: var(--text);
    font-family: var(--mono);
    font-size: 12px;
  }
  select:disabled {
    cursor: not-allowed;
    color: var(--text-faint);
    opacity: 0.7;
  }
  .selected {
    margin: -2px 0 0;
    color: var(--text-faint);
    font-size: 11px;
  }
  @media (max-width: 640px) {
    .head,
    .head > div {
      align-items: stretch;
      flex-direction: column;
      gap: 6px;
    }
    .control {
      grid-template-columns: 1fr;
      gap: 6px;
    }
  }
</style>
