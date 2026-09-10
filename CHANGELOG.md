# Changelog

All notable changes to TRMX-GUI are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow the
`app` / `bridge` / `protocol` triple (protocol major = breaking wire changes, see PROTOCOL.md §13).

## [Unreleased]

### Changed — universal runtime: broadened bundled tools + fully generic progress engine (bridge v0.4.1, 2026-09-10)
- Framing made explicit: TRMX-GUI is a **Universal Linux Runtime & Visual
  Control Plane for Android** — the media trio was a showcase, never the
  ceiling. Bundled schemas now span four more categories: VCS (`git-clone`),
  scripting/AI (`python-run`), system/network (`http-server`), system
  utility (`tar-backup`) — alongside yt-dlp/ffmpeg/aria2c.
- Progress engine is fully generic (§7.2 semantics, zero download
  hardcoding): parses stdout **and** stderr (git/ffmpeg report progress on
  stderr), treats `\r` as a line break (progress bars), parses a final
  unterminated line, and non-numeric capture groups (port bindings,
  key=value metrics) update `progress_detail` without inventing a percent.
- `POST /v1/tools/refresh` now **reloads schema files** — a schema dropped
  into `~/.trmx/tools/` appears after a refresh, no bridge restart needed
  (also the hook an AI schema-builder will use).
- Tests: `test_tools` 14 → 17 (stderr+CR progress via a fake git, port-line
  detail-only, refresh reload); full Termux suite 66/66.


### Added — Phase 9: Tool Registry, dynamic forms, recipes, shortcuts & chains (2026-09-09)
- **Bridge v0.4.0 — PROTOCOL §7 implemented** (frozen since Phase 1):
  `GET /v1/tools[/{id}]` + `POST /v1/tools/refresh` (probe via
  `command -v` + `--version`), bundled schemas yt-dlp/ffmpeg/aria2c, user
  schemas from `~/.trmx/tools/` override bundled by id (malformed skipped,
  reported in `features.tool_schema_errors`), `type:"tool"` submits with
  bridge-side argv synthesis + full ARG_INVALID/TOOL_UNKNOWN/PATH_* validation,
  **live progress** via each schema's `progress_regex` (percent →
  `progress_pct`/`progress_detail` → `job.updated`), risk tier audit-logged.
  Two real bugs caught by the new suite: bool-false args emitted tokens, and
  the common argv validator clobbered synthesized argv.
- **App Toolbox**: live tool cards (installed/version/tier), scan ⟳,
  user-schema errors surfaced, **self-healing one-tap installs**
  (`pkg install -y` as a normal job with live console; auto-rescan via
  `/v1/events` when it finishes).
- **Dynamic forms (FormEngine + ToolFormScreen)**: schema-driven controls per
  type (enum chips, bool switch, numeric fields, URL field, **path args
  picked in the Files browser** — file vs folder per `path_kind`), example
  prefill chips, live argv preview strip, risk-tier confirmation.
- **Mission Control**: tool jobs show schema-parsed live progress bars on the
  dashboard (the % comes from real tool output, parsed by the bridge).
- **Recipes + home-screen shortcuts** (ADR-010): saved forms as JSON in
  `filesDir/recipes.json`, shared/imported via the Phase 8 staging mechanism
  (imports always get a fresh id), newest 4 as one-tap dynamic shortcuts
  (singleTask MainActivity, recipe deep-link through the wizard gate).
- **Chains** (ADR-010): linear visual pipelines with `$PREV_FILE` refs
  (chainable only from tools with an explicit output arg — no filename
  guessing), app-side orchestration over the existing wire (submit → poll →
  next), pause/resume on bridge loss, stop-orchestrating that never hides a
  running job, persisted in `chains.json`.
- Events subscription moved to app-root scope (feeds chains, install
  watcher, and dashboard updates on every screen).
- New fixture `fixtures/v1/tools.list.response.json` documents the frozen
  §7.1 list shape (shape unchanged; added with ADR-009 per the fixtures
  rule). Both suites validate against it.
- Tests: termux `test_tools.py` (14 — synthesis table, probing, progress
  end-to-end with fake binaries, merge precedence, tier audit) → suite
  63/63; app `ToolsWireTest` (4) + `FormEngineTest` (4) + `ChainPlannerTest`
  (3) + `RecipeStoreTest` (4).


### Added — Phase 8: polish, UX & FileProvider (2026-09-08, app-only)
- **Open/Share (ADR-008, download-then-open)**: long-press action sheet with
  Open (ACTION_VIEW) and Share (ACTION_SEND) — the entry streams from the
  bridge into a single-slot `cache/shared/` staging dir (cleared before each
  use), exposed read-only through `androidx.core.content.FileProvider`
  (`file_paths.xml` exposes only `shared/`). No viewer → honest error naming
  file + MIME with a "try Share" hint, never a silent failure.
- **`FileMime`**: our own extension→MIME table (JVM-testable, deterministic
  across OEMs — `MimeTypeMap` regularly lacks mkv — honest
  `application/octet-stream` fallback).
- **Transfer progress**: `BridgeClient.downloadFile/uploadFile` gained
  `onProgress` callbacks (64 KiB chunks); the ViewModel throttles to ~10 Hz;
  determinate bar when the total is known, indeterminate otherwise, byte
  counts included. Upload progress streams via `ProgressRequestBody` with
  byte-identical wire content (test-asserted).
- **State polish**: three-state Files list (loading / error-with-retry /
  empty), back walks up the directory tree before exiting Files, honest
  dashboard status dot, stale Phase-6 footer replaced, long-press hint.
- **Launcher icon**: adaptive vector `>_` prompt (green chevron, blue
  underscore) on dark slate + Android 13+ monochrome layer; minSdk 26 means
  no raster sets.
- Tests: `FileMimeTest` (4) + 2 progress tests in `FilesTest` (7 → 9);
  conformance PASS; Termux suite 49/49 (bridge untouched, regression only).

### Verified — on-device (Android 16, 2026-09-09)
Phase 8 closed on real hardware: launcher icon, back navigation
(walks up the directory tree before exiting), and open/share via
FileProvider all confirmed working. App-only round — bridge stayed
v0.3.0 throughout.

### Added — Phase 7: file manager (both planes) (2026-09-08)
- **Bridge v0.3.0 — PROTOCOL §6 implemented in full**: list/stat with
  1000-entry paging, mkdir/touch/rename/move/copy/delete with the
  recursive-delete confirm interlock, ranged download (206/416), and
  streaming atomic upload (temp+fsync+rename, optional X-TRMX-Sha256 → 422,
  411 without Content-Length, 2 GiB cap). Path policy: realpath containment
  (blocks escapes and outward links) while operations act on the normalized
  path — deleting a symlink removes the link, never its target (a real bug
  the new test suite caught). $PREFIX stays unwritable.
- **App file browser**: navigate/create-folder/rename/delete (with explicit
  recursive+irreversible warnings), download into app-accessible storage,
  upload via the system document picker — all against byte-identical
  fixtures in tests.
- Registry conformance fix: PATH_NOT_EMPTY is 409 per §10 (first
  implementation used 400; the registry check caught it).
- App client wire-shape fixes (caught by the new FilesTest in CI): query
  encoding keeps `~` bare (RFC 3986 unreserved — was %-escaped to `%7E`),
  and §6.3 op requests omit inapplicable optional fields (`new_name`,
  `dest_dir`) instead of sending explicit nulls — matching
  `fixtures/v1/files.ops.request.json`; the jobs plane still sends explicit
  nulls per its fixture.
- Tests: termux/tests/test_files.py — 17 tests (traversal, shapes, paging,
  ops, interlock, ranges, upload edge cases). Full Termux suite 49/49.
- ADR-007 records the security-relevant decisions.


### Verified — on-device (Android 16, 2026-09-08)
Phase 7 closed on real hardware: bridge upgraded to v0.3.0 in place via the
wizard's Re-run setup (idempotent installer) and running stably; Files
browser verified — `~` listing, navigation, folder create/rename/delete,
file download — and the §6 wire shape with protocol headers
(`X-TRMX-Protocol: 1`) confirmed working end-to-end.


### Added — Phase 6: live output streaming (SSE) (2026-09-08)
- **`SseClient`** (pure JVM, OkHttp/Okio): TRMX-P/1 §4 stream parser —
  `event:`/`id:`/`data:` frames, ping/retry lines ignored, multi-line data
  joined, coroutine-cancellation wired to socket close; non-2xx surfaces as
  `SseHttpException`. Tested against byte-identical copies of both normative
  stream fixtures (`jobs.output.stream.txt`, `events.stream.txt`).
- **`OutputReducer`** (pure): frames → console state — stdout/stderr lines,
  status markers ("— COMPLETED (exit 0) —"), evicted-ring honesty flag,
  terminal detection, unknown-event tolerance, malformed-data tolerance,
  1000-line / 200 KB UI budget enforced from the front.
- **Job detail → live console**: full replay (from_seq=1) + live follow,
  stdout/stderr filter, auto-scroll toggle, "replay from start", stderr
  tinted; live `status` frames also update the detail header; stream
  resumes from the last delivered seq on connection loss (up to 30
  attempts), ends cleanly after a terminal status frame.
- **Dashboard auto-update**: `/v1/events` subscription while the dashboard
  is visible — `job.updated` events apply directly to the job list,
  `bridge.stopping` shows a notice; reconnects with 2 s backoff.
- Conformance: SSE routes checked against PROTOCOL.md; embedded-fixture
  matching generalized to all test files (multi-line raw strings must match
  a fixture byte-identically or JSON-equal).


### Verified — on-device end-to-end (Android 16, 2026-09-08)
Phases 4–5 closed with real hardware: wizard → install → pair → start →
handshake → three jobs (J-1…J-3) completed with exit code 0, bridge fully
connected. Three fix rounds were needed and are recorded in ADR-006:
loopback cleartext policy, missing INTERNET permission, and the
STOP-before-PAIR pairing flow (plus stable CI debug signing).


### Fixed — third on-device round (2026-09-08): pairing flow robustness
- **STOP before PAIR in the wizard sequence** (idempotent): a bridge left
  running — e.g. started manually in Termux — holds its old token in memory
  and 401-rejects the app's fresh token until restarted. Stopping first
  makes the sequence self-contained.
- **401 self-healing:** handshake 401 now triggers one automatic
  PAIR → STOP → START → re-handshake recovery before failing.
- **Actionable 401 card + manual pairing escape hatch:** the failure card
  lists the consent checklist; the app's token is shown with a copy button
  (`trmx stop` → `trmx pair <token>` → `trmx start` → Retry) so setup can
  finish even when intents cannot reach Termux.
- Diagnostic documented: `token_generated` in `~/.trmx/bridge.json` tells
  "intent never ran" (true) from "stale token" (false).


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
