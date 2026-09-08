#!/bin/sh
# TRMX test runner — bridge (Ph2) + bootstrap (Ph3) + files (Ph7) suites.
# Works on any Linux with python3 — including Termux:  pkg install python
# Usage: run_tests.sh [bridge|files|bootstrap|all]  (default: all)
set -e
cd "$(dirname "$0")/../.."
SUITES="${1:-all}"
case "$SUITES" in
  bridge)    exec python3 termux/tests/test_bridge.py ;;
  files)     exec python3 termux/tests/test_files.py ;;
  bootstrap) exec python3 termux/tests/test_bootstrap.py ;;
  all)
    python3 termux/tests/test_bridge.py
    python3 termux/tests/test_files.py
    exec python3 termux/tests/test_bootstrap.py ;;
  *) echo "usage: $0 [bridge|files|bootstrap|all]" >&2; exit 2 ;;
esac
