import { useId, useState } from 'react';

const W = 480;
const H = 230;
const PAD = { top: 12, right: 92, bottom: 36, left: 52 };

/**
 * A small line chart for the experiment results (W4): evenly spaced x categories (phone counts,
 * hops), one y axis (linear or log), 2 px lines with ringed markers, direct labels at the line
 * ends, and a hover crosshair that lists every series at that x. A table view sits underneath.
 *
 * series: [{ name, color, values: { [x]: number | null } }]
 */
export default function LineChart({ title, xs, xLabel, series, yScale = 'linear', yDomain, yTicks, format, note }) {
  const [hover, setHover] = useState(null);
  const clipId = useId();
  const plotW = W - PAD.left - PAD.right;
  const plotH = H - PAD.top - PAD.bottom;
  const x = (i) => PAD.left + (xs.length === 1 ? plotW / 2 : (i * plotW) / (xs.length - 1));
  const [lo, hi] = yDomain;
  const y = (v) => {
    const t = yScale === 'log' ? (Math.log10(v) - Math.log10(lo)) / (Math.log10(hi) - Math.log10(lo)) : (v - lo) / (hi - lo);
    return PAD.top + plotH * (1 - Math.min(1, Math.max(0, t)));
  };
  const has = (v) => v != null && Number.isFinite(v) && (yScale !== 'log' || v > 0);

  return (
    <figure className="chart">
      <figcaption>
        <strong>{title}</strong>
        {series.length > 1 && (
          <span className="chart-legend">
            {series.map((s) => (
              <span key={s.name}>
                <span className="legend-swatch" style={{ background: s.color }} />
                {s.name}
              </span>
            ))}
          </span>
        )}
      </figcaption>
      <div className="chart-box">
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={title} onMouseLeave={() => setHover(null)}>
          <defs>
            <clipPath id={clipId}>
              <rect x={PAD.left - 6} y={PAD.top - 6} width={plotW + 12} height={plotH + 12} />
            </clipPath>
          </defs>
          {yTicks.map((t) => (
            <g key={t}>
              <line className="chart-grid" x1={PAD.left} x2={PAD.left + plotW} y1={y(t)} y2={y(t)} />
              <text className="chart-tick" x={PAD.left - 8} y={y(t)} dy="0.32em" textAnchor="end">
                {format(t)}
              </text>
            </g>
          ))}
          {xs.map((v, i) => (
            <text key={v} className="chart-tick" x={x(i)} y={PAD.top + plotH + 18} textAnchor="middle">
              {v}
            </text>
          ))}
          <text className="chart-axis-label" x={PAD.left + plotW / 2} y={H - 4} textAnchor="middle">
            {xLabel}
          </text>
          {hover != null && <line className="chart-crosshair" x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={PAD.top + plotH} />}
          <g clipPath={`url(#${clipId})`}>
            {series.map((s) => {
              const pts = xs.map((v, i) => (has(s.values[v]) ? [x(i), y(s.values[v])] : null));
              // Break the line where a value is missing rather than bridging the gap.
              const d = pts.reduce((path, p, i) => (p ? `${path}${i > 0 && pts[i - 1] ? 'L' : 'M'}${p[0]},${p[1]}` : path), '');
              return (
                <g key={s.name}>
                  <path d={d} fill="none" stroke={s.color} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
                  {pts.map((p, i) => p && <circle key={i} cx={p[0]} cy={p[1]} r={hover === i ? 5 : 4} fill={s.color} className="chart-dot" />)}
                </g>
              );
            })}
          </g>
          {series.length <= 4 &&
            series.map((s) => {
              const last = [...xs].reverse().find((v) => has(s.values[v]));
              if (last == null) return null;
              return (
                <text key={s.name} className="chart-direct" x={x(xs.indexOf(last)) + 10} y={y(s.values[last])} dy="0.32em">
                  {s.name}
                </text>
              );
            })}
          {xs.map((v, i) => (
            <rect
              key={v}
              x={x(i) - plotW / Math.max(1, xs.length - 1) / 2}
              y={PAD.top}
              width={plotW / Math.max(1, xs.length - 1)}
              height={plotH}
              fill="transparent"
              onMouseEnter={() => setHover(i)}
            />
          ))}
        </svg>
        {hover != null && (
          <div className="chart-tooltip" style={{ left: `${(x(hover) / W) * 100}%` }} role="status">
            <div className="chart-tooltip-title">
              {xLabel}: {xs[hover]}
            </div>
            {series.map((s) => (
              <div key={s.name}>
                <span className="legend-swatch" style={{ background: s.color }} /> {s.name}:{' '}
                <strong>{has(s.values[xs[hover]]) ? format(s.values[xs[hover]]) : '–'}</strong>
              </div>
            ))}
          </div>
        )}
      </div>
      {note && <p className="muted small chart-note">{note}</p>}
      <details className="chart-table">
        <summary>Table</summary>
        <table>
          <thead>
            <tr>
              <th>{xLabel}</th>
              {series.map((s) => (
                <th key={s.name}>{s.name}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {xs.map((v) => (
              <tr key={v}>
                <td>{v}</td>
                {series.map((s) => (
                  <td key={s.name}>{has(s.values[v]) ? format(s.values[v]) : '–'}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  );
}
