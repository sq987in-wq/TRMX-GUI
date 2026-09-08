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
| GitHub Actions CI (`android.yml`) | the app **compiles**, JVM unit tests pass, APK artifact produced | on every push — **ACTIVE and GREEN** (run 34146966489, 2m26s) |
| `tests/spec_conformance.py` | the sources cannot drift from CONTROL-PLANE.md §3, PROTOCOL.md routes, fixtures/v1 payloads, manifest declarations — plus a Kotlin nested-comment balance audit | sandbox + CI |
| JVM unit tests (MockWebServer) | wire behavior: headers, routes, 401/parse/refused handling; wizard state machine; handshake poller | CI |

**Activation history:** the automation token pushing this branch lacks
GitHub's `workflows` permission and cannot create `.github/workflows/`
files (pushes containing them are rejected). The workflow content was
committed at `docs/ci/android-workflow.yml` and the repo owner activated
it manually (commit `6fc5cbc`, 2026-09-07). From then on, CI is the
compiler gate for the app.

**CI round 1 lessons (both fixed in `a362d1c`, round 2 green):**
1. *Nested comments.* The `Models.kt` header contained the glob
   `fixtures/v1/*.json`; Kotlin block comments NEST, so the glob's
   `/*` opened a nested comment that swallowed the entire file — every
   "Unresolved reference" in the log was a cascade of that one line.
   The conformance suite now ships a comment-balance audit (mini Kotlin
   lexer) so this bug class is caught before Gradle runs.
2. *Suspend default parameters.* A suspend callable reference
   (`::delay`) cannot be a default parameter value (default-value
   expressions run in a non-suspend context). `Handshaker` now takes a
   nullable delay and resolves it inside the body.
3. *On-device round 1 (2026-09-08): cleartext loopback.* The data plane is
   plain HTTP on 127.0.0.1, but targetSdk>=28 blocks cleartext by default —
   the app needs a loopback-scoped network security config or every request
   dies inside OkHttp with the bridge perfectly healthy. Fixed with
   `res/xml/network_security_config.xml` (loopback only, conformance-checked);
   handshake failure cards now surface the underlying transport error so this
   class self-diagnoses.
4. *On-device round 3: stale token vs. silent intents.* A manually started
   bridge keeps its own token in memory; pairing then 401s until restart —
   the wizard now sends STOP before PAIR (idempotent) and self-heals a 401
   once via PAIR→STOP→START, then falls back to a checklist card plus a
   manual-pairing escape hatch (visible, copyable token). Diagnostic that
   disambiguates "intent never ran" from "stale token":
   `token_generated` in `~/.trmx/bridge.json` (true = bridge-generated,
   false = app-paired).
5. *Reading CI logs from the restricted sandbox.* The log zip lives on
   a blocked host, but `gh api repos/…/actions/jobs/{id}/logs` mints a
   signed plain-text URL (visible in its EOF error output) that the
   platform page fetcher CAN read — the full loop stayed autonomous.

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
