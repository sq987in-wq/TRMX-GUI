# TRMX-GUI

**A native Android graphical remote-control dashboard for [Termux](https://termux.dev).**

> The Android app is a beautiful control plane. **Termux is the execution engine.**
> `Android UI → control/communication layer → Termux → shell · Python · CLI tools · media · automation · AI agents`

TRMX-GUI does not replace Termux — it drives it: run and monitor jobs, stream live output, cancel processes, manage files, launch tools through generated GUIs, and automate recurring tasks, all without typing shell commands.

## Project status

| Phase | State |
|---|---|
| **Phase 0 — Feasibility & Architecture Discovery** | ✅ **Complete & approved** (decisions in Appendix 3) |
| **Phase 1 — Protocol freeze & project constitution** | ✅ **Complete** (TRMX-P/1, ADR-001…003) |
| **Phase 2 — Termux bridge PoC** | ✅ **Code complete — 19/19 tests + smoke demo pass (sandbox); on-device verification in Termux pending** |
| **Phase 3 — Bootstrap automation** | ✅ **Complete — installer + autostart + frozen intent contract; 32/32 tests (sandbox)** |
| **Phase 4 — Android app shell** | ✅ **Complete & on-device verified (Android 16) — 3 fix rounds: cleartext loopback, INTERNET permission, pairing flow** |
| **Phase 5 — Job submission & management** | ✅ **Complete & ON-DEVICE VERIFIED (Android 16, 2026-09-08): wizard → pair → jobs J-1…J-3 exit 0** |
| **Phase 6 — Live output streaming** | ✅ **Complete — SSE console with replay+follow, events-driven dashboard; CI green; on-device round pending** |
| Phases 7–12 | 🚫 not started (Phase 7 = file manager) |

**Phase 0 decisions:** me + friends distribution via GitHub releases · Termux from GitHub Releases · target Android 14+ (minSdk 26) · hybrid GUI (Tool Registry + universal runner) · device-local-only V1 transport · core-first V1 scope. See [Appendix 3 of the report](docs/PHASE-0-DISCOVERY-REPORT.md#appendix-3--phase-0-sign-off-inputs-recorded-2026-09-07).

## Documentation

- [docs/PHASE-0-DISCOVERY-REPORT.md](docs/PHASE-0-DISCOVERY-REPORT.md) — feasibility verdict, *maximum practical control boundary*, communication comparison, full system architecture, threat model, feature map, 13-phase roadmap, testing strategy, risks
- [docs/PROTOCOL.md](docs/PROTOCOL.md) — **TRMX-P/1**: the wire contract between app and bridge (every route, error code, event, limit, versioning rule)
- [docs/CONTROL-PLANE.md](docs/CONTROL-PLANE.md) — **the frozen `RUN_COMMAND` intent interface**: 8 operations, consent preconditions, first-run/warm-start/upgrade sequences, error-state mapping
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — living architecture summary
- [docs/decisions/](docs/decisions/) — ADR decision log (ADR-001 architecture, ADR-002 protocol core, ADR-003 license, ADR-004 bridge PoC, ADR-005 bootstrap automation, ADR-006 Android shell)
- [fixtures/v1/](fixtures/v1/) — normative contract fixtures shared by both test suites
- [CHANGELOG.md](CHANGELOG.md) · [LICENSE](LICENSE) (MIT)

## Architecture at a glance

- **Control plane** — Termux's opt-in `RUN_COMMAND` intent API (bootstrap, install, pairing, start/stop), protected by the `com.termux.permission.RUN_COMMAND` permission and the user's `allow-external-apps` opt-in.
- **Data plane** — a single-file, stdlib-only Python daemon (`trmx-bridge`) inside Termux serving a token-authenticated JSON API with chunked/SSE streaming on `127.0.0.1` (jobs, live logs, files, tool registry, metrics), per the TRMX-P/1 protocol.
- **Philosophy** — hybrid GUI: schema-driven forms for known tools + a structured universal runner + (V2) a PTY terminal.
- **Resilience** — the bridge is the source of truth (SQLite + log ring buffers), so jobs survive the Android app being killed; the app is a disposable, reconnecting client.

## Repository layout

```
docs/        discovery report, protocol spec, control-plane spec, living architecture, ADRs
fixtures/    normative TRMX-P/1 contract fixtures (shared by both test suites)
android/     native Android app (Kotlin + Jetpack Compose)      — Phase 4+
termux/      trmx-bridge daemon, trmx CLI, installer, tests     — Phases 2–3 ✅
```

## Try it (in Termux or any Linux box)

```sh
pkg install python curl          # in Termux; elsewhere just need python3 + curl
git clone https://github.com/sq987in-wq/TRMX-GUI && cd TRMX-GUI

# Phase 3: install into a throwaway home and run the bridge (TRMX_HOME = the .trmx dir)
D=/tmp/trmx-demo; mkdir -p $D
env HOME=$D TRMX_HOME=$D/.trmx sh termux/install.sh --source termux
env HOME=$D TRMX_HOME=$D/.trmx $D/.trmx/trmx start
env HOME=$D TRMX_HOME=$D/.trmx $D/.trmx/trmx status    # → data-plane: UP
env HOME=$D TRMX_HOME=$D/.trmx $D/.trmx/trmx stop

sh termux/tests/smoke.sh         # the full Phase 2 demo (pair → … → re-adopt → stop)
sh termux/tests/run_tests.sh     # both suites: 19 bridge + 12 bootstrap tests
```

## Contributing / building

Nothing to build yet — implementation begins with the Phase 2 bridge PoC, gated on Phase 1 review. The project follows a strict *architecture → smallest working proof → verified communication → controlled execution → reliable jobs → polish → security → testing → production* order, documented in the Phase 0 report §H.
