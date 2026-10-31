import { ago, category, isOpen, shortId, STATUSES } from './labels.js';

/** The SOS list in W2: unacknowledged first, then newest (the server's order). */
export default function SosList({ sos, loaded, now, arrived, onSelect, emptyHint }) {
  if (!loaded) return <p className="empty">Loading SOS…</p>;
  if (sos.length === 0) {
    return (
      <div className="empty">
        <span className="material-symbols-outlined empty-icon" aria-hidden="true">inbox</span>
        <p>No {emptyHint.toLowerCase()} SOS{emptyHint === 'Open' ? ' right now' : ''}.</p>
        <p className="muted small">New SOS appear here as soon as a gateway phone uploads them.</p>
      </div>
    );
  }
  return (
    <ul className="sos-list">
      {sos.map((s) => {
        const c = category(s.category);
        const status = STATUSES[s.status] ?? { label: s.status, tone: 'closed' };
        const expired = isOpen(s) && s.expiresAt < now;
        return (
          <li key={s.id}>
            <button className={`sos-row ${s.status === 'NEW' ? 'is-new' : ''} ${arrived.has(s.id) ? 'just-arrived' : ''}`} onClick={() => onSelect(s.id)}>
              <span className="cat-badge" style={{ background: c.color }} aria-hidden="true">
                <span className="material-symbols-outlined" aria-hidden="true">{c.icon}</span>
              </span>
              <span className="sos-row-main">
                <span className="sos-row-title">
                  {c.label}
                  <span className="muted"> · {s.people === 1 ? '1 person' : `${s.people} people`}</span>
                  {s.isTest && <span className="tag tag-test">TEST</span>}
                </span>
                <span className="sos-row-meta muted">
                  {ago(s.createdAt, now)} · from {s.nodeId}
                  {s.lat == null ? ' · no location' : ''}
                </span>
              </span>
              <span className="sos-row-side">
                <span className={`pill pill-${status.tone}`}>{status.label}</span>
                {expired ? <span className="tag">expired</span> : <span className="mono muted small">{shortId(s.id)}</span>}
              </span>
            </button>
          </li>
        );
      })}
    </ul>
  );
}
