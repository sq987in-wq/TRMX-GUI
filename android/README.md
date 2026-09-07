# android/ — TRMX-GUI Android app

**Status: reserved. No code until Phase 4 (Android PoC), which starts after Phase 1 (protocol freeze) review and Phase 2–3 (bridge PoC + bootstrap automation).**

Planned (per `docs/ARCHITECTURE.md` and the Phase 0 report §E):

- Kotlin 2.x, Jetpack Compose + Material 3, single-activity MVVM
- `BridgeClient` (OkHttp + kotlinx.serialization, SSE/chunked streaming, TRMX-P/1)
- `IntentControlPlane` (Termux `RUN_COMMAND` intents)
- Room (history/tool cache) · DataStore (settings) · Keystore-wrapped token
- minSdk 26, targetSdk latest; tests validate against `fixtures/v1/`
