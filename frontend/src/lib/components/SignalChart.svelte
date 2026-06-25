<script>
  /**
   * Scope-style line chart. Renders one or more series on a shared
   * grid with a pulsing "live" point at the leading edge.
   * series: [{ points:number[], color:string, area?:bool, faint?:bool, dashed?:bool, glow?:bool, width?:number }]
   */
  let {
    series = [],
    label = '',
    unit = '',
    value = null,
    height = 150,
    min = null,
    max = null,
    decimals = 1
  } = $props();

  const W = 600;
  const PAD_Y = 14;

  // shared vertical domain across all series (with headroom)
  const domain = $derived.by(() => {
    let lo = min,
      hi = max;
    if (lo === null || hi === null) {
      const all = series.flatMap((s) => s.points).filter((v) => v != null && !Number.isNaN(v));
      if (!all.length) return { lo: 0, hi: 1 };
      let dMin = Math.min(...all);
      let dMax = Math.max(...all);
      if (dMin === dMax) {
        dMin -= 1;
        dMax += 1;
      }
      const pad = (dMax - dMin) * 0.18;
      lo = lo ?? dMin - pad;
      hi = hi ?? dMax + pad;
    }
    return { lo, hi };
  });

  function x(i, n) {
    return n <= 1 ? W : (i / (n - 1)) * W;
  }
  function y(v) {
    const { lo, hi } = domain;
    const t = (v - lo) / (hi - lo || 1);
    return height - PAD_Y - t * (height - PAD_Y * 2);
  }

  function linePath(points) {
    const pts = points.filter((v) => v != null && !Number.isNaN(v));
    if (pts.length < 2) return '';
    return pts.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i, pts.length).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  }

  function areaPath(points) {
    const d = linePath(points);
    if (!d) return '';
    const n = points.filter((v) => v != null).length;
    return `${d} L${x(n - 1, n).toFixed(1)},${height} L0,${height} Z`;
  }

  function lead(points) {
    const pts = points.filter((v) => v != null && !Number.isNaN(v));
    if (!pts.length) return null;
    return { cx: x(pts.length - 1, pts.length), cy: y(pts[pts.length - 1]) };
  }

  // SVG ids can't contain '#' (would break url(#...) refs); index keeps them unique
  const gid = (color, i) => `fill-${label}-${i}-${color}`.replace(/[^a-z0-9-]/gi, '');
</script>

<figure class="chart" style:--h="{height}px">
  <figcaption>
    <span class="eyebrow">{label}</span>
    {#if value != null}
      <span class="readout mono">
        {value.toFixed(decimals)}<span class="unit">{unit}</span>
      </span>
    {/if}
  </figcaption>

  <svg viewBox="0 0 {W} {height}" preserveAspectRatio="none" role="img" aria-label="{label} over time">
    <!-- scope grid -->
    {#each [0.25, 0.5, 0.75] as g}
      <line x1="0" x2={W} y1={height * g} y2={height * g} class="grid" />
    {/each}

    {#each series as s, i (i)}
      {#if s.area}
        <path d={areaPath(s.points)} fill="url(#{gid(s.color, i)})" stroke="none" />
        <linearGradient id={gid(s.color, i)} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color={s.color} stop-opacity="0.22" />
          <stop offset="100%" stop-color={s.color} stop-opacity="0" />
        </linearGradient>
      {/if}
      <path
        d={linePath(s.points)}
        fill="none"
        stroke={s.color}
        stroke-width={s.width ?? 2}
        stroke-linejoin="round"
        stroke-linecap="round"
        stroke-dasharray={s.dashed ? '4 4' : 'none'}
        opacity={s.faint ? 0.4 : 1}
        style:filter={s.glow ? `drop-shadow(0 0 5px ${s.color})` : 'none'}
        vector-effect="non-scaling-stroke"
      />
    {/each}

    {#each series.filter((s) => !s.faint) as s, i (i)}
      {@const p = lead(s.points)}
      {#if p}
        <circle cx={p.cx} cy={p.cy} r="3" fill={s.color} class="lead" style:--c={s.color} />
      {/if}
    {/each}
  </svg>
</figure>

<style>
  .chart {
    margin: 0;
    display: flex;
    flex-direction: column;
    gap: 6px;
    min-width: 0;
  }
  figcaption {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 10px;
  }
  .readout {
    font-size: 18px;
    font-weight: 600;
    color: var(--text);
    letter-spacing: -0.01em;
  }
  .unit {
    font-size: 11px;
    color: var(--text-dim);
    margin-left: 2px;
  }
  svg {
    width: 100%;
    height: var(--h);
    display: block;
    overflow: visible;
  }
  .grid {
    stroke: var(--line-soft);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .lead {
    animation: pulse 1.8s var(--ease) infinite;
    filter: drop-shadow(0 0 5px var(--c));
  }
  @keyframes pulse {
    0%,
    100% {
      r: 3;
      opacity: 1;
    }
    50% {
      r: 4.5;
      opacity: 0.65;
    }
  }
</style>