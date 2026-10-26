# backend

Node.js + Express 5 API on PostgreSQL (Prisma, Neon free tier), written in JavaScript with zod checks (decisions D1–D3). Starts in week 4.

It verifies every uploaded packet's Ed25519 signature with `node:crypto`. Its tests must pass on `../shared/test-vectors/packets-v1.json`, so the server and the phones agree byte for byte.

Planned endpoints are in the plan doc, TRD section 2.10.
