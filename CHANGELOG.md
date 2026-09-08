# Changelog

All notable changes to TRMX-GUI are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow the
`app` / `bridge` / `protocol` triple (protocol major = breaking wire changes, see PROTOCOL.md §13).

## [Unreleased]

### Fixed — second on-device test round (2026-09-08)
- **Missing INTERNET permission (root cause of `socket failed: EPERM`).**
  Even loopback sockets require `android.permission.INTERNET`; on API 34+
  its absence surfaces as EPERM (not the older EACCES). The manifest now
  declares it (conformance-checked). The round-1 cleartext fix was necessary
  but not sufficient — this second layer was masked behind it.
- **Stable debug signing.** Ephemeral CI runners mint a fresh random debug
  keystore per build, so every artifact APK was signed differently and could
  not be installed over the previous one. A committed PKCS12 test keystore
  (`android/config/debug.keystore`, alias `androiddebugkey`, public
  passwords) now signs all debug builds — in-place updates work from this
  build on. Release signing with a secret keystore remains Phase 11.

### Fixed — first on-device test round (2026-09-08)
- **Cleartext loopback was blocked (root cause of the wizard handshake
  timeout).** Android 9+ blocks cleartext HTTP by default for targetSdk>=28;
  the app had no network security config, so every request to the bridge
  failed inside OkHttp before any socket was opened — the healthy bridge
  never saw a single request. Fixed with a **loopback-scoped**
  `network_security_config.xml` (cleartext permitted for 127.0.0.1/localhost
  only; no blanket fallback), now pinned by the conformance suite.
- The wizard's handshake-timeout card now includes the underlying network
  error ("Last network error: …") — this failure class self-diagnoses from
  now on.

### Added — Phase 5: job submission & management (2026-09-07)
- **Submit** — "+ New job" dialog: name, argv (one line = one argument — no
  shell parsing, no quoting, no injection surface by construction), optional
  cwd + timeout; client-side validation mirrors the bridge's caps for
  instant feedback; every submission carries a fresh idempotency key
  (Idempotent-Replay honored).
- **Job detail** — full TRMX-P/1 job record (argv, timings, pid, exit
  code, error, byte counters, ring-eviction honesty), auto-refresh every
  2 s while active.
- **Cancel** — with confirmation; explains the TERM → grace → KILL
  semantics; grace_ms/force per the wire contract.
- `BridgeClient`: submitJob/getJob/cancelJob/listJobs(filters) — all
  tested against MockWebServer with the normative fixtures (request bodies
  JSON-checked, byte-identical response payloads).
- `SubmitValidator` (pure): mirrored validation caps, line-based argv
  parsing.
- CI round 3 fixed two blind-write bugs: nullable `MutableStateFlow`
  update lambdas and missing JobSummary wire fields (cwd/pgid/cancel_reason/
  script/env — the wire always had them).

### Added — Phase 4: Android app shell & connection wizard (2026-09-07)
- **Compile gate closed (CI round 2, `a362d1c`):** the app builds and all JVM
  unit tests pass on GitHub Actions; debug APK + test results are uploaded as
  artifacts. Round 1 caught two real bugs, both fixed: a nested-comment
  swallow in `Models.kt` (Kotlin block comments nest — a glob in a comment
  ate the file) and an illegal suspend-callable default parameter in
  `Handshaker`. The conformance suite now includes a Kotlin comment-balance
  audit so the first bug class is caught before Gradle runs.
- **`android/`** — native app (Kotlin + Compose, `dev.trmx.gui`, minSdk 26 /
  target 34): single-activity state-driven UI, 3-consent wizard →
  `INSTALL_PY → INSTALL → PAIR → START → handshake` sequence per
  CONTROL-PLANE.md §4 (with warm-start reconnect, per-step failure states,
  skip/retry), dashboard with system info + job list + stop bridge.
- **`ControlOps` / `IntentControlPlane`** — the 8 control-plane ops, argv
  construction with loud placeholder validation + Termux-sandbox path
  invariant; intent sends map to CONTROL-PLANE §5 error states.
- **`BridgeClient` / `Handshaker`** — pure-JVM TRMX-P/1 client (both required
  headers on every request, error-envelope parsing) + handshake poller.
- **JVM unit tests** — MockWebServer wire tests using byte-identical
  `fixtures/v1` payloads, wizard state machine, handshake poller, op-table
  invariants.
- **CI workflow for the app** — the sandbox token cannot create
  `.github/workflows/` files (no `workflows` permission), so the ready-made
  workflow is committed at `docs/ci/android-workflow.yml` with one-step
  activation instructions; once active it is the app's compile gate
  (assembleDebug + unit tests + spec conformance + APK artifact on every push).
- **`tests/spec_conformance.py`** — static conformance gate: Kotlin sources
  vs CONTROL-PLANE.md §3 op table, PROTOCOL.md routes, fixture payloads,
  manifest declarations.
- **`docs/decisions/ADR-006`** — verification-without-local-toolchain
  strategy, version matrix, scope decisions.

### Added — Phase 3: Bootstrap automation (2026-09-07)
- **`termux/install.sh`** — one-command installer driven by `RUN_COMMAND`
  intents: remote (`--base URL`, default GitHub raw) and local (`--source`)
  modes, staged + SHA256-verified + atomic `mv` install (never a partial
  file), idempotent re-runs with `.old` backups, auto stop/replace/restart
  of a **running** bridge, `installed.json` manifest, loud `UNVERIFIED`
  warning when no sums are available.
- **`termux/gen_checksums.sh`** — regenerates `SHA256SUMS` (must run whenever
  the bridge/CLI change).
- **`docs/CONTROL-PLANE.md`** — the frozen intent interface for the Android
  app: 8 operations with exact argv, precondition/consent table, first-run /
  warm-start / upgrade sequences, error-state mapping.
- **`termux/trmx` v0.3.0** — new commands: `enable-boot` (Termux:Boot
  autostart script), `enable-service` (runit service; requires
  `termux-services`), `version`; env overrides (`TRMX_HOME`, `TRMX_PREFIX`,
  `TRMX_BOOT_DIR`) for testability.
- **`termux/tests/test_bootstrap.py`** — 12-test bootstrap suite (install
  verified/refused/idempotent/upgrade-with-restart, remote mode via
  network-free `file://`, enable-boot/service, intent command reference).
  **12/12 passing.**
- **`termux/tests/intent_commands.sh`** — prints the exact `adb` commands for
  all 8 control-plane ops (on-device proof without the app).
- **`docs/decisions/ADR-005`** — bootstrap decisions + trust-anchor honesty.
- `termux/tests/run_tests.sh` now runs both suites (bridge + bootstrap).

### Added — Phase 2: Termux bridge PoC (2026-09-07)
- **`termux/trmx-bridge.py` v0.2.0** — the execution-plane daemon (single file, Python 3
  stdlib only, per ADR-002). Implements the TRMX-P/1 subset: system/info & policy,
  argv job submission with validation + queueing + concurrency cap, job list/get,
  graceful→forced process-group cancel, timeouts, idempotency replay, live SSE output
  streaming with replay-from-seq + honest `evicted`/`reattached` notices, live global
  event stream, bridge stop/restart, WAL SQLite job store, per-job JSONL frame rings,
  startup reconciliation (re-adopt orphans / mark LOST), loopback-only bind with
  fail-loud port-conflict exit, bearer-token auth (constant-time, per-connection
  backoff, audited), Origin rejection, `X-TRMX-Protocol` handshake.
- **`termux/trmx`** — control CLI: `init / pair / unpair / token / start / stop / status / pid`
  (runnable while the bridge is down; the intent control plane of Phase 3 will drive it).
- **`termux/tests/test_bridge.py`** — 19 tests over real TCP against a real bridge
  subprocess: auth/guards, malformed requests, lifecycle, live streaming, replay,
  cancel, timeout, idempotency, queueing, crash→re-adopt→cancel, crash→LOST,
  ring eviction honesty, events stream, pagination, CLI lifecycle. **19/19 passing,
  3 consecutive runs (~8 s each).**
- **`termux/tests/smoke.sh`** — the human-visible Phase 2 demo (pair → start → handshake →
  auth check → live stream → cancel → SIGKILL crash → restart → re-adopt → stop).
  Runs identically in CI and in Termux. **PASSING.**
- **`docs/decisions/ADR-004`** — PoC scope, dev-mode leniency, harness lessons.
- `.gitignore`.

### Added — Phase 1: Protocol freeze & project constitution (2026-09-07)
- **`docs/PROTOCOL.md`** — TRMX-P/1 wire contract: conventions, auth, all endpoints, job object & state semantics, SSE framing, file API + path policy, Tool Registry schema format, error-code registry, limits, versioning/governance. **Draft — awaiting review.**
- **`docs/ARCHITECTURE.md`** — living architecture summary (components, flows, state machines, storage, security core).
- **`docs/decisions/`** — ADR-001 (architecture baseline, stakeholder-approved), ADR-002 (protocol v1 core decisions), ADR-003 (MIT license).
- **`fixtures/v1/`** — normative contract fixtures shared by future Kotlin & Python test suites.
- Repo layout: `android/` (Phase 4+), `termux/` (Phase 2+), `fixtures/`.
- `CHANGELOG.md` (this file) and `LICENSE` (MIT).

### Added — Phase 0: Feasibility & Architecture Discovery (2026-09-07)
- **`docs/PHASE-0-DISCOVERY-REPORT.md`** — feasibility verdict (19 questions answered honestly + Maximum Practical Control Boundary), communication-option comparison (A–G), full system architecture, Termux-side & Android-side designs, security/threat model, GUI philosophy analysis, feature map V1–V3, 13-phase roadmap, tooling, testing strategy & failure matrix, risk register, open questions.
- Stakeholder decisions recorded (Appendix 3); **architecture approved**.

### Notes
- No implementation code exists yet, by design. Phase 2 (`termux/trmx-bridge.py` PoC) begins after the TRMX-P/1 draft is reviewed.
