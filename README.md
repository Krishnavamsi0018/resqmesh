# ResQMesh

Offline SOS messenger for emergencies. Android phones relay signed SOS alerts to each other over Bluetooth and Wi-Fi when there is no network. The first phone that finds signal escalates them to a web dashboard and an SMS number, and the responder's reply travels back through the mesh to the sender.

Mini project, Dept. of CSE, Brindavan College of Engineering and Technology, AY 2026-27: Krishna Vamsi M, Praveen Mali, B G Mohan, Dharmaraj G H.

Plan (PRD, TRD, design spec, build plan): https://claude.ai/artifact/34uNTwCbhUQB4w5N5QbNtR
Agreed decisions: [docs/decisions.md](docs/decisions.md)

## Layout

| Folder | What | Starts |
| --- | --- | --- |
| `android/mesh-core` | Plain Kotlin protocol core: packet format, signing, validation, relay logic | Week 1 |
| `android/app` | Android app: Compose UI, Nearby Connections service, gateway, SMS | Week 2 (link test console now) |
| `android/simulator` | JVM simulator running many virtual phones on `mesh-core` | Week 5 |
| `backend` | Node.js + Express API on PostgreSQL | Week 4 |
| `dashboard` | React + Vite + Leaflet operator dashboard | Week 4 |
| `shared/test-vectors` | Signed sample packets that every implementation must agree on | Week 1 |
| `experiments` | Test plans, raw CSV logs, plots | Week 9 |
| `docs` | Decisions, report drafts | Week 1 |

## Build and run

Keep the repo out of OneDrive or Dropbox folders: synced folders break Gradle rebuilds. Use something like `C:\dev\resqmesh`.

**Android Studio (easiest):** open the `android` folder, wait for the Gradle sync, plug in a phone with USB debugging on, and press Run (the `app` configuration).

**Command line:** any JDK 17+ can start `gradlew`. The build itself runs on Java 25 (`android/gradle/gradle-daemon-jvm.properties`): Gradle uses Android Studio's bundled `jbr` or downloads Java 25 if it can't find one. Gradle also needs to find the Android SDK, through `ANDROID_HOME` or an `android/local.properties` line such as `sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk`.

```bash
cd android
./gradlew :mesh-core:test :app:testDebugUnitTest   # unit tests
./gradlew :app:installDebug                          # build and install on the connected phone
```

On Windows `cmd` or PowerShell, use `gradlew.bat` instead of `./gradlew`.

The week-2 two-phone test is in [docs/week2-link-test.md](docs/week2-link-test.md).

If you change the packet format, regenerate the shared test vectors and commit them:

```bash
cd android
./gradlew :mesh-core:writeTestVectors
```
