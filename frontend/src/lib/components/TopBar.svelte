<script>
  import { store } from '$lib/telemetry.svelte.js';
  import { clock, uptime } from '$lib/format.js';
</script>

<header class="bar">
  <a class="brand" href="/">
    <svg class="glyph" viewBox="0 0 32 22" aria-hidden="true">
      <path
        d="M2 16 L9 16 L12 4 L16 20 L19 9 L22 16 L30 16"
        fill="none"
        stroke="var(--ch-rssi)"
        stroke-width="2"
        stroke-linejoin="round"
        stroke-linecap="round"
      />
    </svg>
    <div class="title">
      <h1>NODEMETRY</h1>
      <span class="eyebrow">live signal monitor</span>
    </div>
  </a>

  <div class="broker mono" title="MQTT subscription">
    <span class="dot" class:live={store.connected}></span>
    {store.broker}
  </div>

  <div class="meta">
    <div class="stat">
      <span class="eyebrow">uptime</span>
      <span class="mono val">{uptime(store.now - store.startedAt)}</span>
    </div>
    <div class="stat">
      <span class="eyebrow">node time</span>
      <span class="mono val">{clock(store.now)}</span>
    </div>
    <a class="feed nav" href="/load-tester">LOAD TESTER</a>
    <span class="feed status-indicator" class:on={store.connected} role="status" aria-live="polite">
      <span class="pip" class:live={store.connected}></span>
      {store.connected ? 'LIVE' : 'OFFLINE'}
    </span>
  </div>
</header>

<style>
  .bar {
    display: flex;
    align-items: center;
    gap: 24px;
    padding: 16px 22px;
    background: linear-gradient(180deg, var(--panel-2), var(--panel));
    border: 1px solid var(--line);
    border-radius: var(--radius);
    flex-wrap: wrap;
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 13px;
    color: inherit;
    text-decoration: none;
  }
  .glyph {
    width: 38px;
    height: 26px;
    filter: drop-shadow(0 0 6px rgba(87, 224, 138, 0.5));
  }
  .title h1 {
    font-family: var(--mono);
    font-size: 19px;
    letter-spacing: 0.16em;
    line-height: 1;
  }
  .title .eyebrow {
    display: block;
    margin-top: 3px;
  }
  .broker {
    display: flex;
    align-items: center;
    gap: 9px;
    font-size: 12px;
    color: var(--text-dim);
    padding: 7px 13px;
    background: var(--bg-2);
    border: 1px solid var(--line);
    border-radius: 999px;
    margin-right: auto;
  }
  .dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--text-faint);
  }
  .dot.live {
    background: var(--live);
    box-shadow: 0 0 8px var(--live);
    animation: blink 1.8s var(--ease) infinite;
  }
  .meta {
    display: flex;
    align-items: center;
    gap: 22px;
  }
  .stat {
    display: flex;
    flex-direction: column;
    gap: 2px;
    text-align: right;
  }
  .val {
    font-size: 15px;
    font-weight: 500;
    color: var(--text);
    font-variant-numeric: tabular-nums;
  }
  .feed {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.12em;
    padding: 9px 15px;
    border-radius: 999px;
    border: 1px solid var(--line);
    background: var(--bg-2);
    color: var(--text-dim);
    text-decoration: none;
    white-space: nowrap;
    transition: border-color 0.2s, color 0.2s;
  }
  .feed.on {
    color: var(--live);
    border-color: color-mix(in srgb, var(--live) 45%, var(--line));
  }
  .feed.nav:hover {
    border-color: var(--text-dim);
  }
  .status-indicator {
    cursor: default;
  }
  .pip {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--text-faint);
  }
  .pip.live {
    background: var(--live);
    box-shadow: 0 0 8px var(--live);
    animation: blink 1.8s var(--ease) infinite;
  }
  @keyframes blink {
    0%,
    100% {
      opacity: 1;
    }
    50% {
      opacity: 0.4;
    }
  }
  @media (max-width: 640px) {
    .broker {
      order: 3;
      margin-right: 0;
      width: 100%;
    }
    .meta {
      gap: 16px;
    }
  }
</style>
