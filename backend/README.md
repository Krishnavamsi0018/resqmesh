# backend

Node.js + Express 5 API on PostgreSQL (Prisma 7, Neon free tier), written in JavaScript with zod checks (decisions D1–D3). It is the server side of TRD 2.10:

| Endpoint | Who | What |
| --- | --- | --- |
| `POST /api/v1/packets` | gateway phone | Batch of up to 50 packets (base64url). Each is checked exactly like a relay would and stored once; returns accepted and rejected IDs plus waiting responder replies. |
| `GET /api/v1/acks?ids=` | online phone | Responder replies for those SOS IDs, as packets to flood into the mesh. |
| `POST /api/v1/devices` | phone | Registers a public key with a name and phone number, signed by that key. |
| `GET /api/v1/server-key` | anyone | The key that signs replies (the app pins it). |
| `POST /api/v1/auth/login`, `/logout`, `GET /auth/me` | operator | Session in an httpOnly cookie. |
| `GET /api/v1/sos`, `GET /api/v1/sos/:id` | operator | List (new first; `?status=&category=&since=&test=1`) and detail with sightings, acks and cancel. |
| `POST /api/v1/sos/:id/ack` | operator | Reply with `TEAM_DISPATCHED`, `STAY_PUT` or `MOVE_HIGHER` (+ note, unit); signed with the server key. |
| `PATCH /api/v1/sos/:id` | operator | Status: `IN_PROGRESS`, `RESOLVED`, `FALSE_ALARM`. |
| `GET /api/v1/stream` | operator | Server-Sent Events: `sos.new`, `sos.updated`, `ack.new`. |

Packets are verified with `node:crypto` Ed25519. The tests run every sample in `../shared/test-vectors/packets-v1.json`, so the server and the phones reach the same verdict on each one.

## Run it

```bash
npm install
npm test
OPERATOR_EMAIL=you@example.com OPERATOR_PASSWORD=choose-one npm run dev
```

With no `DATABASE_URL`, data lives in memory and is lost on restart. Fine for trying things out.

## With PostgreSQL (Neon)

1. Create a free project at neon.tech and copy its connection string.
2. Put it in `.env` as `DATABASE_URL` (see `.env.example`), then:

```bash
npx prisma generate
npm run db:migrate
npm start
```

The schema is `prisma/schema.prisma`; migrations are in `prisma/migrations`.

## Not yet

Experiment uploads and `GET /stats` (week 9). The real server key replaces the test key when the app's pinned key changes (week 7 deploy).
