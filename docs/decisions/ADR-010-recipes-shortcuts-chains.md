# ADR-010 — Recipes, home-screen shortcuts & Chains (app-only orchestration)

**Status:** accepted (2026-09-09) · **Phase:** 9 · **Depends on:** ADR-008
(FileProvider staging), ADR-009 (tool registry)

## Context

Phase 9's user-approved scope added three "next-generation dashboard"
features on top of the tool registry. All three are **app-only**: they reuse
the frozen §3/§7 wire (jobs, events, tools) and change nothing on the bridge.

## Decisions

### 1. Recipes — saved forms, shareable as JSON

A recipe = `{id, title, toolId, args}` persisted in `filesDir/recipes.json`
(`RecipeStore`, atomic tmp+rename write, constructor-injected dir so it is
JVM-testable without Android). Recipes:
- prefill the tool form (`openRecipe`),
- are **shared** as JSON through the Phase 8 single-slot `cache/shared/`
  staging + FileProvider (mime `application/json`) — no new exposure,
- are **imported** from any picked JSON document; imported recipes always get
  a FRESH id so a shared file can never hijack an existing recipe/shortcut,
- survive app updates (app-internal storage).

### 2. Home-screen one-tap shortcuts

The newest 4 recipes are published as **dynamic shortcuts**
(`ShortcutManagerCompat`, label = recipe title, intent = MainActivity +
`recipe_id` extra). MainActivity is `launchMode="singleTask"` so a shortcut
reuses the running task (`onNewIntent`) instead of stacking activities.
Cold-start: the pending recipe id is consumed after the wizard gate — if the
bridge is not set up yet, the wizard still comes first (fail loud, never
guess). No static shortcuts / no manifest shortcuts: dynamic only, refreshed
on every recipe mutation. Honest limit: Android may cap or reorder dynamic
shortcuts per launcher.

### 3. Chains — linear visual pipelines, orchestrated app-side

A chain = ordered tool steps; a step's string/path args may contain
**`$PREV_FILE`**, resolved at run time to the previous step's output file.

- **Only steps with an `output` arg (`type: path`, `path_kind: file`) can be
  chained from.** Tools that write server-named files into a directory
  (yt-dlp) cannot — the app refuses to guess filenames from tool output; the
  builder UI says so inline. This is the honest v1 boundary.
- **Runner**: submit step N → poll `GET /v1/jobs/{id}` every 2 s (loopback,
  cheap) → on COMPLETED resolve `$PREV_FILE` from the step's own output arg
  and submit N+1; on FAILED/CANCELLED the chain fails at that step; on bridge
  loss (5+ consecutive network misses) the chain **PAUSES** with "resume from
  step N" — no hidden state, the resume recomputes prior outputs from the
  saved chain (`ChainPlanner.outputsUpTo` walks refs forward).
- "Stop orchestrating" cancels only the app-side runner and says so — a job
  already running on the phone keeps running there (observable/cancellable in
  the dashboard like any job).
- Chains persist in `filesDir/chains.json` (`ChainStore`, same pattern as
  recipes). `ChainPlanner` is pure and JVM-tested (refs, validation,
  sequential output resolution).
- The run view reads live job status/progress from the dashboard's
  `/v1/events`-fed job list — step rows show per-job status and the bridge's
  schema-parsed progress.

### 4. Events subscription moved to app-root scope

`startEvents()` now runs whenever the wizard is done (was: only while the
dashboard was visible). Rationale: chains, the self-healing install watcher,
and cross-screen job updates all ride on `/v1/events`; a single subscription
while the app is open is simpler and cheaper than per-screen lifecycles.
Reconnect/backoff behavior unchanged.

## Consequences

- The bridge stays v0.4.0 with zero knowledge of recipes/chains — the control
  plane (app) composes existing execution-plane primitives. This is the
  architecture working as designed (ADR-001: disposable control plane).
- If the app is killed mid-chain, the run state is lost but nothing is
  orphaned silently: finished steps are real jobs in the bridge's history;
  the user re-runs or the chain is edited. Accepted v1 limit, documented in
  the on-device guide.
- Branching/conditional chains are explicitly out of scope (linear only).

## Testing

`RecipeStoreTest` (4): persistence across instances, replace-on-same-id,
export/import round-trip, malformed imports → null. `ChainPlannerTest` (3):
output-arg detection, validation table (empty/unknown/first-step ref/no-output
prev/good), `$PREV_FILE` resolution + sequential `outputsUpTo`. Wire-level
behavior covered by ToolsWireTest + existing suites.
