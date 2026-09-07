# Termux CommandDeck — Architecture & Master Prompt Audit

**Audit target:** the "Termux CommandDeck" native Android controller for Termux.

**Scope:** feasibility on Android, Termux↔app transport, long-running task handling, the single-shot AI‑generation prompt, and a concrete set of architectural fixes plus a rewritten generation plan.

**Verdict up front:** the *concept* is sound and genuinely useful, but the prompt as written will not produce a production‑viable app as a single shot. The main issues are (a) the implied file‑transfer model (scoped storage) is underspecified, (b) HTTP request/response semantics are used where streaming is required, (c) Flask/WSGI is the wrong runtime for the job, and (d) the prompt is far too large for one output.

---

## 1. Feasibility & Android OS Pitfalls

### 1.1 Can an external Android app talk to `http://127.0.0.1:8080` in Termux?

**Yes, but only if all three of these are handled:**

1. **INTERNET permission.** The app must declare `<uses-permission android:name="android.permission.INTERNET"/>`. Network access on Android requires it even for loopback.

2. **Cleartext HTTP.** Android 9+ (targetSdk 28+) blocks cleartext traffic by default. An HTTP call to `http://127.0.0.1:8080` will raise `Cleartext HTTP traffic to 127.0.0.1 not permitted` unless you explicitly allow it.
   - Add `android:usesCleartextTraffic="true"` **or** a `network_security_config.xml` that specifically permits cleartext to `127.0.0.1` and `localhost` while keeping everything else blocked.
   - Prefer the scoped network-security-config — it signals intent and is more secure than a global cleartext flag.

3. **The server must be bound and running.** Termux terminates the background process if the session dies. See section 1.2.

**Hostname caveat:** use `http://127.0.0.1:8080`, **not** `http://localhost:8080`. `localhost` may resolve to `::1` (IPv6) while a Termux server often binds IPv4 only, producing `Connection refused`. Pin the IP literal. Also set `baseUrl` as a `BuildConfig` field so it is trivially changeable (e.g., `http://10.0.2.2:8080` for the emulator).

**Loopback sharing note:** Android apps do not get separate network namespaces, so two first‑party apps on the same device can reach each other over loopback. This differs from iOS and from some Android docs' wording, so the architecture should still *treat* Termux↔app as an explicitly shared local network and make the port/configurable.

### 1.2 Will Doze, background limits, and phantom‑process killing break the Flask server?

**Yes, this is the single biggest reliability risk.** They will not make the app design impossible, but they make "the server always runs" an explicit engineering requirement, not an assumption.

- **Doze mode** (Android 6+): network access is deferred and wake locks are restricted during doze. A Termux background session can be suspended.
- **Phantom‑process killing / low‑memory killer (LMKD)** (Android 12+): Android aggressively kills background app processes under memory pressure. Termux is a normal app process; its long‑running Python process can be reaped at any time.
- **App‑start restrictions** (Android 14/15): restricting how apps can launch background services makes it harder to restart automatically.

**Mitigations (all user‑facing, no root required):**

| Mechanism | What it does | Where it belongs |
|---|---|---|
| `termux-wake-lock` | Holds a partial wakelock so Termux is not dozed | Inside `start-server.sh` |
| `termux-boot` (Termux:Boot) | Restarts the server after reboot | Optional auto‑start setup |
| Foreground session/notification | Keeps Termux's process active & is user‑aware | Termux side; document it |
| `adb shell cmd appops` / wireless ADB `am whitelist` | Opt‑out of aggressive background limits | Documented opt‑in step, not a runtime feature |
| Health + auto‑restart in app | App detects offline and can re‑launch/retry seamlessly | App side |

**Design consequence:** the app must never *assume* the server is alive. The "Termux Server Offline" badge is not a nice‑to‑have, it is the core status surface. Add:
- A connectivity `StateFlow<ServerStatus>` refreshed by periodic `GET /health`.
- A "Retry" that also gives optional OS‑level instructions ("run `start-server.sh` in Termux / enable Termux:Boot / keep a wake lock").
- When the server comes back, the client should resume/re‑attach to any in‑flight jobs by `job_id`, not lose all state.

### 1.3 How does scoped storage affect file transfers?

This is the most underspecified part of the prompt and the most likely to produce a "works on the emulator, broken on my phone" result.

- **Android 10+ scoped storage:** a normal app cannot freely read/write `/storage/emulated/0/Download` or any other shared directory without either `MANAGE_EXTERNAL_STORAGE` (full media access, heavy, Play‑policy‑restricted) or the **Storage Access Framework (SAF)**.
- **Termux read access:** Termux can typically read shared storage after `termux-setup-storage`, but neither app should depend on directly reading the *other* app's paths. On Android 11+, non‑owner apps generally cannot traverse another app's `/Android/data/<package>/files`.

**Recommended file model — two supported modes:**

- **Mode A: URL / prompt / typed path (no file transfer).** The UI collects a URL or a user‑typed absolute path that Termux already understands (`/sdcard/Download/foo.mp4`). The app sends only the string. This covers `yt-dlp`, TTS, and many CLI tools, needs **no storage permission**, and should be the default.
- **Mode B: SAF picker with byte streaming.** When a file picker is needed (compress, decompile an APK):
  1. The app opens `ActivityResultContracts.OpenDocument` / `OpenDocumentTree`, gets a `content://` URI.
  2. The app streams the file bytes with `ContentResolver.openInputStream()` to the server (multipart `POST /files/upload` or a raw stream), **not** the URI. The server does not need the content URI.
  3. The server writes to a Termux‑controlled working directory (e.g., `~/commanddeck/jobs/<job_id>/`), runs the command, then serves the result back as a download (`GET /files/<job_id>/output`) which the app saves with `CreateDocument` / adds to MediaStore.
- For a personal/sideload build you *may* declare `MANAGE_EXTERNAL_STORAGE` to let the app directly access shared dirs — but the prompt should explicitly call this a tradeoff, not the default architecture.

**Never pass `content://` URIs to Termux.** The Termux process cannot open another app's `content://` URIs without the app granting URI permission, which is fragile and not a design a CLI command supports.

---

## 2. Communication Protocol Flaws (HTTP REST vs. Streaming)

### 2.1 Is Retrofit POST/GET enough?

**No, not for the two headline features.**

- **`yt-dlp` / `ffmpeg` progress** is a stream of stdout/stderr lines (`[download]  12.3% of ...`). A single request/response call cannot display it live.
- **AI chat streaming** is token‑by‑token. A blocking response defeats the "live stream response box."

REST is still needed for job control and CRUD (create job, list tools, health). The correction is not "use only REST" but **separate control (REST) from events (streaming).**

**Recommended protocol: job‑oriented REST + SSE (or WebSocket).**

```
GET  /health                 → {"status":"ok", ...}
GET  /tools                  → built-in preset metadata
POST /jobs                   → {"job_id":"..."}                # starts async job immediately
GET  /jobs/{id}              → status/progress snapshot        # for reconnect
GET  /jobs/{id}/events       → text/event-stream               # live stdout/stderr/progress
POST /chat                   → text/event-stream               # token stream
POST /files/upload           → streams bytes to server
GET  /files/{job_id}/output  → download result
DELETE /jobs/{id}            → kill job
```

**Why SSE over WebSocket for v1:**
- SSE is simpler, one way, line‑based, and works through the same `http://127.0.0.1` cleartext realm.
- OkHttp ships `okhttp-sse` (`EventSource`), which integrates cleanly with a Retrofit/OkHttp stack.
- A job's event stream has natural lifecycle (server closes it when the job ends) — no handshake or heartbeat protocol to design.
- WebSocket is justified later if you need bidirectional control during a long job (e.g., interactive prompts inside the CLI agent). For that one case, use WebSocket **only** for the agent session, SSE for everything else.

**Pitfall — HTTP/1.1 and `no_proxy`:** on some Android emulators/devices, an HTTP(S)_PROXY env var or a per‑app proxy can route loopback. The client should bypass proxy for `127.0.0.1`/`localhost`.

### 2.2 Is Flask (WSGI) adequate for long-running commands?

**Not as written.** Flask's bundled server is WSGI and *typically single‑threaded*. A `yt-dlp` run that takes 4 minutes will hold a worker until it finishes, so:
- A second request (health or a different tool) blocks.
- Event‑streaming a subprocess inside a WSGI worker is painful and fragile.

**Fix — two layers:**

1. **Runtime:** FastAPI (ASGI) + `uvicorn`. Uvicorn handles concurrency, streaming responses, SSE, and WebSocket far better than Flask's dev server. You still write ordinary Python.

2. **Execution:** even an async server cannot run `subprocess` blocking calls on the event loop. Launch each command with `asyncio.create_subprocess_exec` and read stdout/stderr with `readline()` concurrently, pushing each line to a per‑job queue/`asyncio.Queue`. API request → enqueue job → return `job_id` immediately → SSE consumer drains the queue.

A minimal job manager (in‑memory dict of `job_id` → job) is enough for this scale. **Skip Celery/Redis** — they add unnecessary moving parts and battery drain on a phone. Persist completed job metadata (not live output) to a small SQLite file so jobs survive a server restart.

**Also address:**
- **Buffering:** run commands with `-u`/`--no-buffering`, and set `PYTHONUNBUFFERED=1`; otherwise stdout arrives in big chunks, not line‑by‑line.
- **Cancellation:** endpoints to kill a job (`DELETE /jobs/{id}`) and handle `SIGTERM`.
- **Shutdown:** gracefully terminate child processes on server exit so a killed server doesn't leave `ffmpeg`/`yt-dlp` running.
- **Single server:** run exactly one uvicorn worker. Multiple workers on Android waste battery and complicate event fan‑out.

---

## 3. Prompt Specificity & LLM Generation Limits

### 3.1 Is too much being asked in one shot?

**Yes.** The prompt asks for, in one generation:
- Full Jetpack Compose UI (3 screens + dialogs + animations)
- Material 3 theme + hand‑built glassmorphism
- Room + DataStore persistence
- MVVM + StateFlow
- Retrofit/Ktor + streaming
- Chat UI with markdown + syntax highlighting + copy
- Custom tool builder CRUD
- Networking + offline fallback + retry

That is roughly 30–50 source files and several thousand lines of Kotlin. Even a capable model will (a) run out of output budget, (b) emit `// TODO: implement here`, (c) produce two or three different inconsistent data models (e.g., Room entity vs. server DTO), and (d) paste dependencies that don't resolve in the version catalog.

### 3.2 How should it be restructured?

**Rule: one generation step = one testable unit of work, ending with "it compiles."** Keep the *master prompt* as a short, high‑signal contract, then issue per‑module prompts. Each module prompt must include:

- Exact files to create/overwrite.
- No placeholder rule: `"No TODO, no stubs, no pseudo-code. Provide every line."`
- The serializable data shape (JSON sample) — this kills the "two different models" failure.
- Build/test acceptance criteria ("app compiles with `./gradlew assembleDebug`", "Room DAO has tests", "server health null/offline states are covered").

**Suggested generation order (each is a separate prompt/step):**

| Step | Deliverable | Why this order |
|---|---|---|
| 0 | Exact API contract (OpenAPI/JSON + default values) | Everything downstream depends on it |
| 1 | Gradle scaffold + version catalog + `AndroidManifest` + network security config | Fix all dependency/cleartext issues up front |
| 2 | Server (FastAPI) + mock server + `start-server.sh` | Enables end‑to‑end dev before UI exists |
| 3 | Data models (DTOs, Room entities/DAOs, object mapping) | Single source of truth |
| 4 | Network client (Retrofit + OkHttp-SSE + `SocketFactory`/proxy bypass + server health) | Hard, testable, independent of UI |
| 5 | Repository + domain models + autosave/StateFlow | Interface for ViewModel |
| 6 | Theme + design system (colors, pressed/bouncy modifier, status pulse, glass card) | Needed by every screen |
| 7 | Navigation shell + 3 tabs + offline/retry banner | App becomes runnable end‑to‑end |
| 8 | Dashboard feature (download, compress, TTS, system status) | Replaces mock data with engine |
| 9 | Custom tool builder + persistence | Independent CRUD |
| 10 | AI Agent chat (SSE stream, markdown, code highlight, copy) | Most complex; do last |
| 11 | QA pass: empty/error/offline states, configurable baseUrl, battery/wakelock guidance | Production polish |

### 3.3 What to delete from the prompt

- **"provide complete production-ready code"** as a single deliverable — replace with per‑step acceptance criteria.
- **"Retrofit or Ktor"** — pick one. Pick **Retrofit + OkHttp + okhttp-sse**. Ktor is fine, but "or" makes the generated project internally inconsistent.
- **"add a full markdown renderer with syntax highlighting"** in the same prompt as everything else — break it into its own step (Compose Markdown is heavy).
- **"glassmorphic"** without defining how — see section 4.4.
- **"hardware/system status"** without defining source — Termux:API calls (`termux-battery-status`, `termux-torch`, `termux-storage-info`) come from the *server*, not the app; the app just renders server JSON. Your prompt reads as if the Android app queries hardware directly. Give exact endpoints.

---

## 4. Improvements & Alternative Approaches

### 4.1 Make the API contract authoritative

Write the OpenAPI/JSON contract first and pin it in the prompt. Example health and job payloads:

```json
GET /health
{
  "status": "ok",
  "server_version": "0.2.0",
  "python": "3.12.0",
  "uptime_sec": 642,
  "device": {
    "battery": 86,
    "temperature": 38.2,
    "storage_available_bytes": 1200000000,
    "torch": false
  }
}

POST /jobs
{ "tool_id": "yt-dlp", "args": { "url": "https://...", "format": "mp3" } }
→ 202 { "job_id": "abc123", "status": "queued" }

GET /jobs/abc123/events  (SSE)
event: state     data: {"state":"running"}
event: progress  data: {"percent": 12.5, "line":"[download]  12.5% of ..."}
event: line      data: {"stream":"stdout","text":"..."}
event: done      data: {"exit_code":0,"output_path":"/sdcard/.../x.mp3"}
```

This single artifact fixes the worst failure mode: model drift.

### 4.2 Server lives in the same repo, not described loosely

Because Termux runs the server, the repository should contain:
- `server/` — FastAPI app, job manager, `run_command`, SSE routes, file upload/download, `device_status`.
- `server/requirements.txt` / `server/pyproject.toml` (Flask used only as a fallback if you insist, but FastAPI is the recommendation).
- `server/start-server.sh` — starts uvicorn on `0.0.0.0:8080`? **No:** bind `127.0.0.1:8080`, plus `termux-wake-lock`, plus optionally a token write‑out.
- `server/Dockerfile` or a mock `server/mock.py` for desktop/emulator development.

### 4.3 Health / process lifecycle is a first‑class feature

Add to the app:
- `ServerStatus` (UNKNOWN, CHECKING, ONLINE, OFFLINE, RESTARTING, VERSION_MISMATCH).
- A global banner that is *always* visible, not only inside one tab.
- A "how to keep Termux alive" info sheet (Termux:Boot, wake lock, avoid background kill list).
- Server version handshake so UI can warn on incompatible server/API.

### 4.4 Be explicit about "glassmorphism" (Compose cannot cheat here)

There is **no native blur‑behind** in Compose.
- `Modifier.blur(...)` blurs the composable's **own content** relative to its background — not real frosted glass.
- On Android 12+ you can get *actual* system blur‑behind via `WindowManager.LayoutParams` (`setBlurBehindRadius`) + `RenderEffect`, but combining it with Compose `Surface`s is finicky and mostly decorative.
- **Practical recommendation:** implement *simulated* glass — translucent dark `Color` surfaces (e.g., `#B30B0F19`), a 1dp semi‑opaque border, gradient sheen, soft elevation, and subtle shadow. This looks exactly like the reference images, is fast, and works on all API levels. Mention it as "glass‑look" not true background blur, and keep `Modifier.blur` out of hot paths.

### 4.5 Micro-interactions: keep them cheap

Use a single shared `Modifier` (e.g., `bouncyPress()` with `animateFloatAsState` + `spring`, and a `pulse()` status indicator). Always wrap in a design‑system file so the effect is consistent and not reimplemented per screen. Do this **before** building screens — otherwise each screen gets its own slightly different animation and the app feels inconsistent.

### 4.6 File input strategy (recap the decision)

Make "what happens to a file" part of the contract:
- **Text/URL/path tools** → no permission, just send the string (default for `yt-dlp`, `edge-tts`, `apktool` when user types a path).
- **Picker tools** → SAF picker → stream bytes to server → server returns output URL → app saves via SAF/MediaStore.
- **Manual path override** → expert users can type `/sdcard/...` directly; server owns the source of truth.
- If you choose `MANAGE_EXTERNAL_STORAGE`, document that it's for personal sideload builds and won't pass Play review without a strong use case.

### 4.7 Security posture for a local server

- Keep it bound to `127.0.0.1` only. Never `0.0.0.0` on a phone with ad‑hoc network access.
- Optional shared bearer token: server reads a token file in Termux; app supplies it via header. Good if you ever expose over Bluetooth/USB/ADB forwarding.
- Size/type limits on uploads, `shlex`‑based arg building (never shell‑interpolate user strings), and an explicit blocklist if you want to be safe.

### 4.8 Testing & observability

- Local **mock server** so the app is testable on a desktop/emulator with zero Termux.
- Injectable `Clock`, `IdlingResource`/`ComposeTestRule` for tests; server health and job events are the two most important test seams.
- Keep the `baseUrl` injectable (constructor/DI), not hardcoded in `Retrofit` at compose time.
- Use `coil`/`pdf`/rich text carefully; a full chat markdown renderer is a dependency decision, not an afterthought.

---

## 5. Concrete rewrite of the Master Prompt (short, contract-first)

> **Project:** Termux CommandDeck — a Jetpack Compose + Material 3 Android client for a local Termux FastAPI server on `http://127.0.0.1:8080`.
>
> **Rules for all steps:** Kotlin, Compose, Material 3, MVVM, StateFlow, Retrofit + OkHttp + okhttp-sse. No TODOs, no stubs, no pseudocode — every file complete. Build must succeed (`./gradlew assembleDebug`). `baseUrl` and cleartext/loopback config are set in `network_security_config.xml` and a `BuildConfig` field.
>
> **Architecture:**
> - **Control plane:** Retrofit/REST for `GET /health`, `GET /tools`, `POST /jobs`, `GET /jobs/{id}`, `POST /files/upload`, `GET /files/{job_id}/output`, `DELETE /jobs/{id}`.
> - **Event plane:** SSE for `GET /jobs/{id}/events` and `POST /chat`; use `EventSource` via `okhttp-sse`. Long commands are asynchronous server jobs; the frontend never blocks on a command.
> - **Server contract:** job events carry `state`, `progress`, `line`, `done`; the app must render all four.
> - **File model:** `Text/URL` inputs send strings; `File` inputs use SAF and stream bytes to the server; never pass `content://` URIs to Termux.
> - **Server status:** `ServerStatus` state machine (UNKNOWN/CHECKING/ONLINE/OFFLINE/RESTARTING/VERSION_MISMATCH), a global offline banner, and Retry with Termux keep‑alive guidance.
>
> **UI:**
> - Theme `#0B0F19`, cyan `#06B6D4`, purple `#A855F7`, amber `#F59E0B`, emerald `#10B981`.
> - Glass look = translucent surfaces + borders + sheen + `bouncyPress()`/`pulse()` design‑system modifiers. Avoid `Modifier.blur` for frosted‑glass-as-background.
> - 3 screens: Dashboard (media download, compressor, TTS, system status), AI Agent chat, Custom Actions.
> - Custom Action CRUD persisted in Room/DataStore; fields = name, accent color, icon, command template, input type (None/Text/URL/File path); supports `{input}` and `{output}` placeholders.
>
> **Deliverable for this step:** *(from section 3.2 — one step at a time, e.g. "Gradle scaffold + manifest + network security config", then "API contract + FastAPI mock server", etc.)*

---

## 6. Summary of required corrections

| Area | Current prompt | Required correction |
|---|---|---|
| Cleartext / loopback | Implied only | Explicit `INTERNET`, `network_security_config`, `127.0.0.1` not `localhost` |
| Server reliability | Assumed alive | Termux wakelock/Boot guidance + global health state machine + retry |
| File transfer | "file path / picker" | Define SAF streaming vs. typed-path; no `content://` to Termux |
| Progress / chat | Retrofit POST/GET | REST control plane + SSE/`EventSource` event plane |
| Long commands | Flask default | FastAPI + uvicorn + async subprocess + job queue |
| Prompt size | Single mega‑shot | Contract first, then 10–12 modular generation steps |
| Glassmorphism | Undefined look | Simulated glass design system; no naive `Modifier.blur` |
| Hardware status | "from app" | Via Termux server endpoints (`termux-*`), not the app |
| Dependencies | "Retrofit or Ktor" | Pick Retrofit + OkHttp + okhttp-sse |

The result is a cleanly separable client/server architecture where the hard parts (streaming, background persistence, scoped storage, Termux keep‑alive) are solved before any UI is generated, and each AI generation step is small enough to compile and verify.
