# ADR-005 — Bootstrap Automation (Phase 3)

- **Status:** Accepted (Phase 3)
- **Date:** 2026-09-07
- **Supersedes:** none — builds on ADR-001 §"two planes" and the Phase 0 report §B.5
- **Related:** [docs/CONTROL-PLANE.md](../CONTROL-PLANE.md) (the frozen intent interface), ADR-002 (TRMX-P/1), ADR-004 (bridge PoC)

## Context

Phase 2 delivered the bridge, but "the backend exists" still meant a human
pasting commands into Termux. Phase 3's goal: reduce the entire onboarding to
operations the Android app can trigger through Termux's `RUN_COMMAND` intent
API — no user typing, no shell strings, no root, nothing outside the
Termux home directory (the Phase 0 "maximum practical control boundary").

The three user consents that **cannot and must not** be automated (Termux's
security model, by design): `allow-external-apps=true`, the
`com.termux.permission.RUN_COMMAND` grant, and having opened Termux once.
Everything after those is fair game.

## Decision

### 1. One installer script, two modes

`termux/install.sh` (POSIX sh, curl + sha256sum only):

- **remote mode** (default): fetch `trmx-bridge.py`, `trmx`, `SHA256SUMS`
  from `--base URL` (default: GitHub raw, `main` branch).
- **`--source DIR`**: install from a local checkout — used by the test suite
  and by power users. The two modes share every other line: same staging,
  same verification, same atomic install. Only the *transport* differs.

### 2. Verification and trust-anchor honesty

- Files are staged in `~/.trmx/.staging.XXXXXX`, verified with
  `sha256sum -c` against `SHA256SUMS`, and only then `mv`-ed into place —
  a failure can never leave a half-written `trmx-bridge.py` behind.
- **Honest limitation, stated here so nobody over-trusts it:** SHA256SUMS
  fetched from the same base as the files protects the *download path*
  (corruption, partial mirrors, CDN accidents). It does **not** protect
  against compromise of the base itself, because an attacker who can replace
  the files can replace the sums. The real release-grade anchor is the
  Android app **embedding the pinned digest** of what it asks Termux to
  install (Phase 4: the app passes `--sha256` pointing at its own pinned
  copy, or verifies the manifest afterwards via the data plane). This is the
  same trust posture as `curl | sh` installers in general; we say so instead
  of hiding it.
- Missing `SHA256SUMS` is **not** a silent pass: the installer proceeds only
  after printing an explicit `UNVERIFIED` warning (dev convenience, loudly
  labeled), and the app never uses that path.

### 3. Idempotency, upgrades, and the running-bridge problem

- Re-running the installer is safe: one `.old` backup per file, manifest
  (`installed.json`) rewritten with fresh hashes + timestamp.
- **Upgrading a running bridge:** the installer detects the running state,
  calls `trmx stop` (graceful API stop first), replaces files, and restarts
  via `trmx start` — so the app's "update backend" button is a single
  `INSTALL` intent. If the bridge wasn't running, it stays stopped.

### 4. Autostart: two optional mechanisms, user's choice

| | `trmx enable-boot` | `trmx enable-service` |
|---|---|---|
| Mechanism | Termux:Boot app runs `~/.termux/boot/trmx-bridge` after reboot | runit service via `termux-services` package |
| Gives | start-at-boot | start-at-boot **+ crash auto-restart** |
| Costs | extra app install (Termux:Boot) | extra package (`pkg install termux-services`) |
| Default | off | off |

Rationale: neither is mandatory; the app's wizard offers both as opt-ins and
explains the trade-off. V1 does not silently install either. The generated
scripts are tiny, path-explicit (resolved `TRMX_HOME`, absolute `$PREFIX`
shebang), and `sh -n`-clean.

### 5. Control-plane freeze: `docs/CONTROL-PLANE.md`

The eight intent operations (`INSTALL_PY`, `INSTALL`, `PAIR`, `START`,
`STOP`, `STATUS`, `ENABLE_BOOT`, `ENABLE_SERVICE`), their argv, the
precondition table, the first-run/warm-start/upgrade sequences, and the
error-state mapping are **frozen** — Phase 4's `IntentControlPlane` implements
that document as-is. `termux/tests/intent_commands.sh` prints the exact
`adb shell am start startservice …` commands for all eight, so the control
plane can be proven on a real device **before** any app code exists.

## Testing (sandbox; on-device deferred to Phase 4 as agreed in ADR-004)

`termux/tests/test_bootstrap.py` — 12 tests, all against throwaway
`HOME`/`TRMX_HOME`/`TRMX_PREFIX`/`TRMX_BOOT_DIR`:

- fresh install: layout, modes, manifest hashes match on-disk reality
- tampered source (**both** `--source` and remote-via-`file://`): loud
  refusal, zero partial state, no staging leaks
- reinstall idempotency + `.old` backups
- **live upgrade path**: bridge running → installer stops, replaces,
  restarts; new PID, port re-opens, authenticated `/v1/system/info`
  handshake still answers with the right bridge version
- missing sums → explicit UNVERIFIED warning
- `trmx version`, `enable-boot` (script content + mode + `sh -n`),
  `enable-service` (guidance when `termux-services` absent; run script when
  present)
- `intent_commands.sh`: all 8 ops, correct component/action/extras, argv
  sanity (START is exactly `["start"]`), token only ever in PAIR

Result: **12/12 passing, repeated runs, zero leaked processes/ports.**
Remote mode is exercised network-free via `curl file://` — same code path,
same verification, no network dependency in CI.

## Consequences

- The Phase 4 wizard is now fully specified: three consent screens + the
  CONTROL-PLANE.md sequence table.
- `gen_checksums.sh` **must** be run whenever `trmx-bridge.py`/`trmx` change
  (release tagging will regenerate it; CI should verify it — Phase 10).
- The default remote base points at `main`; until Phase 3 is merged to
  `main`, remote installs must pass `--base <branch-or-tag URL>`.
- Deferred: app-side pinned digests (Phase 4), CI job that fails if
  `SHA256SUMS` is stale (Phase 10).
