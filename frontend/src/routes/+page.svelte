<script>
  import { selectedDashboardNode, emptyHistory } from '$lib/telemetry.svelte.js';
  import TopBar from '$lib/components/TopBar.svelte';
  import OverviewStrip from '$lib/components/OverviewStrip.svelte';
  import SignalChart from '$lib/components/SignalChart.svelte';
  import AlertsPanel from '$lib/components/AlertsPanel.svelte';
  import IngestionMetrics from '$lib/components/IngestionMetrics.svelte';
  import NodeTable from '$lib/components/NodeTable.svelte';
  import NodeDetails from '$lib/components/NodeDetails.svelte';
  import NodeRunSelector from '$lib/components/NodeRunSelector.svelte';

  const node = $derived(selectedDashboardNode());
  const h = $derived(node?.history ?? emptyHistory());
  const last = (a) => (a.length ? a[a.length - 1] : null);
</script>

<svelte:head>
  <title>Nodemetry · live signal monitor</title>
</svelte:head>

<main class="app">
  <TopBar />

  <div class="workspace">
    <aside class="fleet-column">
      <OverviewStrip />
      <div class="panel-bare" style:--stagger="1"><NodeTable /></div>
    </aside>

    <section class="telemetry-column">
      <div class="panel-bare" style:--stagger="1"><NodeDetails /></div>
      <div class="panel-bare" style:--stagger="2"><NodeRunSelector /></div>
      <section class="readings" aria-label="Reading charts">

        <div class="charts">
          <div class="panel wide comparison" style:--stagger="3" style:--chart-color="var(--ch-temp)">
            <div class="sig-head">
              <span class="eyebrow">raw vs filtered · temperature</span>
              <div class="legend mono">
                <span><i class="sw raw"></i>raw</span>
                <span><i class="sw filt"></i>filtered</span>
              </div>
            </div>
            <SignalChart
              label=""
              height={150}
              unit="°C"
              value={last(h.temperature)}
              decimals={2}
              timestamps={h.t}
              series={[
                { name: 'raw', points: h.temperatureRaw, color: 'var(--ch-temp)', faint: true, width: 1.4 },
                { name: 'filtered', points: h.temperature, color: 'var(--ch-temp)', area: true, glow: true, width: 2.4 }
              ]}
            />
          </div>

          <div class="panel wide comparison" style:--stagger="4" style:--chart-color="var(--ch-humid)">
            <div class="sig-head">
              <span class="eyebrow">raw vs filtered · humidity</span>
              <div class="legend mono">
                <span><i class="sw raw"></i>raw</span>
                <span><i class="sw filt"></i>filtered</span>
              </div>
            </div>
            <SignalChart
              label=""
              height={150}
              unit="%"
              value={last(h.humidity)}
              decimals={2}
              timestamps={h.t}
              series={[
                { name: 'raw', points: h.humidityRaw, color: 'var(--ch-humid)', faint: true, width: 1.4 },
                { name: 'filtered', points: h.humidity, color: 'var(--ch-humid)', area: true, glow: true, width: 2.4 }
              ]}
            />
          </div>

          <div class="panel wide comparison" style:--stagger="5" style:--chart-color="var(--ch-light)">
            <div class="sig-head">
              <span class="eyebrow">raw · light</span>
              <div class="legend mono">
                <span><i class="sw raw"></i>raw</span>
              </div>
            </div>
            <SignalChart
              label=""
              height={150}
              unit="lux"
              value={last(h.light)}
              decimals={2}
              timestamps={h.t}
              series={[
                { name: 'raw', points: h.light, color: 'var(--ch-light)', area: true, glow: true, width: 2.4 }
              ]}
            />
          </div>
        </div>
      </section>

      <div class="support-grid">
        <div class="panel-bare" style:--stagger="8"><AlertsPanel /></div>
        <div class="panel-bare" style:--stagger="9"><IngestionMetrics /></div>
      </div>
    </section>
  </div>

  <footer class="foot mono">
    nodemetry · esp32 → mqtt → spring boot → postgresql → websocket dashboard
    <span class="sep">·</span> demo feed simulated client-side — see <code>src/lib/telemetry.svelte.js</code> to connect the real backend
  </footer>
</main>

<style>
  .app {
    max-width: 1680px;
    margin: 0 auto;
    padding: 22px;
    display: flex;
    flex-direction: column;
    gap: var(--gap);
  }
  .workspace {
    display: grid;
    grid-template-columns: minmax(430px, 0.78fr) minmax(0, 1.7fr);
    gap: var(--gap);
    align-items: start;
  }
  .fleet-column,
  .telemetry-column,
  .readings {
    min-width: 0;
  }
  .fleet-column,
  .telemetry-column {
    display: flex;
    flex-direction: column;
    gap: var(--gap);
  }
  .fleet-column {
    position: sticky;
    top: var(--gap);
  }
  .readings {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .charts {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--gap);
  }
  .support-grid {
    display: grid;
    grid-template-columns: minmax(0, 1.2fr) minmax(300px, 0.8fr);
    gap: var(--gap);
  }
  .panel,
  .panel-bare {
    animation: rise 0.5s var(--ease) both;
    animation-delay: calc(var(--stagger, 0) * 70ms);
  }
  .panel {
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    padding: 16px;
  }
  .panel.wide {
    grid-column: 1 / -1;
  }
  .comparison {
    position: relative;
    background:
      radial-gradient(120% 140% at 50% 0%, color-mix(in srgb, var(--chart-color) 8%, transparent), transparent 60%),
      var(--panel);
  }
  .sig-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;
  }
  .legend {
    display: flex;
    gap: 14px;
    font-size: 11px;
    color: var(--text-dim);
  }
  .legend span {
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }
  .sw {
    width: 14px;
    height: 2px;
    border-radius: 1px;
    display: inline-block;
  }
  .sw.raw {
    background: var(--chart-color);
    opacity: 0.4;
  }
  .sw.filt {
    background: var(--chart-color);
    box-shadow: 0 0 5px var(--chart-color);
  }
  .foot {
    font-size: 11px;
    color: var(--text-faint);
    text-align: center;
    padding: 8px 0 4px;
    line-height: 1.7;
  }
  .foot code {
    color: var(--text-dim);
  }
  .sep {
    margin: 0 6px;
  }
  @keyframes rise {
    from {
      opacity: 0;
      transform: translateY(10px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }
  @media (max-width: 1180px) {
    .workspace {
      grid-template-columns: 1fr;
    }
    .fleet-column {
      position: static;
    }
  }
  @media (max-width: 760px) {
    .app {
      padding: 14px;
    }
    .charts,
    .support-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
