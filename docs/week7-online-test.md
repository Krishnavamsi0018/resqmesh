# Online test (week 7, part 3)

Goal: the full loop through the deployed server, with no laptop server. An SOS from a phone with no internet crosses the mesh to a gateway phone, which uploads it to Render. The SOS appears on the dashboard; the operator replies there, and the sender sees the reply in their language. This is gate G3 (full loop) except SMS.

- Dashboard and API: https://resqmesh-ds0v.onrender.com (sign in with the operator email and password from `backend/.env`)
- App: **0.6.0-online**. It uploads to that address by itself and checks replies against the real server key. 0.5.0 phones can't check those replies, so every phone needs 0.6.0.

## Set up

1. Install 0.6.0 on all 4 phones, then Force stop each one and open it again. Chain mode and ticks stay as they were.
2. **A, B, C:** airplane mode, Bluetooth on, Location on. **D:** airplane mode, then Wi-Fi **or mobile data** on, Bluetooth on, Location on.
3. On **D**, open the Network tab. The Gateway box should show `https://resqmesh-ds0v.onrender.com`. If it still shows the laptop address, clear the box and tap **Save**. Within about a minute it shows `Gateway on · uploaded 0 · waiting 0`.
4. On the laptop, open the dashboard and sign in. Opening it first also wakes the free server, which sleeps after 15 quiet minutes and takes about a minute to wake.
5. Check the chain links (A–B, B–C, C–D).

| Phone | Chain ticks |
| --- | --- |
| A 4303-3311 | 6D29-3B67 |
| B 6D29-3B67 | 4303-3311, D182-94BF |
| C D182-94BF | 6D29-3B67, 7C0C-69DB |
| D 7C0C-69DB | D182-94BF |

## Tests

1. **SOS reaches the dashboard.** On **A**, from Home, send a **Flood** SOS (let the countdown finish). Expected within about 15 s: the dashboard plays two tones, and the SOS is at the top of the list (and on the map if A had a location). On A's My SOS, step 3 **Rescue team informed** turns green.
2. **Reply from the dashboard.** Click the SOS, tap the quick reply **Boat in 20 minutes**, then **Send reply**. Within about 30 s, A shows **Help is coming** and the green card in A's language. The dashboard shows the SOS as **In progress** and the reply in *What happened*.
3. **I'm safe.** On A, tap **I'm safe now** → **Yes**. Within about 15 s the dashboard shows **Sender safe** (in the Closed tab).
4. **Second sender.** On **C**, send a **Medical** SOS. It appears on the dashboard; click **Mark as Resolved**. It moves to Closed.

Send screenshots of the dashboard after tests 1 and 2, and of A's My SOS after test 2.

## Record

| Date | Gateway phone (network) | On dashboard | Informed on sender | Reply on sender | Cancel on dashboard | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-10-31 | D 7C0C-69DB (Wi-Fi), Render server | Yes, with tones | Yes | Yes, Help is coming in A's language | Yes, Sender safe | Tests 1–4 passed, checked by the team. Second sender C's Medical SOS marked Resolved. G3 passed except SMS. |

## If something goes wrong

- **D says `Online, server not reachable: …`.** The server may be waking up; wait a minute. Check the address in the Gateway box has no typo.
- **Reply sent but A never shows it.** Check that every phone has 0.6.0 (phone Settings → Apps → ResQMesh, version at the bottom). A phone on 0.5.0 drops the reply because it can't verify the server key.
