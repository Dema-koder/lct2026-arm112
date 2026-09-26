"use client";

/** Lightweight SVG charts for training analytics — no chart library. */

export function avg(values: Array<number | null | undefined>) {
  const nums = values.filter((v): v is number => typeof v === "number" && !Number.isNaN(v));
  if (nums.length === 0) return null;
  return nums.reduce((a, b) => a + b, 0) / nums.length;
}

export function clamp(n: number, min = 0, max = 100) {
  return Math.max(min, Math.min(max, n));
}

type BarItem = { key: string; label: string; value: number; color?: string; hint?: string };

export function BarChart({ items, max = 100, height = 140, ariaLabel }: {
  items: BarItem[];
  max?: number;
  height?: number;
  ariaLabel: string;
}) {
  if (items.length === 0) return <p className="muted chart-empty">Нет данных для диаграммы</p>;
  const width = 420;
  const pad = 28;
  const bar = Math.max(10, Math.min(36, (width - pad * 2) / items.length - 6));
  const scale = max <= 0 ? 1 : max;
  return (
    <svg className="chart-svg" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={ariaLabel}>
      <line x1={pad} y1={height - pad} x2={width - pad / 2} y2={height - pad} stroke="#8e9ca3" />
      {[25, 50, 75, 100].filter((t) => t <= scale).map((t) => {
        const y = height - pad - (t / scale) * (height - pad * 2);
        return <line key={t} x1={pad} y1={y} x2={width - pad / 2} y2={y} stroke="#d7dcde" strokeDasharray="3 3" />;
      })}
      {items.map((item, i) => {
        const h = (clamp(item.value, 0, scale) / scale) * (height - pad * 2);
        const x = pad + i * ((width - pad * 2) / items.length);
        return (
          <g key={item.key}>
            <rect x={x} y={height - pad - h} width={bar} height={Math.max(1, h)} fill={item.color ?? "#0784c6"}>
              {item.hint && <title>{item.hint}</title>}
            </rect>
            <text x={x + bar / 2} y={height - pad + 12} fontSize="9" textAnchor="middle" fill="#56656c">{item.label}</text>
            <text x={x + bar / 2} y={height - pad - h - 4} fontSize="9" textAnchor="middle" fill="#24313a">{Math.round(item.value)}</text>
          </g>
        );
      })}
    </svg>
  );
}

export function HorizontalBars({ items, max }: { items: BarItem[]; max?: number }) {
  if (items.length === 0) return <p className="muted chart-empty">Нет данных</p>;
  const ceiling = max ?? Math.max(1, ...items.map((i) => i.value));
  return (
    <ul className="hbar-list">
      {items.map((item) => (
        <li key={item.key}>
          <span className="hbar-label" title={item.hint}>{item.label}</span>
          <span className="hbar-track">
            <i style={{ width: `${(item.value / ceiling) * 100}%`, background: item.color ?? "#0784c6" }} />
          </span>
          <b className="hbar-value">{Math.round(item.value)}</b>
        </li>
      ))}
    </ul>
  );
}

export function Donut({ segments, center, size = 120 }: {
  segments: Array<{ key: string; value: number; color: string; label: string }>;
  center: string;
  size?: number;
}) {
  const total = segments.reduce((s, x) => s + x.value, 0);
  if (total <= 0) return <p className="muted chart-empty">Нет данных</p>;
  const r = 42;
  const c = 2 * Math.PI * r;
  const visible = segments.filter((segment) => segment.value > 0);
  const arcs = visible.map((segment, index) => ({
    segment,
    length: (segment.value / total) * c,
    offset: visible.slice(0, index).reduce((sum, previous) => sum + (previous.value / total) * c, 0),
  }));
  return (
    <div className="donut-wrap">
      <svg width={size} height={size} viewBox="0 0 120 120" role="img" aria-label={center}>
        <g transform="rotate(-90 60 60)">
          {arcs.map(({ segment, length, offset }) => (
            <circle key={segment.key} cx="60" cy="60" r={r} fill="none" stroke={segment.color}
              strokeWidth="16" strokeDasharray={`${length} ${c - length}`} strokeDashoffset={-offset}>
              <title>{segment.label}: {segment.value}</title>
            </circle>
          ))}
        </g>
        <text x="60" y="58" textAnchor="middle" fontSize="14" fontWeight="bold" fill="#24313a">{center}</text>
        <text x="60" y="74" textAnchor="middle" fontSize="9" fill="#6d797f">всего</text>
      </svg>
      <ul className="donut-legend">
        {segments.map((s) => (
          <li key={s.key}><i style={{ background: s.color }} />{s.label} <b>{s.value}</b></li>
        ))}
      </ul>
    </div>
  );
}

export function Sparkline({ values, width = 160, height = 36 }: { values: number[]; width?: number; height?: number }) {
  if (values.length < 2) return <span className="muted">—</span>;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = Math.max(1, max - min);
  const pts = values.map((v, i) => {
    const x = (i / (values.length - 1)) * (width - 4) + 2;
    const y = height - 4 - ((v - min) / span) * (height - 8);
    return `${x},${y}`;
  }).join(" ");
  return (
    <svg className="sparkline" width={width} height={height} viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
      <polyline fill="none" stroke="#0784c6" strokeWidth="2" points={pts} />
    </svg>
  );
}

export function KpiGrid({ items }: { items: Array<{ label: string; value: string; hint?: string; tone?: "ok" | "warn" | "neutral" }> }) {
  return (
    <div className="kpi-grid">
      {items.map((item) => (
        <div key={item.label} className={`kpi-card ${item.tone ?? "neutral"}`} title={item.hint}>
          <span>{item.label}</span>
          <b>{item.value}</b>
        </div>
      ))}
    </div>
  );
}
