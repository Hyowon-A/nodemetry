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
    timestamps = [],
    height = 150,
    min = null,
    max = null,
    decimals = 1,
    scrollable = false,
    plotWidth = null,
    pointSpacing = 10
  } = $props();

  const BASE_W = 600;
  const AXIS_LEFT = 56;
  const AXIS_RIGHT = 8;
  const AXIS_TOP = 8;
  const AXIS_BOTTOM = 28;
  let plotViewport = $state(null);
  let viewportWidth = $state(BASE_W);
  let hover = $state(null);

  $effect(() => {
    if (!plotViewport || typeof ResizeObserver === 'undefined') return;

    const updateViewportWidth = () => {
      viewportWidth = Math.max(BASE_W, Math.round(plotViewport.clientWidth));
    };
    updateViewportWidth();

    const observer = new ResizeObserver(updateViewportWidth);
    observer.observe(plotViewport);
    return () => observer.disconnect();
  });

  const pointCount = $derived.by(() =>
    Math.max(0, timestamps.length, ...series.map((s) => s.points.length))
  );
  const W = $derived.by(() =>
    Math.max(
      viewportWidth,
      plotWidth ??
        (scrollable
          ? AXIS_LEFT + AXIS_RIGHT + Math.max(1, pointCount - 1) * pointSpacing
          : BASE_W)
    )
  );
  const plotLeft = AXIS_LEFT;
  const plotRight = $derived(W - AXIS_RIGHT);
  const plotTop = AXIS_TOP;
  const plotBottom = $derived(height - AXIS_BOTTOM);
  const plotWidthInner = $derived(Math.max(1, plotRight - plotLeft));
  const plotHeightInner = $derived(Math.max(1, plotBottom - plotTop));

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
    return n <= 1 ? plotRight : plotLeft + (i / (n - 1)) * plotWidthInner;
  }
  function y(v) {
    const { lo, hi } = domain;
    const t = (v - lo) / (hi - lo || 1);
    return plotBottom - t * plotHeightInner;
  }

  function linePath(points) {
    const pts = plottedPoints(points);
    if (pts.length < 2) return '';
    return pts.map((point, i) => `${i === 0 ? 'M' : 'L'}${point.cx.toFixed(1)},${point.cy.toFixed(1)}`).join(' ');
  }

  function areaPath(points) {
    const d = linePath(points);
    if (!d) return '';
    const pts = plottedPoints(points);
    const first = pts[0];
    const last = pts[pts.length - 1];
    return `${d} L${last.cx.toFixed(1)},${plotBottom.toFixed(1)} L${first.cx.toFixed(1)},${plotBottom.toFixed(1)} Z`;
  }

  function lead(points) {
    const pts = plottedPoints(points);
    if (!pts.length) return null;
    return pts[pts.length - 1];
  }

  function plottedPoints(points) {
    const count = Math.max(pointCount, points.length);
    return points
      .map((value, index) => ({ value, index }))
      .filter((point) => point.value != null && !Number.isNaN(point.value))
      .map((point) => ({
        ...point,
        cx: x(point.index, count),
        cy: y(point.value)
      }));
  }

  function formatTimestamp(ts, index) {
    if (!ts) return `point ${index + 1}`;
    return new Intl.DateTimeFormat(undefined, {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    }).format(new Date(ts));
  }

  function formatAxisTimestamp(ts, index) {
    if (!ts) return `#${index + 1}`;
    return new Intl.DateTimeFormat(undefined, {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    }).format(new Date(ts));
  }

  function formatValue(value) {
    return typeof value === 'number' ? value.toFixed(decimals) : value;
  }

  function formatSignedValue(value) {
    if (typeof value !== 'number') return value;
    const sign = value > 0 ? '+' : '';
    return `${sign}${value.toFixed(decimals)}`;
  }

  function axisValue(value) {
    if (typeof value !== 'number') return value;
    if (Math.abs(value) >= 1000) return Math.round(value).toLocaleString();
    return value.toFixed(decimals);
  }

  function comparisonValues(values) {
    const raw = values.find((item) => item.label.toLowerCase() === 'raw');
    const filtered = values.find((item) => item.label.toLowerCase() === 'filtered');
    if (!raw || !filtered) return null;
    return {
      raw,
      filtered,
      delta:
        typeof raw.value === 'number' && typeof filtered.value === 'number'
          ? raw.value - filtered.value
          : null
    };
  }

  function findPointForIndex(points, originalIndex) {
    return plottedPoints(points).find((point) => point.index === originalIndex);
  }

  function primaryPlottedPoints() {
    const targetSeries = series.find((s) => !s.faint && plottedPoints(s.points).length) ?? series.find((s) => plottedPoints(s.points).length);
    return targetSeries ? plottedPoints(targetSeries.points) : [];
  }

  function inspectAt(svgX) {
    const targetPoints = primaryPlottedPoints();
    if (!targetPoints.length) {
      hover = null;
      return;
    }

    const target = targetPoints.reduce((nearest, point) =>
      Math.abs(point.cx - svgX) < Math.abs(nearest.cx - svgX) ? point : nearest
    );

    const values = series
      .map((s, i) => {
        const point = findPointForIndex(s.points, target.index);
        if (!point) return null;
        return {
          label: s.name ?? label ?? `series ${i + 1}`,
          value: point.value,
          color: s.color,
          cx: point.cx,
          cy: point.cy
        };
      })
      .filter(Boolean);

    hover = {
      index: target.index,
      x: target.cx,
      y: values[0]?.cy ?? target.cy,
      timestamp: formatTimestamp(timestamps[target.index], target.index),
      values,
      comparison: comparisonValues(values)
    };
  }

  function handlePointer(event) {
    const rect = event.currentTarget.getBoundingClientRect();
    const svgX = Math.max(0, Math.min(W, ((event.clientX - rect.left) / rect.width) * W));
    inspectAt(svgX);
  }

  function clearHover() {
    hover = null;
  }

  function inspectLatest() {
    const points = primaryPlottedPoints();
    if (points.length) inspectAt(points[points.length - 1].cx);
  }

  function handleKeydown(event) {
    const points = primaryPlottedPoints();
    if (!points.length) return;

    const current = hover ? points.findIndex((point) => point.index === hover.index) : points.length - 1;
    let next = current < 0 ? points.length - 1 : current;

    if (event.key === 'ArrowLeft') next = Math.max(0, next - 1);
    else if (event.key === 'ArrowRight') next = Math.min(points.length - 1, next + 1);
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = points.length - 1;
    else return;

    event.preventDefault();
    inspectAt(points[next].cx);
  }

  const yTicks = $derived.by(() => {
    const { lo, hi } = domain;
    return [hi, (hi + lo) / 2, lo].map((value) => ({
      value,
      y: y(value),
      label: axisValue(value)
    }));
  });

  const xTicks = $derived.by(() => {
    const count = pointCount;
    if (!count) return [];
    const tickCount = Math.min(5, count);
    const indices =
      tickCount === 1
        ? [0]
        : Array.from({ length: tickCount }, (_, i) =>
            Math.round((i / (tickCount - 1)) * (count - 1))
          );

    return [...new Set(indices)].map((index, i, ticks) => ({
      index,
      x: x(index, count),
      label: formatAxisTimestamp(timestamps[index], index),
      anchor: i === 0 ? 'start' : i === ticks.length - 1 ? 'end' : 'middle'
    }));
  });

  // SVG ids can't contain '#' (would break url(#...) refs); index keeps them unique
  const gid = (color, i) => `fill-${label}-${i}-${color}`.replace(/[^a-z0-9-]/gi, '');
</script>

<figure
  class="chart"
  class:scrollable={scrollable}
  style:--h="{height}px"
  style:--w="{W}px"
  style:--axis-left="{AXIS_LEFT}px"
  style:--axis-top="{AXIS_TOP}px"
  style:--axis-bottom="{AXIS_BOTTOM}px"
>
  {#if label}
    <figcaption>
      <span class="eyebrow">{label}</span>
      {#if value != null}
        <span class="readout mono">
          {value.toFixed(decimals)}<span class="unit">{unit}</span>
        </span>
      {/if}
    </figcaption>
  {/if}

  <div
    class="plot-scroll"
    class:scrollable={scrollable}
    class:wide={plotWidth != null}
    bind:this={plotViewport}
  >
    <button
      type="button"
      class="plot"
      aria-label="Inspect {label || 'signal'} time-series values"
      onpointermove={handlePointer}
      onpointerdown={handlePointer}
      onpointerleave={clearHover}
      onfocus={inspectLatest}
      onblur={clearHover}
      onkeydown={handleKeydown}
    >
      <div class="sticky-y-axis" aria-hidden="true">
        {#each yTicks as tick}
          <span class="axis-label y-axis-label" style:top="{tick.y}px">{tick.label}</span>
        {/each}
      </div>

      <svg viewBox="0 0 {W} {height}" preserveAspectRatio="none" role="img" aria-label="{label || 'signal'} over time">
        <!-- scope grid -->
        {#each yTicks as tick}
          <line x1={plotLeft} x2={plotRight} y1={tick.y} y2={tick.y} class="grid" />
        {/each}

        {#each xTicks as tick}
          <line x1={tick.x} x2={tick.x} y1={plotTop} y2={plotBottom} class="grid vertical" />
        {/each}

        <line x1={plotLeft} x2={plotRight} y1={plotBottom} y2={plotBottom} class="axis-line" />

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

        {#if hover}
          <line x1={hover.x} x2={hover.x} y1="0" y2={height} class="hover-line" />
          {#each hover.values as item (`${item.label}-${item.cx}-${item.cy}`)}
            <circle cx={item.cx} cy={item.cy} r="4" fill={item.color} class="hover-point" />
          {/each}
        {/if}
      </svg>

      <!-- Axis labels live outside the stretchy SVG so preserveAspectRatio="none"
           can't distort their glyphs; positions are % of the plot box. -->
      <div class="axis-overlay" aria-hidden="true">
        {#each xTicks as tick}
          <span
            class="axis-label x-axis-label"
            class:anchor-start={tick.anchor === 'start'}
            class:anchor-end={tick.anchor === 'end'}
            style:left="{(tick.x / W) * 100}%"
          >{tick.label}</span>
        {/each}
      </div>

      {#if hover}
        <div
          class="tooltip"
          style:--x-pct={(hover.x / W) * 100}
          style:--y-pct={(hover.y / height) * 100}
        >
          <span class="tip-time mono">{hover.timestamp}</span>
          {#if hover.comparison}
            <span class="tip-value mono" style:--c={hover.comparison.raw.color}>
              <i></i>raw<b>{formatValue(hover.comparison.raw.value)}{unit}</b>
            </span>
            <span class="tip-value mono" style:--c={hover.comparison.filtered.color}>
              <i></i>filtered<b>{formatValue(hover.comparison.filtered.value)}{unit}</b>
            </span>
            <span class="tip-value delta mono">
              <i></i>discrepancy<b>{hover.comparison.delta == null ? '—' : `${formatSignedValue(hover.comparison.delta)}${unit}`}</b>
            </span>
          {:else}
            {#each hover.values as item (`tip-${item.label}`)}
              <span class="tip-value mono" style:--c={item.color}>
                <i></i>{item.label}<b>{formatValue(item.value)}{unit}</b>
              </span>
            {/each}
          {/if}
        </div>
      {/if}
    </button>
  </div>
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
  .plot-scroll {
    min-width: 0;
  }
  .plot-scroll.scrollable {
    overflow-x: auto;
    overflow-y: hidden;
    padding-bottom: 6px;
    scrollbar-color: color-mix(in srgb, var(--text-dim) 55%, transparent) transparent;
    scrollbar-width: thin;
  }
  .plot {
    position: relative;
    min-width: 0;
    width: 100%;
    padding: 0;
    color: inherit;
    cursor: crosshair;
    font: inherit;
    text-align: left;
    background: transparent;
    border: 0;
    touch-action: none;
  }
  .plot-scroll.scrollable .plot,
  .plot-scroll.wide .plot {
    width: var(--w);
    min-width: 100%;
  }
  .grid {
    stroke: var(--line-soft);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .grid.vertical {
    opacity: 0.55;
  }
  .axis-line {
    stroke: color-mix(in srgb, var(--text-dim) 42%, transparent);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .sticky-y-axis {
    position: sticky;
    left: 0;
    top: 0;
    z-index: 2;
    width: var(--axis-left);
    height: 0;
    pointer-events: none;
  }
  .sticky-y-axis::before,
  .sticky-y-axis::after {
    position: absolute;
    content: '';
    pointer-events: none;
  }
  .sticky-y-axis::before {
    left: 0;
    top: 0;
    width: var(--axis-left);
    height: var(--h);
    background: linear-gradient(
      90deg,
      var(--panel) 0%,
      color-mix(in srgb, var(--panel) 96%, transparent) 76%,
      transparent 100%
    );
  }
  .sticky-y-axis::after {
    left: var(--axis-left);
    top: var(--axis-top);
    width: 1px;
    height: calc(var(--h) - var(--axis-top) - var(--axis-bottom));
    background: color-mix(in srgb, var(--text-dim) 42%, transparent);
  }
  .axis-overlay {
    position: absolute;
    inset: 0;
    pointer-events: none;
  }
  .axis-label {
    position: absolute;
    color: var(--text-faint);
    font-family: var(--mono);
    font-size: 11px;
    font-weight: 600;
    line-height: 1;
    white-space: nowrap;
    /* halo replaces the old SVG paint-order stroke so labels stay legible over the grid */
    text-shadow:
      0 0 3px var(--panel),
      0 0 3px var(--panel),
      0 0 3px var(--panel);
  }
  .y-axis-label {
    right: 9px;
    z-index: 1;
    transform: translateY(-50%);
  }
  .x-axis-label {
    bottom: 0;
    color: var(--text-dim);
    transform: translateX(-50%);
  }
  .x-axis-label.anchor-start {
    transform: translateX(0);
  }
  .x-axis-label.anchor-end {
    transform: translateX(-100%);
  }
  .lead {
    animation: pulse 1.8s var(--ease) infinite;
    filter: drop-shadow(0 0 5px var(--c));
  }
  .hover-line {
    stroke: color-mix(in srgb, var(--text-dim) 55%, transparent);
    stroke-dasharray: 4 5;
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .hover-point {
    stroke: var(--bg);
    stroke-width: 2;
    vector-effect: non-scaling-stroke;
    filter: drop-shadow(0 0 5px currentColor);
  }
  .tooltip {
    position: absolute;
    left: clamp(92px, calc(var(--x-pct) * 1%), calc(100% - 92px));
    top: clamp(48px, calc(var(--y-pct) * 1%), calc(100% - 48px));
    z-index: 3;
    min-width: 146px;
    padding: 8px 9px;
    pointer-events: none;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: color-mix(in srgb, var(--bg-2) 94%, transparent);
    box-shadow: 0 12px 28px rgba(0, 0, 0, 0.38);
    transform: translate(-50%, -50%);
  }
  .tip-time {
    display: block;
    margin-bottom: 5px;
    color: var(--text-faint);
    font-size: 10.5px;
  }
  .tip-value {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: 6px;
    color: var(--text-dim);
    font-size: 11px;
    white-space: nowrap;
  }
  .tip-value + .tip-value {
    margin-top: 3px;
  }
  .tip-value i {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--c);
    box-shadow: 0 0 6px var(--c);
  }
  .tip-value.delta {
    color: var(--text-faint);
  }
  .tip-value.delta i {
    background: var(--text-faint);
    box-shadow: none;
  }
  .tip-value b {
    color: var(--text);
    font-weight: 600;
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
