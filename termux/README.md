# termux/ — Termux-side components

**Status: Phase 3 (bootstrap automation) delivered and sandbox-verified — see [CHANGELOG](../CHANGELOG.md) and [ADR-005](../docs/decisions/ADR-005-bootstrap-automation.md).**

```
termux/
  trmx-bridge.py       # the daemon — single file, Python 3, STDLIB ONLY (TRMX-P/1 subset)
  trmx                 # control CLI v0.3.0: init | pair | unpair | token | start | stop |
                       #   status | pid | enable-boot | enable-service | version
  install.sh           # one-command installer (intent-driven; --source DIR for local installs)
  gen_checksums.sh     # regenerates SHA256SUMS — run after any bridge/CLI change
  SHA256SUMS           # pinned digests of trmx-bridge.py + trmx
  tests/
    test_bridge.py     # 19-test bridge suite (real TCP, real subprocess)
    test_bootstrap.py  # 12-test bootstrap suite (installer + lifecycle commands)
    run_tests.sh       #   → sh termux/tests/run_tests.sh [bridge|bootstrap|all]
    smoke.sh           # the human-visible Phase 2 demo
    intent_commands.sh # prints the adb commands for all 8 control-plane ops
  tools/               # Tool Registry schemas (Phase 9)
```

## Install (what the Android app will do for you in Phase 4)

```sh
pkg install python curl
sh install.sh                  # remote mode: fetches from GitHub raw + verifies SHA256SUMS
# or, from a checkout:
sh install.sh --source /path/to/TRMX-GUI/termux
~/.trmx/trmx start             # bridge on 127.0.0.1:27342
```

Optional lifecycle (both off by default):

```sh
~/.trmx/trmx enable-boot       # start at boot via Termux:Boot (install that app, open once)
pkg install termux-services    # then:
~/.trmx/trmx enable-service    # runit service: start at boot + auto-restart on crash
```

- Runtime dependency: `pkg install python` — nothing else, ever (stdlib-only rule, ADR-002).
- The installer is idempotent; upgrading a running bridge stops it, replaces
  files, and restarts it automatically.
- On Termux the bridge is strict (executable allowlist + cwd roots); off-Termux (CI) it is
  dev-lenient so the tests can run on any Linux box.
- Intent contract for the app: [docs/CONTROL-PLANE.md](../docs/CONTROL-PLANE.md).
- Implemented TRMX-P/1 subset + deviations: [ADR-004](../docs/decisions/ADR-004-phase2-poc.md).
