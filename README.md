# ResQMesh

Offline SOS messenger for emergencies. Android phones relay signed SOS alerts to each other over Bluetooth and Wi-Fi when there is no network. The first phone that finds signal escalates them to a web dashboard and an SMS number, and the responder's reply travels back through the mesh to the sender.

Mini project, Dept. of CSE, Brindavan College of Engineering and Technology, AY 2026-27: Krishna Vamsi M, Praveen Mali, B G Mohan, Dharmaraj G H.

Plan (PRD, TRD, design spec, build plan): https://claude.ai/artifact/34uNTwCbhUQB4w5N5QbNtR
Agreed decisions: [docs/decisions.md](docs/decisions.md)

## Layout

| Folder | What | Starts |
| --- | --- | --- |
| `android/mesh-core` | Plain Kotlin protocol core: packet format, signing, validation, relay logic | Week 1 |
| `android/app` | Android app: Compose UI, Nearby Connections service, gateway, SMS | Week 2 |
| `android/simulator` | JVM simulator running many virtual phones on `mesh-core` | Week 5 |
| `backend` | Node.js + Express API on PostgreSQL | Week 4 |
| `dashboard` | React + Vite + Leaflet operator dashboard | Week 4 |
| `shared/test-vectors` | Signed sample packets that every implementation must agree on | Week 1 |
| `experiments` | Test plans, raw CSV logs, plots | Week 9 |
| `docs` | Decisions, report drafts | Week 1 |

## Build and test `mesh-core`

You need JDK 17 or newer (21 recommended). Android Studio's bundled JDK also works.

```bash
cd android
./gradlew :mesh-core:test
```

On Windows `cmd` or PowerShell, use `gradlew.bat :mesh-core:test`.

If you change the packet format, regenerate the shared test vectors and commit them:

```bash
cd android
./gradlew :mesh-core:writeTestVectors
```
