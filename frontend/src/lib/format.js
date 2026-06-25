/** Formatting helpers shared across the dashboard. */

/** Relative "time ago" label from a timestamp (ms) given a reference now (ms). */
export function timeAgo(ts, now) {
  if (!ts) return '—';
  const s = Math.max(0, Math.round((now - ts) / 1000));
  if (s < 5) return 'just now';
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ${m % 60}m ago`;
  return `${Math.floor(h / 24)}d ago`;
}

/** 24h clock HH:MM:SS from a timestamp. */
export function clock(ts) {
  const d = new Date(ts);
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/** Compact uptime label from elapsed ms. */
export function uptime(ms) {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const p = (n) => String(n).padStart(2, '0');
  return `${p(h)}:${p(m)}:${p(sec)}`;
}

/** Fixed-decimal number, or em-dash for null/undefined. */
export function num(v, digits = 1) {
  if (v === null || v === undefined || Number.isNaN(v)) return '—';
  return v.toFixed(digits);
}

/** Average of the latest readings across a set of nodes, ignoring nulls. */
export function avgLatest(nodes, key) {
  const vals = nodes
    .filter((n) => n.status === 'online')
    .map((n) => n.latest?.[key])
    .filter((v) => v !== null && v !== undefined && !Number.isNaN(v));
  if (!vals.length) return null;
  return vals.reduce((a, b) => a + b, 0) / vals.length;
}
