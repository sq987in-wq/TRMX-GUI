# termux/ — Termux-side components

**Status: reserved. No code until Phase 2 (bridge PoC) begins, after Phase 1 (protocol freeze) is reviewed.**

Planned contents (per `docs/ARCHITECTURE.md`, `docs/PROTOCOL.md`):

```
termux/
  trmx-bridge.py     # single-file Python 3 daemon, STDLIB ONLY (TRMX-P/1 server)
  trmx               # CLI helper: pair | unpair | start | stop | status | token | enable-boot | enable-service
  install.sh         # idempotent, SHA256-pinned installer
  tools/*.json       # bundled Tool Registry schemas
```

Install target on device: `~/.trmx/` · runtime dependency: `pkg install python` — nothing else, ever (stdlib-only rule, ADR-002).
