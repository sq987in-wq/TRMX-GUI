# android/ — TRMX-GUI native app

**Status: Phases 4–5 (app shell + wizard + job management) — ON-DEVICE VERIFIED on Android 16 (2026-09-08): full wizard flow, 3 jobs executed exit 0 ([ADR-006](../docs/decisions/ADR-006-android-shell.md) records the 3 fix rounds).**

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

Live SSE output streaming, files, tools: Phases 6–9.
