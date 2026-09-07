# termux/ — Termux-side components

**Status: Phase 2 PoC delivered and sandbox-verified (see [CHANGELOG](../CHANGELOG.md)).**

```
termux/
  trmx-bridge.py       # the daemon — single file, Python 3, STDLIB ONLY (TRMX-P/1 subset)
  trmx                 # control CLI: init | pair | unpair | token | start | stop | status | pid
  tests/
    test_bridge.py     # 19-test suite (real TCP against a real bridge subprocess)
    run_tests.sh       #   → sh termux/tests/run_tests.sh
    smoke.sh           # the human-visible Phase 2 demo → sh termux/tests/smoke.sh
  tools/               # Tool Registry schemas (Phase 9)
  install.sh           # pinned installer (Phase 3)
```

- Runtime dependency: `pkg install python` — nothing else, ever (stdlib-only rule, ADR-002).
- Implemented TRMX-P/1 subset + deviations: [ADR-004](../docs/decisions/ADR-004-phase2-poc.md).
- On Termux the bridge is strict (executable allowlist + cwd roots); off-Termux (CI) it is
  dev-lenient so the tests can run on any Linux box.
- Phase 3 adds the `RUN_COMMAND`-intent-driven installer/bootstrap on top of `trmx`.

