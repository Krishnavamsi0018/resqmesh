# Field experiment (week 9)

Goal: measure delivery, latency and radio overhead against the number of hops on real phones (plan doc, evaluation: forced chains, 50 SOS per setting at 10 s intervals). With 4 phones the chain gives 1, 2 and 3 hops. The results feed the dashboard's Results page and the report next to the simulator's.

Latency is timed on the sender's own clock: the phone at the far end echoes every test SOS, and latency is half the round trip. Phone clocks differ by about a second, so timing one-way between two phones would be wrong.

## You need

- 4 phones with **0.8.0-experiments**, installed over USB (`adb install -r`; Play Protect blocks installing it from a file because of the SMS permission).
- About 35 minutes: three runs of about 8.5 minutes, plus setup.

## Set up (once)

1. On every phone: Force stop the app and open it again.
2. **All four:** airplane mode on, Bluetooth on, Location on. D stays offline too, so nothing uploads during the runs.
3. Chain mode as before, and Stop then Start relaying on all four. Wait for the chain to link.

| Phone | Chain ticks |
| --- | --- |
| A 4303-3311 | 6D29-3B67 |
| B 6D29-3B67 | 4303-3311, D182-94BF |
| C D182-94BF | 6D29-3B67, 7C0C-69DB |
| D 7C0C-69DB | D182-94BF |

Put the phones about 2–3 m apart on a table, and leave them still during a run.

## Runs

The **Field experiment** box is on the Network tab. For each run:

| Run | Tap this setting on **all four** phones | Echo on | Then on A |
| --- | --- | --- | --- |
| 1 | `hops-1` | **B** only | Start sending (50, every 10 s) |
| 2 | `hops-2` | **C** only | Start sending |
| 3 | `hops-3` | **D** only | Start sending |

- Every phone tags its log with the setting it shows, so all four must show the same one.
- Turn the previous phone's echo **off** before turning the next one on.
- A shows `Sent n/50 · echoes n`; wait until it says `Sent 50/50` and the echoes stop rising (about 30 s more) before the next run.

## After the three runs

1. On each phone, tap **Save CSV**. A share box opens; you can close it (the file stays on the phone).
2. Connect each phone to the laptop by USB in turn and say "connected": I copy the CSV files into `experiments/field/raw`, run the analysis, and update the dashboard's Results page.

## Record

| Date | hops-1 delivered | hops-2 delivered | hops-3 delivered | Notes |
| --- | --- | --- | --- | --- |
| 2026-11-01 | 50/50 (100%), median 0.10 s | 50/50 (100%), median 0.16 s | 50/50 (100%), median 0.22 s | All 150 echoed; 3 transmissions per SOS (one per link of the 4-phone chain) and 0 duplicates in every run. Results in experiments/field. |

## If something goes wrong

- **Echoes stay at 0.** Check the far phone's echo switch is on and all four show the same setting; check the chain is linked.
- **A link drops mid-run.** Leave it; reconnects are part of what is measured. Note it in the record.
