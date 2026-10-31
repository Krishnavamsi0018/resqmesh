import fieldCsv from '../../experiments/field/summary.csv?raw';
import simCsv from '../../experiments/simulator/summary.csv?raw';
import LineChart from './LineChart.jsx';

// Categorical slots 1 and 2 (validated: CVD ΔE 24.7 on white). Ours is always blue, the baseline orange.
const OURS = '#2a78d6';
const BASELINE = '#eb6834';

function parse(csv) {
  const [header, ...lines] = csv.trim().split(/\r?\n/);
  const keys = header.split(',');
  return lines.filter(Boolean).map((line) => Object.fromEntries(line.split(',').map((v, i) => [keys[i], v === '' ? null : Number.isNaN(Number(v)) ? v : Number(v)])));
}

const sim = parse(simCsv);
const field = parse(fieldCsv);
const PHONES = [...new Set(sim.map((r) => r.nodes))].sort((a, b) => a - b);
const RANGES = [...new Set(sim.map((r) => r.range_m))].sort((a, b) => a - b);

const pct = (v) => `${Math.round(v)}%`;
function duration(seconds) {
  if (seconds < 1) return `${(seconds * 1000).toFixed(0)} ms`;
  if (seconds < 60) return `${Number(seconds < 10 ? seconds.toFixed(1) : seconds.toFixed(0))} s`;
  return `${Number((seconds / 60).toFixed(seconds < 600 ? 1 : 0))} min`;
}
// Number() drops trailing zeros: 1.0 shows as 1, 0.10 as 0.1.
const count = (v) => String(Number(v >= 10 ? v.toFixed(0) : v >= 1 ? v.toFixed(1) : v.toFixed(2)));

/** One series per scheme for a simulator column at one radio range. */
function simSeries(range, column, scale = 1) {
  const pick = (scheme) => Object.fromEntries(sim.filter((r) => r.range_m === range && r.scheme === scheme).map((r) => [r.nodes, r[column] == null ? null : r[column] * scale]));
  return [
    { name: 'ResQMesh', color: OURS, values: pick('RESQMESH') },
    { name: 'Flooding', color: BASELINE, values: pick('FLOODING') },
  ];
}

const row = (range, nodes, scheme) => sim.find((r) => r.range_m === range && r.nodes === nodes && r.scheme === scheme);

const SIM_METRICS = [
  {
    key: 'delivery',
    title: 'SOS delivered to a gateway',
    column: 'delivery_mean',
    scale: 100,
    yDomain: [0, 100],
    yTicks: [0, 25, 50, 75, 100],
    format: pct,
  },
  {
    key: 'reach',
    title: 'Phones the SOS reached',
    column: 'reach_mean',
    scale: 100,
    yDomain: [0, 100],
    yTicks: [0, 25, 50, 75, 100],
    format: pct,
  },
  {
    key: 'latency',
    title: 'Median time to a gateway (delivered SOS)',
    column: 'latency_median_s',
    yScale: 'log',
    yDomain: [0.1, 1800],
    yTicks: [0.1, 1, 10, 60, 600],
    format: duration,
    note: 'Log scale. Flooding only delivers SOS whose path already exists, so its few deliveries are fast.',
  },
  {
    key: 'overhead',
    title: 'Packet copies sent per SOS',
    column: 'data_tx_per_sos',
    yScale: 'log',
    yDomain: [0.1, 1000],
    yTicks: [0.1, 1, 10, 100, 1000],
    format: count,
    note: 'Log scale. Summary and request frames (store-carry-forward) are listed in experiments/simulator.',
  },
];

/** W4: the evaluation charts, from experiments/simulator and experiments/field (rebuilt with the dashboard). */
export default function Results() {
  const sparse = { ours: row(30, 100, 'RESQMESH'), base: row(30, 100, 'FLOODING') };
  const dense = { ours: row(80, 200, 'RESQMESH'), base: row(80, 200, 'FLOODING') };
  const runs = sim[0]?.runs;
  const latencyTicks = ticks(Math.max(1, ...field.map((r) => (r.latency_median_ms ?? 0) / 1000)));
  const txTicks = ticks(Math.max(4, ...field.map((r) => r.tx_per_sos ?? 0)));

  return (
    <div className="results">
      <header className="topbar">
        <a className="btn btn-quiet" href="#">
          <span className="material-symbols-outlined" aria-hidden="true">arrow_back</span> Live map
        </a>
        <h1 className="results-title">Experiment results</h1>
      </header>

      <main className="results-body">
        <section>
          <h2>Simulator</h2>
          <p className="muted">
            Virtual phones running the app&apos;s own relay code, walking in a 1 km² area; 20 SOS per run, 5% of phones have signal. {runs} runs per point.
            Flooding is the same code without store-carry-forward.
          </p>
          {sparse.ours && dense.ours && (
            <div className="stat-tiles">
              <Stat value={pct(sparse.ours.delivery_mean * 100)} label="delivered by ResQMesh" detail={`100 phones, 30 m range · flooding ${pct(sparse.base.delivery_mean * 100)}`} />
              <Stat value={pct(dense.ours.delivery_mean * 100)} label="delivered in a dense crowd" detail={`200 phones, 80 m · flooding ${pct(dense.base.delivery_mean * 100)}`} />
              <Stat value={duration(dense.ours.latency_median_s)} label="median time to a gateway" detail="200 phones, 80 m range" />
            </div>
          )}
          {SIM_METRICS.map((m) => (
            <div key={m.key} className="chart-row">
              {RANGES.map((range) => (
                <LineChart
                  key={range}
                  title={`${m.title} · ${range} m range`}
                  xs={PHONES}
                  xLabel="Phones"
                  series={simSeries(range, m.column, m.scale ?? 1)}
                  yScale={m.yScale}
                  yDomain={m.yDomain}
                  yTicks={m.yTicks}
                  format={m.format}
                  note={m.note}
                />
              ))}
            </div>
          ))}
        </section>

        <section>
          <h2>Real phones</h2>
          {field.length === 0 ? (
            <p className="muted">
              No field runs yet. Save each phone&apos;s CSV from the Network tab into experiments/field/raw, run{' '}
              <code>node experiments/field/analyze.mjs</code>, and rebuild the dashboard.
            </p>
          ) : (
            <>
              <p className="muted">
                Forced chains of real phones (chain mode); test SOS every 10 s from the first phone, echoed by the last. Latency is half the echo round trip.
              </p>
              <div className="chart-row">
                <LineChart
                  title="Test SOS delivered"
                  xs={field.map((r) => r.hops)}
                  xLabel="Radio hops"
                  series={[{ name: 'ResQMesh', color: OURS, values: Object.fromEntries(field.map((r) => [r.hops, r.delivery_ratio * 100])) }]}
                  yDomain={[0, 100]}
                  yTicks={[0, 25, 50, 75, 100]}
                  format={pct}
                />
                <LineChart
                  title="Median latency"
                  xs={field.map((r) => r.hops)}
                  xLabel="Radio hops"
                  series={[{ name: 'ResQMesh', color: OURS, values: Object.fromEntries(field.map((r) => [r.hops, r.latency_median_ms / 1000])) }]}
                  yDomain={[0, latencyTicks.at(-1)]}
                  yTicks={latencyTicks}
                  format={duration}
                />
                <LineChart
                  title="Radio transmissions per SOS"
                  xs={field.map((r) => r.hops)}
                  xLabel="Radio hops"
                  series={[{ name: 'ResQMesh', color: OURS, values: Object.fromEntries(field.map((r) => [r.hops, r.tx_per_sos])) }]}
                  yDomain={[0, txTicks.at(-1)]}
                  yTicks={txTicks}
                  format={count}
                />
              </div>
            </>
          )}
        </section>
      </main>
    </div>
  );
}

function Stat({ value, label, detail }) {
  return (
    <div className="stat">
      <div className="stat-value">{value}</div>
      <div className="stat-label">{label}</div>
      <div className="muted small">{detail}</div>
    </div>
  );
}

/** Four evenly spaced round ticks from 0 to at least [max]. */
function ticks(max) {
  const step = [0.1, 0.2, 0.25, 0.5, 1, 2, 2.5, 5, 10, 20, 25, 50].find((s) => s * 4 >= max) ?? Math.ceil(max / 4);
  return [0, step, step * 2, step * 3, step * 4];
}
