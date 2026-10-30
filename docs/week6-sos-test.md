# SOS screens test (week 6, part 1)

Goal: a real SOS sent from the Home screen in one tap and a 5-second countdown, carrying GPS, battery and people count, with the sender seeing where it has got to. Covers F-01, F-10 to F-14, F-16 and screens A1, A4, A5, A6.

Not in this build yet: steps 3 and 4 of the tracker (Rescue team informed, Help is coming) need the gateway and backend from week 7. Settings (A9), nearby alerts (A7) and bundled Noto fonts come later.

## You need

- At least 2 phones with **0.4.0-sos** (Settings → Apps → ResQMesh). It installs over 0.3.x and keeps each phone's ID.
- Location on, outdoors or near a window for a GPS fix.
- Each phone sends at most 5 SOS per 10 minutes, test ones included; neighbours drop the rest (F-32). **Force stop** the app between runs if you need more.

## Steps

1. **Language (A1).** Open the app. The first screen asks for a language in all three scripts. Pick a different one on each phone: English, हिन्दी, ಕನ್ನಡ.
2. **Home (A4).** Check, in the chosen language:
   - the status line: `Looking for nearby phones`, then `1 phone nearby` once the phones link. Relaying starts by itself.
   - the red SOS button with `Tap for help`, and five coloured tiles: Medical, Flood, Fire, Trapped, Need water.
   - the language button (top right) brings back the language screen.
3. **Cancel works.** On phone A, tap **Fire**, then **Cancel** during the countdown. Nothing is sent: the other phone's Network tab shows no new SOS.
4. **Countdown (A5).** On A, tap **Flood**. The ring counts 5→0. Pick **2–5** people. At 0 the phone buzzes once, shows `Sending…` for up to 5 s, then opens **My SOS**.
5. **Status (A6).** On A, check:
   - `Your SOS: Flood`, `Sent at hh:mm`.
   - step 1 done, step 2 `Passed to 1 phone` with a time.
   - the location line: `Location sent, accurate to N m`, or `Location could not be found` indoors with no fix.
   - On phone B, the Network tab log shows `SOS FLOOD … from <A's ID> via <A's ID> · hops 0`.
6. **One SOS at a time.** On A's Home, the SOS button pulses and a banner says the SOS is active. Tapping any tile opens My SOS instead of a new countdown.
7. **Late phone.** On B, Network tab → **Stop relaying**. On A, tap **I'm safe now** → **Yes** to close the first SOS, then **Back to home**. Tap **Medical** and let it send. A shows `Looking for nearby phones`. On B, **Start relaying**: within about 15 s A's step 2 changes to `Passed to 1 phone`.
8. **I'm safe (F-16).** On A, tap **I'm safe now** → **Yes**. A shows `You told rescuers you are safe`. B's log shows `CANCEL of …`.
9. **Send now.** On B, tap the big **SOS**, then **Send now** straight away. B's status shows `Your SOS: Emergency`.
10. **Kannada at large text.** On the Kannada phone, set system font size to the largest. Home, the countdown and My SOS should still fit with nothing cut off (scroll is fine).

Send screenshots of: Home in each language, the countdown, My SOS after steps 5, 7 and 8, and B's log after step 5.

## Record

| Date | Phones (model, Android, language) | Countdown + send ok | Location (accuracy) | Passed to n ok | Late phone ok | I'm safe ok | Kannada 200% ok | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-11-01 | A 4303-3311 (English), B 88B9-7505 (Hindi), C D182-94BF (Kannada), D 7C0C-69DB (English, Galaxy M15); app 0.4.0; chain mode still on (A–B–C–D) | yes (A: Fire, Flood; B: Emergency via Send now) | yes (A ±99 m, B ±43 m) | yes (A: 1 phone, B: 2 phones) | yes (fast) | yes (both A SOS and B's cancel reached all 4 phones, 3 hops) | Kannada Home fits at default size; 200% not checked | 0 rejected, 0 duplicates; every phone holds the same 6 packets. Countdown Cancel (step 3) sent nothing. |

**Result: passed on 2026-11-01.**
- Every SOS and every cancel crossed the A–B–C–D chain: D got A's Fire SOS at `hops 2` with `age 1.4 s`.
- Home renders correctly in all three languages.
- My SOS shows the steps, location accuracy and the closed state.
- Cancel during the countdown sent nothing, and the late phone caught up quickly.
- Still to check: Kannada at the largest system font size.

## Also in 0.4.0

- **Stuck links recover by themselves.** When 3 link attempts in a row get no answer at all from Nearby, the phone restarts discovery (at most once a minute) and logs `No answer from … 3 times, restarting discovery`. Links that work are kept. This replaces tapping Stop and Start by hand (week-3 test, 0.3.3 run).
- Hindi and Kannada beyond the design 3.7 terms are drafts; a native speaker on the team should check `values-hi/strings.xml` and `values-kn/strings.xml`.
