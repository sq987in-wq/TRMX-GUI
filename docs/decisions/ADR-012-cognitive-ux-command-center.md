# ADR-012 — Cognitive UX overhaul: Command Center IA, human task labels, honest Files

**Status:** accepted (2026-09-10) · **Phase:** UX-audit P0+P1 · **Depends on:**
ADR-009 (tool registry), ADR-010 (recipes/chains), ADR-011 (terminal-luxe)

## Context

A 31-point Cognitive UX & Architectural audit of the Phase 9.5 build judged
the engine capable but the UI "adb shell + ls -la wrapped in cards": debug
info primary, user goals buried, raw J-IDs as task identity, no screen
passing the 3-second test. Three P0 defects were reported on-device
(`Sto/p/brid/ge` button wrapping, a "must be an integer" validation failure
on a valid integer, a green vertical bar glitch in Files).

Root-cause verification against the tree found the audit correct — and
worse: the "slider bug" was a **wire-level type error** (every scalar sent
as a JSON string; the bridge's `isinstance(int)` validation rightly rejects
`"23"` — every ffmpeg/http-server/aria2c submit with a numeric arg failed),
the button wrapping was a **class** of bug (six unweighted action rows,
last child squeezed to a sliver), and the "green bar" was the squeezed
*filled* upload button rendered ~3 dp wide. The Files listing shows
dotfiles because the bridge's §6.2 listing is intentionally complete.

## Decisions

### 1. Wire typing: native JSON numbers (P0)

`FormEngine.argsPayload` sends int/float args as native JSON numbers per
§7.2. Saved chains/recipes from before the fix are re-typed at submit
(`coerceLegacyArgs`) — unparseable values pass through so the bridge
rejects them loudly. Slider text formatting goes through
`Locale.ROOT` (`sliderValueText`). Bridge untouched; the bridge was right.

### 2. ActionFlowRow: clusters wrap, never squeeze (P0)

All action-button rows go through one shared wrapper (Components.kt).
The rule lives in one place; a squeezed last child is structurally
impossible. Applied: Dashboard, Files, Chains, ToolForm run row (spinner
moved inside RUN).

**Implementation lesson (cost 3 CI rounds):** foundation's `FlowRow` is
experimental + inline, and passing a composable lambda parameter through
its content slot does not compile in our foundation 1.6.8 setup — and
the sandbox cannot compile-check Compose at all (ADR-006), so this was
only discoverable via CI bisection. ActionFlowRow is therefore a
hand-rolled `Layout` (~45 lines): stable MeasureScope/Placeable APIs,
exact gap control, no experimental annotations, no version coupling.
Lessons recorded: (a) prefer stable primitives over experimental layout
APIs when CI is the only compiler; (b) when the log CDN is unreachable,
bisect by pushing file subsets — each run's pass/fail is readable via
the API.

### 3. Dotfiles hidden by default, client-side (P0)

The §6.2 listing stays complete (bridge unchanged); `FilesFilter` hides
dotfiles unless toggled (`rememberSaveable`). A hidden-only folder gets a
"show dotfiles" empty state — `~/.trmx/bridge.log` stays two taps away for
the documented break-glass flow.

### 4. Home IA split: Command Center vs Diagnostics (P1)

Home answers "what are my tasks doing?" first: **status pill**
(connected · N running · error, tap → Diagnostics sheet) → **Quick run**
(saved recipes) → **Tasks**. The metrics dump, Refresh, Stop bridge and
Re-run setup moved to a **Diagnostics bottom sheet** — one tap from Home.
`GET /v1/system/info` remains the §2.1 cold-connect probe; only
presentation moved.

### 5. Human task labels; J-IDs demoted, not banished (P1)

`JobLabels`: label = name-at-submit → binary → tool → type → "job";
status verbs ("done", not "COMPLETED"); relative time; "tool · time"
subtitle. J-IDs render as `CopyableId` (monospace, tap-to-copy) — debug
identity stays one tap away because fail-loud bug reports need it.
Labels are honest: "Video Downloader (yt-dlp) · done", never "video
downloaded successfully" — the app knows status and artifacts, not
whether the file plays.

### 6. Manual argv submission moved to Toolbox (P1)

"+ New job" leaves Home; the Toolbox gains a "⚡ Custom command" card
with the same line-based argv dialog. argv-first survives (ADR-001); the
expert action stops masquerading as a primary goal.

## Consequences

- App-only round: bridge v0.4.1 and TRMX-P/1 untouched (no SHA256SUMS
  churn, no on-device bridge update).
- Deferred to v1.1 (needs protocol): bridge.log viewer endpoint,
  wire-level job labels, per-service metrics.
- P2 (follow-up): Files dirs-first grouping + relative mtimes, systemic
  empty states, argv-preview expander, remaining audit points.
- New tests: `UxAuditTest` (5), `JobLabelsTest` (5) — JVM-total 89.
