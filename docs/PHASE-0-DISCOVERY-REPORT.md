# TRMX-GUI — Phase 0: Feasibility & Architecture Discovery Report

| | |
|---|---|
| **Project** | TRMX-GUI — a native Android graphical remote-control dashboard for Termux |
| **Phase** | Phase 0 — Discovery (no implementation code written) |
| **Date** | 2026-09-07 |
| **Status** | **DRAFT — awaiting answers to §L and explicit architecture approval** |
| **Golden rule for this document** | Nothing in here is promised unless Android and Termux can actually deliver it. Every "yes" below is qualified with its conditions. |

> **Core concept under evaluation:** The Android app is a beautiful graphical control plane. Termux is the execution engine.
> `Android UI → control/communication layer → Termux → shell / Python / CLI / media / automation / AI tools`
> The app must **never** try to replace Termux — only to control it as far as the platform honestly allows.

---

## Table of Contents

- [0. Executive Summary](#0-executive-summary)
- [A. Feasibility Verdict](#a-feasibility-verdict)
- [B. Recommended System Architecture](#b-recommended-system-architecture)
- [C. Communication Recommendation](#c-communication-recommendation)
- [D. Termux-Side Architecture](#d-termux-side-architecture)
- [E. Android-Side Architecture](#e-android-side-architecture)
- [F. Security Architecture](#f-security-architecture)
- [G. Feature Map & GUI Philosophy](#g-feature-map--gui-philosophy)
- [H. Development Roadmap](#h-development-roadmap)
- [I. Required Tools & Environment](#i-required-tools--environment)
- [J. Testing Strategy](#j-testing-strategy)
- [K. Risks & Limitations](#k-risks--limitations)
- [L. Questions For You](#l-questions-for-you)
- [Appendix 1 — References](#appendix-1--references)
- [Appendix 2 — Glossary](#appendix-2--glossary)

---

# 0. Executive Summary

**Verdict: YES, this project is realistically buildable — with a precisely defined control boundary.** A native Android app can control a Termux environment deeply enough to be genuinely useful (run/monitor/cancel jobs, stream live output, manage files, launch tools, orchestrate automation), because the two apps can talk over the device's shared loopback network and via Termux's opt-in command-execution intent. What the app can *never* do is guarantee that Termux survives Android's background-process policies, act outside Termux's UID/sandbox, or silently acquire these powers without a small, irreducible set of one-time user consents.

**The five headline recommendations of this report:**

1. **Communication: Hybrid.** Termux's `RUN_COMMAND` intent as the *bootstrap/control plane* (start, install, pair, stop the backend) + a small **HTTP JSON API with chunked/SSE streaming on `127.0.0.1`** as the *data plane* (jobs, logs, files), authenticated by a device-generated secret token. No WebSocket dependency in V1, no LAN exposure, no root.
2. **Termux backend: one single-file Python 3 daemon (`trmx-bridge`), standard library only.** The only Termux package the system needs is `python` itself. Job state lives in SQLite (stdlib `sqlite3`), which makes the **bridge — not the app — the source of truth**, so jobs survive the Android app being killed, restarted, or reinstalled.
3. **GUI philosophy: Hybrid (C).** Curated graphical modules for known tools (driven by a JSON **Tool Registry**, not hardcoded forms) + a controlled universal runner for arbitrary commands + (later) a PTY-backed terminal view.
4. **Security model: structured argv-first commands, token-authenticated local-only transport, risk-tiered confirmations.** The token defends the bridge port against *other apps on the device* — the realistic attacker — not against LAN, which V1 never exposes.
5. **First milestone is not a dashboard.** It is: *"App pairs with Termux → runs one controlled job → streams its output live → cancels it → survives an app restart."* Everything else is built on top of that proven pipe.

**The three hardest real-world problems (all manageable, none solvable by us):**

- **Android 12+ "phantom process killer"** can kill Termux's background child processes (jobs) — mitigated by user action (a Developer-Options toggle on Android 14+, an ADB command on 12–13), wakelocks, and a documented concurrency cap.
- **OEM "app killers"** (Xiaomi, Oppo, Huawei, Samsung, …) aggressively terminate background apps — mitigated by user guidance, battery-optimization exemptions, and the fact that our controller app is *disposable* (the bridge holds state).
- **One-time setup friction** is irreducible: the user must install Termux, opt in to external command execution, and grant our app the `RUN_COMMAND` permission. These steps **are** the security boundary; automating them away is neither possible nor desirable.

---

# A. Feasibility Verdict

## A.1 The 19 feasibility questions, answered honestly

**1. What does Android allow?**
A third-party app may: open TCP connections to `127.0.0.1` (all apps on a device share the loopback interface, so the app can reach a server bound inside Termux); send *explicit* intents to exported, permission-protected components of other apps (this is exactly what Termux's `RunCommandService` offers); run its own foreground services and receive `BOOT_COMPLETED`; use SAF/MediaStore for shared storage; use Android Keystore, biometrics, and notifications. A third-party app may **not**: read or write another app's `/data/data`, list or signal another UID's processes, ptrace them, or escape its own SELinux sandbox — no exceptions without root.

**2. What does Android restrict?**
Background execution: since Android 8, background apps can't start services and are candidates for killing; since Android 11, cached apps can be **frozen** entirely; Doze defers CPU/network for non-exempt apps; since Android 12, the **phantom process killer** enforces a system-wide budget of 32 background child processes and kills CPU-heavy ones; scoped storage restricts shared-storage access to SAF/MediaStore; cleartext HTTP is blocked by default for apps targeting API 28+; Android 13+ requires runtime notification permission. Every one of these has a design answer in this document, but none can be simply wished away.

**3. What does Termux allow?**
A full Linux userland running as one Android UID: shell, Python, Node, ffmpeg, yt-dlp, aria2, git, servers (including its own `sshd`), daemons, `/proc` introspection of its own processes, shared-storage symlinks (`termux-setup-storage`), CPU wakelocks, and — crucially for us — an **opt-in intent API (`com.termux.RUN_COMMAND`) that lets an external app execute a command inside Termux**, plus optional add-on apps (Termux:API, Termux:Boot, …).

**4. What does Termux not allow?**
External apps cannot write to Termux's private storage, cannot run commands without the user's explicit opt-in (`allow-external-apps=true` in `~/.termux/termux.properties` **and** granting the caller the custom dangerous permission `com.termux.permission.RUN_COMMAND`), cannot survive the Termux app process being killed, cannot auto-start on reboot by itself, and cannot do anything requiring root or touching other apps' data.

**5. What requires Termux add-ons?**
Device-hardware actions (battery status, clipboard, notifications, TTS, sensors, SMS) require **Termux:API** (app + `termux-api` package). Auto-start after reboot requires **Termux:Boot** (or our own app's `BOOT_COMPLETED` receiver firing a `RUN_COMMAND` intent). Home-screen shortcuts (**Termux:Widget**), floating terminal (**Termux:Float**), Tasker integration (**Termux:Tasker**) are optional conveniences we do not need for V1. **Important:** all Termux apps share `android:sharedUserId="com.termux"` and must all be installed from the *same* source (GitHub Releases or F-Droid) or they refuse to work together.

**6. What requires special Android permissions?**
Our app: `com.termux.permission.RUN_COMMAND` (custom dangerous permission, granted by the user in App Info → Additional permissions), `INTERNET`, `POST_NOTIFICATIONS` (Android 13+), `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`. The *Termux* app may need "Display over other apps" (so `RUN_COMMAND` sessions can auto-start on Android 10+ — only matters for foreground-session mode, not our background bridge commands) and battery-optimization exemption. Only "All files access" would be special, and we explicitly avoid needing it in V1.

**7. What cannot be done reliably in the background?**
Guaranteeing that a Termux job runs to completion while the screen is off on an unconfigured device. Also: our controller app streaming logs continuously while backgrounded *without* a foreground service (it would be frozen or killed); precise process-level resource accounting for processes the bridge didn't start; surviving a user's force-stop of Termux.

**8. What do battery/background restrictions affect?**
Doze can suspend CPU/network for both apps (wakelock + battery exemption mitigate); the phantom killer can SIGKILL Termux jobs on Android 12+ (user-side mitigation required — Developer Options toggle on 14+, ADB on 12–13); OEM killers can terminate Termux outright; Android may defer our app's boot receiver. Net effect: the system must be designed so that **job loss is detected and reported**, not silently ignored (see Job `LOST` state, §B.6).

**9. What happens when Termux is killed?**
All of Termux's child processes — including our bridge and any running jobs — are killed (typically SIGKILL, no cleanup). This is the single most important failure mode. Mitigations: wakelock + battery exemption to *prevent* it; the bridge's job journal to *detect* it after restart (jobs are reconciled as `LOST` or re-attached where possible); optional runit supervision to auto-restart the bridge; user guidance (dontkillmyapp.com) for OEM-specific settings.

**10. What happens when the controller app is killed?**
Nothing, from Termux's perspective — and that is by design. Jobs keep running; the bridge buffers output to disk; on next launch the app reconnects and replays. The app holds no irreplaceable state.

**11. Can communication survive app restarts?**
Yes. The bridge keeps the authoritative job database and log buffers; the app is a reconnecting client with a stored token. App reinstall is also survivable (re-pairing required, jobs preserved).

**12. Can it survive a device reboot?**
Not automatically, but with one-time user setup: a `~/.termux/boot` script (Termux:Boot) starts the bridge at boot, and/or our app's `BOOT_COMPLETED` receiver sends a `RUN_COMMAND` intent to start it. Caveats: force-stopped apps receive nothing until manually launched, and some OEMs delay boot receivers. Honest label: **best-effort, not guaranteed**.

**13. Can the system automatically reconnect?**
Yes on the app side (health-check polling with exponential backoff; the app also re-sends a `RUN_COMMAND` start-intent if the bridge doesn't answer, which transparently restarts it). Yes on the bridge side if installed as a runit service (`termux-services`), which restarts it on crash. No, across a Termux app death — until Termux itself is started again (our app's start-intent does exactly that).

**14. Can it support long-running jobs?**
Yes — downloads, encodes, AI-agent sessions, multi-hour scripts. Termux holds a foreground-service notification and a wakelock while sessions/daemons run; the bridge supervises children in their own process groups. The hard ceiling is the Android 12+ phantom killer under heavy background load; we mitigate with a documented concurrent-job cap and a one-time OS-level fix the app detects and instructs the user about.

**15. Can it monitor processes started by Termux?**
Yes for processes the **bridge** starts: it is their parent, reads their live `/proc/<pid>/stat`, `status`, and can report PID, state, CPU%, memory. No for processes owned by other UIDs (Android hides them) and only partially for pre-existing Termux processes the bridge didn't spawn (visible in `ps` output by name, but not controllable).

**16. Can it stream command output in real time?**
Yes. The bridge streams stdout/stderr as chunked HTTP (SSE-framed) events with millisecond-scale latency on loopback, with sequence numbers and replay-from-offset so a reconnecting app never loses a byte. This comfortably covers ffmpeg progress, download logs, and AI-agent token streams.

**17. Can it cancel running processes?**
Yes for bridge children: the bridge signals the job's whole process group (SIGTERM → grace period → SIGKILL), which correctly kills pipelines like `yt-dlp | ffmpeg`. Not possible: processes Termux started outside the bridge, and nothing is instant against uninterruptible (D-state) processes.

**18. Can it expose arbitrary CLI tools through a GUI?**
Yes, three ways, and we recommend all three in a layered design: (1) a **Tool Registry** of JSON schemas from which the app auto-generates forms for known tools; (2) a **universal runner** taking a structured argv array (no shell interpolation) for anything else; (3) later, a **PTY-backed terminal view** streamed over the same API for true interactivity.

**19. What can "100% control" realistically mean?**
Not "control Android." It means: **100% of what the Termux user account itself can do, on demand, with live feedback — bounded by Android's background-mortality rules and by one-time user consents.** Any marketing claim beyond that would be a lie. The precise boundary is the next section.

## A.2 The Maximum Practical Control Boundary

| ✅ **Solidly inside the boundary** (reliable, V1) | ⚠️ **Best-effort** (works with setup / can fail) | ❌ **Outside the boundary** (refuse to promise) |
|---|---|---|
| Run any executable/script in Termux as a structured job | Jobs surviving Termux being killed | Jobs surviving with *guaranteed* zero loss on Android 12+ without OS-level user fix |
| Live stdout/stderr streaming + replay | Bridge auto-start after reboot (Termux:Boot / BOOT_COMPLETED) | Controlling processes owned by other apps/UIDs |
| Cancel jobs (process-group kill), timeouts, queues | Phantom-killer immunity (needs user's Developer-Options/ADB fix on 12–13; toggle exists on 14+) | Root-level operations (system files, other apps' data) |
| Job history, exit codes, logs, scheduling (in-bridge) | Perfect background uptime on OEM ROMs (Xiaomi, etc.) | Running Termux without the Termux app ever being opened by the user |
| File browser/transfer within Termux home + shared storage | Shared-storage write outside `~/storage` sandbox paths | Guaranteeing delivery of a "job finished" push while our app is frozen (poll/notification-based instead) |
| Device info via Termux:API (battery, sensors, clipboard…) | PTY re-attach to a job's terminal after app restart (V2) | LAN/WAN control in V1 (deliberately; V3 with TLS) |
| Tool GUIs from a schema registry; universal argv runner | Termux package installation *fully* unattended (needs the one-time opt-ins first) | Modifying Termux's own `termux.properties` from outside without consent |
| Detecting and reporting Termux/bridge death, auto-restart attempts | Exact per-process network/disk IO stats (kernel-restricted for some counters) | Hiding that setup requires user consent — we will show it honestly in-app |

---

# B. Recommended System Architecture

## B.1 Design principles

1. **Termux is the engine; the app is the steering wheel.** No capability is reimplemented in the app.
2. **The bridge is the source of truth.** Job state, logs, history, scheduling live in Termux. The Android app is a stateless-ish, disposable client. This single decision is what makes the system resilient to the controller being killed, updated, or reinstalled.
3. **Two independent lifecycles.** Controller availability and backend/job availability are separate facts with separate UI indicators (§E.5). Never conflate them.
4. **Structured, versioned protocol.** JSON messages with a protocol version; argv arrays, not shell strings, whenever possible.
5. **Local-only by default.** The data plane binds to `127.0.0.1` only. Remote access is a V3 feature with TLS, not an accident.
6. **No root, ever.** Everything must work on a stock, unrooted device.
7. **Fail loud, fail honest.** Every failure mode in §J must produce a specific, actionable UI state — never a silent spinner.

## B.2 Component diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ANDROID CONTROLLER APP — com.trmx.gui  (control plane, disposable)      │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │ UI: Jetpack Compose (Dashboard · Jobs · Logs · Files · Tools ·     │  │
│  │     Terminal(V2) · Settings · Pairing wizard)                      │  │
│  └───────────────▲────────────────────────────────────────────────────┘  │
│                  │ StateFlow / events (ViewModels, MVVM)                 │
│  ┌───────────────┴────────────────────────────────────────────────────┐  │
│  │ Domain: JobRepository · ToolRegistry · HistoryRepo · Connection-   │  │
│  │         Monitor · PairingController · NotificationManager          │  │
│  └───────▲──────────────────────────────────▲─────────────────────────┘  │
│          │                                  │                             │
│  ┌───────┴────────────┐          ┌──────────┴─────────────────────────┐  │
│  │ BridgeClient       │          │ IntentControlPlane                │  │
│  │ HTTP+chunked/SSE   │          │ com.termux.RUN_COMMAND intents    │  │
│  │ JSON · Bearer token│          │ (bootstrap, start/stop, install,  │  │
│  └───────┬────────────┘          │  pair — fire-and-forget + result  │  │
│          │                       │  callbacks via PendingIntent)     │  │
│          │                       └──────────┬─────────────────────────┘  │
└──────────┼──────────────────────────────────┼─────────────────────────────┘
           │  DATA PLANE                       │  CONTROL PLANE
           │  http://127.0.0.1:27342/v1/...    │  explicit Intent →
           │  (loopback only, token-gated)     │  com.termux RunCommandService
           ▼                                   ▼  (permission-gated)
┌──────────────────────────────────────────────────────────────────────────┐
│  TERMUX — com.termux  (execution plane, owns the truth)                  │
│                                                                          │
│  trmx-bridge  (single-file Python 3 daemon, stdlib only)                 │
│  ├─ HTTP API  /v1/jobs /v1/jobs/{id}/output (chunked stream)             │
│  │            /v1/files /v1/tools /v1/system /v1/events                  │
│  ├─ AuthN: bearer token from ~/.trmx/bridge.json (0600)                  │
│  ├─ JobManager: spawn with setsid → own process group; killpg to cancel; │
│  │             /proc polling → state, PID, CPU/RSS; timeout enforcement │
│  ├─ JobStore: SQLite (~/.trmx/jobs.db) + per-job log ring files         │
│  ├─ ToolRegistry: JSON tool schemas (bundled + user-extendable)          │
│  └─ trmx CLI helper: pair / start / stop / status / token (used by       │
│     RUN_COMMAND intents; writes config without the bridge running)       │
│        │                                                                 │
│        ▼  children (each its own process group)                          │
│  bash · python · node · yt-dlp · ffmpeg · aria2 · git · AI CLIs ·        │
│  custom scripts · cron-like scheduled jobs (in-bridge scheduler)         │
│        │                                                                 │
│  Termux runtime: $PREFIX · $HOME (~) · ~/storage/* (shared storage) ·    │
│  wakelock · foreground-service notification · optional runit service ·   │
│  optional Termux:API (device features) · optional Termux:Boot script     │
└──────────────────────────────────────────────────────────────────────────┘
```

## B.3 The two communication planes (why two)

- **Control plane (intents)** — used only when the data plane is *down*: install/upgrade the bridge, start it, pair a token, stop it, health-probe it. `RUN_COMMAND` is fire-and-forget with an optional one-shot result callback, so it is perfect for "make the backend exist" but terrible for streaming state.
- **Data plane (loopback HTTP)** — used for everything once the bridge is up: job CRUD, live streams, file ops, tool registry, system metrics. Persistent, streaming-capable, debuggable with `curl`.

The app's ConnectionMonitor owns the dance: *try data plane → if dead, send start-intent via control plane → poll data plane → degrade to a specific error state with fix instructions if Termux itself is missing/blocked.*

## B.4 Data flow — a job's life, end to end

```
[App UI]  user fills a yt-dlp form (from Tool Registry schema)
   │ 1. POST /v1/jobs  { name:"download X", tool:"yt-dlp",
   │                     args:["-f","mp4","<url>"], cwd:"~/downloads" }
   ▼
[Bridge] validates: token? tool allowlisted? args schema-valid? queue slot free?
   │ 2. persists job row (QUEUED, id=J-1042) → returns {job_id}
   ▼
[Bridge] JobManager: spawn argv via subprocess in new session+group, cwd, env
   │ 3. state → STARTING → RUNNING (pid, pgid recorded)
   │ 4. stdout/stderr chunks appended to ~/.trmx/logs/J-1042/*.log (ring buffer)
   ▼                    ┌──────────────────────────────────────┐
[App] 5. GET /v1/jobs/J-1042/output?follow=1  ← chunked/SSE stream │
   │   live lines render in the Logs viewer; progress parsed     │
   │                                                              │
   │   (app killed here? job continues; stream is re-opened      │
   │    later with ?from_seq=N — zero data loss)                 │
   └──────────────────────────────────────────────────────────────┘
[Bridge] process exits → waitpid → exit code, duration, final state
   │ 6. → COMPLETED/FAILED; row updated; optional termux-notification
   ▼
[App] Job card flips to finished; history queryable forever (SQLite)
```

## B.5 Bootstrap flow (cold start / first run)

```
First run (one-time, user-assisted — this IS the consent boundary):
  1. User installs Termux (GitHub Releases or F-Droid — same source for all
     Termux apps) and opens it once (bootstraps the environment).
  2. App shows a copy-paste one-liner for Termux:
       mkdir -p ~/.termux && echo allow-external-apps=true >> \
         ~/.termux/termux.properties && termux-reload-settings
  3. User grants com.termux.permission.RUN_COMMAND to TRMX-GUI
     (deep-link to App Info → Additional permissions).
  From here on, AUTOMATED by the app via RUN_COMMAND intents:
  4. App → intent: pkg install -y python            (bridge's only dependency)
  5. App → intent: curl -fsSL <release-url>/install.sh | bash
     (installs ~/.trmx/trmx-bridge.py + trmx helper; SHA256-pinned)
  6. App → intent: trmx pair <app-generated-token>   (stored 0600, printed never)
  7. App → intent: trmx start                        (bridge binds 127.0.0.1:27342)
  8. App ↔ bridge: token-authenticated HTTP. Done. Dashboard appears.

Warm start (any later launch):
  App → GET /v1/system/info on 127.0.0.1:27342
    ├─ 200 OK            → CONNECTED (proceed)
    ├─ connection refused → intent: trmx start → poll → CONNECTED
    ├─ 401               → AUTHENTICATION_FAILED → re-pair wizard
    └─ Termux missing    → TERMUX_NOT_RUNNING → guided fix screen
```

## B.6 Job lifecycle state machine

```
                 ┌──────────┐
    submit       │  QUEUED  │   (queue full? rejected with reason at submit)
      └────────► └────┬─────┘
                      │ dequeued, about to spawn
                ┌─────▼─────┐
                │ STARTING  │   spawn error → FAILED(errno)
                └─────┬─────┘
                      │ exec'd, pid known
                ┌─────▼─────┐   exit==0
                │  RUNNING  ├─────────────────► COMPLETED
                └─┬───┬───┬─┘   exit!=0
   timeout hit ───┘   │   └─── crash/exit  ───► FAILED(exit_code|signal)
                      │ user cancel
                ┌─────▼─────┐
                │CANCELLING │   SIGTERM → grace (default 5s) → SIGKILL
                └─────┬─────┘
                      └──────────────────────► CANCELLED

  Special reconciliation state:
                ┌───────────┐
                │   LOST    │  bridge restarted and found job's pgid gone
                └───────────┘  without an exit record (Termux/jobs were killed)
```

Every job record carries: `job_id, name, type(argv|shell|tool), tool, argv[], script, cwd, env{}, status, created_at, started_at, ended_at, pid, pgid, exit_code, signal, timeout_s, cancel_requested, error, progress_hint, stdout_bytes, stderr_bytes, log_seq`. `log_seq` is the monotonic stream sequence number that makes reconnect-replay lossless.

## B.7 Error flow

Errors are classified at the earliest layer that can understand them, and always surface as a typed state — never a raw stack trace in the UI:

| Layer | Example | Becomes |
|---|---|---|
| Transport | `ConnectException` / timeout | `BRIDGE_NOT_RUNNING` → auto start-intent → `TERMUX_NOT_RUNNING` if intent fails → guided fix |
| Auth | HTTP 401 / 403 | `AUTHENTICATION_FAILED` → re-pair wizard (never auto-delete the token) |
| Protocol | Unknown JSON, bad version | `PROTOCOL_MISMATCH` → offer bridge upgrade via control plane |
| Validation | Unknown tool, bad args, bad path | typed 4xx with field-level message → form error in UI |
| Execution | ENOENT (command not found), EACCES | job FAILED with errno + "install this package?" hint |
| Supervision | Job killed externally (signal 9) | job `FAILED(signal=9)` + phantom-killer advisory if Android 12+ |
| Backend | bridge crash mid-job | runit restart → journal reconcile → jobs marked `LOST` + notification |

## B.8 Authentication / pairing flow

```
App                                  Termux side
───                                  ───────────
generate 256-bit token
(csrand, stored in Android Keystore-
wrapped storage)
   │
   ├── path A (default, automated):
   │     RUN_COMMAND intent: trmx pair <token>
   │                                   CLI writes token to ~/.trmx/bridge.json
   │                                   (chmod 600; refuses overwrite unless
   │                                    `trmx unpair` was run in Termux)
   │
   └── path B (fallback, manual):
         user runs `trmx token` in Termux,
         copies/reads the code, types it into the pairing wizard
   │
   ▼
first HTTP call sends  Authorization: Bearer <token>
                                       bridge constant-time-compares;
                                       on failure: 401 + rate-limit + audit log
```

Pairing is one-time; the token survives app and bridge restarts. `trmx unpair` + re-pair rotates it. See §F for the full threat analysis (including why a token sent via a permission-protected explicit service intent is not sniffable by other apps, and what a `RUN_COMMAND` co-holder can and cannot do).

## B.9 Reconnect flow (controller side)

```
on any transport failure:
  state=DISCONNECTED → backoff reconnect loop (0.5s,1s,2s,…cap 30s)
   ├─ refused + bridge was up recently → send `trmx start` intent (max 1/min)
   ├─ still refused after N tries      → TERMUX_NOT_RUNNING / BRIDGE_NOT_RUNNING
   └─ 401                              → AUTHENTICATION_FAILED (stop loop)
on success:
  GET /v1/system/info (version handshake)
  GET /v1/events  (single global SSE stream: job state changes, system events)
  re-open each open log view: GET /v1/jobs/{id}/output?from_seq=<lastSeq>
  resync job list — the bridge's SQLite is the truth; the app diffs into Room
```

The reconnecting client is one coroutine; every screen observes it, so the whole UI reflects connection state within one poll cycle.

## B.10 Reboot flow (best-effort, by design)

1. User installs Termux:Boot (optional) and taps "Enable autostart" in our app → app writes `~/.termux/boot/trmx-bridge` via a `RUN_COMMAND` intent (`trmx enable-boot`).
2. At boot, Termux:Boot runs the script → bridge up before our app even opens.
3. Belt-and-braces: our app's `BOOT_COMPLETED` receiver (with a 30–60s delay to dodge OEM boot storms) probes the data plane and sends a start-intent if needed.
4. If both fail (force-stopped, OEM blocked): app opens later, notices the bridge is down, starts it — and reports "backend was down since HH:MM; N jobs were LOST" honestly.

---

# C. Communication Recommendation

## C.1 Options evaluated

Seven options were evaluated — the six requested plus SSH, because Termux ships an `sshd` and it would be dishonest to ignore it.

| Criterion | **A** Termux intents (`RUN_COMMAND`) | **B** Localhost HTTP API in Termux | **C** WebSocket in Termux | **D** Unix sockets | **E** Shared files / queue | **G** SSH (`sshd` in Termux) |
|---|---|---|---|---|---|---|
| Reliability | High (system-delivered intents) | High (plain TCP on loopback) | High once up | **N/A cross-app** (see below) | Low–medium (FUSE/timing) | High |
| Security | Excellent (custom dangerous permission + `allow-external-apps` opt-in; explicit intents aren't sniffable) | Good with token (any local app can *connect*; none can *sniff* loopback) | Same as B | — | Poor (shared storage is world-readable on some setups) | Excellent (crypto built in) |
| Performance | Fine for one-shots | Excellent (loopback, ~ms) | Excellent | (would be best, if allowed) | Poor (polling) | Good |
| Real-time push | ❌ None | ✅ Via long-lived SSE/chunked streams | ✅ Native | — | ❌ | ❌ (per-channel, per-command) |
| Streaming logs | ❌ Result only, *after* completion (PendingIntent callback) | ✅ Chunked + replay | ✅ | — | ❌ | ✅ Per exec channel |
| Background operation | ✅ Background mode exists | ✅ Bridge is a Termux daemon | ✅ | — | ⚠️ | ⚠️ sshd must be running (chicken-and-egg via intents anyway) |
| Process control (cancel, killpg, /proc) | ❌ Impossible from another app | ✅ Full (bridge is the parent) | ✅ | — | ❌ | ❌ (signal only via another exec) |
| Job model / history / scheduling | ❌ | ✅ Natural fit | ✅ (but you rebuild HTTP semantics on top) | — | ❌ | ❌ |
| Complexity (our code) | Low | **Low** (stdlib HTTP) | Medium (no stdlib WS server → pip dep or hand-rolled RFC 6455) | — | Medium | High (SSH client lib in app) |
| Maintainability | High | High | Medium | — | Low | Medium |
| Android compatibility | ✅ All (plus package-visibility `<queries>` on API 30+) | ✅ All (loopback is shared) | ✅ | ❌ SELinux blocks cross-app sockets: filesystem sockets live in each app's private dir (unreadable), abstract-namespace sockets are blocked for the `untrusted_app` domain on modern Android | ⚠️ Scoped storage penalties | ✅ |
| Termux compatibility | ✅ Official API | ✅ (binds a port like sshd does) | ✅ | — | ⚠️ needs `termux-setup-storage` | ✅ ships sshd |
| User setup complexity | None (beyond required opt-ins) | None (bridge installs itself) | None extra | — | Medium | Medium (key provisioning) |
| Failure modes | Intent dropped if Termux force-stopped; no feedback channel while running | Port occupied; bridge dead (detectable, restartable) | Same + WS framing bugs + dependency drift | — | Missed polls, partial writes, no ordering | sshd not started; key mismatch; channel lifecycle bugs |

## C.2 Why each option wins or loses

- **A — Intents alone: necessary but not sufficient.** `RUN_COMMAND` is the *only* way an external app can cause something to happen inside Termux without a pre-existing daemon — and it is well protected (custom dangerous permission the user must grant + `allow-external-apps=true` opt-in + path validation in `RunCommandService`). But it is fire-and-forget: results come back once, *after* completion, via a PendingIntent; there is no streaming, no cancellation, no job model, no file API. It is a **control plane**, not a product surface.
- **B — Localhost HTTP: the right data plane.** Every app on the device shares the loopback interface, so the app can talk to a Termux-bound server with zero special permissions — exactly how Termux's own `sshd` (port 8022) is used by other clients. Plain HTTP with JSON gives request/response for control and **chunked transfer (SSE-framed) for live streams**; cancellation is just another POST; it is debuggable with `curl` from Termux itself; the Python standard library can serve it with no dependencies; and it maps cleanly onto a future TLS-terminated remote mode.
- **C — WebSocket: right idea, wrong cost for V1.** The only thing WS adds over B is server-initiated frames on a single connection — and a long-lived SSE event stream already gives us that. The price is a non-stdlib server dependency (supply chain, version drift) or a hand-rolled RFC 6455 implementation (risk). Verdict: **deferred**; revisit for V2's PTY terminal, where bidirectional low-latency framing genuinely matters.
- **D — Unix sockets: not available to us.** Filesystem sockets would have to live inside one app's private data directory, which the other app cannot touch (SELinux + DAC). Abstract-namespace sockets are blocked/unreliable across app boundaries on modern Android. Loopback TCP **is** our Unix socket — same semantics, zero restrictions.
- **E — Shared files: kept only as an install fallback.** Polling latency, scoped-storage friction, no streaming/cancel, and shared storage is the least private place on the device. Rejected as a channel; retained as an offline path to deliver the installer to Termux if `curl` from inside Termux is not possible.
- **G — SSH: strong transport, wrong shape.** A real option for ad-hoc remote admin, but as the primary API it would mean embedding an SSH client library, solving key provisioning *anyway* (via intents or manual paste — the same pairing problem), and still building a job/files/tools protocol on top — SSH would just be the pipe under it. Reconsidered in V3 as a tunnel for remote access.

## C.3 Recommendation — Option F (Hybrid): A + B

> **`RUN_COMMAND` intents are the control plane (bootstrap, start/stop, pairing, upgrade).
> Loopback HTTP with chunked/SSE streaming and a bearer token is the data plane (everything else).**

Why the hybrid strictly dominates every single-mechanism design:

1. **It solves the cold-start paradox.** A pure data-plane design can't start its own server; a pure intent design can't stream. Together, the app can always resurrect the backend (`trmx start` intent) and then do real work over a rich channel.
2. **It keeps the trust boundary exactly where Termux's designers put it.** The powerful capability (executing things in Termux) is reached only through the user-consented `RUN_COMMAND` permission and `allow-external-apps` opt-in. The data plane is additionally token-gated.
3. **It is dependency-minimal.** One Termux package (`python`), one file. Nothing to compile, nothing per-architecture.
4. **It degrades gracefully.** Every state (bridge down, Termux down, unpaired, port squatted, protocol mismatch) is independently detectable and actionable (§B.7).

## C.4 Protocol sketch — **DRAFT v0** (illustrative; frozen as `docs/PROTOCOL.md` in Phase 1)

All requests/responses are JSON with a versioned prefix. Auth: `Authorization: Bearer <token>` on every call. Errors: `{"error": {"code": "...", "message": "...", "field": "..."}}`.

| Method & path | Purpose |
|---|---|
| `GET /v1/system/info` | handshake: bridge version, protocol version, uptime, load, memory, storage, Termux env, detected tools |
| `POST /v1/jobs` | submit job → `{job_id}` |
| `GET /v1/jobs?status=&limit=&offset=` | list/history (bridge SQLite is the truth) |
| `GET /v1/jobs/{id}` | full job record |
| `POST /v1/jobs/{id}/cancel` | graceful→forced process-group kill |
| `GET /v1/jobs/{id}/output?stream=stdout\|stderr\|both&from_seq=N&follow=1` | **chunked/SSE stream** with replay-from-sequence |
| `GET /v1/events` | global SSE stream: job state changes, system events |
| `GET /v1/files?path=&stat=` · `POST /v1/files` (mkdir/rename/move/copy/delete) | filesystem ops (path policy enforced bridge-side) |
| `GET /v1/files/content?path=&range=` · `PUT /v1/files/content` | download/upload (chunked, resumable) |
| `GET /v1/tools` | tool registry: schemas for GUI generation |
| `POST /v1/system/bridge` | stop/restart bridge (used by app's control plane) |

Example submit (structured — the default and strongly preferred form):

```json
POST /v1/jobs
{ "name": "Download: Cats documentary",
  "type": "argv",
  "argv": ["yt-dlp", "-f", "mp4", "--newline", "https://…"],
  "cwd": "~/downloads",
  "timeout_s": 7200 }
```

Example stream frame (SSE-framed chunk):

```
event: stdout
id: 4821
data: {"job_id":"J-1042","seq":4821,"bytes":"[download]  42.1% of …"}

event: status
id: 4822
data: {"job_id":"J-1042","status":"COMPLETED","exit_code":0,"ended_at":"…"}
```

Versioning rules (to be finalized in Phase 1): additive fields only within `/v1/`; breaking changes → `/v2/` mounted side-by-side for one bridge release; the app pins the major version it understands and refuses with `PROTOCOL_MISMATCH` rather than guessing.

---

# D. Termux-Side Architecture

## D.1 What exists inside Termux (the minimal set)

| Component | What it is | Mandatory? |
|---|---|---|
| `~/.trmx/trmx-bridge.py` | Single-file Python 3 daemon: HTTP API, job manager, supervisor, scheduler, auth, file API, tool registry. **Standard library only** — `asyncio`, `http.server`-based handling, `json`, `sqlite3`, `subprocess`, `secrets`, `signal`, `os`. | ✅ Core |
| `~/.trmx/trmx` | Tiny CLI helper (`pair`, `unpair`, `start`, `stop`, `status`, `token`, `enable-boot`, `enable-service`). Runs *without* the bridge so pairing/start intents work cold. | ✅ Core |
| `install.sh` | Idempotent installer (fetch, SHA256-verify, place files, wire helpers). | ✅ Core |
| `~/.trmx/bridge.json` | Config: token, port, policy (allowed roots, concurrency cap, log caps). Mode `0600`. | ✅ Core |
| `~/.trmx/jobs.db` | SQLite: `jobs`, `events`, `audit` tables (WAL mode). | ✅ Core |
| `~/.trmx/logs/<job_id>/` | Per-job `stdout.log` / `stderr.log` ring files + `meta.json`. Appended live; size-capped. | ✅ Core |
| `~/.trmx/tools/*.json` | Tool schemas (bundled set + user-extensible). | ✅ Core |
| runit service dir (`termux-services`) | Auto-restart bridge on crash; survives session close. | ⭕ Recommended |
| `~/.termux/boot/trmx-bridge` | Auto-start after reboot (needs Termux:Boot). | ⭕ Optional |

## D.2 Why a Python-stdlib daemon (and not Node/Go/shell)

- **Python is already packaged in Termux for every supported architecture** (arm64-v8a, armeabi-v7a, x86_64) — `pkg install python` is a single, universal dependency, installable by the app through a `RUN_COMMAND` intent.
- **Standard library only** = no `pip`, no supply chain, no version drift, no virtualenvs. Everything we need — an HTTP loop, JSON, SQLite, subprocess with process groups, signals, `/proc` reading, cryptographic randomness — is stdlib.
- **A compiled daemon (Go/Rust) is impossible to deliver cleanly in V1**: our app cannot write into `$PREFIX`/`$HOME` (Termux's private dir), so a binary would need the shared-storage relay dance or a Termux-side `curl` anyway — at which point Python is simpler. Reconsider for V3 only.
- **Node.js** would work but drags a bigger install and has weaker stdlib ergonomics for process-group supervision and signals.
- **Pure shell + `socat`/`busybox httpd`** cannot do auth, streaming framing, or SQLite reliably.

## D.3 Manual vs. automated — the honest split

| User must do **once**, by hand (this *is* the consent boundary) | App automates afterwards (via `RUN_COMMAND` intents) | **Cannot** be automated, ever |
|---|---|---|
| Install Termux (GitHub Releases or F-Droid) and open it once | `pkg install python -y` | Installing/starting Termux when it is force-stopped |
| Set `allow-external-apps=true` in `~/.termux/termux.properties` (one copy-pasted line) + `termux-reload-settings` | Downloading + installing/upgrading the bridge (SHA256-pinned) | Granting our app the `RUN_COMMAND` permission (Android Settings only — by design) |
| Grant `TRMX-GUI` the `com.termux.permission.RUN_COMMAND` permission in Android Settings | Pairing the token (`trmx pair <token>`), starting/stopping the bridge | Removing Android's background/battery restrictions without the user (we can only deep-link them to the right screens and explain) |
| *(Optional)* install Termux:API / Termux:Boot from the **same source** as Termux | Enabling the runit service & boot script; `termux-setup-storage` if the user wants shared storage | Disabling the phantom process killer without the user (Android 14+: Developer Options toggle; 12–13: ADB from a PC) |
| *(Recommended)* battery-optimization exemption for Termux | Job submission, monitoring, cancellation, scheduling — all over the data plane | Anything requiring root |

## D.4 Bridge internals (responsibilities)

1. **HTTP server** (asyncio): routes in §C.4; every request token-checked (constant-time), rate-limited on failures, audit-logged.
2. **JobManager**: spawns jobs with `setsid` → each job owns a process group (so `yt-dlp | ffmpeg` dies as a unit); enforces per-job timeouts and a global concurrency cap (default 4 — deliberately below phantom-killer pressure); records PID/PGID; `waitpid` reaping.
3. **Supervisor**: 250ms–1s tick reading `/proc/<pid>/stat` → state, CPU%, RSS; detects external kills (process gone without exit record → `FAILED(signal 9)` + advisory).
4. **Journal & recovery**: SQLite is write-ahead-logged; on bridge restart, running jobs' PGIDs are checked against `/proc` → still alive ⇒ re-adopt (metrics resume; note: output *streams* can't be re-attached without a PTY — logs still flush from the ring files, so the app loses nothing it hadn't already read); gone ⇒ `LOST`.
5. **Streaming**: append chunks to ring files + fan out to any connected SSE followers; every chunk gets a monotonic `seq` per job → reconnect replay is exact.
6. **File API**: path canonicalization; policy check (default allowlist: `$HOME` and `$HOME/storage/*`; deny `$PREFIX` writes and anything outside roots); size caps; range support.
7. **Tool Registry**: bundled schemas (yt-dlp, ffmpeg, aria2c, mpv, git, pip, node, …) + auto-detection (`command -v` probe at startup and on demand) + user-supplied schemas.
8. **Scheduler (V1.5)**: interval and cron-subset schedules persisted in SQLite; fires jobs as if submitted locally.
9. **Notifications (optional)**: on job completion, shells out to `termux-notification` if Termux:API exists.

---

# E. Android-Side Architecture

## E.1 Technology stack and why

| Layer | Choice | Rationale (and rejected alternatives) |
|---|---|---|
| Language | **Kotlin** | The platform default; coroutines/Flow are exactly the model this app needs. |
| UI | **Jetpack Compose + Material 3**, single Activity + Navigation | Declarative UI for reactive job state; Material 3 gives the "beautiful dashboard" baseline. XML/Views would be fighting the future. |
| Pattern | **MVVM**: ViewModels exposing `StateFlow`; unidirectional data flow | Testable, lifecycle-safe; no over-engineered MVI framework needed. |
| Networking | **OkHttp + kotlinx.serialization** | OkHttp streams chunked bodies first-class (essential), has MockWebServer for tests, and is the most boring/reliable choice. Retrofit's value (typed REST) is marginal for ~12 endpoints; add later if wanted. Ktor client is fine too — OkHttp wins on maturity. |
| Local cache | **Room** (job history mirror, tool catalog, stream position bookmarks) | Queryable, type-safe, migrations. A JSON file would force hand-rolled filtering/paging; history is inherently relational. |
| Settings | **DataStore (Preferences)** | Small, typed, coroutine-native; SharedPreferences is legacy, and `EncryptedSharedPreferences` is deprecated. |
| Secrets | **Android Keystore**-generated AES key wrapping the pairing token at rest | The Keystore-backed file is unreadable to other apps and to backups; EncryptedSharedPreferences is deprecated so we do it directly (small utility, well-documented pattern). |
| DI | **Manual DI** (`AppContainer`) in V1 | A dozen classes don't need Hilt's build complexity; migrate if the graph grows. |
| Background | Foreground service ("Watching job…") only while a screen is visible/streaming; otherwise none | The app is disposable by design — no wakelocks of our own, no job execution in the app. WorkManager only if we later add periodic history sync. |
| Min / target SDK | **minSdk 26 (Android 8.0)** / target latest stable | Android 8 is the first release with the background-execution model we design for; Android 7.x is <1–2% of devices and adds W^X/Doze quirks. (If you personally need Android 7, tell me in §L — it's possible, just more testing.) |
| Packaging | Gradle version catalogs, single module, R8 | No multi-module ceremony until it pays for itself. |

## E.2 Key runtime components

| Component | Responsibility |
|---|---|
| `BridgeClient` | Typed API calls + SSE/chunked stream handling + seq-based resume; all errors mapped to typed `ConnectionState`s |
| `IntentControlPlane` | Builds/sends `RUN_COMMAND` intents (install, pair, start, stop, probe); handles Android 12 `PendingIntent` mutability flags and API-30+ package visibility (`<queries>` for `com.termux`) |
| `ConnectionMonitor` | Owns the state machine of §B.9; single source of connection truth; drives the global status banner |
| `JobRepository` | Submits jobs, mirrors bridge state into Room, exposes paged history |
| `ToolRegistry` | Fetches/caches tool schemas; renders form models for Compose |
| `PairingController` | Token generation (SecureRandom), intent-push + manual-entry fallback, Keystore storage |
| `StreamService` (foreground, on-demand) | Keeps the app alive while the user actively watches a stream with the screen on/locked |
| `BootReceiver` | Delayed post-boot probe + start-intent (best-effort, §B.10) |
| `NotifyHelper` | Job-completion notifications for running-app cases (bridge's Termux:API notification covers the backend-only case) |

## E.3 Storage on the Android side

| Data | Store | Why |
|---|---|---|
| Connection config (port, paths), UI prefs | DataStore | Small, typed, observable |
| Pairing token | Keystore-wrapped file | Hardware-backed at rest; excluded from backups |
| Job history **cache** (bridge is truth) | Room | Offline browsing, instant list render, diff-resync |
| Cached log tails | Room (capped) or files | Fast re-open of recently viewed streams |
| Tool catalog cache | Room | Form rendering before first bridge round-trip |

## E.4 Controller availability vs. backend availability (never conflate)

Two orthogonal state machines, always rendered independently:

- **Backend state** (from §13 list): `CONNECTED · CONNECTING · DISCONNECTED · AUTHENTICATION_FAILED · TERMUX_NOT_RUNNING · BRIDGE_NOT_RUNNING · PERMISSION_REQUIRED · PROTOCOL_MISMATCH · NETWORK_ERROR · UNKNOWN_ERROR` — each with a specific banner + a "Fix…" action (e.g., `PERMISSION_REQUIRED` → deep link to grant `RUN_COMMAND`; `TERMUX_NOT_RUNNING` → "Open Termux once, then tap Retry").
- **Controller state**: foreground / backgrounded / frozen / killed — irrelevant to job fate, and the UI must *say so*: "Your app was closed for 2h; job J-1042 completed at 13:47. [View output]".

---

# F. Security Architecture

## F.1 Threat model

| | |
|---|---|
| **Assets** | The pairing token; the Termux filesystem (scripts, keys, `~/.ssh`); job data & logs; device capabilities reachable via Termux:API; device bandwidth (a compromised bridge could exfiltrate) |
| **Attackers** | ① A **malicious/compromised app on the device** (the realistic one) — can open sockets to `127.0.0.1:port`, may hold its own `RUN_COMMAND` grant, may try to squat our port. ② A **LAN/WAN peer** — excluded in V1 (bridge binds loopback only). ③ **Malicious job content** (a script/URL that runs something destructive). ④ **Physical attacker** with the phone unlocked. ⑤ **Supply chain** (poisoned installer/dependency). |
| **Attack surfaces** | The bridge's HTTP port; `RUN_COMMAND` grants; token at rest (both sides); intent extras appearing in logs/logcat; log files; shared-storage relay; the installer URL |
| **Trust boundaries** | (1) Android app sandbox ↔ Termux sandbox — crossed *only* by loopback HTTP and permission-protected explicit intents. (2) Device ↔ network — V1 opens nothing. |

## F.2 Authentication

- Token: 256-bit (`secrets.token_urlsafe`), stored `0600` in Termux and Keystore-wrapped in the app; compared in constant time; never logged; never included in URLs (header only).
- Pairing paths (§B.8): intent-push (default) or manual entry (fallback). Rotation: `trmx unpair` in Termux → re-pair in app. Loss of the phone: unpair + rotate from Termux; revoke is instant (token is the only credential).
- Brute force: 401s are rate-limited (exponential delay per source connection) and audit-logged; the token space makes guessing hopeless.

## F.3 Authorization (V1: single tier, V2: risk tiers)

V1 is a single-user local tool: token ⇒ full control. V2 adds **risk tiers** enforced bridge-side: `safe` (list files, run whitelisted tools), `confirm` (shell jobs, writes outside home, package installs), `destructive` (patterns like `rm -rf`, `dd of=`, `mkfs`, `chmod -R`, `:(){ :|:& };:`) — the last two require an explicit in-app confirmation, and all shell-mode executions land in the audit table.

## F.4 Command execution security (the core analysis)

- **Default is structured argv — no shell exists in the path.** `{"tool":"ffmpeg","args":[…]}` → `execve`-style spawn of an allowlisted executable with a validated argument array. Classic injection (`; rm -rf ~`, backticks, `$(…)`, unquoted spaces) **cannot occur** because there is no string parsing step at all. This is the single most important design decision for security.
- **Validation happens at the bridge**, not only the UI: tool allowlist (resolved against `$PREFIX/bin` + `~/.trmx/tools`), schema-typed arguments (enum for formats, bounded ints for quality, `file:`-typed args constrained by path policy).
- **Shell mode is opt-in and explicit.** `{"type":"shell","script":"…"}` exists because real work sometimes needs pipelines and `bash` semantics. It is: labeled in the UI, requires confirmation (V2 tiers), fully audit-logged with the exact string, and runs with the same path policy. We do not pretend a regex makes arbitrary shell "safe" — the confirmation + audit + bounded roots are the actual controls; the destructive-pattern scanner is a **UX guardrail, not a security boundary**, and the docs will say so plainly.
- **Path policy (bridge-enforced):** default roots = `$HOME` and `$HOME/storage/*`. Writes to `$PREFIX` (Termux internals) and anything outside the roots are rejected unless the user explicitly widens policy in `~/.trmx/bridge.json`. Canonicalization (`realpath`) defeats `../../` traversal.
- **Privilege escalation:** there is no `sudo`, no root, no daemon running as another UID — the ceiling *is* the Termux user account, which is also the floor. Nothing to escalate to.
- **Why the token must exist even on loopback:** any app can *connect* to the port; no app can *sniff* another connection's loopback traffic or read either side's private storage. Hence connection-capable but credential-less attackers are the exact threat the token neutralizes.

## F.5 Transport & the port-squat scenario

| Scenario | Reality | Mitigation |
|---|---|---|
| Another app connects to `127.0.0.1:27342` | Gets `401` on any call | Token (constant-time, rate-limited); audit log records attempts |
| Another app binds 27342 *before* our bridge | Bridge fails to bind → **fails loud** → app sees start-intent failure, not a silent fake | App treats "port in use by unknown process" as `BRIDGE_NOT_RUNNING (PORT_SQUATTED)` and offers an alternate port reconfiguration |
| Squatter impersonates the bridge to harvest the token | Possible in theory: our app would send the token to whoever holds the port | V1: bind-failure check + fixed-port collision is a targeted, low-likelihood attack on a personal device; documented. **V2 hardening:** bridge generates a self-signed cert at first run; app pins it at pairing → impersonation becomes cryptographically detectable (this is also exactly what V3's remote mode needs, so we build it once) |
| Cleartext HTTP on loopback | Traffic cannot be sniffed by other apps (no promiscuous access to other sockets' loopback traffic without root) | Accepted for V1 with the pinned-TLS upgrade path above; app's `usesCleartextTraffic` scoped to this need (verify NSC-vs-raw-IP behavior in the Phase 4 spike) |

## F.6 The RUN_COMMAND co-trust problem (accepted risk, documented)

Any app the *user* grants `com.termux.permission.RUN_COMMAND` can execute arbitrary commands in Termux — that is Termux's design, and our bridge cannot and should not defend against a user-granted peer (it could just run `trmx unpair` or read files directly anyway). Our app therefore: requests only this one custom permission, explains *in the pairing wizard* what it grants, and the docs state that the `RUN_COMMAND` holders list is visible in Termux's own UI. Token security protects against **non-holders** — the overwhelming majority of apps.

## F.7 Supply chain & operational hygiene

- `install.sh` and bridge releases are **SHA256-pinned**; the app verifies the digest via a second intent before first start; no `pip` dependencies ever (stdlib-only rule).
- Logs: token redaction filter is unit-tested; `trmx` never prints the token after pairing (only a masked hint).
- One spike in Phase 4 explicitly greps `logcat` for our intent extras to confirm no secret leakage via system logs.
- App-side: `android:allowBackup="false"` for the token store, `networkSecurityConfig` restricting cleartext to the bridge port, exported components kept to the minimum (only the boot receiver + result receiver), and the result-PendingIntent uses `FLAG_IMMUTABLE` where possible.

---

# G. Feature Map & GUI Philosophy

## G.1 Three GUI philosophies compared

| | **A — GUI-first** (curated modules only) | **B — Universal controller** (generic runner for everything) | **C — Hybrid** (recommended) |
|---|---|---|---|
| Example | yt-dlp form: URL, quality dropdown, folder, Download | "Command + args" form for any binary | Curated forms for common tools **plus** universal runner **plus** (V2) PTY terminal |
| Learning curve for non-CLI users | Lowest | Highest (it's a terminal with extra steps) | Low for common tasks, unbounded for experts |
| Coverage of Termux tools | Only what we built forms for | ~Everything | Everything (forms where they pay off, runner elsewhere) |
| Maintenance cost | High per tool (hardcoded forms rot as CLIs change) | Low | Medium — **but amortized by the Tool Registry** |
| Safety | Highest (bounded inputs) | Lowest (raw power, no schemas) | Tiered (bounded inputs by default; raw power behind confirmation) |
| Build order friendliness | Slow start (many modules before it feels useful) | Fast start | Fast start (runner first, modules accumulate) |

**Recommendation: Philosophy C, made scalable by the Tool Registry.** Forms are *not hardcoded per tool* — they are **generated from JSON schemas** (`~/.trmx/tools/*.json`: argument names, types, enums, defaults, validators). That single abstraction gives us Philosophy A's polish with Philosophy B's coverage curve: adding a tool = adding a schema, no app update needed. Power users can write their own schemas; the community could share them later (V3 marketplace idea). The universal runner (structured argv) covers every unschema'd binary, and a streamed PTY terminal (V2) covers the interactive remainder (htop, python REPL, AI agent chats).

## G.2 Feature inventory → version mapping

| Category | Features | Version |
|---|---|---|
| **Core — Connection** | Pairing wizard, connection states + guided fixes, bridge install/upgrade/start/stop, diagnostics (versions, port, token health) | **V1** |
| **Core — Jobs** | Submit (structured/universal), live status, cancel, timeout, concurrency view, history (persisted bridge-side), notifications | **V1** |
| **Core — Logs** | Live stdout/stderr stream, replay, per-job viewer, pause/follow, search in buffer, share/export | **V1** (search/export V1.5) |
| **Core — Dashboard** | Backend health card, running jobs, recent completions, CPU/mem/storage, device battery (via Termux:API) | **V1.5** |
| **Files** | Termux home browser (list/stat/mkdir/rename/move/copy/delete), upload/download, text/image/video preview, shared-storage (`~/storage`) access via SAF bridge, search | **V2** (browser V1.5) |
| **Media** | yt-dlp, aria2c, ffmpeg modules; download queue; mpv control (via mpv IPC if present) | **V2** (first modules per your §L answer) |
| **Development** | Git status/log/pull/push module; Python venv/runner; Node runner; `pkg` manager module (search/install/upgrade) | **V2** |
| **AI** | Structured AI-CLI runners with prompt input + token-by-token streaming; session list; cost/time stats; (LLM CLI schemas: `llm`, `aider`, `ollama run`, etc.) | **V2** (streaming rides the V1 pipe) |
| **Automation** | Interval & cron-subset schedules, job chaining (on-success/on-fail), retry policy | **V2** |
| **Terminal** | PTY-backed interactive terminal over the bridge stream | **V2** |
| **Device (Termux:API)** | Battery, sensors, clipboard, TTS, notifications sent from the phone | **V2/optional** |
| **Ecosystem** | Multiple Termux backends, LAN remote (TLS), multi-controller, tool marketplace, workflow builder | **V3** (protocol designed for it, nothing built) |

**Explicitly not in any version:** anything requiring root, controlling other apps, or bypassing user consent.

---

# H. Development Roadmap

Guiding rule (yours, adopted verbatim): *correct architecture → smallest working proof → verified communication → controlled execution → reliable job management → polished UI → security → testing → production.* Each phase ends with a **demo you can see**, and we do not start the next one until you confirm it.

### PHASE 0 — Feasibility & Architecture Discovery ← **you are here**
- **Objective:** prove/disprove feasibility; choose architecture; surface all constraints.
- **Output:** this report. **Test:** your review. **DoD:** you answer §L and approve (or amend) the architecture.
- **Failure points:** none technical — only unexamined assumptions. That's what this document is for.

### PHASE 1 — Protocol freeze & project constitution
- **Objective:** turn §C.4's draft into a complete, testable protocol spec before any code.
- **Prereqs:** Phase 0 approved. **Tasks:** write `docs/PROTOCOL.md` (every route, every error code, every event type, versioning rules, size caps, rate limits); write `docs/ARCHITECTURE.md` (living summary of this report); agree repo layout (`android/` + `termux/` + `docs/`); start `CHANGELOG.md` and a decision log (`docs/decisions/ADR-001…`).
- **Output:** reviewed spec + shared JSON fixtures (`fixtures/`) usable as contract tests later. **Test:** fixtures reviewed by both "sides" mentally; nothing ambiguous left. **DoD:** spec merged; every Phase-2+ test references it.
- **Failure points:** under-specified error semantics (fix now, cheap; later, expensive).

### PHASE 2 — Termux bridge PoC (no Android code)
- **Objective:** standing inside Termux, prove the backend: serve HTTP, auth, run one job, stream it, cancel it.
- **Prereqs:** Phase 1; a device with Termux + `pkg install python`. **Tasks:** `trmx-bridge.py` skeleton (HTTP loop, token check, JobManager with setsid/killpg, SQLite job row, one log ring file); smoke-test script.
- **Output:** bridge that a human drives with `curl`. **Test:** `curl` submits a `yes | head -c 10M` style job; second `curl` tails the stream live; third cancels it; DB row shows the right final state.
- **DoD:** that demo works on a real phone, twice, after a bridge restart (jobs reconcile).
- **Failure points:** signal/PGID edge cases (pipelines), SQLite locking under stream load — both fixed here, in isolation, where they're cheap.

### PHASE 3 — Bootstrap automation (intents)
- **Objective:** make "the backend exists" a one-tap operation.
- **Prereqs:** Phase 2. **Tasks:** `trmx` CLI (pair/start/stop/status/token), `install.sh` (SHA256-pinned), runit service dir; verify each step by sending the *exact* intents our app will send, via `adb shell am startservice` (a faithful stand-in for the app).
- **Output:** from a clean Termux: three intents later, the bridge is running and paired. **Test:** repeat on a second device/user profile; check `logcat` for leaked extras.
- **DoD:** full bootstrap via intents alone; zero manual typing after the one-time consents (§D.3). **Failure points:** `allow-external-apps` not reloaded; permission grant flow misunderstood by user — both produce explicit error states, not mystery.

### PHASE 4 — Android PoC (**the first real milestone**)
- **Objective:** *Android app ↔ Termux: pair, run one controlled job, stream output live, cancel, survive app restart.* Ugly UI is allowed; reliability is not.
- **Prereqs:** Phases 2–3. **Tasks:** minimal app: `BridgeClient`, `IntentControlPlane`, `PairingController` (Keystore), one Compose screen (status line + button + log box), `ConnectionMonitor` v0. **Spikes:** cleartext-to-loopback config (NSC vs `usesCleartextTraffic`); package visibility `<queries>`; `PendingIntent` flags on Android 12+; optional: abstract-socket probe (to close Option D's open question with data).
- **Output:** the milestone, demonstrable. **Test:** kill the app mid-stream → reopen → stream resumes with no lost lines; kill bridge → app restarts it via intent automatically.
- **DoD:** you reproduce the milestone on your phone and confirm. **Failure points:** NSC cleartext config on some OEM ROMs (fallback: allow-listed cleartext flag); Termux not granted Draw-over-apps (only affects session-mode; we use background mode).

### PHASE 5 — Job management core
- **Objective:** the full job model (§B.6) end-to-end.
- **Tasks:** job list/history screens, Room mirror + resync, cancel/timeout UX, notifications (app-side), concurrency cap, queue. **Output:** a jobs screen that behaves like a real product.
- **Test:** 20-job soak (mixed quick/long), kill-matrix subset, restart mid-job → correct `LOST`/re-adopt.
- **DoD:** job lifecycle table demoed state-by-state. **Failure points:** state divergence between app cache and bridge truth — resolved by "bridge wins" diff rule, tested explicitly.

### PHASE 6 — Reliability & lifecycle hardening
- **Objective:** survive everything §J's matrix throws.
- **Tasks:** full reconnect loop (§B.9); bridge crash → runit restart → app reconciliation; boot autostart (Termux:Boot script + our `BOOT_COMPLETED` belt-and-braces); phantom-killer detector (Android 12+ + `signal 9` history) with step-by-step fix screen; battery-optimization deep links; `LOST` jobs report.
- **Test:** automated kill-matrix script (see §J). **DoD:** every row of the matrix produces the *designed* state, never a hang or silent failure.
- **Failure points:** OEM-specific killers (document per-OEM guidance; some things only the user can fix — we make that honest and visible).

### PHASE 7 — Dashboard, connection UX & settings
- **Objective:** the "beautiful" part, on top of a proven core.
- **Tasks:** dashboard (health, running jobs, resources), all 10 connection states with banners/fix actions, settings (port, roots policy mirror, appearance), pairing wizard polish, error reporting screen.
- **Test:** debug screen that can force every connection state; Compose UI tests; visual review. **DoD:** a stranger can pair and understand every screen without docs.

### PHASE 8 — File management
- **Tasks:** Termux FS browser + operations via `/v1/files`; upload/download with progress/resume; text/image/video preview (range reads); shared storage via `~/storage` + SAF; path policy UI. **Test:** 100 MB round-trip with SHA256 verify; delete/move correctness; permission-denied paths rejected. **DoD:** files demo + integrity proof.

### PHASE 9 — Tool Registry & first GUI modules
- **Tasks:** schema engine (types, enums, validation, conditional fields), form renderer, bundled schemas for your priority tools (from §L answers — likely yt-dlp/aria2c/ffmpeg), universal runner with confirmations, tool auto-detection UI. **Test:** add a new schema at runtime → form renders with zero app changes; schema fixture tests. **DoD:** your #1 real-world task is doable start-to-finish in the GUI.

### PHASE 10 — Security hardening
- **Tasks:** risk tiers bridge-side, destructive-pattern guardrails + confirmations, audit log viewer, optional self-signed-TLS pinning (also pre-work for V3), app-lock (biometric/PIN), port-squat handling. **Test:** attack checklist (§F) executed by hand + scripted: wrong token, no token, squatted port, injection payloads in every field, path traversal payloads. **DoD:** checklist fully green; findings logged as ADRs.

### PHASE 11 — Testing & soak
- **Tasks:** implement the full §J matrix as repeatable scripts; soak runs (24 h job, 10k-line streams, reconnect storm, 32+ process pressure); Android-version/OEM manual matrix. **DoD:** matrix table committed with results; no P0/P1 defects open.

### PHASE 12 — Packaging & release
- **Tasks:** GitHub Actions CI (APK build + bridge artifact + SHA256 + contract-test gate), signing keystore, `CHANGELOG.md`, install guide (README + in-app), versioning (`app` and `bridge` versioned together with protocol major). **DoD:** a person who has never seen this project can install and pair using only the README.

---

# I. Required Tools & Environment

| Category | Tool | Status | Why |
|---|---|---|---|
| Android dev | **Android Studio** (current stable) | **Mandatory** | Bundles JDK 17+, Android SDK, Gradle, device tooling, Compose preview |
| Android dev | A **physical Android phone (8.0+)** with USB debugging | **Mandatory** | Termux, phantom-killer behavior, OEM battery logic, and intent delivery **cannot be faithfully emulated**; emulators lie about exactly the parts that matter here |
| Termux dev | **Termux from GitHub Releases** (recommended) or F-Droid — *one source for all Termux apps* | **Mandatory** | Play Store build is frozen/deprecated (v0.101, 2020) and breaks on Android 10+; mixing F-Droid & GitHub APKs fails on signature/sharedUserId checks |
| Termux dev | `pkg install python curl git jq` in Termux | **Mandatory** | python = the bridge runtime; curl/jq = curl-based API testing; git = versioning the termux/ side |
| Termux dev | `termux-services` (runit), **Termux:API** + `termux-api` pkg, **Termux:Boot** | Optional (recommended) | runit: bridge auto-restart; API: device features; Boot: autostart |
| Termux dev | `termux-setup-storage` run once | Optional | only if you want shared-storage features |
| Tooling | **git** | **Mandatory** | version control, obviously |
| Tooling | **gh** CLI | Optional | PRs, releases from terminal |
| Tooling | Android emulator (x86_64) | Optional | UI-only iterations; *not* a substitute for device testing |
| Tooling | A second test device (different OEM) | Optional, high value | OEM-killer coverage |
| Release | GitHub Actions, release signing keystore | Optional (Phase 12) | CI + signed APKs |
| Release | F-Droid inclusion / Play Store | **Out of scope V1** | Play would reject Termux-dependent design anyway; decide later |

**Note:** no root, no custom ROM, no ADB-on-device are required to *build or use* V1. ADB from a PC is only needed for the optional Android 12–13 phantom-killer fix (the app detects and instructs; Android 14+ exposes a settings toggle instead).

---

# J. Testing Strategy

**Philosophy: every layer gets its own proof, plus one shared contract layer, plus a scripted failure matrix. Nothing is called "working" without a reproduced demo.**

### J.1 Layer-by-layer

| Layer | What | How |
|---|---|---|
| Android unit | state machines (connection, job lifecycle mapping), token storage, arg-model validation | JUnit + Turbine (Flow assertions) |
| Android integration | `BridgeClient` vs. a scripted fake bridge | MockWebServer (chunked bodies, SSE frames, delays, resets) |
| Android UI | forms render from schemas; states show right banners | Compose UI tests + a debug screen that forces each connection state |
| Bridge unit | auth (constant-time, rate-limit), path policy, ring buffer bounds, /proc parsing, killpg semantics, journal reconciliation | Python `unittest` (stdlib — consistent with the no-dependency rule) |
| **Contract (shared)** | request/response/event JSON shapes | **shared `fixtures/` validated by both the Kotlin and Python test suites** — the protocol can never drift silently |
| End-to-end | the §B.4 flow, the §B.5 bootstrap, cancel, restart-recovery | scripted on a real device: `adb` drives the app, `curl` cross-checks the bridge, expected UI states asserted (screenshot-diff optional) |

### J.2 The failure matrix (each row must produce its designed state — never a hang, never silence)

| Failure injected | Expected behavior |
|---|---|
| Termux not installed / force-stopped | `TERMUX_NOT_RUNNING` + install/open guidance; app still browses cached history |
| Bridge not running | auto start-intent → `CONNECTED`; else `BRIDGE_NOT_RUNNING` + "Start" button |
| Wrong / revoked token | `AUTHENTICATION_FAILED`; re-pair wizard; bridge audit-logs the attempt |
| `allow-external-apps` not set | control-plane intents fail with Termux's own error → app shows the exact one-liner fix |
| `RUN_COMMAND` permission not granted | `PERMISSION_REQUIRED` + deep link to grant screen |
| Command not found (ENOENT) | job `FAILED` + "tool not installed — install via pkg?" hint |
| Job crashes (non-zero/signal) | `FAILED(exit_code|signal)` + tail of stderr surfaced |
| Bridge crashes mid-job | runit restart → reconcile → `LOST`/re-adopt + notification; app resyncs |
| Termux killed mid-job | next start: jobs reconciled `LOST`; honest report screen |
| App killed mid-stream | nothing lost; reopen resumes from `seq` |
| Device reboot | boot script / receiver path restores bridge (or reports downtime honestly) |
| Malformed request | typed 4xx; bridge never crashes on bad input (fuzzed payloads) |
| Huge output (GBs) | ring-buffer caps + backpressure; UI follows tail without unbounded memory |
| 24 h job, screen off | survives with wakelock; on phantom-killer devices, detector explains the fix |
| Reconnect storm | backoff holds; no duplicate submissions (idempotency keys) |
| Port squatted | bind fails loud → `PORT_SQUATTED` state + change-port flow |
| 32+ processes spawned | concurrency cap + phantom advisory |

### J.3 Soak & manual matrices
- Soak: 24 h mixed jobs; 10 k-line stream; 1 GB log job; 100 reconnects/10 min; parallel-jobs pressure test up to cap.
- Manual device matrix: Android 8 / 10 / 12 / 13 / 14+ × stock-Android / Xiaomi / Samsung / Oppo-Realme (whatever you + testers own) — OEM rows documented even when "fails, user mitigation required."

---

# K. Risks & Limitations (brutally honest)

| # | Risk | Likelihood | Impact | Mitigation | Residual |
|---|---|---|---|---|---|
| 1 | **Phantom process killer (Android 12+)** kills background jobs | High on 12–13, medium on 14+ (toggle exists) | Jobs die with signal 9 | Detector + guided fix (toggle/ADB); wakelock; concurrency cap; honest `LOST` reporting | Jobs can still die on unpatched 12–13 devices — this is an OS limit, **not fixable by us** |
| 2 | **OEM app killers** terminate Termux | Medium–high (Xiaomi, Oppo, Huawei…) | Backend down until user intervenes | dontkillmyapp-style guidance in-app; runit restart; start-intent resurrection | Some ROMs require manual whitelisting — user education is the only cure |
| 3 | **Termux upstream changes** break `RUN_COMMAND` or internals | Low (API stable since v0.95, years) | Control plane breaks | Version handshake; pin guidance; graceful degrade with clear error | An upstream redesign could force rework — watch releases |
| 4 | **F-Droid/GitHub source mixing** breaks add-ons | Medium (classic user error) | Confusing failures | Pairing wizard checks Termux package + version and explains the same-source rule | Human error remains possible |
| 5 | **Setup friction** (3 manual consents) scares users | Medium | Product feels "hard" | Wizard with exactly 3 clearly-explained steps; nothing else manual; video fallback | These consents are the security boundary — they cannot be removed |
| 6 | **Battery drain** from wakelocks | Low–medium | User trust | Wakelock only while jobs run; visible indicator; scheduler honors charging state | None structural |
| 7 | **Port-squat / local impersonation** | Low (targeted) | Token theft in theory | Bind-fail loud; V2 pinned self-signed TLS (kills the class) | V1 window documented & accepted for a personal-device threat model |
| 8 | **Python version drift** in Termux | Low | Bridge breaks on upgrade | stdlib-only rule; CI test on oldest supported Python in Termux; shebang pinned | Small |
| 9 | **Log/secret leakage** via logcat or files | Low | Token compromise | Redaction filters + unit tests + Phase 4 logcat spike | None expected |
| 10 | **SQLite/log corruption** on hard kills | Medium (kills are frequent) | History holes | WAL mode; integrity check + repair on bridge start; ring files are append-only | Rare, cosmetic |
| 11 | **Protocol drift** app↔bridge | Medium over time | Subtle bugs | Contract fixtures in CI for both sides; version handshake | None structural |
| 12 | **Scope creep** (the master prompt lists a small product's worth of future) | **High (the real one)** | Project death by ambition | Phase gates; V1 = the milestone in §H Phase 4–7, nothing more | Requires discipline from both of us |
| 13 | Termux:Boot / boot receivers blocked by OEMs | Medium | No autostart after reboot | Dual path (Boot add-on + our receiver); honest downtime report | Best-effort by nature |
| 14 | Single-maintainer bus factor | High (personal project) | Project stall | ADRs, changelog, this doc — designed for a mentor-led, incremental build | — |

**Hard limitations we will not pretend to solve:** no guaranteed background immortality on stock Android; no cross-UID/root actions; no controlling Termux when the user has force-stopped it; no Play-Store-friendly way to depend on Termux; no "zero-setup" first run. Everything else in this document is buildable.

---

# L. Questions For You

Only questions whose answers can change the architecture. ★ = the six most critical (also asked in the interactive prompt alongside this report).

**Product & distribution**
1. ★ **Who is this for?** (a) Just me, sideloaded APK — (b) me + friends, shared APK/GitHub release — (c) public open-source release (GitHub-first, maybe F-Droid later) — (d) commercial ambitions. *(Affects security rigor, onboarding polish, licensing, CI.)*
2. Should it eventually support **multiple Termux devices / multiple controllers**, or is one phone + one Termux the whole universe for now? *(The protocol is designed multi-backend-ready; the answer sizes the V1 UI.)*

**Termux environment**
3. ★ **Which Termux build is (or will be) on the phone?** GitHub Releases / F-Droid / currently the old Play Store one / not installed yet. *(Drives the wizard, add-on advice, and version checks.)*
4. ★ **What Android version is your main target device?** (14+ / 12–13 / 8–11 / several devices). *(Drives minSdk, phantom-killer handling weight, test matrix.)*
5. Which tools are must-haves on day one (yt-dlp? ffmpeg? a specific AI CLI? Node? git?) — i.e., **which schemas does Phase 9 build first?**
6. Is that Termux already set up (storage access, wakelock habit), or greenfield?

**Control philosophy**
7. ★ **GUI philosophy:** (a) GUI-first curated modules, (b) universal controller, (c) hybrid as recommended in §G.1?
8. How much **raw shell** should V1 expose: universal argv runner with confirmation only? Full shell-script jobs? Should destructive commands always require an explicit confirmation tap?
9. How important is a **terminal view** (PTY) in V1, versus V2 as planned?

**Networking**
10. ★ **Scope:** local-device-only for V1 (recommended), with LAN control planned for later — or is LAN/internet control a near-term must? *(Near-term changes the TLS decision from V2 to V1.)*

**Security**
11. Do you want **app-lock** (biometric/PIN gate on the app) in V1, V1.5, or not needed for personal use?

**Automation & AI**
12. Are **scheduled/recurring jobs** needed in V1 or acceptable in V2?
13. Which **AI tools** do you actually use (e.g., `llm`, `aider`, `ollama`, OpenAI/Claude CLIs, custom agents)? *(Determines whether token-streaming and PTY move up the roadmap.)*

**UI**
14. ★ **What is the #1 job this app must do well first** — media/downloads, dev+AI tooling, automation, or "connection + jobs + logs core first"?
15. Dashboard taste: dark-first minimal (Material You dynamic color), or light/dense "workbench" style?

---

# Appendix 1 — References

Facts in this report were verified against these primary/secondary sources (checked 2026-09-07):

- Termux app README (install sources, Android ≥ 7 requirement, sharedUserId same-signature rule, F-Droid lag, phantom-process notice): https://github.com/termux/termux-app
- Termux wiki — RUN_COMMAND Intent (permission, `allow-external-apps`, extras, PendingIntent results, package visibility, Draw-over-apps note): https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
- Termux app security behavior summary (permission protection levels, `RunCommandService` path validation, `allow-external-apps` enforcement order): https://deepwiki.com/termux/termux-app/8-security-and-permissions and the `RunCommandService`/`TermuxService` sources in https://github.com/termux/termux-app
- W^X / targetSdk 28 background ("Termux and Android 10"): https://github.com/termux/termux-packages/wiki/Termux-and-Android-10
- Play Store deprecation notice (last Play build v0.101, Sept 2020): https://www.reddit.com/r/termux/comments/pkujfa/important_deprecation_notice_for_google_play/
- Phantom process killer (32-process limit, signal 9, per-version fixes): https://github.com/termux/termux-app/issues/2366 · https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md · https://ivonblog.com/en-us/posts/fix-termux-signal9-error/ · https://cosyra.com/guides/termux-signal-9-fix.html
- OEM background-kill landscape: https://dontkillmyapp.com/
- Termux add-ons (API, Boot, Float, Styling, Tasker, Widget): https://github.com/termux/termux-api · https://github.com/termux/termux-boot
- Android platform behavior (scoped storage, network security config, cached-app freezing, background execution limits): https://developer.android.com/guide/topics/manifest/uses-cleartext-traffic and related platform docs.

# Appendix 2 — Glossary

| Term | Meaning |
|---|---|
| **Bridge** (`trmx-bridge`) | The Python daemon running inside Termux that exposes the HTTP API and manages jobs |
| **Control plane / Data plane** | Intent channel (bootstrap/lifecycle) / HTTP channel (jobs, logs, files) |
| **RUN_COMMAND** | Termux's opt-in intent API letting external apps execute a command inside Termux |
| **Phantom process** | Android 12+ term for a background child process of an app; subject to a 32-process system-wide kill budget |
| **PGID / `killpg`** | Process group; killing the group takes down pipelines (`a | b | c`) atomically |
| **SSE / chunked streaming** | HTTP-based server→client event streaming used for live logs |
| **PTY** | Pseudo-terminal; what a V2 terminal view would stream |
| **Scoped storage / SAF** | Android's shared-storage permission model / Storage Access Framework |
| **W^X** | Write-XOR-execute; Android 10 rule that broke exec-in-home for targetSdk 29+ apps (why Termux targets 28) |
| **runit / termux-services** | Process supervisor available inside Termux; auto-restarts the bridge on crash |
| **Tool Registry** | JSON schemas describing CLI tools; the app renders GUIs from them |
| **LOST** | Job state: bridge restarted and the job's process group no longer exists without an exit record |

---

*End of Phase 0 report. Next step: answer §L (or the interactive prompt), then approve or amend — Phase 1 (protocol freeze) begins only after your explicit go-ahead.*
