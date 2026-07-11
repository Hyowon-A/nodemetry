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
    return `${(ms / 1000).toFixed(0)}s`;
  }

  function effectiveDurationMs(run) {
    if (run.durationMs != null) return run.durationMs;
    if (!run.running || !run.startedAt) return null;

    const startedAt = Date.parse(run.startedAt);
    return Number.isNaN(startedAt) ? null : Math.max(0, Date.now() - startedAt);
  }

  function expectedSaved(run) {
    const durationMs = effectiveDurationMs(run);
    if (durationMs == null || !run.intervalSec || run.intervalSec <= 0) return null;
    return Math.floor((run.nodeCount * durationMs) / (run.intervalSec * 1000));
  }

  function deliveryPct(run) {
    const expected = expectedSaved(run);
    if (!expected) return null;
    return ((run.totalReceived ?? 0) * 100) / expected;
  }

  function persistencePct(run) {
    const receivedUnique = (run.totalReceived ?? 0) - (run.duplicatesSkipped ?? 0);
    if (receivedUnique <= 0) return null;
    return (run.totalSaved * 100) / receivedUnique;
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
            <th>expected</th>
            <th>received</th>
            <th>delivery %</th>
            <th>saved</th>
            <th>persistence %</th>
            <th>dupes</th>
            <th>dupe %</th>
            <th>throughput</th>
          </tr>
        </thead>
        <tbody>
          {#each runs as run, i (run.runId)}
            <tr class:running={run.running} class:latest={i === 0}>
              <td><span class="label-text">{run.label}</span></td>
              <td class="mono center">{run.qos}</td>
              <td class="mono center">{run.nodeCount}</td>
              <td class="mono dim">{fmtDuration(run.durationMs)}</td>
              <td class="mono">{expectedSaved(run)?.toLocaleString() ?? '—'}</td>
              <td class="mono">{(run.totalReceived ?? 0).toLocaleString()}</td>
              <td class="mono" class:good={deliveryPct(run) != null && deliveryPct(run) >= 95}>
                {deliveryPct(run) != null ? `${num(deliveryPct(run), 1)}%` : '—'}
              </td>
              <td class="mono">{run.totalSaved.toLocaleString()}</td>
              <td class="mono" class:good={persistencePct(run) != null && persistencePct(run) >= 95}>
                {persistencePct(run) != null ? `${num(persistencePct(run), 1)}%` : '—'}
              </td>
              <td class="mono">{(run.duplicatesSkipped ?? 0).toLocaleString()}</td>
              <td
                class="mono"
                class:good={run.dupeRatePct != null &&
                  run.dupeRatePct === minDupeRate &&
                  isFinite(minDupeRate)}
              >
                {run.dupeRatePct != null ? `${num(run.dupeRatePct, 1)}%` : '—'}
              </td>
              <td
                class="mono"
                class:best={run.throughputMsgPerSec != null &&
                  run.throughputMsgPerSec === maxThroughput &&
                  maxThroughput > 0}
              >
                {run.throughputMsgPerSec != null ? `${num(run.throughputMsgPerSec, 1)} msg/s` : '—'}
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
