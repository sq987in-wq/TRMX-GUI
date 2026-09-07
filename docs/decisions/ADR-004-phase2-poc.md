# ADR-004 — Phase 2 bridge PoC: scope, deviations, and harness lessons

- **Status:** Accepted (2026-09-07, Phase 2)
- **Related:** ADR-001, ADR-002, [PROTOCOL.md](../PROTOCOL.md)

## Delivered

`termux/trmx-bridge.py` v0.2.0 (single file, Python 3 stdlib only) + `termux/trmx` control CLI
+ `termux/tests/` (19 tests + human-visible smoke demo). Verified in a clean Linux sandbox:
19/19 tests across 3 consecutive runs; smoke demo passes end-to-end (pair → start → info →
submit → live stream → cancel → SIGKILL crash → restart → re-adopt → cancel → stop).

## Protocol subset implemented (additive backlog, no contract changes)

Implemented: §2 (system/info, system/policy), §3 (argv jobs, list/get, cancel, timeout,
queueing, idempotency replay), §4 (output streams with replay-from-seq, evicted notices,
reattached notices), §5 (events stream, live only), §8 (bridge stop/restart/reload).

Deferred to later phases (each already specified in PROTOCOL.md): tools & file endpoints
(Phases 8–9), tool-schema argv synthesis, `progress_regex`, `Last-Event-ID` replay,
per-source auth backoff (per-connection only today), request rate limiting (Phase 10).

## Dev-mode leniency (non-Termux hosts only)

On Termux the bridge is strict: executables must resolve in `$PREFIX/bin` or `~/.trmx/bin`,
and `cwd` must lie inside policy roots. On ordinary Linux (CI/sandbox) it allows PATH lookup
and any existing `cwd` so the suite can drive it with `sh`/`sleep`/`seq`. The leniency is
detected via `is_termux()` and documented in the module header; Phase 10 hardening will gate
it behind an explicit `--dev` flag instead of host detection.

## Conventions decided during implementation

- The synthetic `evicted` info frame carries `seq = min_seq - 1` (the boundary position),
  consistent with §4.1's info payload shape.
- `from_seq=0` is normalized to `start_seq=1` before eviction checks (asking "from the
  beginning" is not a gap when nothing was evicted).

## Test-harness lessons (recorded so we never relearn them)

1. **unittest creates one instance per test method** — storing harness state as `self.x`
   in one test leaks nothing to the next test; the restart tests must assign the class
   attribute (`TestBridge.bridge = …`). An instance-attribute bug produced a "phantom
   bridge": `kill9()` no-opped on a stale handle while the real bridge kept serving the
   port, and the next start attempt failed with EADDRINUSE.
2. **Never trust "port answers" as proof a spawned server is yours** — the harness now
   verifies `~/.trmx/bridge.pid` equals the spawned child's PID, and verifies the port
   closes after kills. Silent wrong-process kills are now loud failures.
3. **`_wait_exit` must drain the output pumps before finalizing** — otherwise the terminal
   status frame can precede buffered stdout frames (ordering guarantee of §4.1).
4. `pkill -f` patterns must not appear literally in the calling command line (self-kill);
   use bracket-trick patterns or kill by explicit PID.
