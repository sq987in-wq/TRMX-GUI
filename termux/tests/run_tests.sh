#!/bin/sh
# TRMX test runner — Phase 2 (bridge) + Phase 3 (bootstrap) suites.
# Works on any Linux with python3 — including Termux:  pkg install python
# Usage: run_tests.sh [bridge|bootstrap|all]  (default: all)
set -e
cd "$(dirname "$0")/../.."
SUITES="${1:-all}"
case "$SUITES" in
  bridge)    exec python3 termux/tests/test_bridge.py ;;
  bootstrap) exec python3 termux/tests/test_bootstrap.py ;;
  all)
    python3 termux/tests/test_bridge.py
    exec python3 termux/tests/test_bootstrap.py ;;
  *) echo "usage: $0 [bridge|bootstrap|all]" >&2; exit 2 ;;
esac
