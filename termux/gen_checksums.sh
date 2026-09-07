#!/bin/sh
# Regenerate termux/SHA256SUMS — MUST be run whenever trmx-bridge.py or trmx
# changes (release tagging also regenerates it; see ADR-005).
set -eu
cd "$(dirname "$0")"
sha256sum trmx-bridge.py trmx > SHA256SUMS
cat SHA256SUMS
