// The operator API (TRD 2.10). Same origin as this page, so the session cookie rides along.

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function call(method, path, body) {
  const res = await fetch(`/api/v1${path}`, {
    method,
    credentials: 'same-origin',
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 204) return null;
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(res.status, data.error ?? `Server answered ${res.status}`);
  return data;
}

export const api = {
  me: () => call('GET', '/auth/me').then((d) => d.operator),
  login: (email, password) => call('POST', '/auth/login', { email, password }).then((d) => d.operator),
  logout: () => call('POST', '/auth/logout'),
  list: ({ test }) => call('GET', `/sos${test ? '?test=1' : ''}`).then((d) => d.sos),
  get: (id) => call('GET', `/sos/${encodeURIComponent(id)}`).then((d) => d.sos),
  reply: (id, reply) => call('POST', `/sos/${encodeURIComponent(id)}/ack`, reply).then((d) => d.ack),
  setStatus: (id, status) => call('PATCH', `/sos/${encodeURIComponent(id)}`, { status }),
};

/**
 * Live updates over Server-Sent Events. Calls [onChange] with each event and [onState] with
 * 'live' or 'reconnecting'; the browser reconnects by itself. Returns a function that closes it.
 */
export function subscribe(onChange, onState) {
  const source = new EventSource('/api/v1/stream', { withCredentials: true });
  source.onopen = () => onState('live');
  source.onerror = () => onState('reconnecting');
  for (const name of ['sos.new', 'sos.updated', 'ack.new']) {
    source.addEventListener(name, (e) => onChange(name, JSON.parse(e.data)));
  }
  return () => source.close();
}
