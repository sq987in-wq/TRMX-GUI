# TRMX-GUI — Living Architecture Summary

> **Status:** Phase 1. This file is the short, always-current overview. Full detail & reasoning live in
> [PHASE-0-DISCOVERY-REPORT.md](PHASE-0-DISCOVERY-REPORT.md) (why) and [PROTOCOL.md](PROTOCOL.md) (the wire contract).

## One-paragraph summary

A native Android app (Kotlin + Compose) is a **disposable control plane**; a single-file **Python-stdlib daemon inside Termux (`trmx-bridge`)** is the **execution plane and source of truth**. The app talks to the bridge over token-authenticated localhost HTTP (`TRMX-P/1`, see PROTOCOL.md) for jobs, live output streams, files and tools, and uses Termux's opt-in `RUN_COMMAND` intent API as a **control plane** to bootstrap/install/pair/start the backend when it is down (frozen intent contract: [CONTROL-PLANE.md](CONTROL-PLANE.md); installer: `termux/install.sh`, see ADR-005). Termux executes everything; the app never reimplements a capability it can drive. The system is a **universal runtime**: every Termux binary — git, python, tar, servers, AI CLIs, media tools — becomes a first-class, form-driven citizen through the generic schema registry (PROTOCOL §7); nothing in the engine is hardcoded to a tool category.

## Components

| Component | Lives in | Responsibility |
|---|---|---|
| Compose UI (Dashboard, Jobs, Logs, Files, Tools, Settings, Pairing) | Android app | rendering, forms from tool schemas, confirmations |
| ViewModels / repositories (MVVM, StateFlow) | Android app | state orchestration; bridge truth mirrored into Room |
| `BridgeClient` + `IntentControlPlane` | Android app | data plane (HTTP/SSE) & control plane (RUN_COMMAND intents) |
| `ConnectionMonitor` | Android app | connection state machine, reconnect + auto-start intents |
| `trmx-bridge.py` | Termux (`~/.trmx/`) | HTTP API, job manager/supervisor, scheduler, file API, tool registry, auth, audit |
| `trmx` CLI | Termux (`~/.trmx/`) | `pair`/`unpair`/`start`/`stop`/`status`/`token` — runnable when bridge is down |
| `jobs.db` (SQLite, WAL) + log ring files | Termux (`~/.trmx/`) | **authoritative** job state & buffered output (survives app death) |
| runit service (optional) + `~/.termux/boot` (optional) | Termux | bridge auto-restart & boot autostart |

## Key flows (detail in Phase 0 report §B)

- **Bootstrap:** intents install `python` → installer (SHA256-pinned) → `trmx pair <token>` → `trmx start` → HTTP on `127.0.0.1:27342`.
- **Job:** `POST /v1/jobs` → spawn (setsid, own process group) → ring-buffered output → SSE stream with per-job monotonic `seq` → terminal state in SQLite.
- **Reconnect:** app backoff-retries, re-opens streams from last `seq`, resyncs job list (bridge wins).
- **Cancel:** SIGTERM to process group → grace (5 s default) → SIGKILL; idempotent.
- **Reboot:** Termux:Boot script and/or app `BOOT_COMPLETED` → start-intent; failures reported honestly (`LOST` jobs).

## State machines

- **Job:** `QUEUED → STARTING → RUNNING → COMPLETED | FAILED`, with `RUNNING/CANCELLING → CANCELLED (reason: user|timeout)` and `LOST` for post-restart reconciliation (PROTOCOL §3.1).
- **Connection (app-side):** `CONNECTED · CONNECTING · DISCONNECTED · AUTHENTICATION_FAILED · TERMUX_NOT_RUNNING · BRIDGE_NOT_RUNNING · PERMISSION_REQUIRED · PROTOCOL_MISMATCH · NETWORK_ERROR · UNKNOWN_ERROR` — independent of **controller availability** (app alive/frozen/killed); the two are never conflated in the UI.

## Storage

| Data | Where | Tech |
|---|---|---|
| Job history (truth), events, audit | Termux | SQLite (stdlib, WAL) |
| Log content | Termux | append-only ring files (2 MiB/stream default) |
| Token | Termux `bridge.json` (0600) / Android Keystore-wrapped file | secrets.token_urlsafe / AES-GCM under Keystore key |
| Settings | Android | DataStore |
| Job history cache, tool catalog cache | Android | Room |

## Security core (full threat model: Phase 0 report §F)

Loopback-only bind · bearer token (constant-time, backoff on failures) · **argv-first execution (no shell string path)** · bridge-enforced path policy · risk tiers (`safe|confirm|destructive`) · no CORS + `Origin` rejection · SHA256-pinned installer · stdlib-only Python (no pip, no supply chain) · audit log.

## Roadmap status

Phase 0 ✅ approved · **Phase 1 (this) — protocol frozen as TRMX-P/1 draft** · Phase 2 (bridge PoC) next · full plan: Phase 0 report §H.
