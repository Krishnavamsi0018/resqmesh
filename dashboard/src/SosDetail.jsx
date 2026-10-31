import { useEffect, useState } from 'react';
import { api } from './api.js';
import {
  ago,
  CANCEL_REASONS,
  category,
  dateTime,
  isOpen,
  LANGUAGES,
  OPERATOR_STATUSES,
  REPLY_CODES,
  REPLY_TEMPLATES,
  STATUSES,
  timeOfDay,
} from './labels.js';

const MAX_MESSAGE = 160; // Protocol.MAX_ACK_MESSAGE_LENGTH
const MAX_UNIT = 60; // Protocol.MAX_UNIT_NAME_LENGTH

/** W3: everything about one SOS, its path to the server, and the reply that goes back to the sender. */
export default function SosDetail({ id, sos, now, operator, onBack, onChanged, onError }) {
  if (!sos || sos.id !== id) {
    return (
      <div className="detail">
        <BackButton onBack={onBack} />
        <p className="empty">Loading…</p>
      </div>
    );
  }

  const c = category(sos.category);
  const status = STATUSES[sos.status] ?? { label: sos.status, tone: 'closed' };
  const expired = sos.expiresAt < now;
  const replies = sos.acks.filter((a) => a.kind === 'RESPONDER');

  return (
    <div className="detail">
      <BackButton onBack={onBack} />

      <div className="detail-head" style={{ '--cat': c.color }}>
        <span className="cat-badge cat-badge-large" style={{ background: c.color }} aria-hidden="true">
          <span className="material-symbols-outlined" aria-hidden="true">{c.icon}</span>
        </span>
        <div>
          <h2>
            {c.label} <span className="muted">· {sos.people === 1 ? '1 person' : `${sos.people} people`}</span>
          </h2>
          <div className="badges">
            <span className={`pill pill-${status.tone}`}>{status.label}</span>
            {sos.isTest && <span className="tag tag-test">TEST</span>}
            {expired && isOpen(sos) && <span className="tag">Expired {ago(sos.expiresAt, now)}</span>}
            <span className="badge badge-ok" title="The phone's Ed25519 signature was checked when this SOS arrived">
              <span className="material-symbols-outlined" aria-hidden="true">verified</span> Signature verified
            </span>
            <span className={`badge ${sos.registered ? 'badge-ok' : 'badge-warn'}`} title="Whether this phone registered its key with the server">
              <span className="material-symbols-outlined" aria-hidden="true">{sos.registered ? 'how_to_reg' : 'help'}</span>
              {sos.registered ? 'Registered phone' : 'Unknown phone'}
            </span>
          </div>
        </div>
      </div>

      {sos.status === 'CANCELLED' && (
        <div className="callout callout-safe">
          <span className="material-symbols-outlined" aria-hidden="true">check_circle</span>
          <div>
            <strong>{CANCEL_REASONS[sos.cancelReason] ?? 'Cancelled by the sender'}</strong>
            {sos.cancelledAt && <div className="muted small">at {dateTime(sos.cancelledAt)}</div>}
          </div>
        </div>
      )}

      <dl className="facts">
        <Fact label="Sent">
          {dateTime(sos.createdAt)} <span className="muted">({ago(sos.createdAt, now)})</span>
        </Fact>
        <Fact label="Reached server">
          {dateTime(sos.firstReceivedAt)} <span className="muted">({delay(sos.firstReceivedAt - sos.createdAt)} after sending)</span>
        </Fact>
        <Fact label="Location">
          {sos.lat == null ? (
            <span className="muted">Not available (the phone had no GPS fix)</span>
          ) : (
            <>
              <span className="mono">
                {sos.lat.toFixed(5)}, {sos.lon.toFixed(5)}
              </span>
              {sos.accuracyM != null && <span className="muted"> ±{sos.accuracyM} m</span>}
              {sos.fixAgeS != null && sos.fixAgeS > 60 && <span className="muted"> · fix {Math.round(sos.fixAgeS / 60)} min old</span>}
              <div>
                <a href={`https://www.google.com/maps/search/?api=1&query=${sos.lat},${sos.lon}`} target="_blank" rel="noreferrer">
                  Open in Google Maps
                </a>
                {' · '}
                <a href={`https://www.google.com/maps/dir/?api=1&destination=${sos.lat},${sos.lon}`} target="_blank" rel="noreferrer">
                  Directions
                </a>
              </div>
            </>
          )}
        </Fact>
        <Fact label="Sender">
          <span className="mono">{sos.nodeId}</span>
          {sos.sender?.name && <> · {sos.sender.name}</>}
          {sos.sender?.phone && (
            <>
              {' · '}
              <a href={`tel:${sos.sender.phone.replace(/\s/g, '')}`}>{sos.sender.phone}</a>
            </>
          )}
        </Fact>
        {sos.note && <Fact label="Note">{sos.note}</Fact>}
        <Fact label="Phone">
          {sos.battery != null ? `Battery ${sos.battery}%` : 'Battery unknown'} · {LANGUAGES[sos.lang] ?? sos.lang}
        </Fact>
        <Fact label="Delivery">
          {sos.gateways === 1 ? '1 gateway' : `${sos.gateways} gateways`}
          {sos.minHops != null && ` · shortest path ${sos.minHops} ${sos.minHops === 1 ? 'hop' : 'hops'}`}
          {sos.smsEscalated && ' · also sent by SMS'}
        </Fact>
        <Fact label="SOS ID">
          <span className="mono small">{sos.id}</span>
        </Fact>
      </dl>

      {isOpen(sos) && !expired && <ReplyForm sos={sos} operator={operator} hasReplied={replies.length > 0} onSent={onChanged} onError={onError} />}

      <StatusButtons sos={sos} onChanged={onChanged} onError={onError} />

      <section>
        <h3>What happened</h3>
        <Timeline sos={sos} />
      </section>
    </div>
  );
}

function BackButton({ onBack }) {
  return (
    <button className="btn btn-quiet back" onClick={onBack}>
      <span className="material-symbols-outlined" aria-hidden="true">arrow_back</span> All SOS
    </button>
  );
}

function Fact({ label, children }) {
  return (
    <div className="fact">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function ReplyForm({ sos, operator, hasReplied, onSent, onError }) {
  const [code, setCode] = useState('TEAM_DISPATCHED');
  const [msg, setMsg] = useState('');
  const [unit, setUnit] = useState(() => readUnit() ?? operator.name ?? '');
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(null);

  useEffect(() => setSent(null), [sos.id]);

  async function send(e) {
    e.preventDefault();
    setBusy(true);
    try {
      await api.reply(sos.id, { code, ...(msg.trim() ? { msg: msg.trim() } : {}), ...(unit.trim() ? { by: unit.trim() } : {}) });
      saveUnit(unit.trim());
      setSent(Date.now());
      setMsg('');
      onSent();
    } catch (err) {
      onError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="reply" onSubmit={send}>
      <h3>{hasReplied ? 'Send another reply' : 'Reply to the sender'}</h3>
      <div className="templates" aria-label="Quick replies">
        {REPLY_TEMPLATES.map((t) => (
          <button
            type="button"
            key={t.msg}
            className="chip"
            onClick={() => {
              setCode(t.code);
              setMsg(t.msg);
            }}
          >
            {t.msg}
          </button>
        ))}
      </div>
      <fieldset className="codes">
        <legend className="small muted">Shown on the phone in the sender's language</legend>
        {Object.entries(REPLY_CODES).map(([key, r]) => (
          <label key={key} className={`code ${code === key ? 'on' : ''}`}>
            <input type="radio" name="code" value={key} checked={code === key} onChange={() => setCode(key)} />
            <span className="material-symbols-outlined" aria-hidden="true">{r.icon}</span>
            {r.label}
          </label>
        ))}
      </fieldset>
      <label>
        Message <span className="muted small">(optional, shown as typed)</span>
        <textarea rows={2} maxLength={MAX_MESSAGE} value={msg} onChange={(e) => setMsg(e.target.value)} placeholder="Boat in 20 minutes" />
        <span className="counter muted small">
          {msg.length}/{MAX_MESSAGE}
        </span>
      </label>
      <label>
        Signed as
        <input maxLength={MAX_UNIT} value={unit} onChange={(e) => setUnit(e.target.value)} placeholder="Control room" />
      </label>
      <button className="btn btn-primary" type="submit" disabled={busy}>
        <span className="material-symbols-outlined" aria-hidden="true">send</span>
        {busy ? 'Sending…' : 'Send reply'}
      </button>
      {sent && (
        <p className="success small" role="status">
          Reply signed and queued at {timeOfDay(sent)}. It reaches the sender when a gateway phone next checks in (usually within 30 s).
        </p>
      )}
    </form>
  );
}

function StatusButtons({ sos, onChanged, onError }) {
  const [busy, setBusy] = useState(null);
  async function set(status) {
    setBusy(status);
    try {
      await api.setStatus(sos.id, status);
      onChanged();
    } catch (err) {
      onError(err);
    } finally {
      setBusy(null);
    }
  }
  return (
    <section className="status-actions">
      <h3>Mark as</h3>
      <div className="row">
        {OPERATOR_STATUSES.map((s) => (
          <button key={s} className={`btn ${sos.status === s ? 'btn-current' : ''}`} disabled={sos.status === s || busy != null} onClick={() => set(s)}>
            {busy === s ? 'Saving…' : STATUSES[s].label}
          </button>
        ))}
      </div>
    </section>
  );
}

/** The relay path and every reply, oldest first. */
function Timeline({ sos }) {
  const events = [
    { at: sos.createdAt, icon: 'sos', text: `SOS sent from phone ${sos.nodeId}` },
    ...sos.sightings.map((s) => ({
      at: s.receivedAt,
      icon: 'cloud_upload',
      text: `Uploaded by gateway ${s.gatewayNodeId} after ${s.hops} ${s.hops === 1 ? 'hop' : 'hops'} through the mesh`,
    })),
    ...sos.acks.map((a) =>
      a.kind === 'RESPONDER'
        ? {
            at: a.createdAt,
            icon: 'verified',
            tone: 'reply',
            text: `Reply: ${REPLY_CODES[a.code]?.label ?? a.code}${a.message ? `, "${a.message}"` : ''}${a.unit ? ` (${a.unit})` : ''}`,
          }
        : {
            at: a.createdAt,
            icon: a.sms ? 'sms' : 'cloud_done',
            text: `Gateway ${a.gatewayNodeId ?? ''} told the mesh the rescue team was informed${a.sms ? ' by SMS' : ''}`,
          },
    ),
    ...(sos.cancel ? [{ at: sos.cancel.ts, icon: 'check_circle', tone: 'safe', text: CANCEL_REASONS[sos.cancel.reason] ?? 'Cancelled by the sender' }] : []),
  ].sort((a, b) => a.at - b.at);

  return (
    <ol className="timeline">
      {events.map((e, i) => (
        <li key={i} className={e.tone ? `tl-${e.tone}` : ''}>
          <span className="material-symbols-outlined" aria-hidden="true">{e.icon}</span>
          <div>
            <div>{e.text}</div>
            <div className="muted small">{dateTime(e.at)}</div>
          </div>
        </li>
      ))}
    </ol>
  );
}

function delay(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  return m < 60 ? `${m} min ${s % 60} s` : `${Math.floor(m / 60)} h ${m % 60} min`;
}

function readUnit() {
  try {
    return localStorage.getItem('resqmesh.unit');
  } catch {
    return null;
  }
}

function saveUnit(unit) {
  try {
    localStorage.setItem('resqmesh.unit', unit);
  } catch {
    // private window
  }
}
