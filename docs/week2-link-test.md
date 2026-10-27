# Week 2 link test (gate G1)

Goal: two phones link over Nearby Connections with no internet and swap signed test SOS packets, including with both screens off.

## You need

- Two Android 8.0+ phones with Google Play Services and USB debugging on.
- The debug app installed on both (Run from Android Studio, or `./gradlew :app:installDebug` once per phone).

## Steps

1. On both phones, turn mobile data and Wi-Fi off. Bluetooth and Location stay on; Wi-Fi may stay on but must not be connected to a network.
2. Open ResQMesh on both phones. Tap **Allow** and grant every permission. Note each phone's ID (e.g. `65B6-0673`).
3. If a card says battery optimisation may stop relaying, tap **Allow background** and choose Allow.
4. Tap **Start relaying** on both. Within about 30 s each phone should show the other under *Nearby phones* as **linked**, and the notification should read "Linked to 1 phone".
5. On phone A, tap **Send test SOS: Flood**. Phone B's log should show a green line: `SOS FLOOD … from <A's ID> via <A's ID> · hops 0 · age … s`. Repeat from B to A with Medical.
6. Screen-off check: lock both phones and wait 2 minutes. Unlock only A and send a test SOS. Then unlock B: its log must show the packet, received while its screen was off.
7. Distance check (optional): walk phone B away until the link drops, note the distance, then walk back and check that it relinks by itself.

## Record

| Date | Phone A (model, Android) | Phone B (model, Android) | Linked in (s) | A→B ok | B→A ok | Screen-off ok | Drop distance (m) | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-10-27 | OnePlus CPH2467, Android 15 (node AF0F-62E5) | Samsung Galaxy M15 SM-M156B, Android 16 (node 7C0C-69DB) | ~5 | yes | yes | yes | not measured | Airplane mode + Bluetooth; link over encrypted Bluetooth Classic. 0 rejected, 0 duplicates. |

**Result: gate G1 passed on 2026-10-27**, ahead of the planned end of week 3.

Two things from the logs that matter later:

- **The phones' clocks differ by about 1.1 s.** A→B packets showed an age of +1.2–1.3 s and B→A −1.0 s. Taken together, that means one-way delivery takes roughly 0.1 s and the M15's clock runs about 1.1 s ahead. Week-9 latency figures therefore need NTP-synced clocks or the round-trip measurement (TRD 2.12); raw `age` values aren't latency.
- **The lower node ID made the connection request** (7C0C… asked, AF0F… accepted), as LinkPolicy intends.

## If something goes wrong

- **Never links:** check Location is on (Android 11 and older need it) and that both phones granted Nearby devices. Stop and start relaying on both. Look for red `Advertising failed` or `Discovery failed` lines in the log.
- **Links, then drops when the screen turns off:** the phone's battery optimisation is killing the app. Allow background (step 3); on Xiaomi also turn on Autostart, and on Samsung add ResQMesh under *Never sleeping apps*.
- **Red `Rejected (...)` lines:** report the reason shown. It means a packet arrived but failed a check.

Send the log lines (or a screenshot) and the filled-in table back. That's the evidence for gate G1.
