#!/bin/sh
# TRMX bridge unit/integration tests (Phase 2).
# Works on any Linux with python3 — including Termux:  pkg install python
set -e
cd "$(dirname "$0")/../.."
exec python3 termux/tests/test_bridge.py "$@"
