// The v1 wire format (TRD 2.4), the same rules as mesh-core's PacketCodec and PacketValidator.
// shared/test-vectors/packets-v1.json pins both sides to the same verdict on every sample.

import { createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';
import { z } from 'zod';

export const Protocol = Object.freeze({
  VERSION: 1,
  HEADER_SIZE: 8,
  SIGNATURE_SIZE: 64,
  MAX_BODY_SIZE: 900,
  MAX_PACKET_SIZE: 8 + 900 + 64,
  ID_SIZE: 16,
  DEFAULT_TTL: 8,
  MAX_TTL: 8,
  DEFAULT_LIFETIME_MS: 12 * 60 * 60 * 1000,
  MAX_LIFETIME_MS: 24 * 60 * 60 * 1000,
  CLOCK_SKEW_MS: 10 * 60 * 1000,
  MAX_NOTE_LENGTH: 120,
  MAX_ACK_MESSAGE_LENGTH: 160,
  MAX_UNIT_NAME_LENGTH: 60,
  MAX_PEOPLE: 999,
});

export const PacketType = Object.freeze({ SOS: 1, CANCEL: 2, ACK: 3 });
const TYPE_NAMES = { 1: 'SOS', 2: 'CANCEL', 3: 'ACK' };

export class MalformedPacketError extends Error {
  constructor(reason) {
    super(`Malformed packet: ${reason}`);
    this.reason = reason;
  }
}

/** base64url without padding, as the phones write keys and IDs. Returns null for anything else. */
export function fromBase64Url(text) {
  if (typeof text !== 'string' || !/^[A-Za-z0-9_-]*$/.test(text) || text.length % 4 === 1) return null;
  return Buffer.from(text, 'base64url');
}

export const toBase64Url = (bytes) => Buffer.from(bytes).toString('base64url');

/** Parses the binary envelope only; the body and signature are checked by validatePacket. */
export function decodePacket(bytes) {
  const b = Buffer.from(bytes);
  if (b.length < Protocol.HEADER_SIZE + 1 + Protocol.SIGNATURE_SIZE) throw new MalformedPacketError('TOO_SHORT');
  if (b.length > Protocol.MAX_PACKET_SIZE) throw new MalformedPacketError('TOO_LONG');
  if (b[0] !== 0x52 || b[1] !== 0x51) throw new MalformedPacketError('BAD_MAGIC');
  if (b[2] !== Protocol.VERSION) throw new MalformedPacketError('UNSUPPORTED_VERSION');
  const type = TYPE_NAMES[b[3]];
  if (!type) throw new MalformedPacketError('UNKNOWN_TYPE');
  const bodyLen = b.readUInt16BE(6);
  if (bodyLen === 0 || bodyLen > Protocol.MAX_BODY_SIZE) throw new MalformedPacketError('BAD_BODY_LENGTH');
  if (b.length !== Protocol.HEADER_SIZE + bodyLen + Protocol.SIGNATURE_SIZE) throw new MalformedPacketError('LENGTH_MISMATCH');
  return {
    type,
    ttl: b[4],
    hops: b[5],
    body: b.subarray(Protocol.HEADER_SIZE, Protocol.HEADER_SIZE + bodyLen),
    signature: b.subarray(Protocol.HEADER_SIZE + bodyLen),
  };
}

export function encodePacket({ type, ttl, hops, body, signature }) {
  const out = Buffer.alloc(Protocol.HEADER_SIZE + body.length + Protocol.SIGNATURE_SIZE);
  out[0] = 0x52;
  out[1] = 0x51;
  out[2] = Protocol.VERSION;
  out[3] = PacketType[type];
  out[4] = ttl;
  out[5] = hops;
  out.writeUInt16BE(body.length, 6);
  Buffer.from(body).copy(out, Protocol.HEADER_SIZE);
  Buffer.from(signature).copy(out, Protocol.HEADER_SIZE + body.length);
  return out;
}

// Body shapes. Unknown keys are allowed, like the phones' ignoreUnknownKeys: a newer app may add fields.
// A JSON null counts as absent, as it does for kotlinx.serialization's nullable fields.
const common = {
  id: z.string(),
  pk: z.string(),
  ts: z.number().int(),
  exp: z.number().int(),
};
const SosBody = z.looseObject({
  t: z.literal('SOS'),
  ...common,
  cat: z.enum(['MEDICAL', 'FLOOD', 'FIRE', 'TRAPPED', 'WATER', 'GENERAL']),
  lat: z.number().nullish(),
  lon: z.number().nullish(),
  acc: z.number().int().nullish(),
  fixAge: z.number().int().nullish(),
  bat: z.number().int().nullish(),
  ppl: z.number().int().default(1),
  note: z.string().nullish(),
  lang: z.string().default('en'),
  test: z.boolean().default(false),
});
const CancelBody = z.looseObject({
  t: z.literal('CANCEL'),
  ...common,
  ref: z.string(),
  reason: z.enum(['SAFE', 'MISTAKE']),
});
const AckBody = z.looseObject({
  t: z.literal('ACK'),
  ...common,
  ref: z.string(),
  kind: z.enum(['GATEWAY', 'RESPONDER']),
  up: z.boolean().nullish(),
  sms: z.boolean().nullish(),
  code: z.string().nullish(),
  msg: z.string().nullish(),
  by: z.string().nullish(),
});
const Body = z.discriminatedUnion('t', [SosBody, CancelBody, AckBody]);

const isPacketId = (text) => fromBase64Url(text)?.length === Protocol.ID_SIZE;

/**
 * Decides whether a decoded packet may be stored: the same checks, in the same order, as
 * mesh-core's PacketValidator. Returns { ok: true, body } or { ok: false, reason, detail }.
 */
export function validatePacket(packet, { serverPublicKey, now }) {
  let json;
  try {
    json = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(packet.body));
  } catch (e) {
    return reject('BAD_BODY', e.message);
  }
  const parsed = Body.safeParse(json);
  if (!parsed.success) return reject('BAD_BODY', parsed.error.issues[0]?.message);
  const body = parsed.data;

  if (body.t !== packet.type) return reject('TYPE_MISMATCH', `Header says ${packet.type}, body says ${body.t}`);
  if (!isPacketId(body.id)) return reject('BAD_ID');
  const publicKey = fromBase64Url(body.pk);
  if (!publicKey || publicKey.length !== 32) return reject('BAD_KEY');

  if (packet.ttl < 1) return reject('TTL_EXHAUSTED');
  if (packet.ttl > Protocol.MAX_TTL) return reject('TTL_TOO_HIGH', `ttl ${packet.ttl}`);

  const lifetime = body.exp - body.ts;
  if (lifetime <= 0 || lifetime > Protocol.MAX_LIFETIME_MS) return reject('BAD_LIFETIME', `exp - ts = ${lifetime} ms`);
  // No check on ts being in the future: a sender with a fast clock still needs help.
  if (now > body.exp + Protocol.CLOCK_SKEW_MS) return reject('EXPIRED');

  const problem = fieldProblem(body);
  if (problem) return reject('BAD_FIELDS', problem);

  if (body.t === 'ACK' && body.kind === 'RESPONDER' && !publicKey.equals(serverPublicKey)) return reject('UNTRUSTED_RESPONDER');
  if (!verifyEd25519(publicKey, packet.body, packet.signature)) return reject('BAD_SIGNATURE');

  return { ok: true, body };
}

function fieldProblem(body) {
  switch (body.t) {
    case 'SOS':
      if ((body.lat == null) !== (body.lon == null)) return 'lat and lon must both be present or both absent';
      if (body.lat != null && (body.lat < -90 || body.lat > 90)) return 'lat out of range';
      if (body.lon != null && (body.lon < -180 || body.lon > 180)) return 'lon out of range';
      if (body.acc != null && body.acc < 0) return 'acc is negative';
      if (body.fixAge != null && body.fixAge < 0) return 'fixAge is negative';
      if (body.bat != null && (body.bat < 0 || body.bat > 100)) return 'bat out of range';
      if (body.ppl < 1 || body.ppl > Protocol.MAX_PEOPLE) return 'ppl out of range';
      if (body.note != null && body.note.length > Protocol.MAX_NOTE_LENGTH) return 'note too long';
      if (body.lang.length < 2 || body.lang.length > 8) return 'lang must be 2-8 characters';
      return null;
    case 'CANCEL':
      return isPacketId(body.ref) ? null : 'ref is not a packet ID';
    case 'ACK':
      if (!isPacketId(body.ref)) return 'ref is not a packet ID';
      if (body.kind === 'GATEWAY' && (body.up == null || body.sms == null)) return 'gateway ack needs up and sms';
      if (body.kind === 'RESPONDER' && !body.code?.trim()) return 'responder ack needs a code';
      if (body.msg != null && body.msg.length > Protocol.MAX_ACK_MESSAGE_LENGTH) return 'msg too long';
      if (body.by != null && body.by.length > Protocol.MAX_UNIT_NAME_LENGTH) return 'by too long';
      return null;
    default:
      return 'unknown type';
  }
}

const reject = (reason, detail) => ({ ok: false, reason, detail });

// Kotlin String.length counts UTF-16 units, as JavaScript's does, so the length limits agree.

/** Raw 32-byte Ed25519 keys, via JWK because node:crypto has no raw-key import. */
export function verifyEd25519(publicKey, message, signature) {
  if (signature.length !== Protocol.SIGNATURE_SIZE) return false;
  try {
    const key = createPublicKey({ key: { kty: 'OKP', crv: 'Ed25519', x: toBase64Url(publicKey) }, format: 'jwk' });
    return verify(null, message, key, signature);
  } catch {
    return false;
  }
}

/** The server's signing key from its 32-byte seed. */
export function serverKeyPair(seed) {
  if (seed.length !== 32) throw new Error('Server key seed must be 32 bytes');
  const privateKey = createPrivateKey({
    key: Buffer.concat([Buffer.from('302e020100300506032b657004220420', 'hex'), seed]),
    format: 'der',
    type: 'pkcs8',
  });
  const jwk = createPublicKey(privateKey).export({ format: 'jwk' });
  return { privateKey, publicKey: Buffer.from(jwk.x, 'base64url') };
}

export const signEd25519 = (privateKey, message) => sign(null, message, privateKey);
