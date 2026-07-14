<script>
  import { onMount } from 'svelte';
  import { num, timeAgo } from '$lib/format.js';

  let {
    nodes = [],
    now = Date.now(),
    running = false,
    expectedRate = 0,
    observedRate = 0
  } = $props();

  const TAU = Math.PI * 2;
  const MAX_RENDERED_NODES = 1100;
  const PREVIEW_NODES = 132;
  const CLUSTER_COLORS = [
    '#536fc9',
    '#70a21f',
    '#ad7a35',
    '#a13d82',
    '#8f9897',
    '#f2c43d',
    '#2fb9c4'
  ];

  let canvas;
  let ctx;
  let raf = 0;
  let ro;
  let reduceMotion = false;
  let size = { width: 0, height: 0 };
  let graph = emptyGraph();
  let hover = $state(null);

  const totalNodes = $derived(nodes?.length ?? 0);
  const onlineNodes = $derived(nodes?.filter((node) => node.status === 'online').length ?? 0);
  const isPreview = $derived(totalNodes === 0);
  const renderedNodes = $derived(isPreview ? PREVIEW_NODES : Math.min(totalNodes, MAX_RENDERED_NODES));
  const visualizedLabel = $derived(
    totalNodes > MAX_RENDERED_NODES
      ? `${MAX_RENDERED_NODES.toLocaleString()}/${totalNodes.toLocaleString()}`
      : renderedNodes.toLocaleString()
  );
  const shardSummaries = $derived.by(() => summarizeGraph(graph));
  const topologySignature = $derived.by(() => {
    const source = nodes ?? [];
    if (!source.length) return 'preview';
    return source.map((node) => node.nodeId).join('|');
  });

  $effect(() => {
    topologySignature;
    graph = buildGraph(nodes ?? []);
  });

  function emptyGraph() {
    return { preview: true, vertices: [], edges: [] };
  }

  function hashString(value) {
    let hash = 2166136261;
    for (let i = 0; i < value.length; i++) {
      hash ^= value.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return hash >>> 0;
  }

  function seededUnit(seed) {
    const value = Math.sin(seed * 12.9898) * 43758.5453;
    return value - Math.floor(value);
  }

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  function sampleNodes(source) {
    if (source.length <= MAX_RENDERED_NODES) return source;

    const sampled = [];
    const step = source.length / MAX_RENDERED_NODES;
    for (let i = 0; i < MAX_RENDERED_NODES; i++) {
      sampled.push(source[Math.floor(i * step)]);
    }
    return sampled;
  }

  function previewNodes() {
    const startedAt = Date.now();
    return Array.from({ length: PREVIEW_NODES }, (_, index) => {
      const id = `vnode-${String(index + 1).padStart(4, '0')}`;
      const hash = hashString(id);
      const online = index % 13 !== 0;
      return {
        nodeId: id,
        name: id,
        status: online ? 'online' : 'offline',
        lastSeenAt: online ? startedAt - (hash % 45000) : startedAt - 180000 - (hash % 180000),
        rssi: online ? -48 - (hash % 38) : null,
        battery: online ? 35 + (hash % 61) : null,
        ingestion: {
          lastMessageAt: online && index % 4 !== 0 ? startedAt - (hash % 4200) : null,
          throughput: online && index % 4 !== 0 ? +((hash % 18) / 10).toFixed(1) : 0
        },
        preview: true
      };
    });
  }

  function clusterCountFor(count, preview) {
    if (count <= 1) return count;
    if (preview) return 14;
    return Math.min(count, Math.max(4, Math.ceil(Math.sqrt(count) * 1.15)));
  }

  function clusterForNode(node, clusterCount) {
    if (!clusterCount) return 0;
    return hashString(node.nodeId ?? node.name ?? '') % clusterCount;
  }

  function buildGraph(source) {
    const preview = source.length === 0;
    const renderSource = preview ? previewNodes() : sampleNodes(source);
    const vertices = [
      {
        id: 'control-plane',
        label: 'control plane',
        kind: 'hub',
        cluster: -1,
        x: 0,
        y: 0,
        r: 18
      }
    ];
    const edges = [];
    const clusterCount = clusterCountFor(renderSource.length, preview);
    const groups = Array.from({ length: clusterCount }, () => []);

    renderSource.forEach((node) => {
      groups[clusterForNode(node, clusterCount)].push(node);
    });

    groups.forEach((group, cluster) => {
      const clusterSeed = hashString(`cluster-${cluster}-${renderSource.length}`);
      const angle = (cluster / clusterCount) * TAU - Math.PI / 2 + (seededUnit(clusterSeed) - 0.5) * 0.24;
      const ring = 0.52 + (cluster % 3) * 0.12 + seededUnit(clusterSeed + 4) * 0.05;
      const cx = Math.cos(angle) * ring;
      const cy = Math.sin(angle) * ring * 0.9;
      const coordinatorIndex = vertices.length;

      vertices.push({
        id: `vnode-shard-${cluster + 1}`,
        label: `shard ${cluster + 1}`,
        kind: 'coordinator',
        cluster,
        x: cx,
        y: cy,
        r: clamp(5 + group.length * 0.08, 6, 12)
      });
      edges.push({ from: 0, to: coordinatorIndex, kind: 'spoke', phase: seededUnit(clusterSeed + 11) });

      const spread = clamp(0.075 + Math.sqrt(group.length) * 0.019, 0.09, 0.23);

      group.forEach((node, localIndex) => {
        const nodeSeed = hashString(node.nodeId ?? `${cluster}-${localIndex}`);
        const localAngle =
          (localIndex / Math.max(group.length, 1)) * TAU +
          seededUnit(nodeSeed) * 0.85 +
          (cluster % 2 ? 0.2 : -0.2);
        const localRadius = spread * (0.32 + seededUnit(nodeSeed + 7) * 0.92);
        const index = vertices.length;

        vertices.push({
          id: node.nodeId,
          label: node.name || node.nodeId,
          kind: 'node',
          cluster,
          node,
          x: clamp(cx + Math.cos(localAngle) * localRadius, -0.98, 0.98),
          y: clamp(cy + Math.sin(localAngle) * localRadius * 0.86, -0.9, 0.9),
          r: 3.5 + seededUnit(nodeSeed + 3) * 1.6
        });
        edges.push({ from: coordinatorIndex, to: index, kind: 'membership', phase: seededUnit(nodeSeed + 23) });
      });
    });

    return { preview, vertices, edges };
  }

  function project(vertex) {
    const scale = Math.min(size.width, size.height) * 0.47;
    return {
      x: size.width * 0.5 + vertex.x * scale * 1.1,
      y: size.height * 0.52 + vertex.y * scale
    };
  }

  function isActiveNode(node) {
    if (!node) return running || observedRate > 0;
    if (node.preview) return node.status === 'online' && (running || (node.ingestion?.throughput ?? 0) > 0);
    return Boolean(node.ingestion?.lastMessageAt && now - node.ingestion.lastMessageAt <= 5000);
  }

  function hasIssue(node) {
    const latest = node?.latest ?? {};
    return (
      (node?.battery != null && node.battery < 20) ||
      (node?.rssi != null && node.rssi < -75) ||
      (latest.temperature != null && latest.temperature > 28) ||
      (latest.humidity != null && latest.humidity < 35) ||
      (latest.light != null && latest.light < 50)
    );
  }

  function avg(values) {
    const real = values.filter((value) => value !== null && value !== undefined && !Number.isNaN(value));
    if (!real.length) return null;
    return real.reduce((total, value) => total + value, 0) / real.length;
  }

  function summarizeGraph(sourceGraph) {
    const coordinators = sourceGraph.vertices.filter((vertex) => vertex.kind === 'coordinator');
    const nodeGroups = new Map();
    for (const vertex of sourceGraph.vertices) {
      if (vertex.kind !== 'node') continue;
      if (!nodeGroups.has(vertex.cluster)) nodeGroups.set(vertex.cluster, []);
      nodeGroups.get(vertex.cluster).push(vertex.node);
    }

    return coordinators.map((coordinator) => {
      const shardNodes = nodeGroups.get(coordinator.cluster) ?? [];
      const online = shardNodes.filter((node) => node.status === 'online').length;
      const active = shardNodes.filter(isActiveNode).length;
      const issueCount = shardNodes.filter(hasIssue).length;
      const throughput = shardNodes.reduce((total, node) => total + (node.ingestion?.throughput ?? 0), 0);

      return {
        id: coordinator.id,
        label: coordinator.label,
        cluster: coordinator.cluster,
        total: shardNodes.length,
        online,
        active,
        issueCount,
        throughput,
        rssi: avg(shardNodes.map((node) => node.rssi)),
        battery: avg(shardNodes.map((node) => node.battery))
      };
    });
  }

  function vertexColor(vertex, liveClusters) {
    if (vertex.kind === 'hub') {
      return {
        fill: running || observedRate > 0 ? '#8f9897' : '#606873',
        stroke: running || observedRate > 0 ? '#57e08a' : '#7d8793',
        alpha: 1
      };
    }

    if (vertex.kind === 'coordinator') {
      const active = liveClusters.has(vertex.cluster);
      return {
        fill: CLUSTER_COLORS[vertex.cluster % CLUSTER_COLORS.length],
        stroke: active ? '#57e08a' : '#7d8793',
        alpha: active ? 1 : 0.82
      };
    }

    const node = vertex.node;
    if (node?.status !== 'online') {
      return { fill: '#333a42', stroke: '#525d68', alpha: 0.62 };
    }
    if (hasIssue(node)) {
      return { fill: '#f0a93b', stroke: '#ffd166', alpha: 1 };
    }

    return {
      fill: CLUSTER_COLORS[vertex.cluster % CLUSTER_COLORS.length],
      stroke: isActiveNode(node) ? '#57e08a' : '#7d8793',
      alpha: isActiveNode(node) ? 1 : 0.88
    };
  }

  function liveClusterSet() {
    const clusters = new Set();
    for (const vertex of graph.vertices) {
      if (vertex.kind === 'node' && isActiveNode(vertex.node)) clusters.add(vertex.cluster);
    }
    return clusters;
  }

  function drawRings() {
    const center = { x: size.width * 0.5, y: size.height * 0.52 };
    const base = Math.min(size.width, size.height) * 0.47;

    ctx.save();
    ctx.strokeStyle = 'rgba(86, 97, 111, 0.16)';
    ctx.lineWidth = 1;
    ctx.setLineDash([2, 12]);
    for (const ratio of [0.28, 0.5, 0.72, 0.92]) {
      ctx.beginPath();
      ctx.ellipse(center.x, center.y, base * ratio * 1.1, base * ratio, 0, 0, TAU);
      ctx.stroke();
    }
    ctx.restore();
  }

  function drawEdges(timestamp, liveClusters) {
    ctx.save();
    ctx.lineCap = 'round';

    for (const edge of graph.edges) {
      const from = graph.vertices[edge.from];
      const to = graph.vertices[edge.to];
      const a = project(from);
      const b = project(to);
      const active =
        from.kind === 'hub'
          ? running || observedRate > 0 || liveClusters.has(to.cluster)
          : liveClusters.has(from.cluster) || liveClusters.has(to.cluster);

      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      ctx.lineWidth = edge.kind === 'spoke' ? 0.9 : 0.55;
      ctx.strokeStyle = active ? 'rgba(87, 224, 138, 0.22)' : 'rgba(125, 135, 147, 0.2)';
      ctx.stroke();
    }

    if (!reduceMotion && (running || observedRate > 0 || graph.preview)) {
      const step = Math.max(1, Math.floor(graph.edges.length / 170));
      for (let i = 0; i < graph.edges.length; i += step) {
        const edge = graph.edges[i];
        const from = graph.vertices[edge.from];
        const to = graph.vertices[edge.to];
        const active = from.kind === 'hub' || liveClusters.has(from.cluster) || liveClusters.has(to.cluster);
        if (!active && !graph.preview) continue;

        const a = project(from);
        const b = project(to);
        const speed = edge.kind === 'spoke' ? 0.00018 : 0.00012;
        const t = (timestamp * speed + edge.phase) % 1;
        const x = a.x + (b.x - a.x) * t;
        const y = a.y + (b.y - a.y) * t;

        ctx.beginPath();
        ctx.fillStyle = edge.kind === 'spoke' ? 'rgba(87, 224, 138, 0.9)' : 'rgba(52, 211, 222, 0.72)';
        ctx.arc(x, y, edge.kind === 'spoke' ? 1.8 : 1.35, 0, TAU);
        ctx.fill();
      }
    }

    ctx.restore();
  }

  function drawNodes(timestamp, liveClusters) {
    ctx.save();

    for (const vertex of graph.vertices) {
      const { x, y } = project(vertex);
      const color = vertexColor(vertex, liveClusters);
      const live = vertex.kind === 'node' ? isActiveNode(vertex.node) : vertex.kind === 'coordinator' ? liveClusters.has(vertex.cluster) : running || observedRate > 0;
      const pulse = live && !reduceMotion ? Math.sin(timestamp * 0.004 + vertex.cluster) * 0.8 + 0.8 : 0;
      const radius =
        vertex.kind === 'hub'
          ? vertex.r + pulse * 1.4
          : vertex.kind === 'coordinator'
            ? vertex.r + pulse * 0.65
            : vertex.r + Math.min(vertex.node?.ingestion?.throughput ?? 0, 3) * 0.45 + pulse * 0.34;

      ctx.globalAlpha = color.alpha;
      ctx.beginPath();
      ctx.fillStyle = color.fill;
      ctx.arc(x, y, radius, 0, TAU);
      ctx.fill();

      ctx.globalAlpha = live ? 0.85 : 0.36;
      ctx.lineWidth = vertex.kind === 'hub' ? 2 : 1;
      ctx.strokeStyle = color.stroke;
      ctx.stroke();

      if (live && !reduceMotion) {
        ctx.globalAlpha = vertex.kind === 'hub' ? 0.16 : 0.12;
        ctx.beginPath();
        ctx.arc(x, y, radius + 6 + pulse * 2.5, 0, TAU);
        ctx.strokeStyle = color.stroke;
        ctx.lineWidth = 1;
        ctx.stroke();
      }
    }

    ctx.restore();
  }

  function draw(timestamp = 0) {
    if (!ctx || !size.width || !size.height) return;

    ctx.clearRect(0, 0, size.width, size.height);
    drawRings();

    const liveClusters = liveClusterSet();
    drawEdges(timestamp, liveClusters);
    drawNodes(timestamp, liveClusters);
  }

  function frame(timestamp) {
    draw(timestamp);
    raf = requestAnimationFrame(frame);
  }

  function resize() {
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const dpr = Math.max(1, Math.min(window.devicePixelRatio || 1, 2));
    size = { width: rect.width, height: rect.height };
    canvas.width = Math.max(1, Math.floor(rect.width * dpr));
    canvas.height = Math.max(1, Math.floor(rect.height * dpr));
    ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    draw();
  }

  function tooltipPosition(x, y) {
    const width = 248;
    const height = 270;
    return {
      x: clamp(x + 14, 12, Math.max(12, size.width - width)),
      y: clamp(y + 14, 12, Math.max(12, size.height - height))
    };
  }

  function hoverPayload(vertex, pointerX, pointerY) {
    const node = vertex.node;
    const latest = node?.latest ?? {};
    const position = tooltipPosition(pointerX, pointerY);
    const status =
      vertex.kind === 'hub'
        ? running
          ? 'running'
          : observedRate > 0
            ? 'receiving'
            : 'idle'
        : node?.status ?? 'online';

    return {
      ...position,
      pointerX,
      pointerY,
      kind: vertex.kind === 'hub' ? 'control plane' : vertex.kind === 'coordinator' ? 'vnode shard' : 'vnode',
      title: vertex.label,
      nodeId: node?.nodeId ?? vertex.id,
      status,
      throughput: vertex.kind === 'node' ? (node?.ingestion?.throughput ?? 0) : null,
      lastSeen: node?.lastSeenAt ? timeAgo(node.lastSeenAt, now) : vertex.kind === 'node' ? 'none' : null,
      rssi: node?.rssi ?? null,
      battery: node?.battery ?? null,
      temperature: latest.temperature ?? null,
      humidity: latest.humidity ?? null,
      light: latest.light ?? null,
      firmwareVersion: node?.firmwareVersion ?? null,
      preview: node?.preview ?? false
    };
  }

  function inspectAt(pointerX, pointerY) {
    if (!size.width || !size.height) {
      hover = null;
      return;
    }

    let nearest = null;
    let nearestDistance = Infinity;
    for (const vertex of graph.vertices) {
      const p = project(vertex);
      const distance = Math.hypot(p.x - pointerX, p.y - pointerY);
      const threshold = vertex.kind === 'hub' ? 26 : vertex.kind === 'coordinator' ? 18 : 12;
      if (distance <= threshold && distance < nearestDistance) {
        nearest = vertex;
        nearestDistance = distance;
      }
    }

    hover = nearest ? hoverPayload(nearest, pointerX, pointerY) : null;
  }

  function handlePointer(event) {
    const rect = canvas.getBoundingClientRect();
    inspectAt(event.clientX - rect.left, event.clientY - rect.top);
  }

  function clearHover() {
    hover = null;
  }

  onMount(() => {
    reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    ro = new ResizeObserver(resize);
    ro.observe(canvas);
    resize();
    raf = requestAnimationFrame(frame);

    return () => {
      cancelAnimationFrame(raf);
      ro?.disconnect();
    };
  });
</script>

<section class="topology-panel">
  <div class="topology-head">
    <div>
      <span class="eyebrow">vnode topology</span>
      <h3>Shard layout</h3>
    </div>

    <div class="readouts mono">
      <span>
        <b>{visualizedLabel}</b>
        <small>visualized</small>
      </span>
      <span>
        <b>{isPreview ? 'preview' : `${onlineNodes}/${totalNodes}`}</b>
        <small>online</small>
      </span>
      <span>
        <b>{num(observedRate, 1)}</b>
        <small>msg/s</small>
      </span>
      <span>
        <b>{num(expectedRate, 1)}</b>
        <small>target</small>
      </span>
    </div>
  </div>

  <div class="viewport" class:preview={isPreview}>
    <canvas
      bind:this={canvas}
      aria-label="Virtual node distributed topology"
      onpointermove={handlePointer}
      onpointerleave={clearHover}
    ></canvas>

    {#if isPreview}
      <div class="watermark mono">waiting for vnode telemetry</div>
    {/if}

    {#if hover}
      <div class="tooltip mono" style:left="{hover.x}px" style:top="{hover.y}px">
        <span class="eyebrow">{hover.kind}</span>
        <strong>{hover.title}</strong>
        <dl>
          <div>
            <dt>id</dt>
            <dd>{hover.nodeId}</dd>
          </div>
          <div>
            <dt>status</dt>
            <dd class:live={hover.status === 'online' || hover.status === 'running' || hover.status === 'receiving'}>
              {hover.status}
            </dd>
          </div>
          {#if hover.throughput !== null}
            <div>
              <dt>rate</dt>
              <dd>{num(hover.throughput, 1)} msg/s</dd>
            </div>
          {/if}
          {#if hover.lastSeen}
            <div>
              <dt>seen</dt>
              <dd>{hover.preview ? 'preview' : hover.lastSeen}</dd>
            </div>
          {/if}
          {#if hover.rssi !== null}
            <div>
              <dt>rssi</dt>
              <dd>{hover.rssi} dBm</dd>
            </div>
          {/if}
          {#if hover.battery !== null}
            <div>
              <dt>battery</dt>
              <dd>{num(hover.battery, 0)}%</dd>
            </div>
          {/if}
          {#if hover.temperature !== null}
            <div>
              <dt>temp</dt>
              <dd>{num(hover.temperature, 1)}°C</dd>
            </div>
          {/if}
          {#if hover.humidity !== null}
            <div>
              <dt>humidity</dt>
              <dd>{num(hover.humidity, 0)}%</dd>
            </div>
          {/if}
          {#if hover.light !== null}
            <div>
              <dt>light</dt>
              <dd>{Math.round(hover.light)} lux</dd>
            </div>
          {/if}
          {#if hover.firmwareVersion}
            <div>
              <dt>firmware</dt>
              <dd>{hover.firmwareVersion}</dd>
            </div>
          {/if}
        </dl>
      </div>
    {/if}
  </div>

  <div class="shards" aria-label="Shard summaries">
    {#each shardSummaries as shard (shard.id)}
      <article class="shard" style:--shard-color={CLUSTER_COLORS[shard.cluster % CLUSTER_COLORS.length]}>
        <div class="shard-head">
          <span class="shard-dot"></span>
          <strong class="mono">{shard.label}</strong>
        </div>
        <div class="shard-grid mono">
          <span>
            <b>{shard.online}/{shard.total}</b>
            <small>online</small>
          </span>
          <span>
            <b>{shard.active}</b>
            <small>active</small>
          </span>
          <span>
            <b>{num(shard.throughput, 1)}</b>
            <small>msg/s</small>
          </span>
          <span class:warn={shard.issueCount > 0}>
            <b>{shard.issueCount}</b>
            <small>issues</small>
          </span>
          <span>
            <b>{shard.rssi == null ? 'none' : num(shard.rssi, 0)}</b>
            <small>rssi</small>
          </span>
          <span>
            <b>{shard.battery == null ? 'none' : `${num(shard.battery, 0)}%`}</b>
            <small>battery</small>
          </span>
        </div>
      </article>
    {/each}
  </div>
</section>

<style>
  .topology-panel {
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    padding: 16px;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .topology-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 18px;
  }
  h3 {
    margin-top: 4px;
    font-size: 19px;
    line-height: 1.1;
  }
  .readouts {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 14px;
    color: var(--text-dim);
    font-size: 11px;
  }
  .readouts span {
    display: grid;
    gap: 3px;
    min-width: 62px;
    text-align: right;
  }
  .readouts b {
    color: var(--text);
    font-size: 15px;
    font-weight: 600;
    line-height: 1;
    font-variant-numeric: tabular-nums;
  }
  .readouts small {
    color: var(--text-faint);
    font-size: 10px;
    text-transform: uppercase;
  }
  .viewport {
    position: relative;
    height: min(56vw, 620px);
    min-height: 430px;
    overflow: hidden;
    background-color: #05080c;
    background-image:
      linear-gradient(rgba(24, 33, 43, 0.8) 1px, transparent 1px),
      linear-gradient(90deg, rgba(24, 33, 43, 0.8) 1px, transparent 1px);
    background-size: 34px 34px;
    border: 1px solid var(--line-soft);
    border-radius: var(--radius-sm);
  }
  .shards {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
    gap: 1px;
    max-height: 250px;
    overflow: auto;
    background: var(--line);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
  }
  .shard {
    min-width: 0;
    padding: 12px;
    background: var(--bg-2);
    border-top: 2px solid var(--shard-color);
  }
  .shard-head {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 10px;
  }
  .shard-head strong {
    color: var(--text);
    font-size: 12px;
    font-weight: 600;
  }
  .shard-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--shard-color);
    box-shadow: 0 0 10px color-mix(in srgb, var(--shard-color) 70%, transparent);
  }
  .shard-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 9px 10px;
  }
  .shard-grid span {
    min-width: 0;
    display: grid;
    gap: 3px;
  }
  .shard-grid b {
    color: var(--text);
    font-size: 13px;
    line-height: 1;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    overflow-wrap: anywhere;
  }
  .shard-grid small {
    color: var(--text-faint);
    font-size: 9px;
    line-height: 1;
    text-transform: uppercase;
  }
  .shard-grid .warn b {
    color: var(--warn);
  }
  canvas {
    display: block;
    width: 100%;
    height: 100%;
    touch-action: none;
  }
  .watermark {
    position: absolute;
    right: 16px;
    bottom: 13px;
    color: var(--text-faint);
    font-size: 11px;
    text-transform: uppercase;
    pointer-events: none;
  }
  .tooltip {
    position: absolute;
    z-index: 2;
    width: min(248px, calc(100% - 24px));
    max-height: min(360px, calc(100% - 24px));
    overflow: auto;
    padding: 11px 12px;
    color: var(--text-dim);
    background: rgba(11, 16, 25, 0.94);
    border: 1px solid var(--line);
    border-radius: 8px;
    box-shadow: 0 12px 30px rgba(0, 0, 0, 0.32);
    pointer-events: none;
  }
  .tooltip strong {
    display: block;
    margin-top: 3px;
    color: var(--text);
    font-size: 13px;
    line-height: 1.25;
    overflow-wrap: anywhere;
  }
  dl {
    display: grid;
    gap: 5px;
    margin: 9px 0 0;
    font-size: 11px;
  }
  dl div {
    display: grid;
    grid-template-columns: 58px minmax(0, 1fr);
    gap: 8px;
  }
  dt {
    color: var(--text-faint);
    text-transform: uppercase;
  }
  dd {
    margin: 0;
    color: var(--text-dim);
    overflow-wrap: anywhere;
  }
  dd.live {
    color: var(--ch-rssi);
  }
  @media (max-width: 760px) {
    .topology-panel {
      padding: 13px;
    }
    .topology-head {
      flex-direction: column;
    }
    .readouts {
      width: 100%;
      justify-content: flex-start;
    }
    .readouts span {
      text-align: left;
    }
    .viewport {
      height: 72vw;
      min-height: 340px;
    }
  }
</style>
