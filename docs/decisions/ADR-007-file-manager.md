# ADR-007 — File Manager (Phase 7)

- **Status:** Accepted (Phase 7)
- **Date:** 2026-09-08
- **Related:** [PROTOCOL.md §6](../PROTOCOL.md) (frozen in Phase 1), ADR-002, ADR-004

## Context

The file endpoints were specified in Phase 1 but never implemented (ADR-004
deferred them). Phase 7 implements **both sides**: the bridge endpoints
(trmx-bridge v0.3.0) and the app's file browser. This is the most
security-sensitive surface so far: the Phase 0 threat model makes the path
policy the load-bearing wall.

## Bridge (trmx-bridge v0.3.0)

### Path policy — realpath for containment, normalized path for the operation

§6.1 says a path is allowed iff `realpath(path)` lies under a resolved root.
The subtle part (found by the test suite, not by review): if the handler also
*acts* on the realpath, **deleting a symlink deletes its target** — the
Phase 7 test suite caught exactly this (a symlink-delete test silently
destroyed the fixture file the next test needed). The implementation now:

- validates containment with `realpath` (blocks `~/../../etc` and links
  pointing outside the roots), but
- performs the operation on the *normalized, unresolved* path, so deleting a
  symlink removes the link, never the target.

Other §6.1 properties: roots are `[~ , ~/storage]` (realpath-resolved per
request — same effect as "at policy load", tolerates dirs appearing later);
`$PREFIX` is never writable; not-yet-existing paths (mkdir/touch/upload
destination) validate through the PARENT's realpath.

### Endpoints

- `GET /v1/files` — list (page 1000 + `next_offset`) and `stat=1`
  (implementation shape: `{"path": …, "entry": …}` — the spec fixes the
  semantics, this ADR fixes the envelope). `mtime` is second-precision ISO
  (matching the normative fixture, not §1.2's millisecond convention).
- `POST /v1/files` — mkdir / touch / rename / move / copy / delete with the
  §6.3 interlock: recursive delete requires `confirm:true` → else
  `CONFIRM_REQUIRED`; non-recursive delete of a non-empty dir →
  `PATH_NOT_EMPTY` (**409** — the §10 registry overrides the §6.3 prose;
  the registry check caught my first implementation using 400).
- `GET /v1/files/content` — download, single `Range` (206/416; the first CI
  run of Phase 6's sibling taught us `HTTP_REASONS` needed a 206 entry —
  a missing reason crashed the connection task).
- `PUT /v1/files/content` — streaming upload: the request reader carves
  this route out of the buffered-body path (2 GiB cap), streams to
  `.trmx-upload-*` in the destination directory, fsyncs, verifies optional
  `X-TRMX-Sha256` (mismatch → 422, temp deleted), then `os.replace()`.
  Validation errors answer BEFORE the body is read and force-close the
  connection (an unread body would poison keep-alive). `Expect:
  100-continue` is answered by the upload path only after validation.

Honest limitations (documented, not hidden): TOCTOU races between check and
op are possible (single local client, acceptable in v1); copy follows
symlinks (copies target content).

### Tests

`termux/tests/test_files.py` — 17 tests against a real bridge: traversal
denied, list shapes vs fixture, stat, paging at 1000, all six ops, the
confirm interlock, ranges (206/416/Content-Range), upload atomicity +
checksum mismatch + 411. Full Termux suite: 19 + 17 + 13 = **49/49**.

## App

- `BridgeClient`: `listFiles`/`statFile`/`fileOp`/`downloadFile` (streamed,
  Content-Length verified, truncation deletes the partial file)/`uploadFile`
  (streaming from a file, `X-TRMX-Sha256` computed, `overwrite` param).
- UI (`FilesScreen`): browse/navigate/up, mkdir, rename, delete (dir
  deletes explain recursiveness + irreversibility in the confirm dialog),
  download into the app's external documents dir (visible to any file
  manager under `Android/data/dev.trmx.gui/files/`), upload via the system
  document picker. Open/share intents via FileProvider: Phase 8 polish.
- Wire tests against MockWebServer with byte-identical fixtures (the
  delete-op request body is asserted JSON-equal to the normative fixture).

## Consequences

- The bridge now covers PROTOCOL §6 in full; remaining protocol gaps are
  §7 (tools, Phase 9) and §5 auth-backoff hardening (Phase 10).
- `gen_checksums.sh` re-run (bridge v0.3.0) — the installer's SHA256 gate
  stays honest; on-device updates must run `install.sh` again (the app's
  INSTALL op does exactly that).
- File operations are auditable (`file.upload` audit rows now exist).
