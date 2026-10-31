# Decisions

Agreed by the team on 2026-10-26. Full context is in the plan doc, part 4: https://claude.ai/artifact/34uNTwCbhUQB4w5N5QbNtR

| # | Decision | Agreed choice | Alternative not taken |
| --- | --- | --- | --- |
| D1 | Database | PostgreSQL + Prisma on Neon's free tier | MongoDB Atlas; SQLite on a laptop |
| D2 | Where the backend runs for the demo | Render (API) + Vercel (dashboard); amended 2026-10-31: one Render service hosts both, see below | A team laptop on the LAN via hotspot |
| D3 | Backend and dashboard language | JavaScript with zod checks | TypeScript |
| D4 | Simulator | Kotlin console app reusing `mesh-core` | ONE simulator |
| D5 | Packet body encoding | JSON signed as raw bytes | Protobuf or CBOR |
| D6 | Home screen | SOS button + 5 category tiles, each with a 5 s countdown | Hold-to-send SOS button only |
| D7 | "I'm safe" check-in packet (SAFE) | After the MVP | In the MVP |
| D8 | Test phones | 5 phones from at least 2 brands | 3 phones (2 hops only) |
| D9 | Responder acknowledging from a phone in the field | After the MVP | In the MVP |
| D10 | App ID | `com.resqmesh.app` | A college namespace |

SMS escalation goes only to team test numbers during the project, never to 112.

**D2 amended (2026-10-31).** The API server also hosts the built dashboard, so both run as one free Render web service (`render.yaml`). The dashboard and API share one origin, so the operator cookie needs no CORS or rewrite, and there is one deploy and one account instead of two. Vercel is no longer used.
