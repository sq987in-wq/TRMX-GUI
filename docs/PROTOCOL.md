# TRMX Protocol — Version 1 (TRMX-P/1)

| | |
|---|---|
| **Status** | **DRAFT — Phase 1, awaiting stakeholder review** |
| **Replaces** | the illustrative sketch in [PHASE-0-DISCOVERY-REPORT.md](PHASE-0-DISCOVERY-REPORT.md) §C.4 |
| **Governs** | every byte exchanged between the Android app (`com.trmx.gui`) and the Termux bridge (`trmx-bridge`) |
| **Rule zero** | If it is not in this document, it does not exist. Every Phase-2+ test on either side must reference a section here. |

---

## Table of Contents

1. [Conventions](#1-conventions)
2. [System endpoints](#2-system-endpoints)
3. [Jobs](#3-jobs)
4. [Output streaming](#4-output-streaming)
5. [Global event stream](#5-global-event-stream)
6. [Files](#6-files)
7. [Tools & the Tool Registry schema](#7-tools--the-tool-registry-schema)
8. [Bridge control](#8-bridge-control)
9. [Security & behavioral requirements](#9-security--behavioral-requirements)
10. [Error model & code registry](#10-error-model--code-registry)
11. [SSE framing rules](#11-sse-framing-rules)
12. [Limits summary](#12-limits-summary)
13. [Versioning & governance](#13-versioning--governance)
14. [Fixture index](#14-fixture-index)

---

## 1. Conventions

### 1.1 Transport
- HTTP/1.1 over TCP, **bound to `127.0.0.1` only** (never `0.0.0.0` in v1). Default port **`27342`**, configurable in `~/.trmx/bridge.json` and overridable per-client.
- Keep-alive encouraged. The app SHOULD use at most: 1 global event stream + up to 8 job output streams + short-lived request calls; **max 12 concurrent connections per client** (enforced, §9).
- The bridge MUST NOT emit CORS headers and MUST reject any request carrying an `Origin` header with `403 ORIGIN_DENIED` (defense against browser-based attacks from web pages open on the phone).

### 1.2 Encoding
- All request/response bodies are UTF-8 JSON, `Content-Type: application/json`, except: streams (`text/event-stream`, §11) and file content (`application/octet-stream`, §6).
- JSON request bodies are capped at **1 MiB** (uploads excepted, §6.5).
- Timestamps are ISO-8601 UTC strings with milliseconds: `"2026-09-07T13:47:02.481Z"`.
- Unknown fields in received objects MUST be ignored (forward compatibility, §13).

### 1.3 Authentication
- Every request MUST carry: `Authorization: Bearer <token>` **and** `X-TRMX-Protocol: 1`.
- The token is 256-bit, URL-safe base64 (43 chars), generated at pairing (report §B.8).
- The bridge compares in constant time. On failure: `401` with `AUTH_INVALID`, audit-logged (§9). Missing header: `401 AUTH_REQUIRED`.
- Wrong/unsupported protocol major in `X-TRMX-Protocol`: `400 PROTOCOL_MISMATCH` with `details.supported: [1]`.
- Failed-auth backoff: after **5 consecutive failures**, the offending connection is closed and further connections from that source are refused for `min(2^(n-5) × 2s, 60s)` where *n* is the failure count (resets on a successful auth or bridge restart). Guessing is computationally hopeless; this exists to stop busy-looping clients.

### 1.4 Versioning
- Major version lives in the path: `/v1/…`. Within `/v1`, changes are **additive only** (new optional fields, new endpoints). Breaking changes require `/v2/` mounted **side-by-side** with `/v1` for at least one bridge release.
- `GET /v1/system/info` returns `protocol_versions: [1, …]`; the client requires its major to be present.

### 1.5 Identifiers
- `job_id`: `J-` + base-36 monotonic counter, e.g. `J-1A2B`. Unique for the lifetime of `jobs.db`.
- Stream `seq`: a **single monotonic 64-bit counter per job**, incremented for every stream frame (stdout, stderr, status, info — see §4). This is the replay cursor.
- Global `event_id`: monotonic 64-bit counter for the global event stream (§5).

---

## 2. System endpoints

### 2.1 `GET /v1/system/info` — handshake & health
Response `200`:

```json
{
  "bridge_version": "1.0.0",
  "protocol_versions": [1],
  "uptime_s": 3600,
  "now": "2026-09-07T13:47:02.481Z",
  "load": { "jobs_running": 2, "jobs_queued": 0, "loadavg": [0.42, 0.35, 0.31] },
  "memory": { "total_mb": 7420, "free_mb": 2180 },
  "storage": { "home_free_mb": 10240, "shared_available": true },
  "termux": { "home": "/data/data/com.termux/files/home", "prefix": "/data/data/com.termux/files/usr" },
  "caps": { "max_concurrent_jobs": 4, "queue_depth": 32, "log_ring_mb": 2 },
  "features": { "termux_api": true, "runit": true, "scheduler": false },
  "tools_detected": 17
}
```

Errors: standard (§10). This endpoint is the client's health probe: no other call may be made before it on a cold connect.

### 2.2 `GET /v1/system/policy` — effective policy mirror
Response `200`: `{"roots": ["~", "~/storage"], "deny_write": ["$PREFIX"], "log_ring_mb": 2, "max_concurrent_jobs": 4, "queue_depth": 32, "cancel_grace_ms": 5000}`. Read-only view of `~/.trmx/bridge.json` (edited by the user in Termux only; the app never writes policy).

---

## 3. Jobs

### 3.1 The Job object

| Field | Type | Notes |
|---|---|---|
| `job_id` | string | `J-…` (§1.5) |
| `name` | string ≤ 200 chars | display name |
| `type` | `argv` \| `shell` \| `tool` | execution model (§3.2) |
| `tool` | string \| null | set for `type:"tool"` |
| `argv` | string[] \| null | resolved argument array (`argv`/`tool` types) |
| `script` | string \| null | shell source (`shell` type only) |
| `cwd` | string | `~`-prefixed or absolute path within policy roots; default `~` |
| `env` | object \| null | ≤ 32 keys, names matching `^[A-Za-z_][A-Za-z0-9_]*$`, values ≤ 4 KiB |
| `status` | enum | `QUEUED` `STARTING` `RUNNING` `CANCELLING` `COMPLETED` `FAILED` `CANCELLED` `LOST` |
| `created_at` / `started_at` / `ended_at` | ISO-8601 \| null | |
| `pid` / `pgid` | int \| null | process & group ids while alive |
| `exit_code` | int \| null | |
| `signal` | int \| null | signal number if killed |
| `cancel_requested` | bool | |
| `cancel_reason` | `user` \| `timeout` \| null | |
| `error` | `{code, message}` \| null | typed spawn/infra failure (e.g. `ENOENT`) |
| `timeout_s` | int \| null | default null; hard cap 2 592 000 (30 days) |
| `progress_pct` | float 0–100 \| null | parsed via tool `progress_regex` when defined |
| `progress_detail` | string \| null | last matched progress line (trimmed, ≤ 300 chars) |
| `stdout_bytes` / `stderr_bytes` | int | total bytes seen (monotonic) |
| `log_seq` | int | last stream `seq` emitted for this job |
| `log_truncated` | bool | ring cap was hit — oldest log content was evicted |
| `idempotency_key` | string \| null | see §3.3 |

**State semantics (authoritative):**

| State | Meaning |
|---|---|
| `QUEUED` | accepted, waiting for a concurrency slot |
| `STARTING` | dequeued; `exec` is being attempted (spawn failure here → `FAILED` + `error`) |
| `RUNNING` | child process alive (pid known) |
| `CANCELLING` | cancel requested: SIGTERM sent to the job's process group; after `cancel_grace_ms` (default 5000) → SIGKILL |
| `COMPLETED` | exited with code 0 |
| `FAILED` | non-zero exit, fatal signal, or spawn error |
| `CANCELLED` | terminated due to cancellation; `cancel_reason` ∈ `user` \| `timeout` |
| `LOST` | bridge restarted; recorded process group no longer exists and no exit was observed. Honest admission, never guessed |

### 3.2 `POST /v1/jobs` — submit
Request body (one of three execution models):

```json
{ "name": "Download: Cats documentary",
  "type": "argv",
  "argv": ["yt-dlp", "-f", "mp4", "--newline", "https://example.com/v/xyz"],
  "cwd": "~/downloads",
  "env": null,
  "timeout_s": 7200,
  "idempotency_key": "8f14e45f-…-cea4" }
```

- `type:"argv"` — the bridge executes the **first element as an allowlisted executable** (must resolve in `$PREFIX/bin` or `~/.trmx/bin`) and passes the rest as literal arguments. **No shell exists in this path.**
- `type:"tool"` — `"tool": "yt-dlp", "args": { "url": "…", "format": "mp4" }` (object form). The bridge validates args against the tool schema (§7) and **synthesizes the argv** — this is the form the GUI uses.
- `type:"shell"` — `"script": "…"` executed by `$PREFIX/bin/bash -c`. **V1.5, behind a client-side confirmation**; bridge still accepts and audit-logs it distinctly.

Validation & limits: `argv` ≤ 256 elements, each ≤ 8 KiB, total ≤ 64 KiB · `script` ≤ 64 KiB · `cwd` must exist and be a directory inside policy roots (else `PATH_DENIED`/`PATH_NOT_FOUND`) · `env` per §3.1 (reserved keys `TRMX_JOB_ID`, `TRMX_TOKEN` are bridge-injected and rejected if supplied).

Response `201`: `{ "job_id": "J-1A2B", "status": "QUEUED" }` · Header `Location: /v1/jobs/J-1A2B`.

**Idempotency:** if `idempotency_key` was seen within 24 h, the bridge replays the original response (same `job_id`) with header `Idempotent-Replay: true`. The client MUST set a key for every user-initiated submission (protects against retry duplicates after reconnects).

Rejections: `QUEUE_FULL`, `CONCURRENCY_LIMIT` (both `429`, §10), `VALIDATION_FAILED`, `TOOL_UNKNOWN`, `ARG_INVALID`, `PATH_DENIED`, `PATH_NOT_FOUND`.

### 3.3 `GET /v1/jobs` — list/history
Query: `status` (optional, one of the enums), `limit` (1–200, default 50), `before` (cursor = `job_id`, pages newest-first).
Response `200`: `{ "jobs": [Job…], "next_cursor": "J-19Z" | null }`. The bridge's SQLite is the source of truth; clients diff this into their cache.

### 3.4 `GET /v1/jobs/{id}` — one job
Response `200`: the Job object. `404 JOB_NOT_FOUND` otherwise.

### 3.5 `POST /v1/jobs/{id}/cancel` — cancel
Request: `{ "grace_ms": 5000, "force": false }` (both optional; `force:true` skips SIGTERM and SIGKILLs immediately).
Behavior: sends SIGTERM to the job's **process group**; after `grace_ms` (capped 60000) sends SIGKILL. Sets `cancel_requested=true`, state `CANCELLING`.
Response `200`: the Job object. **Idempotent**: cancelling an already-terminal job returns its current state, `200`, no side effects.

## 4. Output streaming

### 4.1 `GET /v1/jobs/{id}/output`
Query params:

| Param | Default | Meaning |
|---|---|---|
| `stream` | `both` | `stdout` \| `stderr` \| `both` |
| `from_seq` | `0` | replay starts **at** this seq (inclusive) |
| `follow` | `1` | keep the stream open after replay until terminal state |
| `tail` | – | if set with `from_seq=0`, start N frames from the end of the ring |

Response `200`, `Content-Type: text/event-stream` (framing §11). Frame event types:

| Event | `data` payload |
|---|---|
| `stdout` | `{ "job_id", "seq", "text" }` |
| `stderr` | `{ "job_id", "seq", "text" }` |
| `status` | `{ "job_id", "seq", "status", "exit_code", "signal", "error", "ended_at", "progress_pct", "progress_detail" }` — emitted on every state change and once, finally, on the terminal state |
| `info` | `{ "job_id", "seq", "type": "evicted" \| "truncating" \| "reattached", "resume_from_seq"? }` |

Rules:
- `text` is UTF-8 with invalid sequences replaced (U+FFFD); the on-disk ring keeps raw bytes; the DB keeps byte counts. Chunks are coalesced to at most one frame per stream per **250 ms**, each frame's `data.text` ≤ 64 KiB (larger bursts are split across frames).
- If `from_seq` precedes evicted ring content: the bridge first emits `info` `{"type":"evicted","resume_from_seq":N}` and continues from `N` — the stream never lies about gaps.
- When the ring cap is hit during a run: `info` `{"type":"truncating"}` once, and `log_truncated=true` on the job.
- After a bridge restart, a job whose process group is confirmed alive is `info` `{"type":"reattached"}` — metrics continue, but **new output is captured only if the process still writes through the bridge's PTY-less pipe**; adopted processes' future stdout cannot be re-attached (documented limitation; the ring retains pre-restart output).
- `follow` streams close right after the terminal `status` frame. Non-follow (`follow=0`) streams end when replay is exhausted.
- Max **8 concurrent follow streams per client** (§9).

## 5. Global event stream

### 5.1 `GET /v1/events`
One long-lived SSE stream the app holds whenever connected. Event types:

| Event | `data` payload |
|---|---|
| `job.updated` | the full Job object |
| `system.updated` | the `system/info` object (throttled to ≤ 1 per 5 s) |
| `bridge.stopping` | `{ "reason": "restart" \| "stop" \| "upgrade", "resume_hint_s": 5 }` |

- SSE `id:` = the global `event_id` (§1.5). On reconnect the client passes it via `Last-Event-ID`; the bridge replays up to **1000** buffered events, then continues live.
- Heartbeat: a `: ping` comment every **15 s** (detects half-dead connections).
- `retry: 3000` is sent once per stream.

## 6. Files

### 6.1 Path policy (bridge-enforced, not optional)
- Allowed roots come from `~/.trmx/bridge.json`, default `["~", "~/storage"]`. Each root is `realpath()`-ed at policy load; a requested path is allowed iff `realpath(path)` lies under a resolved root. This makes `~/storage/downloads/…` (which resolves under `/storage/emulated/0/…`) work while blocking `~/../../etc/passwd`.
- `$PREFIX` (`…/files/usr`) is **never** writable through the API in v1 (deny-write list).
- Violations: `PATH_DENIED` (`403`). Nonexistent: `PATH_NOT_FOUND` (`404`).

### 6.2 `GET /v1/files?path=~&stat=0` — list / stat
Response `200`:
```json
{ "path": "~/downloads", "entries": [
    { "name": "video.mp4", "type": "file", "size": 104857600, "mtime": "2026-09-07T09:12:44Z", "mode": "-rw-r--r--", "target": null },
    { "name": "music", "type": "dir", "size": 4096, "mtime": "2026-09-06T18:02:10Z", "mode": "drwx------", "target": null }
] }
```
`type` ∈ `file|dir|symlink|other`; `target` set for symlinks. Listing pages at **1000 entries** (`?offset=` continues; `next_offset` returned when truncated). `stat=1` returns the entry for the path itself.

### 6.3 `POST /v1/files` — operations
Request: `{ "op": "mkdir"|"touch"|"rename"|"move"|"copy"|"delete", … }`

| op | extra fields | notes |
|---|---|---|
| `mkdir` | `path`, `recursive?` | |
| `touch` | `path` | |
| `rename` | `path`, `new_name` | same directory |
| `move` | `path`, `dest_dir` | cross-dir move |
| `copy` | `path`, `dest_dir` | recursive for dirs |
| `delete` | `path`, `recursive?`, `confirm?` | `recursive:true` on a **directory requires `confirm:true`** or the bridge answers `400 CONFIRM_REQUIRED` — the safety interlock for `rm -rf`-class actions |

Response `200`: `{ "ok": true }` (+ `{ "entries_moved": n }` where meaningful). Errors: `PATH_DENIED`, `PATH_NOT_FOUND`, `PATH_EXISTS`, `PATH_NOT_EMPTY` (non-recursive delete of a non-empty dir).

### 6.4 `GET /v1/files/content?path=~%2Fnotes.txt` — download
`200`, `application/octet-stream`, `Content-Length`, `Accept-Ranges: bytes`. Single `Range: bytes=a-b` supported (`206` with `Content-Range`; bad range → `416`). Large transfers stream chunked; the client SHOULD use ranges for resumable transfers. Directory → `400 NOT_A_FILE`.

### 6.5 `PUT /v1/files/content?path=…&overwrite=0` — upload
Raw body streamed to a temp file **in the destination directory**, fsync, atomic `rename()` (never a partial file). `overwrite=0` + existing path → `409 PATH_EXISTS`. Body size cap **2 GiB**; `Content-Length` required (`411` otherwise). Checksum (optional): `X-TRMX-Sha256: <hex>` — if present the bridge verifies before rename and answers `422 CHECKSUM_MISMATCH` on failure, deleting the temp.

## 7. Tools & the Tool Registry schema

### 7.1 `GET /v1/tools` · `GET /v1/tools/{id}` · `POST /v1/tools/refresh`
Response `200` (`list`): `{ "tools": [ToolStatus…] }` where `ToolStatus = { "schema": ToolSchema, "installed": true, "version": "2026.01.01" }`. `refresh` re-probes availability (`command -v` + `--version` capture, 10 s timeout each) and returns the list.

### 7.2 ToolSchema — the format the GUI is generated from

```json
{
  "id": "yt-dlp",
  "name": "Video Downloader (yt-dlp)",
  "description": "Download videos from thousands of sites.",
  "binary": "yt-dlp",
  "risk_tier": "safe",
  "progress_regex": "\\[download\\]\\s+(\\d{1,3}(?:\\.\\d+)?)%",
  "fixed_argv": ["--newline"],
  "args": [
    { "name": "url",    "label": "Video URL",  "type": "url",   "required": true,
      "help": "The page or direct video URL." },
    { "name": "format", "label": "Quality",    "type": "enum",  "required": false,
      "enum": ["mp4", "mkv", "best"], "default": "mp4", "argv": ["-f", "{value}"] },
    { "name": "audio",  "label": "Audio only", "type": "bool",  "default": false, "argv": ["-x"] },
    { "name": "rate",   "label": "Rate limit", "type": "string", "required": false,
      "pattern": "^\\d+[KM]$", "argv": ["-r", "{value}"] },
    { "name": "outdir", "label": "Output folder", "type": "path", "path_kind": "dir",
      "default": "~/downloads", "argv": ["-P", "{value}"], "mkdir": true }
  ],
  "examples": [ { "label": "MP4, best quality", "args": { "url": "https://…", "format": "mp4" } } ]
}
```

Rules (bridge-enforced at submit for `type:"tool"`):
- Final argv = `[binary] + fixed_argv + (for each arg present, in schema order: its `argv` tokens)`. Required args must be present. `bool` args contribute their tokens iff true (and may not contain `{value}`). Non-bool `argv` must contain `{value}` exactly once.
- Arg types: `string` · `int` (with optional `min`/`max`) · `float` (`min`/`max`) · `bool` · `enum` · `path` (with `path_kind`: `file`|`dir`; validated against §6.1 policy — `write` semantics for `file` paths not under a writable root are rejected) · `url` (must parse as `http(s)://`).
- `mkdir: true` (dir args only, bridge ≥ 0.4.2): the directory is CREATED at submit if missing — parents too (`mkdir -p` semantics) — instead of `404 PATH_NOT_FOUND`. Resolution uses **write** semantics, so creation is confined to the §6.1 writable roots. For *output* folders (yt-dlp `-P`, aria2c `-d`); input folders (e.g. http-server's serve dir) keep the existence check. Schemas with `mkdir` on a non-dir arg are rejected as malformed.
- `pattern` (RE2-safe regex) applies to `string`/`url` pre-validated strings.
- `progress_regex`: first capture group parsed as the percent for `progress_pct`; the matched line populates `progress_detail`. Absent → both null.
- `risk_tier` ∈ `safe|confirm|destructive` — the *app* renders confirmation UX per tier; the bridge audit-logs the tier with the job. Tiers do not replace path/argv validation.
- Schemas merge in precedence order: user schemas (`~/.trmx/tools/*.json`, alphabetical) **override** bundled schemas with the same `id`. Malformed user schemas are skipped and reported in `system/info.features.tool_schema_errors`.

## 8. Bridge control

### 8.1 `POST /v1/system/bridge`
Request: `{ "action": "stop" | "restart" | "reload_policy" }`.
Response `200 { "ok": true }`; for `stop`/`restart` the bridge broadcasts `bridge.stopping`, waits ≤ 2 s for stream close, then exits (restart is `stop` + `exec` self under runit, or exit code 42 otherwise — runit restarts it).
This endpoint complements the intent control plane (report §B.3): intents remain the only way to start the bridge from cold.

## 9. Security & behavioral requirements

The bridge implementation MUST (verifiable by §J of the Phase 0 report):
1. Bind loopback only; refuse to start if the configured port is taken (`PORT_SQUATTED` is a **client-side** state after a failed start-intent; the bridge never silently picks another port).
2. Constant-time token compare; rate-limited auth failures (§1.3); every 401/403 audit-logged with timestamp and available peer info (loopback → PID of the connecting app is not reliably available; log attempt counts).
3. Never log, echo, or expose the token after pairing (masked `trmx token` output shows 4 leading chars only).
4. Enforce path policy on every file/job `cwd`/`path`-typed access (§6.1).
5. Execute `argv`/`tool` jobs via `execve` semantics — never through a shell. The `shell` type is the only shell path and is audit-logged with the full script.
6. Enforce all limits of §12; answer with typed errors, never crash on malformed input (fuzzed in Phase 11).
7. Write SQLite in WAL mode; reconcile jobs on startup (`LOST` per §3.1).
8. Kill jobs by **process group** on cancel/timeout.
9. Keep an append-only `audit` table: every auth failure, every `shell` job, every tier-`confirm`/`destructive` job, every policy override event.

## 10. Error model & code registry

Every non-2xx JSON body: `{ "error": { "code": "MACHINE_CODE", "message": "human text", "field": "argv[2]" | null, "details": { … } } }`

| HTTP | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | malformed body/params (`field` set) |
| 400 | `PROTOCOL_MISMATCH` | unsupported `X-TRMX-Protocol` major; `details.supported` lists majors |
| 400 | `TOOL_UNKNOWN` | submit with unknown tool id |
| 400 | `ARG_INVALID` | tool-arg fails schema (type/enum/pattern/min/max/path) |
| 400 | `CONFIRM_REQUIRED` | recursive delete without `confirm:true` |
| 400 | `NOT_A_FILE` / `NOT_A_DIRECTORY` | file op on wrong entry type |
| 400 | `CHECKSUM_MISMATCH` | upload `X-TRMX-Sha256` mismatch (422 may be used) |
| 401 | `AUTH_REQUIRED` / `AUTH_INVALID` | missing / wrong token |
| 403 | `PATH_DENIED` | outside policy roots / deny-write list |
| 403 | `ORIGIN_DENIED` | request carried an `Origin` header |
| 404 | `NOT_FOUND` | unknown route |
| 404 | `JOB_NOT_FOUND` / `TOOL_NOT_FOUND` / `PATH_NOT_FOUND` | missing resource |
| 405 | `METHOD_NOT_ALLOWED` | wrong verb on a known route |
| 409 | `PATH_EXISTS` | create/move onto existing path (when not overwriting) |
| 409 | `PATH_NOT_EMPTY` | non-recursive delete of non-empty dir |
| 411 | `LENGTH_REQUIRED` | upload without `Content-Length` |
| 413 | `PAYLOAD_TOO_LARGE` | JSON body > 1 MiB; upload > 2 GiB |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | JSON expected, not received |
| 416 | `RANGE_NOT_SATISFIABLE` | bad file `Range` |
| 429 | `RATE_LIMITED` | > 120 requests / 10 s per connection (`Retry-After`) |
| 429 | `QUEUE_FULL` | queue depth exceeded |
| 429 | `CONCURRENCY_LIMIT` | `max_concurrent_jobs` reached at a no-queue moment (policy `queue=0`) |
| 500 | `INTERNAL_ERROR` | unexpected — bridge must survive it |
| 503 | `BRIDGE_SHUTTING_DOWN` | during `stop`/`restart` drain |

## 11. SSE framing rules

```
retry: 3000

event: stdout
id: 4821
data: {"job_id":"J-1042","seq":4821,"text":"[download]  42.1% of ~123.45MiB"}

: ping
```
- Frames follow SSE: `event:`, `id:`, `data:` (single-line JSON), blank-line terminator; `: ping` heartbeats carry no event name.
- For job streams, `id` = job-local `seq` (§1.5); for the global stream, `id` = `event_id`.
- Clients MUST tolerate blank `data`, comment lines, and unknown event names (forward compatibility).

## 12. Limits summary (defaults; `bridge.json` overrides where marked)

| Limit | Default | Override |
|---|---|---|
| Concurrent running jobs | 4 | ✅ policy |
| Queue depth | 32 | ✅ policy |
| Log ring per stream (stdout, stderr) | 2 MiB | ✅ policy |
| Frame coalescing window | 250 ms | – |
| Max frame `text` | 64 KiB | – |
| JSON body | 1 MiB | – |
| Upload body | 2 GiB | – |
| Listing page | 1000 entries | – |
| `argv` | 256 × 8 KiB, 64 KiB total | – |
| `script` | 64 KiB | – |
| `timeout_s` | null; cap 30 days | – |
| Cancel grace | 5000 ms (cap 60 000) | per-request |
| Follow streams / client | 8 | – |
| Connections / client | 12 | – |
| Requests | 120 per 10 s per connection | – |
| Global event replay buffer | 1000 events | – |
| Auth failures before backoff | 5 | – |

## 13. Versioning & governance

- This document is the single source of truth for TRMX-P/1. Changes require: (a) an ADR in `docs/decisions/`, (b) updated fixtures (§15), (c) a `CHANGELOG.md` entry — in that order, in the same commit.
- **Current minor: 1.1** (§14, services — bridge ≥ 0.5.0). The request header stays `X-TRMX-Protocol: 1`; minors are additive and capability-gated via `system/info.features`.
- Additive changes (new optional fields, new endpoints, new error codes, new event names) bump the **protocol minor** (`1.x`) and MUST be ignored-unknown-field-safe by older clients.
- Breaking changes (field removal/retyping, semantic changes) open `/v2/`, mounted beside `/v1` for ≥ 1 bridge release; the app supports both during the migration window.
- Both test suites (Kotlin app, Python bridge) validate against the same fixtures — drift is a CI failure, not a runtime surprise.

## 14. Services (protocol 1.1, additive — bridge ≥ 0.5.0)

Capability flag: `system/info.features.service_registry` = `true`. Clients MUST check it before offering service UI; older bridges 404 these routes. Malformed definition files are skipped and reported via `features.service_errors` (same pattern as `tool_schema_errors`).

A service is a **persistent definition that runs a registry tool (§7) with fixed args**. There is no second execution path: `start` submits a normal `type:"tool"` job through the full §7.2 synthesis/validation and binds it; lifecycle = job lifecycle.

Definition (`~/.trmx/services/<id>.json`, written by the bridge):

```json
{ "id": "web-server", "name": "Local HTTP Server", "tool": "http-server",
  "args": { "port": 8000, "dir": "~" }, "autostart": false,
  "created_at": "2026-09-11T10:00:00Z" }
```

`id` matches `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`; `name` is 1..80 chars; `tool` must exist (`TOOL_UNKNOWN` otherwise); `args` must pass §7.2 synthesis (`ARG_INVALID` otherwise); `autostart` is boolean.

| Route | Semantics |
|---|---|
| `GET /v1/services` | list of status elements (below) |
| `POST /v1/services` | create/replace a definition → `201` + status. Replace is refused while running (`409 SERVICE_RUNNING`) |
| `GET /v1/services/{id}` | one status element (`404 SERVICE_NOT_FOUND`) |
| `DELETE /v1/services/{id}` | remove the definition (refused while running); response `{"ok": true, "id": …}` |
| `POST /v1/services/{id}/start` | submit the bound tool job → `200` + status (`409 SERVICE_RUNNING` if active) |
| `POST /v1/services/{id}/stop` | cancel the bound job — **idempotent**: `200` + status even when already stopped |
| `POST /v1/services/{id}/restart` | stop, wait ≤ 30 s for terminal, start → `200` + status |
| `POST /v1/services/{id}/autostart` | body `{"enabled": boolean}` — persist the flag → `200` + status |

Status element = definition fields plus:

| Field | Meaning |
|---|---|
| `state` | `"running"` (bound job in an active state) \| `"stopped"` |
| `job_id` | the active job while running, else `null` |
| `last_job_id` | last job started for this service (binding survives restarts of the service) |
| `last_status` / `last_exit_code` | terminal status of that job (`COMPLETED`/`FAILED`/`CANCELLED`/`LOST`) |

New error codes: `SERVICE_NOT_FOUND` (404) · `SERVICE_RUNNING` (409). Definition problems reuse `VALIDATION_FAILED` / `TOOL_UNKNOWN` / `ARG_INVALID`.

Events: `service.updated` carries the full status object on create/replace/start/stop/restart/autostart, and `{"id": …, "deleted": true}` on delete. Job-driven changes arrive as ordinary `job.updated` — clients correlate via `job_id`.

Autostart: after boot reconciliation (§3.6), definitions with `autostart: true` are started best-effort; a failure is audited and skipped, never fatal to the bridge.

## 15. Fixture index

Shared contract fixtures live in [`fixtures/v1/`](../fixtures/v1/) and are normative:

| Fixture | Covers |
|---|---|
| `system.info.response.json` | §2.1 |
| `system.policy.response.json` | §2.2 |
| `jobs.submit.request.argv.json` · `jobs.submit.request.tool.json` | §3.2 |
| `jobs.submit.response.json` | §3.2 |
| `jobs.list.response.json` · `jobs.get.response.json` | §3.3–3.4 |
| `jobs.cancel.request.json` · `jobs.cancel.response.json` | §3.5 |
| `jobs.output.stream.txt` | §4 (stdout/stderr/status/info frames) |
| `events.stream.txt` | §5 |
| `files.list.response.json` | §6.2 |
| `files.ops.request.json` | §6.3 |
| `tool.schema.yt-dlp.json` | §7.2 |
| `services.def.request.json` · `services.list.response.json` | §14 |
| `error.response.json` | §10 |

*End of TRMX-P/1 draft. Reviewing this document is the Phase 1 exit gate; Phase 2 (bridge PoC) starts only after sign-off.*

