// HTTP API v1 (TRD 2.10). Every body is checked with zod and capped at 1 MB; public endpoints are
// rate-limited per IP. The dashboard reaches the API through its own origin (Vite proxy in
// development; in production this server hosts the built dashboard), so the session cookie is
// first-party and no CORS is needed.

import bcrypt from 'bcryptjs';
import cookieParser from 'cookie-parser';
import express from 'express';
import jwt from 'jsonwebtoken';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { z } from 'zod';
import { MAX_BATCH, OperatorStatuses, ResponderCodes } from './service.js';
import { Protocol, toBase64Url } from './packet.js';

const SESSION_COOKIE = 'rq_session';
const SESSION_HOURS = 12;
const nodeId = z.string().regex(/^[0-9A-F]{4}-[0-9A-F]{4}$/);
const packetId = z.string().regex(/^[A-Za-z0-9_-]{22}$/);

const UploadBody = z.object({
  gateway: nodeId.optional(),
  packets: z.array(z.string().max(2 * Protocol.MAX_PACKET_SIZE)).min(1).max(MAX_BATCH),
});
const DeviceBody = z.object({
  publicKey: z.string().max(64),
  ts: z.number().int(),
  name: z.string().trim().min(1).max(60).optional(),
  phone: z.string().trim().regex(/^\+?[0-9 ]{6,20}$/).optional(),
  signature: z.string().max(128),
});
const LoginBody = z.object({ email: z.string().trim().toLowerCase().max(200), password: z.string().max(200) });
const AckBody = z.object({
  code: z.enum(ResponderCodes),
  msg: z.string().trim().max(Protocol.MAX_ACK_MESSAGE_LENGTH).optional(),
  by: z.string().trim().max(Protocol.MAX_UNIT_NAME_LENGTH).optional(),
});
const StatusBody = z.object({ status: z.enum(OperatorStatuses) });
const ListQuery = z.object({
  status: z.enum(['NEW', 'IN_PROGRESS', 'RESOLVED', 'FALSE_ALARM', 'CANCELLED']).optional(),
  category: z.enum(['MEDICAL', 'FLOOD', 'FIRE', 'TRAPPED', 'WATER', 'GENERAL']).optional(),
  since: z.coerce.number().int().optional(),
  test: z.enum(['0', '1']).optional(),
});

export function createApp({ service, store, jwtSecret, secureCookies = false, rateLimits = {}, dashboardDir = null }) {
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', 1); // Render's proxy sits in front; req.ip is the client.
  app.use((req, res, next) => {
    res.set({ 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', 'Cache-Control': 'no-store' });
    next();
  });
  app.use(express.json({ limit: '1mb' }));
  app.use(cookieParser());

  const publicLimit = rateLimit(rateLimits.publicPerMinute ?? 120);
  const loginLimit = rateLimit(rateLimits.loginPerMinute ?? 10);

  // Phones and anyone.
  app.get('/api/v1/health', (req, res) => res.json({ ok: true }));

  app.get('/api/v1/server-key', publicLimit, (req, res) => res.json({ publicKey: toBase64Url(service.serverPublicKey) }));

  app.post('/api/v1/packets', publicLimit, async (req, res) => {
    const { gateway, packets } = UploadBody.parse(req.body);
    const { results, acks } = await service.ingest({ packets, gateway });
    res.json({
      accepted: results.filter((r) => r.status !== 'rejected').map((r) => r.id),
      rejected: results.filter((r) => r.status === 'rejected').map(({ index, reason }) => ({ index, reason })),
      results,
      acks,
    });
  });

  app.get('/api/v1/acks', publicLimit, async (req, res) => {
    const ids = String(req.query.ids ?? '').split(',').filter(Boolean);
    z.array(packetId).max(100).parse(ids);
    res.json({ acks: await service.acksFor(ids) });
  });

  app.post('/api/v1/devices', publicLimit, async (req, res) => {
    const result = await service.registerDevice(DeviceBody.parse(req.body));
    if (!result.ok) return res.status(400).json({ error: result.reason });
    res.status(201).json({ nodeId: result.device.nodeId });
  });

  // Operators.
  app.post('/api/v1/auth/login', loginLimit, async (req, res) => {
    const { email, password } = LoginBody.parse(req.body);
    const operator = await store.findOperatorByEmail(email);
    // Compare against a dummy hash when the email is unknown, so timing doesn't reveal which emails exist.
    const ok = await bcrypt.compare(password, operator?.passwordHash ?? DUMMY_HASH);
    if (!operator || !ok) return res.status(401).json({ error: 'Wrong email or password' });
    const token = jwt.sign({ sub: operator.id }, jwtSecret, { expiresIn: `${SESSION_HOURS}h` });
    res.cookie(SESSION_COOKIE, token, {
      httpOnly: true,
      secure: secureCookies,
      sameSite: 'lax',
      maxAge: SESSION_HOURS * 3600 * 1000,
      path: '/api',
    });
    res.json({ operator: publicOperator(operator) });
  });

  app.post('/api/v1/auth/logout', (req, res) => {
    res.clearCookie(SESSION_COOKIE, { path: '/api' });
    res.status(204).end();
  });

  const requireOperator = async (req, res, next) => {
    try {
      const { sub } = jwt.verify(req.cookies[SESSION_COOKIE] ?? '', jwtSecret);
      req.operator = await store.getOperator(sub);
    } catch {
      req.operator = null;
    }
    if (!req.operator) return res.status(401).json({ error: 'Sign in first' });
    next();
  };

  app.get('/api/v1/auth/me', requireOperator, (req, res) => res.json({ operator: publicOperator(req.operator) }));

  app.get('/api/v1/sos', requireOperator, async (req, res) => {
    const q = ListQuery.parse(req.query);
    res.json({ sos: await service.listSos({ ...q, includeTest: q.test === '1' }) });
  });

  app.get('/api/v1/sos/:id', requireOperator, async (req, res) => {
    const sos = await service.getSos(packetId.parse(req.params.id));
    if (!sos) return res.status(404).json({ error: 'No such SOS' });
    res.json({ sos });
  });

  app.post('/api/v1/sos/:id/ack', requireOperator, async (req, res) => {
    const ack = await service.respond(packetId.parse(req.params.id), AckBody.parse(req.body), req.operator.id);
    if (!ack) return res.status(404).json({ error: 'No such SOS' });
    const { envelope, signerPk, ...shown } = ack;
    res.status(201).json({ ack: shown });
  });

  app.patch('/api/v1/sos/:id', requireOperator, async (req, res) => {
    const sos = await service.setStatus(packetId.parse(req.params.id), StatusBody.parse(req.body).status);
    if (!sos) return res.status(404).json({ error: 'No such SOS' });
    res.json({ status: sos.status });
  });

  // Live updates for the dashboard (Server-Sent Events): sos.new, sos.updated, ack.new.
  app.get('/api/v1/stream', requireOperator, (req, res) => {
    res.set({ 'Content-Type': 'text/event-stream', Connection: 'keep-alive', 'X-Accel-Buffering': 'no' });
    res.flushHeaders();
    res.write('retry: 3000\n\n');
    const names = ['sos.new', 'sos.updated', 'ack.new'];
    const handlers = names.map((name) => {
      const handler = (data) => res.write(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`);
      service.events.on(name, handler);
      return [name, handler];
    });
    // Comments keep proxies from closing an idle stream.
    const heartbeat = setInterval(() => res.write(': ping\n\n'), 25_000);
    req.on('close', () => {
      clearInterval(heartbeat);
      for (const [name, handler] of handlers) service.events.off(name, handler);
    });
  });

  app.use('/api', (req, res) => res.status(404).json({ error: 'Not found' }));

  // The operator dashboard (dashboard/dist), when built: same origin as the API.
  if (dashboardDir && existsSync(join(dashboardDir, 'index.html'))) {
    app.use((req, res, next) => {
      // OpenStreetMap asks browsers to send a Referer with tile requests.
      res.set({ 'Content-Security-Policy': DASHBOARD_CSP, 'Referrer-Policy': 'strict-origin-when-cross-origin', 'X-Frame-Options': 'DENY' });
      next();
    });
    app.use(
      express.static(dashboardDir, {
        index: false,
        setHeaders: (res, path) => {
          // Vite puts a content hash in every asset name, so assets never change under one URL.
          if (/[\\/]assets[\\/]/.test(path)) res.set('Cache-Control', 'public, max-age=31536000, immutable');
        },
      }),
    );
    app.get('/{*page}', (req, res) => res.sendFile(join(dashboardDir, 'index.html'), { headers: { 'Cache-Control': 'no-cache' } }));
  }

  // Express 5 forwards rejected promises here.
  app.use((err, req, res, next) => {
    if (err instanceof z.ZodError) return res.status(400).json({ error: 'Invalid request', issues: err.issues.slice(0, 5) });
    if (err.type === 'entity.too.large') return res.status(413).json({ error: 'Too large' });
    if (err.type === 'entity.parse.failed') return res.status(400).json({ error: 'Invalid JSON' });
    console.error(err);
    res.status(500).json({ error: 'Server error' });
  });

  return app;
}

const DASHBOARD_CSP = [
  "default-src 'self'",
  "script-src 'self'",
  // Leaflet positions tiles and pins with inline styles.
  "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
  "font-src https://fonts.gstatic.com",
  "img-src 'self' data: https://tile.openstreetmap.org",
  "connect-src 'self'",
  "frame-ancestors 'none'",
  "base-uri 'none'",
  "form-action 'self'",
].join('; ');

const publicOperator = ({ id, email, name }) => ({ id, email, name });

const DUMMY_HASH = bcrypt.hashSync('not-a-real-password', 10);

/** Fixed one-minute window per client IP; enough for one server instance (TRD 2.10). */
function rateLimit(perMinute) {
  const windows = new Map();
  return (req, res, next) => {
    const minute = Math.floor(Date.now() / 60_000);
    const key = `${req.ip}:${minute}`;
    const count = (windows.get(key) ?? 0) + 1;
    windows.set(key, count);
    if (windows.size > 10_000) {
      for (const k of windows.keys()) if (!k.endsWith(`:${minute}`)) windows.delete(k);
    }
    if (count > perMinute) return res.status(429).json({ error: 'Too many requests' });
    next();
  };
}
