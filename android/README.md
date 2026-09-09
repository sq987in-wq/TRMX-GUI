# android/ — TRMX-GUI native app

**Status: Phases 4–7 (shell + wizard + jobs + streaming + files) — 4–6 on-device verified on Android 16; Phase 7 CI-green, on-device round pending (ADRs [006](../docs/decisions/ADR-006-android-shell.md), [007](../docs/decisions/ADR-007-file-manager.md)).**

Native Android app (Kotlin + Jetpack Compose). The app is a *disposable control
plane*: it drives Termux through `RUN_COMMAND` intents (control plane, see
[docs/CONTROL-PLANE.md](../docs/CONTROL-PLANE.md)) and talks to `trmx-bridge`
over loopback HTTP (data plane, TRMX-P/1). Termux does the work.

```
app/src/main/java/dev/trmx/gui/
  MainActivity.kt            single-activity Compose host, state-driven screens
  AppViewModel.kt            orchestrates both planes; thin by design
  control/ControlOps.kt      the 8 intent ops — mirror of CONTROL-PLANE.md §3 (conformance-tested)
  control/IntentControlPlane.kt  intent assembly + send, permission handling
  net/BridgeClient.kt        TRMX-P/1 client (OkHttp, pure JVM, MockWebServer-tested)
  net/Handshaker.kt          START→poll-until-answer logic
  wizard/WizardEngine.kt     pure first-run state machine
  store/TokenStore.kt        pairing token + install base (private prefs; Phase 10: crypto store)
  ui/                        WizardScreen (3 consents + 5 steps), DashboardScreen, theme
```

## Building

```sh
# locally: JDK 17 + any recent Android Studio (or SDK 34 + Gradle 8.7)
cd android && gradle :app:assembleDebug
# unit tests
gradle :app:testDebugUnitTest
```

The repo deliberately does not commit a `gradle-wrapper.jar` (generated
artifact); CI installs Gradle 8.7 directly. Locally, run `gradle wrapper` once
or open the project in Android Studio and let it create the wrapper.

**CI activation is a one-time manual step** (the automation token pushing
this branch cannot create `.github/workflows/` files): copy
[`docs/ci/android-workflow.yml`](../docs/ci/android-workflow.yml) to
`.github/workflows/android.yml` — exact steps in
[docs/ci/README.md](../docs/ci/README.md). After that, every push builds the
APK in GitHub Actions and uploads it as an artifact — that is the compile
gate, since the dev sandbox has no JDK/Android SDK.

## Version matrix (ADR-006)

| Component | Version |
|---|---|
| Gradle | 8.7 |
| Android Gradle Plugin | 8.5.1 |
| Kotlin / serialization plugin | 1.9.24 |
| Compose compiler | 1.5.14 |
| Compose BOM | 2024.06.00 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 |

## What works now (Phases 4–5)

- 3-consent wizard → `INSTALL_PY → INSTALL → PAIR → START → handshake`
  (CONTROL-PLANE.md §4), warm-start reconnect, per-step failure states with
  actionable hints
- dashboard: system info + job list (manual refresh), stop bridge
- **submit jobs** (line-based argv editor, validation, idempotency keys),
  **job detail** (full record, 2 s auto-refresh while active), **cancel**
  with confirmation
- spec conformance: `python3 tests/spec_conformance.py` (also in CI)

- **live output console** in job detail: full replay + live follow (SSE),
  stdout/stderr filter, auto-scroll, ring-eviction honesty; dashboard job
  list updates in real time via the events stream

- **file browser**: navigate the Termux home (list/stat per PROTOCOL §6),
  new folder, rename, delete (recursive+confirm interlock surfaced in the
  UI), download to app storage, upload via the document picker, open/share
  via FileProvider (Phase 8)
- **toolbox** (Phase 9): live tool cards from the bridge registry
  (PROTOCOL §7), one-tap pkg installs with auto-rescan, schema-driven
  dynamic forms (path args pick in the Files browser), live progress bars,
  recipes + home-screen shortcuts, and linear `$PREV_FILE` chains — all
  orchestrated app-side over the existing wire

Tools: `./gradlew :app:testDebugUnitTest` (JVM suite, run in CI).
