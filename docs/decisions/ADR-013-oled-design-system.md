# ADR-013 — OLED design system: Tokens + Trmx component foundation

**Status:** accepted (2026-09-11) · **Phase:** UX-audit P3 · **Depends on:**
ADR-011 (terminal-luxe), ADR-012 (Command Center IA)

## Context

The P2 on-device round still read as "disconnected prototypes glued
together": screens styled themselves, accents were terminal-green/amber,
and every new screen risked re-inventing buttons, cards and fields. The
user supplied a production-grade blueprint package
(`stitch_trmx_gui_design_system`) with strict directives: true-OLED
black, deep-contrast bordered surfaces, a calm modern-tech accent pair
(ice-cyan / steel blue), green/mint and orange/amber **forbidden**, and a
mandatory reusable component foundation so future screens (AI schema
builder, services, daemon settings) need zero ad-hoc styling.

**Honesty note:** the blueprint package did not persist into the
workspace (third occurrence of the workspace-reset environment issue;
full-disk search found no uploads) and images cannot be inspected
without vision. The palette therefore derives from the *written*
directives — which were complete on every architectural dimension
(background/container hexes, forbidden accents, component contract,
per-screen hierarchy). All constants live in ONE file (`Tokens.kt`), so
a fidelity pass against the re-attached blueprint is a one-file diff.

## Decisions

### 1. Tokens — single source of truth

`Tokens.Palette` (ground/surfaces/borders/text/accent/steel/danger),
`Tokens.Space` (4/8/16/24/32), `Tokens.Radius` (8/12/16). Screens and
components may not hardcode visual constants; grep-able rule, and the
conformance-style audit in CI can enforce it later.

### 2. Component foundation

`TrmxTopBar` (back arrow + title, token colors) · `TrmxCard`
(border-defined elevation — no shadows on black) · `TrmxButton`
(Primary = ice-cyan fill, the one loud element per screen; Secondary =
quiet outline; Ghost; Danger = semantic red) · `TrmxTextField` (dark
SurfaceHigh container, token borders/cursor, FieldShape) — plus the
existing `ActionFlowRow` (wrap-never-squeeze) and `CopyableId`.
Future screens compose these; styling changes happen in exactly two
files (Tokens + Components).

### 3. Palette & semantics

True OLED `#000000` ground; `#0A0E17` cards, `#121824` inputs/sheets;
1 dp outlines. Electric Ice-Cyan `#22D3EE` = focus/primary/running;
Steel Blue = completed/secondary; dim steel = queued; muted slate =
cancelled/metadata; semantic red = failures only. **No green, no
amber** — status semantics remapped accordingly (running is "active"
cyan, not "success" green; the app never claimed success, only status).

### 4. Scope guarantee

Presentation-tier only: TRMX-P/1, bridge, process runner, FormEngine
validation and file handlers untouched (zero diff outside
`android/app/src/main/java/dev/trmx/gui/ui/` + res values + AppViewModel
signature-free). The JVM suite needed no changes — no test asserts
visual constants.

## Consequences

- The Ph 9.5 "terminal green" identity is superseded; TrmxColors keeps
  its API but remaps semantics, so every screen migrated for free.
- Wizard (first-run) still uses raw M3 widgets — themed by the scheme;
  acceptable until the blueprint fidelity pass.
- Blueprint re-attachment → fidelity pass = Tokens diff + component
  spacing tweaks, no architecture.
