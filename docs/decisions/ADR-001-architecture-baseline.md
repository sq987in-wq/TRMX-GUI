# ADR-001 — Architecture baseline (Phase 0 sign-off)

- **Status:** Accepted (2026-09-07, stakeholder-approved)
- **Supersedes:** none · **Superseded by:** —
- **Input record:** Phase 0 report, Appendix 3 (stakeholder decisions 1–6)

## Context

We need a native Android app that controls a Termux environment deeply and safely, on stock unrooted Android, without replacing Termux. Constraints discovered in Phase 0: apps cannot touch each other's private storage or processes; loopback TCP is the only always-available cross-app channel; Termux's `RUN_COMMAND` intent (permission + `allow-external-apps` opt-in) is the only way to cause execution from outside; Android 12+ kills background child processes (phantom killer); the Play Store Termux build is dead; all Termux APKs must share one install source.

## Decision

1. **Hybrid communication** — `RUN_COMMAND` intents = control plane (bootstrap, install, pair, start/stop); localhost HTTP with chunked/SSE streaming = data plane (TRMX-P/1). WebSocket deferred (needs a non-stdlib server dep for marginal gain); Unix sockets rejected (SELinux blocks cross-app); shared-files rejected as a channel (kept only as installer fallback); SSH rejected as primary (wrong shape for job control).
2. **Termux backend = one single-file Python 3 daemon, stdlib only** (`trmx-bridge` + tiny `trmx` CLI + pinned installer). Sole Termux dependency: `pkg install python`.
3. **Bridge is the source of truth** — SQLite (WAL) + log ring files hold job state; the Android app is a disposable reconnecting client.
4. **Android stack** — Kotlin, Jetpack Compose + Material 3, single-activity MVVM (StateFlow), OkHttp + kotlinx.serialization, Room, DataStore, Keystore-wrapped token, manual DI, minSdk 26 / target latest.
5. **GUI = Philosophy C (hybrid)** — JSON-schema Tool Registry generates forms; universal argv runner; PTY terminal in V2.
6. **Security baseline** — loopback-only bind, 256-bit bearer token, argv-first execution, bridge-enforced path policy, risk tiers, audit log, stdlib-only supply chain.
7. **Stakeholder decisions:** distribution = me + friends via GitHub releases; Termux = GitHub Releases builds; target Android 14+ (minSdk 26); device-local-only V1 networking; core-first V1 scope (connection + jobs + logs before modules).

## Consequences

- Jobs survive controller death; controller bugs can't lose job history.
- One-time manual consents (Termux install, `allow-external-apps`, `RUN_COMMAND` grant) are irreducible — they are the security boundary.
- Background survival on Android 12–13 needs a user-side OS fix (ADB); 14+ has a settings toggle — the app detects and instructs instead of promising immortality.
- The protocol (TRMX-P/1) becomes a versioned contract with shared fixtures; drift is a CI failure.
