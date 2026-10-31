# Field experiments (week 9)

Real phones in a forced chain (chain mode) A–B–C–D, 2–3 m apart on a table. In each run phone A sent 50 test SOS, one every 10 s, and the phone 1, 2 or 3 hops away echoed each one back. Latency is half the echo round trip, timed on A's own clock, because phone clocks differ by about a second. Steps: `docs/week9-field-test.md`.

- `raw/`: each phone's CSV log, from the Network tab's Save CSV (copied with `adb pull`).
- `analyze.mjs`: turns them into `summary.csv` and `summary.md` (`node experiments/field/analyze.mjs`). The dashboard's Results page reads `summary.csv` when it is built.

## Results (2026-11-01)

| Hops | Delivered | Median latency | 90th pct | Transmissions per SOS | Duplicates |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 50/50 | 0.10 s | 0.14 s | 3.0 | 0 |
| 2 | 50/50 | 0.16 s | 0.21 s | 3.0 | 0 |
| 3 | 50/50 | 0.22 s | 0.27 s | 3.0 | 0 |

- Every test SOS arrived; nothing was lost or delayed by a dropped link.
- Each extra hop adds about 60 ms, so a 3-hop delivery takes about a fifth of a second.
- Each SOS crossed each of the chain's 3 links exactly once (it travels to the end of the chain whichever phone echoes), and no phone received a copy it already had: duplicate suppression costs nothing on a chain.
- Phones were 2–3 m apart and still; links at longer range, through walls, or while walking will be slower and may drop.
