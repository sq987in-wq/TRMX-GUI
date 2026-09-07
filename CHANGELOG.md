# Changelog

All notable changes to TRMX-GUI are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow the
`app` / `bridge` / `protocol` triple (protocol major = breaking wire changes, see PROTOCOL.md §13).

## [Unreleased]

### Added — Phase 1: Protocol freeze & project constitution (2026-09-07)
- **`docs/PROTOCOL.md`** — TRMX-P/1 wire contract: conventions, auth, all endpoints, job object & state semantics, SSE framing, file API + path policy, Tool Registry schema format, error-code registry, limits, versioning/governance. **Draft — awaiting review.**
- **`docs/ARCHITECTURE.md`** — living architecture summary (components, flows, state machines, storage, security core).
- **`docs/decisions/`** — ADR-001 (architecture baseline, stakeholder-approved), ADR-002 (protocol v1 core decisions), ADR-003 (MIT license).
- **`fixtures/v1/`** — normative contract fixtures shared by future Kotlin & Python test suites.
- Repo layout: `android/` (Phase 4+), `termux/` (Phase 2+), `fixtures/`.
- `CHANGELOG.md` (this file) and `LICENSE` (MIT).

### Added — Phase 0: Feasibility & Architecture Discovery (2026-09-07)
- **`docs/PHASE-0-DISCOVERY-REPORT.md`** — feasibility verdict (19 questions answered honestly + Maximum Practical Control Boundary), communication-option comparison (A–G), full system architecture, Termux-side & Android-side designs, security/threat model, GUI philosophy analysis, feature map V1–V3, 13-phase roadmap, tooling, testing strategy & failure matrix, risk register, open questions.
- Stakeholder decisions recorded (Appendix 3); **architecture approved**.

### Notes
- No implementation code exists yet, by design. Phase 2 (`termux/trmx-bridge.py` PoC) begins after the TRMX-P/1 draft is reviewed.
