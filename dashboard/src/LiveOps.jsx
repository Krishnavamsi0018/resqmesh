import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, subscribe } from './api.js';
import { CATEGORIES, isOpen } from './labels.js';
import SosDetail from './SosDetail.jsx';
import SosList from './SosList.jsx';
import SosMap from './SosMap.jsx';

const VIEWS = {
  open: { label: 'Open', test: isOpen },
  closed: { label: 'Closed', test: (s) => !isOpen(s) },
  all: { label: 'All', test: () => true },
};

/** W2 (live map and list) with W3 (detail) opening in the side panel. */
export default function LiveOps({ operator, onLogout, onSignedOut }) {
  const [sos, setSos] = useState([]);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState(null);
  const [view, setView] = useState('open');
  const [categories, setCategories] = useState(() => new Set());
  const [showTest, setShowTest] = useState(() => readPref('showTest', true));
  const [sound, setSound] = useState(() => readPref('sound', true));
  const [selectedId, setSelectedId] = useState(null);
  const [detail, setDetail] = useState(null);
  const [live, setLive] = useState('connecting');
  const [now, setNow] = useState(Date.now());
  const [arrived, setArrived] = useState(() => new Set()); // SOS that arrived while this page was open

  const selectedRef = useRef(selectedId);
  selectedRef.current = selectedId;
  const soundRef = useRef(sound);
  soundRef.current = sound;
  const showTestRef = useRef(showTest);
  showTestRef.current = showTest;

  const handleError = useCallback(
    (err) => {
      if (err.status === 401) onSignedOut();
      else setLoadError(err.message);
    },
    [onSignedOut],
  );

  const loadList = useCallback(async () => {
    try {
      setSos(await api.list({ test: showTestRef.current }));
      setLoadError(null);
      setLoaded(true);
    } catch (err) {
      handleError(err);
    }
  }, [handleError]);

  const loadDetail = useCallback(
    async (id) => {
      try {
        const found = await api.get(id);
        if (selectedRef.current === id) setDetail(found);
      } catch (err) {
        if (err.status === 404 && selectedRef.current === id) setSelectedId(null);
        else handleError(err);
      }
    },
    [handleError],
  );

  // The list, again whenever the test toggle changes, and once a minute as a safety net.
  useEffect(() => {
    loadList();
    const timer = setInterval(loadList, 60_000);
    return () => clearInterval(timer);
  }, [loadList, showTest]);

  // Live updates: refresh what changed, at most a few times a second.
  useEffect(() => {
    let pending = null;
    let wasDown = false;
    const close = subscribe(
      (name, data) => {
        if (name === 'sos.new') {
          setArrived((prev) => new Set(prev).add(data.id));
          if (soundRef.current) beep();
        }
        const id = data.sosId ?? data.id;
        if (id === selectedRef.current) loadDetail(id);
        clearTimeout(pending);
        pending = setTimeout(loadList, 250);
      },
      (state) => {
        setLive(state);
        if (state === 'reconnecting') {
          wasDown = true;
          api.me().catch((err) => err.status === 401 && onSignedOut());
        } else if (wasDown) {
          wasDown = false;
          loadList(); // catch up on anything missed while disconnected
          if (selectedRef.current) loadDetail(selectedRef.current);
        }
      },
    );
    return () => {
      clearTimeout(pending);
      close();
    };
  }, [loadList, loadDetail, onSignedOut]);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 15_000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    setDetail(null);
    if (selectedId) loadDetail(selectedId);
  }, [selectedId, loadDetail]);

  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && setSelectedId(null);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const counts = useMemo(
    () => ({
      new: sos.filter((s) => s.status === 'NEW').length,
      progress: sos.filter((s) => s.status === 'IN_PROGRESS').length,
      people: sos.filter(isOpen).reduce((sum, s) => sum + s.people, 0),
    }),
    [sos],
  );

  useEffect(() => {
    document.title = counts.new ? `(${counts.new}) New SOS · ResQMesh` : 'ResQMesh Control Room';
  }, [counts.new]);

  const shown = useMemo(
    () => sos.filter((s) => VIEWS[view].test(s) && (categories.size === 0 || categories.has(s.category))),
    [sos, view, categories],
  );

  const toggleCategory = (code) =>
    setCategories((prev) => {
      const next = new Set(prev);
      if (next.has(code)) next.delete(code);
      else next.add(code);
      return next;
    });

  const changeShowTest = (value) => {
    setShowTest(value);
    writePref('showTest', value);
  };
  const changeSound = (value) => {
    setSound(value);
    writePref('sound', value);
  };

  const select = (id) => {
    setSelectedId(id);
    setArrived((prev) => {
      if (!prev.has(id)) return prev;
      const next = new Set(prev);
      next.delete(id);
      return next;
    });
  };

  const afterChange = () => {
    loadList();
    if (selectedRef.current) loadDetail(selectedRef.current);
  };

  return (
    <div className="ops">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">SOS</span>
          <strong>ResQMesh</strong>
          <span className="muted">Control room</span>
        </div>
        <div className="counts" aria-label="Summary">
          <Count value={counts.new} label="new" tone="new" />
          <Count value={counts.progress} label="in progress" tone="progress" />
          <Count value={counts.people} label="people waiting" />
        </div>
        <div className="topbar-right">
          <a className="btn btn-quiet" href="#results">
            <span className="material-symbols-outlined" aria-hidden="true">monitoring</span> Results
          </a>
          <span className={`live live-${live}`} title={live === 'live' ? 'Receiving updates as they happen' : 'Connection lost, reconnecting'}>
            <span className="live-dot" />
            {live === 'live' ? 'Live' : live === 'connecting' ? 'Connecting…' : 'Reconnecting…'}
          </span>
          <button className="icon-btn" onClick={() => changeSound(!sound)} title={sound ? 'Sound on for new SOS' : 'Sound off'} aria-pressed={sound}>
            <span className="material-symbols-outlined" aria-hidden="true">{sound ? 'notifications_active' : 'notifications_off'}</span>
          </button>
          <span className="muted operator-name">{operator.name}</span>
          <button className="btn btn-quiet" onClick={onLogout}>
            Sign out
          </button>
        </div>
      </header>

      <div className="workspace">
        <aside className="panel">
          {selectedId ? (
            <SosDetail id={selectedId} sos={detail} now={now} operator={operator} onBack={() => setSelectedId(null)} onChanged={afterChange} onError={handleError} />
          ) : (
            <>
              <div className="filters">
                <div className="segmented" role="tablist" aria-label="Which SOS">
                  {Object.entries(VIEWS).map(([key, v]) => (
                    <button key={key} role="tab" aria-selected={view === key} className={view === key ? 'on' : ''} onClick={() => setView(key)}>
                      {v.label}
                    </button>
                  ))}
                </div>
                <div className="chips" aria-label="Categories">
                  {Object.entries(CATEGORIES).map(([code, c]) => (
                    <button
                      key={code}
                      className={`chip ${categories.has(code) ? 'on' : ''}`}
                      style={{ '--cat': c.color }}
                      aria-pressed={categories.has(code)}
                      onClick={() => toggleCategory(code)}
                    >
                      <span className="material-symbols-outlined" aria-hidden="true">{c.icon}</span>
                      {c.label}
                    </button>
                  ))}
                </div>
                <label className="check">
                  <input type="checkbox" checked={showTest} onChange={(e) => changeShowTest(e.target.checked)} />
                  Show test SOS
                </label>
              </div>
              {loadError && <p className="error banner">Could not load SOS: {loadError}</p>}
              <SosList sos={shown} loaded={loaded} now={now} arrived={arrived} onSelect={select} emptyHint={VIEWS[view].label} />
            </>
          )}
        </aside>
        <section className="map-area" aria-label="Map">
          <SosMap sos={shown} selected={detail ?? sos.find((s) => s.id === selectedId)} onSelect={select} />
        </section>
      </div>
    </div>
  );
}

function Count({ value, label, tone }) {
  return (
    <span className={`count ${tone ? `count-${tone}` : ''} ${tone === 'new' && value > 0 ? 'count-alert' : ''}`}>
      <strong>{value}</strong> {label}
    </span>
  );
}

function readPref(key, fallback) {
  try {
    const value = localStorage.getItem(`resqmesh.${key}`);
    return value == null ? fallback : value === 'true';
  } catch {
    return fallback;
  }
}

function writePref(key, value) {
  try {
    localStorage.setItem(`resqmesh.${key}`, String(value));
  } catch {
    // private window: the choice lasts until reload
  }
}

/** Two short tones for a new SOS. Browsers allow sound once the operator has clicked the page. */
function beep() {
  try {
    const ctx = new AudioContext();
    [0, 0.25].forEach((start) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.frequency.value = 880;
      gain.gain.setValueAtTime(0.0001, ctx.currentTime + start);
      gain.gain.exponentialRampToValueAtTime(0.25, ctx.currentTime + start + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + start + 0.18);
      osc.connect(gain).connect(ctx.destination);
      osc.start(ctx.currentTime + start);
      osc.stop(ctx.currentTime + start + 0.2);
    });
    setTimeout(() => ctx.close(), 1000);
  } catch {
    // no audio available
  }
}
