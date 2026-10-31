# Gateway test (week 7, part 2)

Goal: an SOS from a phone with no internet crosses the mesh to a phone that has internet, which uploads it. The sender's My SOS turns to **Rescue team informed**. An operator's reply comes back through the mesh and the sender sees **Help is coming**. This covers F-40, F-43 and F-44, and the step to gate G3 (full loop) except SMS.

Until the API is deployed (next step), the server runs on the laptop and the gateway phone reaches it over the same Wi-Fi.

## You need

- 4 phones with **0.5.0-gateway**. Force stop the app on each before starting; chain mode and ticks stay as they were.
- The laptop and phone **D** on the **same Wi-Fi**. The laptop's address on that Wi-Fi: run `ipconfig` and look for *IPv4 Address* (it was `192.168.0.103` on 2026-10-31).

## 1. Start the server on the laptop

```bash
cd backend
npm start
```

It prints `ResQMesh API on http://localhost:8080/api/v1`. The first time, Windows asks whether Node.js may use networks: tick **Private networks** and click **Allow**. Leave this window open for the whole test.

## 2. Set up the phones

| Phone | Network | Chain ticks |
| --- | --- | --- |
| A 4303-3311 | Airplane mode, Bluetooth on | 6D29-3B67 |
| B 6D29-3B67 | Airplane mode, Bluetooth on | 4303-3311, D182-94BF |
| C D182-94BF | Airplane mode, Bluetooth on | 6D29-3B67, 7C0C-69DB |
| D 7C0C-69DB | Airplane mode, then **Wi-Fi on** (same Wi-Fi as the laptop) and Bluetooth on | D182-94BF |

On **D**, open the Network tab. In **Gateway (server upload)**, type `http://192.168.0.103:8080` (your laptop's address) and tap **Save**. Within 15 s it shows `Gateway on · uploaded 0 · waiting 0`, and D's Home shows the cloud line *Online: this phone passes SOS to the rescue team*.

## 3. Tests

1. **Upload and "informed".** On **A**, tap **Flood** and let it send. Expected within about 15 s:
   - D's log: `Uploaded 1 packet(s) to the server`, then `Told the mesh: rescue team informed of SOS …`.
   - A's My SOS: step 3 **Rescue team informed** turns green, with a time.
   - On the laptop, in a second terminal: `cd backend` then `node scripts/ops.mjs list`. The Flood SOS from 4303-3311 is listed, `gateways 1`.
2. **Reply ("help is coming").** On the laptop: `node scripts/ops.mjs reply <first 6 letters of the ID> TEAM_DISPATCHED Boat in 20 minutes`. Within about 30 s A shows step 4 **Help is coming** and a green card *Team is on the way* in A's language, with *Boat in 20 minutes* and *Control room (test)*.
3. **I'm safe reaches the server.** On A, tap **I'm safe now** → **Yes**. Run `node scripts/ops.mjs list` again: the SOS shows `CANCELLED`.
4. **Gateway loses internet.** On D, turn Wi-Fi off. D's log says `Internet lost: no longer a gateway`, and the cloud line leaves Home. On **B**, send a **Medical** SOS: it reaches D but waits (`waiting 1`). Turn D's Wi-Fi back on: within about 15 s it uploads, and B's My SOS turns to *Rescue team informed*.

Send screenshots of A's My SOS after tests 1 and 2, D's Network tab, and the laptop terminal.

## Record

| Date | Gateway phone | Informed shown on sender | Reply shown on sender | Cancel reached server | Upload after internet came back | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-10-31 | D 7C0C-69DB (Wi-Fi, laptop server on Neon) | Yes, A within 15 s | Yes, A showed Help is coming | Yes, QeFFOT CANCELLED | Yes, B's Medical J7LOCP uploaded after Wi-Fi came back | B's ID changed to 6D29-3B67 after a data wipe. A's SOS had no location (indoors); B's had one (±20 m). |

## If something goes wrong

- **D says `Online, server not reachable: …`.** Check that the laptop window still shows the server running, the address is the laptop's IPv4 with `:8080`, and both are on the same Wi-Fi. Some routers (guest networks, college Wi-Fi) block phone-to-laptop traffic; a phone hotspot from a fifth phone works around it.
- **D says `No internet: not a gateway`.** The Wi-Fi must reach the internet; Android only counts networks it has checked.
