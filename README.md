# TRMX-GUI

**A native Android graphical remote-control dashboard for [Termux](https://termux.dev).**

> The Android app is a beautiful control plane. **Termux is the execution engine.**
> `Android UI → control/communication layer → Termux → shell · Python · CLI tools · media · automation · AI agents`

TRMX-GUI does not replace Termux — it drives it: run and monitor jobs, stream live output, cancel processes, manage files, launch tools through generated GUIs, and automate recurring tasks, all without typing shell commands.

## Project status

| Phase | State |
|---|---|
| **Phase 0 — Feasibility & Architecture Discovery** | ✅ **Complete — decisions recorded (Appendix 3); awaiting final architecture approval** |
| Phase 1 — Protocol freeze | ⏳ blocked on Phase 0 sign-off |
| Phases 2–12 — Implementation | 🚫 not started (by design: no implementation code before architecture approval) |

**Phase 0 decisions:** me + friends distribution via GitHub releases · Termux from GitHub Releases · target Android 14+ (minSdk 26) · hybrid GUI (Tool Registry + universal runner) · device-local-only V1 transport · core-first V1 scope. See [Appendix 3 of the report](docs/PHASE-0-DISCOVERY-REPORT.md#appendix-3--phase-0-sign-off-inputs-recorded-2026-09-07).

## Read this first

- **[docs/PHASE-0-DISCOVERY-REPORT.md](docs/PHASE-0-DISCOVERY-REPORT.md)** — the complete Phase 0 discovery report: feasibility verdict, the *maximum practical control boundary*, communication-architecture comparison, full system design (Termux side + Android side), security/threat model, feature map, phased roadmap, testing strategy, risks, and open questions.

## Architecture at a glance

- **Control plane** — Termux's opt-in `RUN_COMMAND` intent API (bootstrap, install, pairing, start/stop), protected by the `com.termux.permission.RUN_COMMAND` permission and the user's `allow-external-apps` opt-in.
- **Data plane** — a single-file, stdlib-only Python daemon (`trmx-bridge`) inside Termux serving a token-authenticated JSON API with chunked/SSE streaming on `127.0.0.1` (jobs, live logs, files, tool registry, metrics).
- **Philosophy** — hybrid GUI: schema-driven forms for known tools + a structured universal runner + (V2) a PTY terminal.
- **Resilience** — the bridge is the source of truth (SQLite + log ring buffers), so jobs survive the Android app being killed; the app is a disposable, reconnecting client.

## Repository layout (planned)

```
docs/        discovery report, protocol spec, ADRs (decision log)
android/     native Android app (Kotlin + Jetpack Compose)      — Phase 4+
termux/      trmx-bridge daemon, trmx CLI, install.sh, schemas  — Phase 2+
fixtures/    shared contract-test fixtures                       — Phase 1+
```

## Contributing / building

Nothing to build yet — implementation begins after the Phase 0 architecture is approved. The project follows a strict *architecture → smallest working proof → verified communication → controlled execution → reliable jobs → polish → security → testing → production* order.
