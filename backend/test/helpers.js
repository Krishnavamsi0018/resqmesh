// Builds signed packets the way the phones do, from the shared test keys.
import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import bcrypt from 'bcryptjs';
import request from 'supertest';
import { createApp } from '../src/app.js';
import { encodePacket, Protocol, serverKeyPair, signEd25519, toBase64Url } from '../src/packet.js';
import { createService } from '../src/service.js';
import { MemoryStore } from '../src/store/memory.js';

const vectors = JSON.parse(readFileSync(new URL('../../shared/test-vectors/packets-v1.json', import.meta.url), 'utf8'));
const seed = (name) => Buffer.from(vectors.keys.find((k) => k.name === name).privateKeyHex, 'hex');

export const keys = {
  sender: serverKeyPair(seed('sender')),
  gateway: serverKeyPair(seed('gateway')),
  server: serverKeyPair(seed('server')),
  stranger: serverKeyPair(Buffer.alloc(32, 7)),
};

export const NOW = 1_793_000_000_000;
export const newId = () => toBase64Url(randomBytes(Protocol.ID_SIZE));

/** Signs [fields] as a body from [key] and wraps it in an envelope, as base64url. */
export function packet(key, type, fields, { ttl = 8, hops = 0, tamper } = {}) {
  const body = { t: type, id: newId(), pk: toBase64Url(key.publicKey), ts: NOW, exp: NOW + Protocol.DEFAULT_LIFETIME_MS, ...fields };
  const bytes = Buffer.from(JSON.stringify(body), 'utf8');
  const signature = signEd25519(key.privateKey, bytes);
  const signed = tamper ? Buffer.from(JSON.stringify({ ...body, ...tamper }), 'utf8') : bytes;
  return { id: body.id, body, envelope: toBase64Url(encodePacket({ type, ttl, hops, body: signed, signature })) };
}

export const sos = (fields = {}, options) =>
  packet(keys.sender, 'SOS', { cat: 'FLOOD', lat: 12.9716, lon: 77.59456, acc: 15, ppl: 2, lang: 'kn', test: false, ...fields }, options);

export const OPERATOR = { email: 'ops@resqmesh.test', password: 'correct horse battery' };

/** A fresh API on an in-memory store with one operator, and a clock the test controls. */
export async function setup() {
  const store = new MemoryStore();
  await store.addOperator({ id: 'op-1', email: OPERATOR.email, name: 'Test Operator', passwordHash: await bcrypt.hash(OPERATOR.password, 4) });
  const clock = { now: NOW + 60_000 };
  const service = createService({ store, serverKeys: keys.server, clock: () => clock.now });
  const app = createApp({ service, store, jwtSecret: 'test-secret', rateLimits: { publicPerMinute: 10_000, loginPerMinute: 10_000 } });
  return { app, store, service, clock, http: request(app) };
}

/** Signs in and returns an agent that keeps the session cookie. */
export async function operator(app) {
  const agent = request.agent(app);
  await agent.post('/api/v1/auth/login').send(OPERATOR).expect(200);
  return agent;
}
