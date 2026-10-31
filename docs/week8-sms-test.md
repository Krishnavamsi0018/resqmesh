# SMS test (week 8)

Goal: an SOS reaches the rescue team by SMS when the phone that holds it has mobile signal but no internet (F-41, TRD 2.7). One phone texts it; the others learn that through the mesh and stay quiet. The sender sees *Rescue team informed (also by SMS)*, and once any phone is online the dashboard shows that it was texted. This completes gate G3.

SMS goes only to the team numbers built into the app (`resqmesh.smsNumbers` in `android/local.properties`, never committed). The app refuses every other number, and 112 and other emergency numbers always. Test SOS from the Network tab buttons are never texted; use the Home screen SOS.

## You need

- 4 phones with **0.7.0-sms**, installed from the laptop over USB (`adb install -r`): Play Protect blocks installing an app that asks for SMS from a file. Force stop the app on each before starting; chain ticks stay as they were.
- **D** must have a SIM with SMS balance.
- The **receiving phone**: the team number's phone, not one of A–D in airplane mode (a 5th phone, or any team member's own phone).

## Set up

1. **A, B, C:** airplane mode, Bluetooth on, Location on.
2. **D:** airplane mode **off**, **mobile data off**, Wi-Fi **off** (so it has signal but no internet), Bluetooth on, Location on.
3. On **D**, open the Network tab → **SMS to the rescue team** box:
   - tap **Allow SMS** and allow it;
   - check the team number is the receiving phone's number (change it and tap **Save** if not);
   - the box should say `Signal: texts SOS to the team · texted 0 · waiting 0`.
4. Check the chain links (A–B, B–C, C–D), then Stop and Start relaying on all four.

| Phone | Chain ticks |
| --- | --- |
| A 4303-3311 | 6D29-3B67 |
| B 6D29-3B67 | 4303-3311, D182-94BF |
| C D182-94BF | 6D29-3B67, 7C0C-69DB |
| D 7C0C-69DB | D182-94BF |

## Tests

1. **SOS by SMS.** On **A**, from Home, send a **Flood** SOS. Expected within about 30 s:
   - D's log: `Texting SOS …`, then `Texted SOS … to the rescue team; told the mesh`. The SMS box shows `texted 1`.
   - The receiving phone gets one SMS like `SOS FLOOD via ResQMesh. 1 ppl. … from 4303-3311 ID … hops 2`.
   - A's My SOS step 3 says **Rescue team informed (also by SMS)**.
2. **Only one SMS.** Wait 2 minutes. No second SMS arrives for the same SOS.
3. **No signal, then signal.** On D, turn airplane mode **on** (Bluetooth back on). The SMS box says `No mobile signal`. On **B**, send a **Medical** SOS: D's box shows `waiting 1`. Turn airplane mode **off** on D (mobile data still off): within about 30 s the SMS arrives and B shows *also by SMS*.
4. **The dashboard learns it.** On D, turn mobile data or Wi-Fi **on**. Within about 30 s the dashboard shows A's and B's SOS; their detail says *also sent by SMS*, and *What happened* has *Phone 7C0C-69DB texted this SOS*.
5. **Wrong number refused.** On D, type `112` in the team number and tap **Save**: it says only team test numbers are allowed.

Send screenshots of the SMS on the receiving phone, D's SMS box after test 1, A's My SOS after test 1, and the dashboard detail after test 4.

## Record

| Date | Texting phone | SMS arrived | Only one | After signal came back | Dashboard shows SMS | 112 refused | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-11-01 | D 7C0C-69DB (signal, no data) | Yes, on C's SIM | Yes | Yes, B's Medical texted after airplane mode off | Yes, also sent by SMS | Yes | All 5 tests passed, checked by the team. 0.7.0 was installed over USB (adb): Play Protect's fraud protection blocks sideloaded apps that ask for SMS. C received the SMS, so C ran with airplane mode off, data off and SMS not allowed. |

## If something goes wrong

- **The box says the build has no team numbers.** The APK was built without `resqmesh.smsNumbers`; ask for a new build.
- **`Last SMS failed: send failed`.** Usually no SMS balance, or the SIM can't send. Try the same text by hand from D's Messages app.
- **Nothing texted, `waiting` stays at 1.** Check D shows `Signal:` (not `No mobile signal`) and that SMS permission is allowed.
