// What the API does, independent of HTTP: gateway uploads (F-40), responder replies (F-54),
// operator views (F-52, F-53) and device registration (F-33). The store decides where data lives.

import { createHash, randomBytes } from 'node:crypto';
import { EventEmitter } from 'node:events';
import {
  decodePacket,
  encodePacket,
  fromBase64Url,
  MalformedPacketError,
  Protocol,
  signEd25519,
  toBase64Url,
  validatePacket,
  verifyEd25519,
} from './packet.js';

export const ResponderCodes = Object.freeze(['TEAM_DISPATCHED', 'STAY_PUT', 'MOVE_HIGHER']);
export const OperatorStatuses = Object.freeze(['IN_PROGRESS', 'RESOLVED', 'FALSE_ALARM']);
export const MAX_BATCH = 50;
const REGISTRATION_WINDOW_MS = 10 * 60 * 1000;

/** Stable phone ID, as the app shows it: first 8 bytes of SHA-256(public key), "7F3A-91C2". */
export function nodeIdOf(publicKey) {
  const hex = createHash('sha256').update(publicKey).digest('hex').slice(0, 16).toUpperCase();
  return `${hex.slice(0, 4)}-${hex.slice(4, 8)}`;
}

/** The exact bytes a phone signs to register its key (F-33). */
export const registrationMessage = ({ publicKey, ts, name, phone }) =>
  Buffer.from(`RQREG1\n${publicKey}\n${ts}\n${name ?? ''}\n${phone ?? ''}`, 'utf8');

export function createService({ store, serverKeys, clock = Date.now }) {
  const events = new EventEmitter();
  events.setMaxListeners(100);
  const serverPublicKey = serverKeys.publicKey;

  /**
   * One gateway upload: each packet is checked like a relay would, stored once, and its sighting
   * recorded. Returns a result per packet plus the responder acks waiting for the SOS it named.
   */
  async function ingest({ packets, gateway }) {
    const now = clock();
    const results = [];
    const touched = new Set();
    for (const [index, encoded] of packets.entries()) {
      const result = await ingestOne(encoded, gateway, now);
      results.push({ index, ...result });
      if (result.sosId) touched.add(result.sosId);
    }
    const acks = await store.acksFor([...touched], 'RESPONDER');
    return { results, acks: acks.map((a) => a.envelope) };
  }

  async function ingestOne(encoded, gateway, now) {
    const bytes = fromBase64Url(encoded);
    if (!bytes) return { status: 'rejected', reason: 'NOT_BASE64URL' };
    let packet;
    try {
      packet = decodePacket(bytes);
    } catch (e) {
      if (e instanceof MalformedPacketError) return { status: 'rejected', reason: `MALFORMED_${e.reason}` };
      throw e;
    }
    const verdict = validatePacket(packet, { serverPublicKey, now });
    if (!verdict.ok) return { status: 'rejected', reason: verdict.reason, detail: verdict.detail };
    const { body } = verdict;
    const envelope = toBase64Url(bytes);
    switch (body.t) {
      case 'SOS':
        return ingestSos(body, packet, envelope, gateway, now);
      case 'CANCEL':
        return ingestCancel(body, now);
      case 'ACK':
        return ingestAck(body, envelope, gateway, now);
    }
  }

  async function ingestSos(body, packet, envelope, gateway, now) {
    const existing = await store.getSos(body.id);
    if (existing && existing.senderPk !== body.pk) return { status: 'rejected', reason: 'ID_CONFLICT', id: body.id };
    // A sighting refers to its SOS row, so the SOS is stored first.
    const sighted = () => gateway && store.addSighting({ sosId: body.id, gatewayNodeId: gateway, hops: packet.hops, receivedAt: now });
    if (existing) {
      await sighted();
      events.emit('sos.updated', { id: body.id });
      return { status: 'duplicate', id: body.id, sosId: body.id };
    }
    await store.createSos({
      id: body.id,
      senderPk: body.pk,
      nodeId: nodeIdOf(fromBase64Url(body.pk)),
      category: body.cat,
      lat: body.lat ?? null,
      lon: body.lon ?? null,
      accuracyM: body.acc ?? null,
      fixAgeS: body.fixAge ?? null,
      people: body.ppl,
      note: body.note ?? null,
      battery: body.bat ?? null,
      lang: body.lang,
      isTest: body.test,
      createdAt: body.ts,
      expiresAt: body.exp,
      firstReceivedAt: now,
      status: 'NEW',
      cancelReason: null,
      cancelledAt: null,
      smsEscalated: false,
      envelope,
      updatedAt: now,
    });
    await sighted();
    // A cancel can overtake its SOS through the mesh; apply it now.
    const cancel = await store.getCancelFor(body.id);
    if (cancel && cancel.senderPk === body.pk) await applyCancel(body.id, cancel, now);
    events.emit('sos.new', { id: body.id });
    return { status: 'stored', id: body.id, sosId: body.id };
  }

  async function ingestCancel(body, now) {
    const sos = await store.getSos(body.ref);
    // A cancel must be signed by the key that sent the SOS (TRD 2.4).
    if (sos && sos.senderPk !== body.pk) return { status: 'rejected', reason: 'CANCEL_KEY_MISMATCH', id: body.id };
    const cancel = { id: body.id, ref: body.ref, senderPk: body.pk, reason: body.reason, ts: body.ts };
    await store.putCancel(cancel);
    if (sos && sos.status !== 'CANCELLED') {
      await applyCancel(sos.id, cancel, now);
      return { status: 'stored', id: body.id, sosId: sos.id };
    }
    return { status: sos ? 'duplicate' : 'stored', id: body.id, sosId: body.ref };
  }

  async function applyCancel(sosId, cancel, now) {
    await store.updateSos(sosId, { status: 'CANCELLED', cancelReason: cancel.reason, cancelledAt: cancel.ts, updatedAt: now });
    events.emit('sos.updated', { id: sosId });
  }

  async function ingestAck(body, envelope, gateway, now) {
    if (await store.getAck(body.id)) return { status: 'duplicate', id: body.id, sosId: body.ref };
    await store.addAck({
      id: body.id,
      sosId: body.ref,
      kind: body.kind,
      signerPk: body.pk,
      code: body.code ?? null,
      message: body.msg ?? null,
      unit: body.by ?? null,
      uploaded: body.up ?? null,
      sms: body.sms ?? null,
      gatewayNodeId: body.kind === 'GATEWAY' ? nodeIdOf(fromBase64Url(body.pk)) : (gateway ?? null),
      operatorId: null,
      createdAt: body.ts,
      envelope,
    });
    if (body.kind === 'GATEWAY' && body.sms) {
      const sos = await store.updateSos(body.ref, { smsEscalated: true, updatedAt: now });
      if (sos) events.emit('sos.updated', { id: body.ref });
    }
    return { status: 'stored', id: body.id, sosId: body.ref };
  }

  /** Responder acks for SOS a phone holds (GET /acks), as envelopes to flood into the mesh (F-44). */
  async function acksFor(ids) {
    return (await store.acksFor(ids, 'RESPONDER')).map((a) => a.envelope);
  }

  /** F-54: a server-signed reply that phones show in the sender's language. */
  async function respond(sosId, { code, msg, by }, operatorId) {
    const sos = await store.getSos(sosId);
    if (!sos) return null;
    const now = clock();
    // "t" first, like the phones write it; signatures cover these exact bytes, so key order is free.
    const body = {
      t: 'ACK',
      id: toBase64Url(randomBytes(Protocol.ID_SIZE)),
      pk: toBase64Url(serverPublicKey),
      ts: now,
      exp: now + Protocol.DEFAULT_LIFETIME_MS,
      ref: sosId,
      kind: 'RESPONDER',
      code,
      ...(msg ? { msg } : {}),
      ...(by ? { by } : {}),
    };
    const bytes = Buffer.from(JSON.stringify(body), 'utf8');
    const envelope = encodePacket({
      type: 'ACK',
      ttl: Protocol.DEFAULT_TTL,
      hops: 0,
      body: bytes,
      signature: signEd25519(serverKeys.privateKey, bytes),
    });
    const ack = {
      id: body.id,
      sosId,
      kind: 'RESPONDER',
      signerPk: body.pk,
      code,
      message: msg ?? null,
      unit: by ?? null,
      uploaded: null,
      sms: null,
      gatewayNodeId: null,
      operatorId,
      createdAt: now,
      envelope: toBase64Url(envelope),
    };
    await store.addAck(ack);
    if (sos.status === 'NEW') await store.updateSos(sosId, { status: 'IN_PROGRESS', updatedAt: now });
    events.emit('ack.new', { id: ack.id, sosId });
    events.emit('sos.updated', { id: sosId });
    return ack;
  }

  async function setStatus(sosId, status) {
    const sos = await store.updateSos(sosId, { status, updatedAt: clock() });
    if (sos) events.emit('sos.updated', { id: sosId });
    return sos;
  }

  /** F-52: unacknowledged first, then newest. */
  async function listSos(filters) {
    const alerts = await store.listSos(filters);
    const ids = alerts.map((a) => a.id);
    const [sightings, devices] = await Promise.all([
      store.sightingsFor(ids),
      store.devicesFor([...new Set(alerts.map((a) => a.senderPk))]),
    ]);
    const registered = new Set(devices.map((d) => d.publicKey));
    const summaries = alerts.map((alert) => summarize(alert, sightings.filter((s) => s.sosId === alert.id), registered));
    return summaries.sort(byUrgency);
  }

  async function getSos(id) {
    const alert = await store.getSos(id);
    if (!alert) return null;
    const [sightings, acks, cancel, device] = await Promise.all([
      store.sightingsFor([id]),
      store.acksFor([id]),
      store.getCancelFor(id),
      store.getDevice(alert.senderPk),
    ]);
    return {
      ...summarize(alert, sightings, new Set(device ? [device.publicKey] : [])),
      sender: device ? { name: device.displayName, phone: device.phone } : null,
      sightings,
      acks: acks.map(({ envelope, signerPk, ...rest }) => rest),
      cancel: cancel && cancel.senderPk === alert.senderPk ? { reason: cancel.reason, ts: cancel.ts } : null,
    };
  }

  /** F-33: a phone proves it holds the key by signing its registration. */
  async function registerDevice({ publicKey, ts, name, phone, signature }) {
    const key = fromBase64Url(publicKey);
    const sig = fromBase64Url(signature);
    if (!key || key.length !== 32 || !sig) return { ok: false, reason: 'BAD_KEY' };
    if (Math.abs(clock() - ts) > REGISTRATION_WINDOW_MS) return { ok: false, reason: 'STALE' };
    if (!verifyEd25519(key, registrationMessage({ publicKey, ts, name, phone }), sig)) return { ok: false, reason: 'BAD_SIGNATURE' };
    const existing = await store.getDevice(publicKey);
    const device = {
      publicKey,
      nodeId: nodeIdOf(key),
      displayName: name ?? null,
      phone: phone ?? null,
      registeredAt: existing?.registeredAt ?? clock(),
      lastSeenAt: clock(),
    };
    await store.putDevice(device);
    return { ok: true, device };
  }

  return { events, ingest, acksFor, respond, setStatus, listSos, getSos, registerDevice, serverPublicKey };
}

function summarize(alert, sightings, registeredKeys) {
  const { envelope, senderPk, ...fields } = alert;
  const hops = sightings.map((s) => s.hops);
  return {
    ...fields,
    registered: registeredKeys.has(senderPk),
    gateways: new Set(sightings.map((s) => s.gatewayNodeId)).size,
    minHops: hops.length ? Math.min(...hops) : null,
  };
}

const URGENCY = { NEW: 0, IN_PROGRESS: 1, CANCELLED: 2, RESOLVED: 3, FALSE_ALARM: 3 };
const byUrgency = (a, b) => URGENCY[a.status] - URGENCY[b.status] || b.createdAt - a.createdAt;

