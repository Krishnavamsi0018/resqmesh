# Multi-hop test (gate G2)

Goal: with no internet, an SOS crosses 3 hops on 4 phones (A → B → C → D), each phone linked only to its neighbours. With 3 phones you can run the same test as 2 hops (A → B → C).

## You need

- 4 Android 8.0+ phones with Google Play Services, each with app version **0.3.3-multihop** or later (check it under Settings → Apps → ResQMesh). 0.3.1 fixed the link collisions seen in the first three-phone run, 0.3.2 those left in the first four-phone run, and 0.3.3 gives up on a link attempt Nearby never answers after 15 s instead of 30 s.
- Labels A, B, C, D on the phones (sticky notes help).

## Set up the chain

1. On every phone: Airplane mode on, then Bluetooth on and Location on.
2. Force stop ResQMesh (Settings → Apps), then open it on every phone and tap **Start relaying**. Use only Force stop: clearing the app's data gives the phone a new ID. Note each phone's ID (`This phone: XXXX-XXXX`).
3. On every phone, turn on **Chain test mode**. Links drop: nothing is ticked yet.
4. Tick neighbours, on both phones of each pair:

   | Phone | Tick |
   | --- | --- |
   | A | B |
   | B | A and C |
   | C | B and D |
   | D | C |

5. Each ticked pair should link within about 15 s: the phone shows `connecting…`, then `linked`. A and D should end with `linked: 1`, and B and C with `linked: 2`. The phone with the lower ID asks; if it can't see the other phone, the higher one asks itself after 30–40 s.

## Run it

6. **Forward path:** on A, tap **Send test SOS: Flood**. Expected:
   - B: `SOS FLOOD … from A via A · hops 0`, then `Forwarded … to C`
   - C: `… from A via B · hops 1`, then `Forwarded … to D`
   - D: `… from A via C · hops 2`. **That's 3 hops.**
7. **Reverse path:** on D, tap **Send test SOS: Medical**. A should log `… from D via B · hops 2`.
8. **Late phone (store-and-forward):** on D, untick C so D drops out. On A, send another Flood. Then on D, tick C again. Within seconds D logs the new SOS (`hops 2`), and C logs `Caught D up: sent 1 packet(s) it missed`.
9. **Screen off:** lock B and C, send from A, then check that D received it.
10. **No loops (optional):** turn chain mode off on every phone so they all link to each other, then send from A. Each phone accepts the SOS once; extra copies show up as `Duplicate` and stop there.

Turn chain mode off on every phone when you finish; it stays on after the app closes.

## Record

| Date | Phones (A, B, C, D: model, Android) | A→D hops | D→A ok | Late phone ok | Screen off ok | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-10-29 | A 4303-3311, B A5A3-0424, C D182-94BF, D 7C0C-69DB (Galaxy M15, Android 16); app 0.3.1 | 3 (`hops 2` at D) | yes (`hops 2` at A) | yes (25 s late, `Caught 7C0C-69DB up: sent 1`) | yes | Airplane mode + Bluetooth. No loops: in a full mesh each phone accepted the SOS once. 0 rejected on every phone; all four end holding the same 8 packets. |

**Result: gate G2 passed on 2026-10-29.** Every SOS reached every phone, in both directions and from every phone (B and C sent one each too).

What the logs show:

- **Delivery takes about 0.5 s across 3 hops.** Adding the `age` of an A→D packet (−0.4 s) to that of a D→A packet (+1.4 s) cancels the clock difference: 1.0 s there and back. Single hops come out at 0.1–0.15 s the same way. Raw `age` values still aren't latency: the clocks differ by up to about 1 s.
- **Link setup is the weak spot.** A–B linked in seconds, but C–D took about 45 s after both were ticked. Almost every `STATUS_ENDPOINT_IO_ERROR 8012` appears on both phones of a pair within the same second (C/D at 22:17:26, 22:17:42 and 22:20:24; B/D at 22:21:41; A/C at 22:21:46 and 22:22:01): both phones asked at once. The higher-ID phone gives the other side only 10 s before asking itself, and a Bluetooth request on a busy phone can take 13 s to fail. When chain mode was switched off, the higher-ID phones asked immediately, because their wait counted from long before.
- **The red `STATUS_RADIO_ERROR 8007` pair on D (22:17:17)** was D's own pending requests being cancelled when chain mode turned on, not a radio fault. It shouldn't be logged as an error.
- **A link can stall silently.** At 22:22:12 C and D each forwarded the last SOS to the other, neither copy arrived, and Nearby only dropped the link 22 s later. Nothing was lost (both already had it), and the catch-up on relinking covers this case, but failed transfers aren't logged yet.

**0.3.2 fixes these four:**
- The higher-ID phone now waits 30 s before asking, and the wait restarts whenever chain mode or the ticks change.
- A request the phone cancelled itself is no longer logged as an error.
- `retrying in` shows the real wait.
- A failed send hangs up the link, so it relinks and catches up.

The retest repeats the whole run. Paired `8012` lines should be rare, and switching chain mode off should link everything within about 15 s.

| Date | Phones (A, B, C, D) | A→D hops | D→A ok | Late phone ok | Screen off ok | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-10-30 | A 4303-3311, B 88B9-7505 (new ID after a data wipe), C D182-94BF, D 7C0C-69DB; app 0.3.2 | 3 (`hops 2` at D) | yes (`hops 2` at A) | yes (15 s late, `Caught 7C0C-69DB up: sent 1`) | yes | No loops in a full mesh; 0 rejected on every phone; all four hold the same 9 packets. |

**Result: gate G2 passed again on 0.3.2.**
- The paired `8012` clashes are gone.
- A refused link (`8004` because B hadn't ticked A yet) relinked 4 s after the tick.
- A stalled B–C link was caught the moment a send failed (`Sending to … failed, reconnecting` on both phones at 22:40:25) and was rebuilt.

What's left is slow attempts. Six link attempts got no answer from Nearby and hit the 30 s timeout. As a result, B–C took about 55 s to link, and switching chain mode off took about 45 s to reach a full mesh. Links that did form came up within seconds, and failures came back within 13 s, so 0.3.3 cuts the timeout to 15 s.

An earlier attempt the same day was blocked by the per-sender limit (5 SOS per 10 min, F-32): the phones had sent many test SOS before the run. **Force stop** the app before a test run, and press each send button once per step.

## If something goes wrong

- **A pair won't link:** both phones must tick each other, and a refused attempt waits up to 60 s before retrying. Stopping and starting relaying on one of the two phones retries at once.
- **Grey `Already connecting to …, waiting` lines are normal:** both phones started connecting at the same moment, and one waits for the other's attempt. Only red lines are errors.
- **Grey `Sending to … failed, reconnecting`:** a link went dead. It drops and relinks by itself, and the catch-up resends anything missed.
- **Red `Discovery failed (…)` line:** it now names the cause and retries by itself. `MISSING_PERMISSION_…` or `LOCATION_MUST_BE_ON` means open the app, check its permissions and turn Location on.
- **`Relaying without location access`:** relaying was started while the app wasn't on screen. Open the app, then tap Stop and Start.
