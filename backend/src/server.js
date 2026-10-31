// Starts the API. Configuration comes from the environment (see .env.example).

import { randomBytes, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import bcrypt from 'bcryptjs';
import { createApp } from './app.js';
import { serverKeyPair } from './packet.js';
import { createService } from './service.js';
import { MemoryStore } from './store/memory.js';

const env = process.env;
const production = env.NODE_ENV === 'production';

function fail(message) {
  console.error(`resqmesh-backend: ${message}`);
  process.exit(1);
}

// The server's Ed25519 key signs responder acks; the app pins its public half (F-31).
let seedHex = env.SERVER_KEY_SEED_HEX;
if (!seedHex) {
  if (production) fail('SERVER_KEY_SEED_HEX is required in production');
  // Until week 7 ships a real key, the app pins the public TEST key from the shared vectors.
  const vectors = JSON.parse(readFileSync(new URL('../../shared/test-vectors/packets-v1.json', import.meta.url), 'utf8'));
  seedHex = vectors.keys.find((k) => k.name === 'server').privateKeyHex;
  console.warn('Using the TEST server key from shared/test-vectors. Never do this in a real deployment.');
}
const serverKeys = serverKeyPair(Buffer.from(seedHex, 'hex'));

let jwtSecret = env.JWT_SECRET;
if (!jwtSecret) {
  if (production) fail('JWT_SECRET is required in production');
  jwtSecret = randomBytes(32).toString('hex');
  console.warn('No JWT_SECRET: operator sessions end when the server restarts.');
}

let store;
if (env.DATABASE_URL) {
  const { PrismaStore } = await import('./store/prisma.js');
  store = await PrismaStore.connect(env.DATABASE_URL);
} else {
  if (production) fail('DATABASE_URL is required in production');
  store = new MemoryStore();
  console.warn('No DATABASE_URL: keeping data in memory only.');
}

// The first operator comes from the environment; more can be added in the database later.
if (env.OPERATOR_EMAIL && env.OPERATOR_PASSWORD && (await store.countOperators()) === 0) {
  await store.addOperator({
    id: randomUUID(),
    email: env.OPERATOR_EMAIL.trim().toLowerCase(),
    name: env.OPERATOR_NAME ?? 'Operator',
    passwordHash: await bcrypt.hash(env.OPERATOR_PASSWORD, 12),
  });
  console.log(`Created operator ${env.OPERATOR_EMAIL}`);
}

const service = createService({ store, serverKeys });
// The dashboard build (cd dashboard && npm run build), served from the same origin when present.
const dashboardDir = fileURLToPath(new URL('../../dashboard/dist', import.meta.url));
const app = createApp({ service, store, jwtSecret, secureCookies: production, dashboardDir });
const port = Number(env.PORT ?? 8080);
app.listen(port, () => console.log(`ResQMesh API on http://localhost:${port}/api/v1, dashboard on http://localhost:${port}/`));
