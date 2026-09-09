# ADR-008 — Open/Share via FileProvider (download-then-open), Phase 8 polish

**Status:** accepted (2026-09-08) · **Phase:** 8 · **Depends on:** ADR-007 (§6 files),
PROTOCOL.md §6.4 (ranged download)

## Context

Phase 7 left files stuck inside Termux storage as far as the rest of the phone
was concerned: the only way out was "Save to app documents"
(`getExternalFilesDir(DIRECTORY_DOCUMENTS)`), which other apps cannot reach on
Android 11+ (scoped storage). The accepted Phase 8 scope: open/share, a real
launcher icon, transfer progress, and state polish — app-only, no bridge
changes.

## Decision

### 1. Open/Share = download-then-open (user-approved option)

Stream the entry from the bridge (`GET /v1/files/content`) into a **single-slot
staging dir** `cacheDir/shared/` — cleared before every open, so the cache holds
at most one staged file — then fire:

- **Open:** `ACTION_VIEW` with `setDataAndType(uri, mime)` +
  `FLAG_GRANT_READ_URI_PERMISSION`
- **Share:** `ACTION_SEND` with `EXTRA_STREAM` + `ClipData` (some viewers read
  only the clip) + the same grant flag

The URI comes from `androidx.core.content.FileProvider` (authority
`dev.trmx.gui.fileprovider`). `res/xml/file_paths.xml` exposes **only**
`<cache-path path="shared/"/>` — never the whole cache dir, never internal
storage. The provider is `exported=false` + `grantUriPermissions=true`:
reachable only via the explicit per-intent grants we hand out.

If no activity can handle the MIME type (`ActivityNotFoundException`), we show
an honest error naming the file and MIME and suggesting *Share* — we never
pretend the file is "openable" and never swallow the failure.

**Rejected / deferred alternatives:**

- *Streaming ContentProvider* proxying the bridge's ranged download: instant
  video open + seek without a full transfer. Rejected for v1 — a second
  provider implementation, cursor + pipe lifecycle complexity, and a memory of
  §6 edge cases (Range parsing) on the app side; benefits only large video.
  Deliberately revisitable later; the wire protocol already supports it
  (`Range` → 206).
- *MediaStore insert*: requires broader permissions, pollutes the system
  provider with what may be private Termux files, and picks the location for us.
- *Legacy shared-storage paths*: blocked by design on Android 11+.

### 2. MIME mapping: our own table

`dev.trmx.gui.files.FileMime` maps ~50 extensions to MIME types instead of
using `android.webkit.MimeTypeMap`, because (a) it is JVM-testable without
android.jar, (b) it is deterministic across OEMs — `MimeTypeMap` regularly
lacks `video/x-matroska`, which this project's own fixtures use — and (c) the
fallback is honest: unknown extension → `application/octet-stream`, and the UI
says so if no viewer is found.

### 3. Progress

`BridgeClient.downloadFile/uploadFile` gained optional `onProgress` callbacks
(64 KiB chunks; download reports `(bytes, totalOrNull)` from `Content-Length`,
upload reports `bytes` with the length known up front). The ViewModel's
`TransferPoster` throttles state updates to ~10 Hz (plus the final value) so
Compose recomposition stays cheap. UI: determinate `LinearProgressIndicator`
when the total is known, indeterminate otherwise, with human-readable byte
counts. No mid-transfer **cancel** in v1 — OkHttp `Call.cancel()` is the
natural follow-up; not promised, not faked.

Upload progress wraps the body in a `ProgressRequestBody` that streams the file
itself (64 KiB reads, `sink.write(buf, 0, n)`) — no double buffering, and the
wire bytes stay exactly what they were (the round-trip test asserts this).

### 4. UX polish

- Long-press opens an **action sheet**: Open / Share / Save / Rename / Delete
  (dirs: Rename / Delete only). The per-row "save" quick-button was folded
  into the sheet; a hint line under the path says "long-press a row for
  actions".
- **Back walks up the directory tree first**, then exits Files to the
  dashboard (`filesUp(): Boolean`).
- Files list is a proper three-state surface: loading (spinner), error with
  **retry** when nothing is listed, empty-folder message otherwise.
- Dashboard: the status dot is honest (`● connected` vs
  `● connection error` when the last load failed); the stale "streaming
  arrives in Phase 6" footer was replaced with what the app actually does.

### 5. Launcher icon

Adaptive icon: vector foreground (terminal prompt `>_` — green chevron, blue
underscore) on dark-slate `#0B1220`, plus a `monochrome` layer for Android 13+
themed icons. minSdk 26 means `mipmap-anydpi-v26` covers every installable
device — no raster PNG sets to maintain.

## Consequences

- The staged file lives in cache until the *next* open/share replaces it
  (single slot). Android may reclaim cache under pressure — fine, it is a
  disposable copy; the Termux original is the source of truth.
- Downloads for *Save* remain permission-free app-external storage; Open/Share
  adds the FileProvider read grant only for the staged copy.
- `FileBrowserState` grows `transfer` + `pendingOpen` (both nullable, cleared
  on completion/error; `resetWizard` resets them with the rest).
- Known limits (documented in the on-device guide): no cancel mid-transfer;
  streaming video still requires the full transfer first.

## Testing

- `FileMimeTest` (4): mapping incl. mkv, case-insensitivity, honest fallback.
- `FilesTest` (7 → 9): download progress monotonic + final sample exact;
  upload progress ends at file size **and** the wire still receives
  byte-identical content (Content-Length + full body compare).
- Conformance suite re-run: PASS. Termux suite 49/49 (bridge untouched —
  regression only).
