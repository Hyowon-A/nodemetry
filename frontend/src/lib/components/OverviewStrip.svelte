<script>
  import { dashboardNodes } from '$lib/telemetry.svelte.js';

  const nodes = $derived(dashboardNodes());
  const online = $derived(nodes.filter((n) => n.status === 'online').length);
  const total = $derived(nodes.length);

  const cards = $derived([
    {
      label: 'active nodes',
      value: `${online}`,
      sub: `/ ${total}`,
      color: 'var(--ch-rssi)',
      bars: nodes.map((n) => n.status === 'online')
    }
  ]);
</script>

<section class="strip">
  {#each cards as c (c.label)}
    <article class="card" style:--accent={c.color}>
      <span class="eyebrow">{c.label}</span>
      <div class="value mono">
        {c.value}{#if c.unit}<span class="unit">{c.unit}</span>{/if}{#if c.sub}<span class="sub">{c.sub}</span>{/if}
      </div>
      {#if c.bars}
        <div class="fleet" aria-hidden="true">
          {#each c.bars as on}
            <span class="seg" class:on></span>
          {/each}
        </div>
      {:else}
        <div class="rail"><span class="fill"></span></div>
      {/if}
    </article>
  {/each}
</section>

<style>
  .strip {
    display: grid;
    grid-template-columns: 1fr;
    gap: var(--gap);
  }
  .card {
    position: relative;
    padding: 16px 18px 14px;
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
  }
  .card::before {
    content: '';
    position: absolute;
    inset: 0 auto 0 0;
    width: 3px;
    background: var(--accent);
    box-shadow: 0 0 12px var(--accent);
  }
  .value {
    margin-top: 8px;
    font-size: 30px;
    font-weight: 600;
    line-height: 1;
    letter-spacing: -0.02em;
    color: var(--text);
    font-variant-numeric: tabular-nums;
  }
  .unit {
    font-size: 13px;
    color: var(--text-dim);
    margin-left: 4px;
    font-weight: 500;
  }
  .sub {
    font-size: 15px;
    color: var(--text-faint);
    margin-left: 4px;
  }
  .fleet {
    display: flex;
    gap: 4px;
    margin-top: 13px;
  }
  .seg {
    height: 5px;
    flex: 1;
    border-radius: 2px;
    background: var(--line);
  }
  .seg.on {
    background: var(--accent);
    box-shadow: 0 0 7px var(--accent);
  }
  .rail {
    margin-top: 13px;
    height: 5px;
    border-radius: 2px;
    background: var(--line);
    overflow: hidden;
  }
  .fill {
    display: block;
    height: 100%;
    width: 100%;
    background: linear-gradient(90deg, transparent, var(--accent));
    opacity: 0.5;
  }
  @media (max-width: 900px) {
    .strip {
      grid-template-columns: repeat(2, 1fr);
    }
  }
  @media (max-width: 460px) {
    .strip {
      grid-template-columns: 1fr;
    }
  }
</style>
