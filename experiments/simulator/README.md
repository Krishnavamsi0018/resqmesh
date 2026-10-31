# Simulator experiments (week 9)

`android/simulator` runs many virtual phones on the **same relay code the app uses** (`mesh-core`'s `Router`, real Ed25519-signed packets, TTL 8, duplicate suppression, rate limit). Only the radio and the movement are simulated. It compares ResQMesh with a **plain-flooding baseline**: the same code with store-carry-forward turned off, so an SOS reaches only the phones linked while it spreads.

Run it from `android/`:

```bash
./gradlew :simulator:run
```

The full sweep (400 runs) takes about a minute on a laptop and rewrites `runs.csv` (one row per run), `summary.csv` and `summary.md` here. Add `--args="--quick"` for a 3-run check.

## Setup

| Setting | Value |
| --- | --- |
| Area | 1 km × 1 km |
| Phones | 10, 25, 50, 100, 200 |
| Radio range | 30 m (Bluetooth indoors, crowds) and 80 m (Bluetooth or Wi-Fi in the open) |
| Movement | Random waypoint: walk 0.5–1.5 m/s to a random point, pause 0–60 s, repeat |
| Link setup | 5 s in range before a link is up (Nearby connection time); down as soon as out of range |
| Per hop | 150–250 ms |
| Gateways | 5% of phones (at least 1), moving like the rest; an SOS counts as delivered when a gateway holds it |
| Traffic | 20 SOS from different non-gateway phones at random times in minutes 1–10; each run lasts 30 min |
| Runs | 20 per point, different seeds; both schemes use the same seed, so they see identical movement and senders |

## Findings

From `summary.md` (2026-11-01, 20 runs per point, ± is a 95% confidence interval):

1. **Store-carry-forward is what makes delivery work in a disaster area.** At 30 m range a phone has fewer than one link on average, so plain flooding delivers almost nothing (0–6%). ResQMesh delivers 93% with 50 phones and 99–100% with 100 or more, because walking phones carry the SOS until they meet a gateway.
2. **Even in a dense network, flooding misses SOS.** With 200 phones at 80 m (5 links each), flooding delivers 86% ± 3% and reaches 43% of phones; ResQMesh delivers 100% and reaches all of them.
3. **The price is latency when the network is sparse.** Delivered SOS take minutes (median 9 min with 50 phones at 30 m) because they travel at walking speed. As the network gets denser, latency drops to seconds: median 8 s with 100 phones and 0.5 s with 200 at 80 m.
4. **Overhead stays modest.** With 200 phones at 80 m, ResQMesh sends 645 packet copies per SOS against flooding's 426 (about 1.5×), plus summary and request frames on each new link: about 280 KB of radio traffic per SOS in total. Sparse networks cost much less because there are fewer links.
5. **Paths are short.** Delivered SOS took 2–4.4 radio hops on average, well inside the TTL of 8.

## Limits of the model

- No radio contention or bandwidth limit: every transfer takes 150–250 ms however busy the area is.
- No cap on links per phone (Nearby handles only a few at once), so the 200-phone, 80 m case is optimistic for both schemes.
- Only SOS are simulated; gateway and responder acks would add traffic in the opposite direction.
- Control frames are counted per new link and divided by the 20 SOS, so they are the cost of the whole run rather than of one SOS.
- Random-waypoint movement is a standard benchmark, not a model of people sheltering in place.
