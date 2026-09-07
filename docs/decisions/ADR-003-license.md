# ADR-003 — License: MIT

- **Status:** Accepted (2026-09-07)

## Context

Distribution decision (Phase 0 Appendix 3): personal use + friends, shared via GitHub releases, public repo. Possible future F-Droid submission (F-Droid requires buildable-from-source FOSS; MIT qualifies).

## Decision

MIT License, copyright "The TRMX-GUI Authors". Chosen over Apache-2.0 for maximum simplicity for a hobby-grade project; the patent-grant and NOTICE machinery of Apache-2.0 is unnecessary here. The Termux bridge is stdlib-only (no third-party code to attribute); if the Android app later adds dependencies, their licenses go in `android/` release notes.

## Consequences

- Anyone can fork/modify/redistribute; attribution preserved.
- Switching to Apache-2.0 later is possible while all contributors are known (now) — it gets harder as outside contributions arrive, so revisit early if patent concerns emerge.
