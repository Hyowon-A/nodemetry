<script>
  import { onMount } from 'svelte';
  import { num } from '$lib/format.js';
  import { fetchRuns, runStore } from '$lib/runs.svelte.js';

  let timer;
  const runs = $derived(runStore.runs);

  onMount(() => {
    fetchRuns();
    timer = setInterval(fetchRuns, 5000);
    return () => clearInterval(timer);
  });

  function fmtDuration(ms) {
    if (ms == null) return '—';
    if (ms < 60000) return `${(ms / 1000).toFixed(0)}s`;
    return `${(ms / 60000).toFixed(1)}m`;
  }

  const maxThroughput = $derived(
    Math.max(0, ...runs.filter((r) => r.throughputMsgPerSec != null).map((r) => r.throughputMsgPerSec))
  );
  const minDupeRate = $derived(
    Math.min(Infinity, ...runs.filter((r) => r.dupeRatePct != null).map((r) => r.dupeRatePct))
  );
</script>

<section class="panel">
  <div class="head">
    <span class="eyebrow">run history</span>
    <span class="hint mono">{runs.length} run{runs.length !== 1 ? 's' : ''}</span>
  </div>

  {#if runs.length === 0}
    <p class="empty mono">No runs yet — start the simulator to record a run.</p>
  {:else}
    <div class="scroll">
      <table>
        <thead>
          <tr>
            <th>label</th>
            <th>qos</th>
            <th>nodes</th>
            <th>duration</th>
            <th>saved</th>
            <th>throughput</th>
            <th>dupe %</th>
          </tr>
        </thead>
        <tbody>
          {#each runs as run, i (run.runId)}
            <tr class:running={run.running} class:latest={i === 0}>
              <td><span class="label-text">{run.label}</span></td>
              <td class="mono center">{run.qos}</td>
              <td class="mono center">{run.nodeCount}</td>
              <td class="mono dim">{fmtDuration(run.durationMs)}</td>
              <td class="mono">{run.totalSaved.toLocaleString()}</td>
              <td
                class="mono"
                class:best={run.throughputMsgPerSec != null &&
                  run.throughputMsgPerSec === maxThroughput &&
                  maxThroughput > 0}
              >
                {run.throughputMsgPerSec != null ? `${num(run.throughputMsgPerSec, 1)} msg/s` : '—'}
              </td>
              <td
                class="mono"
                class:good={run.dupeRatePct != null &&
                  run.dupeRatePct === minDupeRate &&
                  isFinite(minDupeRate)}
              >
                {run.dupeRatePct != null ? `${num(run.dupeRatePct, 1)}%` : '—'}
              </td>
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
    margin-bottom: 12px;
  }
  .hint {
    color: var(--text-faint);
    font-size: 11px;
  }
  .empty {
    color: var(--text-faint);
    font-size: 12px;
    margin: 0;
  }
  .scroll {
    overflow-x: auto;
  }
  table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
  }
  th {
    padding: 0 12px 8px;
    border-bottom: 1px solid var(--line);
    color: var(--text-faint);
    font-family: var(--mono);
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 0.12em;
    text-align: left;
    text-transform: uppercase;
    white-space: nowrap;
  }
  td {
    padding: 11px 12px;
    border-bottom: 1px solid var(--line-soft);
    vertical-align: middle;
    white-space: nowrap;
    color: var(--text-dim);
  }
  .label-text {
    color: var(--text);
  }
  .center {
    text-align: center;
  }
  .dim {
    color: var(--text-faint);
  }
  tr.latest td {
    background: color-mix(in srgb, var(--ch-rssi) 5%, transparent);
  }
  tr.running td {
    opacity: 0.75;
  }
  .best,
  .good {
    color: var(--ch-rssi);
    font-weight: 600;
  }
</style>
