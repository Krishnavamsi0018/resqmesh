// The server must reach the same verdict as the phones on every shared sample packet.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { decodePacket, fromBase64Url, MalformedPacketError, serverKeyPair, validatePacket } from '../src/packet.js';

const vectors = JSON.parse(readFileSync(new URL('../../shared/test-vectors/packets-v1.json', import.meta.url), 'utf8'));
const serverPublicKey = fromBase64Url(vectors.serverPublicKey);

for (const vector of vectors.vectors) {
  test(`vector ${vector.name}: ${vector.expect}${vector.reason ? ` ${vector.reason}` : ''}`, () => {
    const bytes = Buffer.from(vector.envelopeHex, 'hex');
    if (vector.expect === 'MALFORMED') {
      assert.throws(() => decodePacket(bytes), (e) => e instanceof MalformedPacketError && e.reason === vector.reason);
      return;
    }
    const verdict = validatePacket(decodePacket(bytes), { serverPublicKey, now: vectors.verifyAtMillis });
    if (vector.expect === 'ACCEPT') {
      assert.equal(verdict.ok, true, `rejected: ${verdict.reason} ${verdict.detail ?? ''}`);
      assert.equal(verdict.body.id, JSON.parse(vector.bodyJson).id);
    } else {
      assert.equal(verdict.ok, false);
      assert.equal(verdict.reason, vector.reason);
    }
  });
}

test('the test server seed in the vectors gives the pinned server public key', () => {
  const seed = Buffer.from(vectors.keys.find((k) => k.name === 'server').privateKeyHex, 'hex');
  assert.deepEqual(serverKeyPair(seed).publicKey, serverPublicKey);
});
