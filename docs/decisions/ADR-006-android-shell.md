# ADR-006 — Android App Shell (Phase 4)

- **Status:** Accepted (Phase 4)
- **Date:** 2026-09-07
- **Related:** ADR-001 (two planes), [docs/CONTROL-PLANE.md](../CONTROL-PLANE.md) (frozen intent contract), [docs/PROTOCOL.md](../PROTOCOL.md) (TRMX-P/1)

## Context

Phase 4 starts the native app. A hard environment fact shapes this ADR: the
dev sandbox has **no JDK, no Android SDK, and no network beyond github.com**
(Maven/Google repos unreachable). "Write code and run it" is not possible
here — so the verification strategy had to be designed honestly, not assumed.

## Decision

### 1. Verification without a local toolchain (three honest layers)

| Layer | What it proves | Where |
|---|---|---|
| GitHub Actions CI (`android.yml`) | the app **compiles**, JVM unit tests pass, APK artifact produced | on every push — **pending one-time activation**, see below |
| `tests/spec_conformance.py` | the sources cannot drift from CONTROL-PLANE.md §3, PROTOCOL.md routes, fixtures/v1 payloads, manifest declarations | sandbox + CI |
| JVM unit tests (MockWebServer) | wire behavior: headers, routes, 401/parse/refused handling; wizard state machine; handshake poller | CI |

**Activation gap, stated plainly:** the automation token that pushes this
branch lacks GitHub's `workflows` permission, so it cannot create
`.github/workflows/android.yml` itself (pushes containing it are rejected).
The workflow content is committed at `docs/ci/android-workflow.yml` with
one-step activation instructions ([docs/ci/README.md](../ci/README.md)).
Until a human activates it (or builds once in Android Studio), the app is
**UNCOMPILED** — spec conformance catches contract drift, not syntax errors.
This ADR is accepted only together with a green CI run or a user-side build.

### 2. Project shape

- Single-activity Compose app, **state-driven screens** (no navigation
  library in Phase 4 — fewer dependencies, fewer blind-build risks).
- Package/applicationId: **`dev.trmx.gui`** (stable identity for the
  RUN_COMMAND permission grant; no personal handle in the id).
- minSdk 26 / target & compile 34 (Phase 0 decision: user on Android 14+).
- Version matrix pinned to a known-good set (Gradle 8.7, AGP 8.5.1, Kotlin
  1.9.24, Compose compiler 1.5.14, BOM 2024.06.00) — recorded in
  android/README.md so upgrades are deliberate.
- No `gradle-wrapper.jar` committed (generated binary we cannot produce
  offline); CI installs Gradle 8.7 directly, Android Studio generates the
  wrapper locally on first open.

### 3. Architecture split (all decision logic is pure JVM)

- `ControlOps` — pure data table mirroring CONTROL-PLANE.md §3, with
  placeholder resolution (`<BASE>`, `<TOKEN>`) that **fails loud** on
  unresolved placeholders, and a sandbox-path invariant (we only ever
  execute inside `/data/data/com.termux/files/...`).
- `WizardEngine` — pure reducer (consents → 5 install steps → done/failed).
- `Handshaker` — injectable-delay poller; surfaces 401/403 immediately
  (retrying cannot fix auth).
- `BridgeClient` — pure JVM OkHttp client, both TRMX-P/1 headers on every
  request, TRMX-P/1 error-envelope parsing; tested against MockWebServer
  with **byte-identical fixtures/v1 payloads** (drift-checked by the
  conformance suite).
- `AppViewModel` — deliberately thin glue (AndroidViewModel, flows).
- `IntentControlPlane` — the only file touching intent APIs; sends are
  `Result`-typed, mapping `SecurityException`/`IllegalStateException` to
  CONTROL-PLANE.md §5 states.

### 4. Scope kept intentionally small (core-first)

The wizard's step acceptance is observed **on the data plane**, never via
intent callbacks (CONTROL-PLANE.md §1). Fixed waits (40 s Python install,
20 s bridge install, 3 s pair) are honest placeholders — they are skippable
or retryable, and the handshake (30 s poll) is the real gate. Warm start:
probe → optional START intent → probe again.

Deferred: SSE live output (Phase 6), submit/cancel UI (Phase 5), token in
EncryptedSharedPreferences (Phase 10 — plain `MODE_PRIVATE` prefs now, with
the file-locked data directory as the practical boundary), release signing
and pinned install digests (Phase 10/11), icons (Phase 8).

## Consequences

- Every push from now on must keep CI green for the app — the workflow is
  the project's compiler.
- `tests/spec_conformance.py` joins the permanent gates (sandbox + CI).
- If Actions is disabled on the repo, the first user-side Android Studio
  build substitutes as the compile gate (instructions in android/README.md).
