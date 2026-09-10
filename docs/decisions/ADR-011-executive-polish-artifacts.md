# ADR-011 — Executive polish, x_trmx vocabulary & artifact cards (Phase 9.5)

**Status:** accepted (2026-09-10) · **Phase:** 9.5 · **Depends on:** ADR-008
(FileProvider staging), ADR-009 (tool registry), ADR-010

## Context

The on-device Phase 9 verdict: functionally right, visually a prototype —
default purple M3 theme, unpadded fields, red error labels before first
interaction, misaligned folder buttons, and back-button chains instead of
navigation. This round makes the shell executive-grade without touching the
bridge.

## Decisions

### 1. Terminal-luxe design system (Theme.kt)

One designed dark scheme (dark is the identity, not a toggle): terminal
green primary `#34D399`, sky secondary, amber tertiary (confirm accents),
deep-slate surfaces with proper `surfaceContainer` tonal steps. Full M3
type scale; monospace reserved for DATA (paths, argv, sizes). Shape
signature: 12 dp fields (6/8/12/20/28 ladder). Spacing on a strict
`Sp` grid (4/8/16/24). All status/tier colors centralized in `TrmxColors`
(replacing per-screen color maps — one source of truth).

### 2. Form overhaul

- **Touched-state errors** (`FormEngine.visibleError`): a field shows its
  validation error only after it was edited (or after a run attempt — the
  ViewModel marks everything touched). No premature red labels.
- **RUN stays enabled**: a premature tap surfaces every field's error via
  touched-marking instead of presenting a dead button with no explanation.
- **Path pickers are trailing icons INSIDE the field** — kills the baseline
  misalignment at the root; help text moves to `supportingText`.
- **Enums ≤ 4 options → M3 SegmentedButtons**; more → chips. **Bounded
  numerics (range ≤ 200) → sliders** with value readout; wide ranges stay
  numeric fields. `x_trmx.widget` overrides any default.
- **Secrets** (`x_trmx.secret`) render masked with a reveal toggle.
  Rendering only — the VALUES still travel as plain argv parts today;
  bridge-side log redaction is Phase 10 (that is where redaction can be
  enforced, never in the client).
- **Units** (`x_trmx.unit`) as field suffixes.

`x_trmx` is additive and optional: unknown-field tolerance is contractual,
the bridge passes schemas through untouched, and validation/argv synthesis
never depend on it. A schema without `x_trmx` renders exactly as before.

### 3. Bottom NavigationBar (Home / Files / Tools / Chains)

Replaces the "← Dashboard" button chains. Overlay screens (job detail,
open tool form, wizard) render above the shell without the bar; system
back inside Files still walks up the directory tree first, then Home.
The form closes back onto whichever tab opened it (Toolbox/Chains/Home).

### 4. Artifact cards (first-class outputs)

For a COMPLETED tool job, job detail shows an **Artifacts** card:

- **Exact outputs** — args the schema marks as outputs (`output` path/file
  arg or `x_trmx.artifact`), remembered at submit time (`ToolJobMeta`,
  in-memory) or re-derived from schema defaults after an app restart.
- **Detected files** — everything new in the job's outdir with
  `mtime ≥ started_at` (second-precision compare), listed via §6.2 and
  labeled **"detected"** — an honest heuristic, never presented as
  guaranteed.

Each artifact row offers **open / share** through the Phase 8
download-then-open machinery (`openRemote`), reused verbatim. No protocol
change: the bridge stays v0.4.1 and unaware.

## Consequences

- All screens now take a `modifier` (Scaffold supplies padding) — an
  internal API break for the screens, invisible outside the app.
- `x_trmx.widget` reserves names (slider|segmented|chips|toggle|textarea);
  unknown hints fall back to type defaults.
- Artifact "detected" listing may miss files whose tools set mtimes oddly;
  exact outputs are authoritative. Documented in the guide.

## Testing

`PolishTest` (7): touched-state visibility, widget selection (defaults +
override), x_trmx decode round-trip, output-arg detection, outdir default
fallback, mtime second-precision heuristic, collect/merge/dedupe.
