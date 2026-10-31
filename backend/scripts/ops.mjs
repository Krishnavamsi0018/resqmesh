// Operator helper for testing before the dashboard exists (week 7), using the operator in .env.
//   node scripts/ops.mjs list                       newest SOS first, test ones included
//   node scripts/ops.mjs reply <id-start> <CODE> [note]   CODE: TEAM_DISPATCHED | STAY_PUT | MOVE_HIGHER
// API: RESQMESH_API (default http://localhost:8080).
process.loadEnvFile?.(new URL('../.env', import.meta.url));
const base = process.env.RESQMESH_API ?? `http://localhost:${process.env.PORT ?? 8080}`;
const [command, idStart, code, ...note] = process.argv.slice(2);

const login = await fetch(`${base}/api/v1/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: process.env.OPERATOR_EMAIL, password: process.env.OPERATOR_PASSWORD }),
});
if (!login.ok) throw new Error(`login failed: ${login.status}`);
const headers = { Cookie: login.headers.get('set-cookie').split(';')[0], 'Content-Type': 'application/json' };
const { sos } = await (await fetch(`${base}/api/v1/sos?test=1`, { headers })).json();

if (command === 'list') {
  for (const s of sos) {
    const when = new Date(s.createdAt).toLocaleTimeString();
    console.log(`${s.id.slice(0, 6)}  ${when}  ${s.category.padEnd(8)} ${s.status.padEnd(11)} from ${s.nodeId}  people ${s.people}  ` +
      `${s.lat != null ? `${s.lat},${s.lon} ±${s.accuracyM ?? '?'} m` : 'no location'}  gateways ${s.gateways}${s.isTest ? '  TEST' : ''}`);
  }
  if (sos.length === 0) console.log('No SOS yet.');
} else if (command === 'reply') {
  const target = sos.find((s) => s.id.startsWith(idStart ?? '-'));
  if (!target) throw new Error(`no SOS starting with ${idStart}`);
  const res = await fetch(`${base}/api/v1/sos/${target.id}/ack`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ code, by: 'Control room (test)', ...(note.length ? { msg: note.join(' ') } : {}) }),
  });
  console.log(res.status === 201 ? `Replied ${code} to ${target.id.slice(0, 6)}` : `Failed: ${res.status} ${await res.text()}`);
} else {
  console.log('Usage: node scripts/ops.mjs list | reply <id-start> <TEAM_DISPATCHED|STAY_PUT|MOVE_HIGHER> [note]');
}
