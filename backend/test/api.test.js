import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { get } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, test } from 'node:test';
import { decodePacket, fromBase64Url, signEd25519, toBase64Url, validatePacket } from '../src/packet.js';
import { nodeIdOf, registrationMessage } from '../src/service.js';
import { keys, newId, NOW, operator, packet, setup, sos } from './helpers.js';

const upload = (http, packets, gateway = 'BA81-12FA') => http.post('/api/v1/packets').send({ gateway, packets });

describe('gateway upload (F-40)', () => {
  test('a valid SOS is stored once, and each gateway sighting is counted', async () => {
    const { app, http } = await setup();
    const flood = sos();

    const first = await upload(http, [flood.envelope]).expect(200);
    assert.deepEqual(first.body.accepted, [flood.id]);
    assert.equal(first.body.results[0].status, 'stored');

    const relayed = sos({}, { hops: 3 }); // a different SOS, to check that IDs don't mix
    const again = await upload(http, [flood.envelope, relayed.envelope], 'C000-0001').expect(200);
    assert.deepEqual(again.body.results.map((r) => r.status), ['duplicate', 'stored']);

    const ops = await operator(app);
    const detail = (await ops.get(`/api/v1/sos/${flood.id}`).expect(200)).body.sos;
    assert.equal(detail.gateways, 2);
    assert.equal(detail.category, 'FLOOD');
    assert.equal(detail.lat, 12.9716);
    assert.equal(detail.nodeId, nodeIdOf(keys.sender.publicKey));
    assert.equal(detail.status, 'NEW');
  });

  test('bad packets are rejected one by one without failing the batch', async () => {
    const { http } = await setup();
    const good = sos();
    const tampered = sos({}, { tamper: { lat: 13.5 } });
    const res = await upload(http, [good.envelope, tampered.envelope, 'not*base64', toBase64Url(Buffer.from('RQ'))]).expect(200);
    assert.deepEqual(res.body.accepted, [good.id]);
    assert.deepEqual(res.body.rejected, [
      { index: 1, reason: 'BAD_SIGNATURE' },
      { index: 2, reason: 'NOT_BASE64URL' },
      { index: 3, reason: 'MALFORMED_TOO_SHORT' },
    ]);
  });

  test('requests outside the limits are refused', async () => {
    const { http } = await setup();
    await http.post('/api/v1/packets').send({ packets: [] }).expect(400);
    await http.post('/api/v1/packets').send({ packets: Array(51).fill(sos().envelope) }).expect(400);
    await http.post('/api/v1/packets').send({ gateway: 'nobody', packets: [sos().envelope] }).expect(400);
    await http.post('/api/v1/packets').set('Content-Type', 'application/json').send('{oops').expect(400);
  });

  test('an SOS ID already used by another key is refused', async () => {
    const { http } = await setup();
    const real = sos();
    await upload(http, [real.envelope]);
    const squat = packet(keys.stranger, 'SOS', { id: real.id, cat: 'FIRE' });
    const res = await upload(http, [squat.envelope]).expect(200);
    assert.deepEqual(res.body.rejected, [{ index: 0, reason: 'ID_CONFLICT' }]);
  });
});

describe('cancels and gateway acks', () => {
  test("only the sender's key can cancel, and the dashboard shows why", async () => {
    const { app, http } = await setup();
    const flood = sos();
    await upload(http, [flood.envelope]);

    const forged = packet(keys.stranger, 'CANCEL', { ref: flood.id, reason: 'MISTAKE' });
    assert.deepEqual((await upload(http, [forged.envelope])).body.rejected, [{ index: 0, reason: 'CANCEL_KEY_MISMATCH' }]);

    const safe = packet(keys.sender, 'CANCEL', { ref: flood.id, reason: 'SAFE' });
    await upload(http, [safe.envelope]).expect(200);
    const detail = (await (await operator(app)).get(`/api/v1/sos/${flood.id}`)).body.sos;
    assert.equal(detail.status, 'CANCELLED');
    assert.equal(detail.cancelReason, 'SAFE');
  });

  test('a cancel that overtakes its SOS is applied when the SOS arrives', async () => {
    const { app, http } = await setup();
    const flood = sos();
    const safe = packet(keys.sender, 'CANCEL', { ref: flood.id, reason: 'SAFE' });
    await upload(http, [safe.envelope, flood.envelope]);
    const detail = (await (await operator(app)).get(`/api/v1/sos/${flood.id}`)).body.sos;
    assert.equal(detail.status, 'CANCELLED');
  });

  test('a gateway ack with sms marks the SOS as texted', async () => {
    const { app, http } = await setup();
    const flood = sos();
    const texted = packet(keys.gateway, 'ACK', { ref: flood.id, kind: 'GATEWAY', up: false, sms: true });
    await upload(http, [flood.envelope, texted.envelope]);
    const detail = (await (await operator(app)).get(`/api/v1/sos/${flood.id}`)).body.sos;
    assert.equal(detail.smsEscalated, true);
    assert.equal(detail.acks[0].kind, 'GATEWAY');
  });
});

describe('operators (F-50 to F-54)', () => {
  test('sign-in is required, and a wrong password is refused', async () => {
    const { http } = await setup();
    await http.get('/api/v1/sos').expect(401);
    await http.post('/api/v1/auth/login').send({ email: 'ops@resqmesh.test', password: 'nope' }).expect(401);
    await http.post('/api/v1/auth/login').send({ email: 'nobody@x.test', password: 'nope' }).expect(401);
  });

  test('the list puts new SOS first and hides test packets unless asked', async () => {
    const { app, http, clock } = await setup();
    const older = sos({ ts: NOW - 60_000 });
    const handled = sos({ ts: NOW });
    const drill = sos({ test: true });
    await upload(http, [older.envelope, handled.envelope, drill.envelope]);
    const ops = await operator(app);
    await ops.patch(`/api/v1/sos/${handled.id}`).send({ status: 'IN_PROGRESS' }).expect(200);
    clock.now += 1000;

    const list = (await ops.get('/api/v1/sos').expect(200)).body.sos;
    assert.deepEqual(list.map((s) => s.id), [older.id, handled.id]);
    const withTests = (await ops.get('/api/v1/sos?test=1').expect(200)).body.sos;
    assert.equal(withTests.length, 3);
    assert.equal(list[0].envelope, undefined, 'raw packets stay on the server');
  });

  test('a reply is signed with the server key, and phones accept it', async () => {
    const { app, http } = await setup();
    const flood = sos();
    await upload(http, [flood.envelope]);
    const ops = await operator(app);

    const ack = (await ops.post(`/api/v1/sos/${flood.id}/ack`).send({ code: 'TEAM_DISPATCHED', msg: 'Boat in 20 min', by: 'NDRF 4' }).expect(201)).body.ack;
    assert.equal(ack.code, 'TEAM_DISPATCHED');
    assert.equal((await ops.get(`/api/v1/sos/${flood.id}`)).body.sos.status, 'IN_PROGRESS');

    // An online phone pulls it (F-44), and the gateway's next upload of that SOS gets it too.
    const pulled = (await http.get(`/api/v1/acks?ids=${flood.id}`).expect(200)).body.acks;
    assert.equal(pulled.length, 1);
    assert.deepEqual((await upload(http, [flood.envelope])).body.acks, pulled);

    // The phones' rules: a RESPONDER ack is valid only when signed by the pinned server key.
    const verdict = validatePacket(decodePacket(fromBase64Url(pulled[0])), { serverPublicKey: keys.server.publicKey, now: NOW + 120_000 });
    assert.equal(verdict.ok, true, verdict.reason);
    assert.equal(verdict.body.ref, flood.id);
    assert.equal(verdict.body.msg, 'Boat in 20 min');
  });

  test('replies use the fixed codes only', async () => {
    const { app, http } = await setup();
    const flood = sos();
    await upload(http, [flood.envelope]);
    const ops = await operator(app);
    await ops.post(`/api/v1/sos/${flood.id}/ack`).send({ code: 'SEND_MONEY' }).expect(400);
    await ops.post(`/api/v1/sos/${newId()}/ack`).send({ code: 'STAY_PUT' }).expect(404);
    await ops.patch(`/api/v1/sos/${flood.id}`).send({ status: 'NEW' }).expect(400);
  });

  test('the live stream announces a new SOS', { timeout: 10_000 }, async () => {
    const { app, http } = await setup();
    const login = await http.post('/api/v1/auth/login').send({ email: 'ops@resqmesh.test', password: 'correct horse battery' });
    const cookie = login.headers['set-cookie'][0].split(';')[0];
    const server = app.listen(0);
    await once(server, 'listening');
    try {
      const res = await new Promise((resolve) => get({ port: server.address().port, path: '/api/v1/stream', headers: { Cookie: cookie } }, resolve));
      assert.match(res.headers['content-type'], /^text\/event-stream/);
      let text = '';
      const seen = new Promise((resolve) => res.on('data', (chunk) => (text += chunk) && text.includes('event: sos.new') && resolve()));
      const flood = sos();
      await upload(http, [flood.envelope]);
      await seen;
      assert.match(text, new RegExp(`data: \\{"id":"${flood.id}"\\}`));
      res.destroy();
    } finally {
      server.closeAllConnections();
      server.close();
    }
  });
});

describe('device registration (F-33)', () => {
  const register = (key, overrides = {}) => {
    const fields = { publicKey: toBase64Url(key.publicKey), ts: NOW + 60_000, name: 'Asha', phone: '+91 98450 00000', ...overrides };
    const signature = toBase64Url(signEd25519(key.privateKey, registrationMessage(fields)));
    return { ...fields, signature, ...overrides };
  };

  test('a phone that proves its key is shown as registered', async () => {
    const { app, http } = await setup();
    const flood = sos();
    await upload(http, [flood.envelope]);
    const res = await http.post('/api/v1/devices').send(register(keys.sender)).expect(201);
    assert.equal(res.body.nodeId, nodeIdOf(keys.sender.publicKey));
    const detail = (await (await operator(app)).get(`/api/v1/sos/${flood.id}`)).body.sos;
    assert.equal(detail.registered, true);
    assert.deepEqual(detail.sender, { name: 'Asha', phone: '+91 98450 00000' });
  });

  test("nobody can register someone else's key, or replay an old registration", async () => {
    const { http } = await setup();
    const stolen = { ...register(keys.stranger), publicKey: toBase64Url(keys.sender.publicKey) };
    assert.equal((await http.post('/api/v1/devices').send(stolen).expect(400)).body.error, 'BAD_SIGNATURE');
    const stale = register(keys.sender, { ts: NOW - 3_600_000 });
    assert.equal((await http.post('/api/v1/devices').send(stale).expect(400)).body.error, 'STALE');
  });
});

test('the server key endpoint serves the public half of the signing key', async () => {
  const { http } = await setup();
  const res = await http.get('/api/v1/server-key').expect(200);
  assert.equal(res.body.publicKey, toBase64Url(keys.server.publicKey));
});

describe('dashboard hosting', () => {
  const dashboardDir = mkdtempSync(join(tmpdir(), 'resqmesh-dashboard-'));
  mkdirSync(join(dashboardDir, 'assets'));
  writeFileSync(join(dashboardDir, 'index.html'), '<!doctype html><title>ResQMesh</title>');
  writeFileSync(join(dashboardDir, 'assets', 'index-abc123.js'), 'console.log(1)');

  test('pages load the app with a strict content policy; assets are cached for good', async () => {
    const { http } = await setup({ dashboardDir });
    for (const path of ['/', '/sos/some-deep-link']) {
      const page = await http.get(path).expect(200);
      assert.match(page.text, /<title>ResQMesh<\/title>/);
      assert.match(page.headers['content-security-policy'], /script-src 'self'/);
      assert.equal(page.headers['cache-control'], 'no-cache');
    }
    const asset = await http.get('/assets/index-abc123.js').expect(200);
    assert.match(asset.headers['cache-control'], /immutable/);
  });

  test('unknown API paths still answer JSON, not the dashboard', async () => {
    const { http } = await setup({ dashboardDir });
    const res = await http.get('/api/v1/nothing-here').expect(404);
    assert.deepEqual(res.body, { error: 'Not found' });
  });
});
