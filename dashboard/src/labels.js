// Words, colours and icons shared by the list, the map and the detail view. Category colours match
// the app tiles (design spec 3.2), so a Flood pin is the same blue as the Flood tile on the phone.

export const CATEGORIES = {
  GENERAL: { label: 'Emergency', color: '#B3261E', icon: 'sos' },
  MEDICAL: { label: 'Medical', color: '#C2185B', icon: 'medical_services' },
  FLOOD: { label: 'Flood', color: '#1565C0', icon: 'flood' },
  FIRE: { label: 'Fire', color: '#E65100', icon: 'local_fire_department' },
  TRAPPED: { label: 'Trapped', color: '#6A1B9A', icon: 'crisis_alert' },
  WATER: { label: 'Need water', color: '#00838F', icon: 'water_drop' },
};

export const category = (code) => CATEGORIES[code] ?? CATEGORIES.GENERAL;

export const STATUSES = {
  NEW: { label: 'New', tone: 'new' },
  IN_PROGRESS: { label: 'In progress', tone: 'progress' },
  RESOLVED: { label: 'Resolved', tone: 'done' },
  FALSE_ALARM: { label: 'False alarm', tone: 'closed' },
  CANCELLED: { label: 'Sender safe', tone: 'closed' },
};

/** Statuses an operator can set; CANCELLED only ever comes from the sender's phone. */
export const OPERATOR_STATUSES = ['IN_PROGRESS', 'RESOLVED', 'FALSE_ALARM'];

export const isOpen = (sos) => sos.status === 'NEW' || sos.status === 'IN_PROGRESS';

/** The three replies a phone can show in the sender's language (F-54). */
export const REPLY_CODES = {
  TEAM_DISPATCHED: { label: 'Team is on the way', icon: 'directions_run' },
  STAY_PUT: { label: 'Stay where you are', icon: 'pin_drop' },
  MOVE_HIGHER: { label: 'Move to higher ground', icon: 'terrain' },
};

/** One-tap messages for the reply box; operators can edit them before sending. */
export const REPLY_TEMPLATES = [
  { code: 'TEAM_DISPATCHED', msg: 'Boat in 20 minutes' },
  { code: 'TEAM_DISPATCHED', msg: 'Ambulance on the way' },
  { code: 'STAY_PUT', msg: 'Stay where you are, keep your phone on' },
  { code: 'MOVE_HIGHER', msg: 'Go to the nearest roof or upper floor' },
];

export const CANCEL_REASONS = {
  SAFE: 'Sender says they are safe',
  MISTAKE: 'Sender says it was sent by mistake',
};

export const LANGUAGES = { en: 'English', hi: 'Hindi', kn: 'Kannada' };

export function timeOfDay(ms) {
  return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function dateTime(ms) {
  const d = new Date(ms);
  const sameDay = d.toDateString() === new Date().toDateString();
  return sameDay ? timeOfDay(ms) : d.toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

export function ago(ms, now = Date.now()) {
  const s = Math.max(0, Math.round((now - ms) / 1000));
  if (s < 60) return `${s} s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} h ${m % 60} min ago`;
  return `${Math.floor(h / 24)} d ago`;
}

export const shortId = (id) => id.slice(0, 6);
