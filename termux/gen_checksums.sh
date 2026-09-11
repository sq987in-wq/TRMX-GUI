#!/bin/sh
# Regenerate termux/SHA256SUMS — MUST be run whenever trmx-bridge.py, trmx
# or trmx-ai changes (release tagging also regenerates it; see ADR-005).
set -eu
cd "$(dirname "$0")"
sha256sum trmx-bridge.py trmx trmx-ai > SHA256SUMS
cat SHA256SUMS
