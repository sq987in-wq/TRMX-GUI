# ADR-002 — TRMX Protocol v1 (TRMX-P/1) core decisions

- **Status:** Accepted (2026-09-07, Phase 1) — details frozen in [PROTOCOL.md](../PROTOCOL.md)
- **Related:** ADR-001

## Decisions & rationale

| Decision | Rationale |
|---|---|
| Plain HTTP/1.1 + SSE-style chunked streams, **no WebSocket** in v1 | Python stdlib can serve it; cancel is just a POST; loopback latency makes WS's bidirectional win irrelevant; SSE `Last-Event-ID` gives free replay semantics |
| Default port **27342** | Above well-known service ranges, below the ephemeral range (49152+), unlikely to collide; configurable; bridge **fails loud** if taken (never silently re-binds — port-squat must be visible) |
| **Single per-job monotonic `seq`** across stdout/stderr/status/info | one replay cursor per job makes lossless reconnect trivial (`?from_seq=N`) |
| Auth = `Authorization: Bearer <256-bit token>` + `X-TRMX-Protocol: 1` | header-only (no token in URLs/logs); the protocol header doubles as a per-request handshake so `PROTOCOL_MISMATCH` is detectable on any call |
| **argv-first execution**; `type:"tool"` resolves args→argv via schemas; `type:"shell"` is the only shell path (audit-logged, confirmed V1.5) | injection becomes structurally impossible in the default path; schemas keep GUIs data-driven |
| Tool schemas: `fixed_argv` + per-arg `argv` token templates (`{value}`) | expressive enough for real CLIs (flags, value flags, boolean toggles, ordering) without a template language |
| Idempotency keys on job submission (24 h replay window) | reconnect-retry storms must not duplicate downloads/encodes |
| Caps chosen small & explicit (4 concurrent jobs, 32 queue, 2 MiB rings, 1 MiB JSON, 2 GiB uploads, 12 conns/client) | conservative defaults survive phantom-killer pressure; everything user-tunable via `bridge.json` |
| No CORS, ever + `Origin` header rejection | a web page open on the phone must never be a client |
| Path policy = `realpath()` membership in resolved roots | symlink-safe traversal control; makes `~/storage` work while blocking escapes |
| Additive-only within `/v1`; `/v2` side-by-side ≥ 1 release | clients and bridge can drift a release apart safely |
| Fixtures are normative (both test suites validate them) | contract drift becomes a CI failure, not a runtime surprise |
