# ADR-009 — Tool Registry & dynamic forms (PROTOCOL §7, bridge v0.4.0)

**Status:** accepted (2026-09-09) · **Phase:** 9 · **Depends on:** ADR-002 (protocol
freeze — §7 was frozen in Phase 1), ADR-007 (§6 path policy)

## Context

Phase 0/1 froze a JSON-schema "Tool Registry" wire contract (§7): the bridge
serves tool schemas, probes binary availability, and (bridge-side) synthesizes
argv from validated form args — so the GUI never builds commands and never has
to be trusted. The fixtures (`tool.schema.yt-dlp.json`,
`jobs.submit.request.tool.json`) existed since Phase 1; nothing implemented
them. Phase 9 implements §7 verbatim on both planes.

## Bridge (v0.3.0 → v0.4.0)

- **`GET /v1/tools` · `GET /v1/tools/{id}` · `POST /v1/tools/refresh`** — list
  serves merged schemas with availability; first list lazily probes
  (`resolve_binary` + `<binary> --version` first line, 10 s cap each);
  `refresh` re-probes everything and audits `tools.refresh`.
- **Bundled schemas**: yt-dlp (verbatim the frozen fixture + a `pkg` hint
  field), ffmpeg (confirm tier), aria2c. `pkg` is an additive, optional field —
  unknown-field tolerance is part of the contract, so no protocol bump.
- **Merge rule**: user schemas (`~/.trmx/tools/*.json`, alphabetical) override
  bundled ones by id; malformed user schemas are skipped and reported in
  `system/info.features.tool_schema_errors` (bundled malformed = our bug =
  loud failure at boot). `tools_detected` now counts the registry.
- **Tool submits**: `type:"tool"` + `args` → argv synthesized per §7.2
  (binary + fixed_argv + per-arg tokens in schema order; bool true only;
  `{value}` exactly once for non-bools), then the SAME allowlist/size checks
  as raw argv. `TOOL_UNKNOWN` (400) / `ARG_INVALID` (400, `field` set) /
  `PATH_DENIED` (403) / `PATH_NOT_FOUND` (404) per §10.
- **Path args are substituted as ABSOLUTE paths** (§6.1 policy applied with
  the arg's read/write semantics): argv is exec'd without a shell, so `~`
  would never expand. The bridge also checks a `path_kind: dir` arg exists.
- **Live progress**: a tool job's stdout is line-scanned against the schema's
  `progress_regex` (first capture group = percent); changes update
  `progress_pct`/`progress_detail` on the job and stream as `job.updated`.
  Non-tool jobs and regex-less tools are untouched.
- **Risk tier is audit-logged** with tool submits (`tool=<id> tier=<tier>`);
  tiers never replace validation, only shape app-side confirmation UX.
- **Two real bugs the new suite caught before shipping**: bool-false args
  still emitted their tokens (now: `False` contributes nothing), and the
  common argv validator clobbered the synthesized argv (now guarded for
  `type:"tool"`).

## App

- **Models + client**: `ToolSchema/ToolArg/ToolStatus/ToolsResponse` +
  `ToolSubmitRequest` (`type:"tool"`; encoded with the jobs plane's explicit
  nulls — its fixture carries `env: null`); `listTools`/`refreshTools`/
  `getTool`/`submitToolJob`.
- **FormEngine (pure Kotlin)**: schema → form state — defaults →
  `FieldValue`s, per-type validation mirroring §7.2 (url/enum/pattern/int
  bounds/bool), a live **argv preview strip**, and the args payload
  (effective values only; bool false ≡ absent, matching both the bridge
  semantics and the fixture's minimal args object). The bridge re-validates —
  the engine is UX, not security.
- **ToolFormScreen**: enum → chips, bool → switch, int/float → numeric
  fields, url/string → text, **path → opens the Phase 7 Files browser as a
  picker** (file vs folder per `path_kind`); example chips prefill; risk tier
  gates the run button (confirm tier → dialog with the exact command).
- **Toolbox**: live cards (installed ✓/✗, version, tier badge), `scan ⟳`
  (refresh), user-schema errors surfaced from `features.tool_schema_errors`.
- **Self-healing installs**: an uninstalled tool with a `pkg` hint gets an
  install button → `pkg install -y <pkg>` as a NORMAL job (live console, §3
  semantics) → the app watches the job via `/v1/events` and rescans the
  registry when it finishes. No new bridge capability was needed.
- **Dashboard**: tool jobs render their live `progress_pct` as a progress bar
  (the % is parsed from real tool output by the bridge).

## Consequences

- Adding a tool = dropping a JSON schema in `~/.trmx/tools/` (or bundling) —
  full form, validation, progress parsing, zero app changes. This is the
  Phase 9 "wow", and it was contractual since Phase 1.
- New fixture `fixtures/v1/tools.list.response.json` documents the frozen
  §7.1 response shape (shape unchanged; fixture added per the fixtures rule,
  with this ADR + CHANGELOG in the same commit). Both suites validate
  against it.
- `progress_regex` is per-line on stdout only; progress lines on stderr (badly
  behaved tools) are not parsed — documented honestly.

## Testing

Bridge `test_tools.py` (14): registry endpoints, probing via fake binaries on
PATH, synthesis table (fixed/positional/enum/bool/int/path→absolute), all
ARG_INVALID paths, PATH_DENIED/PATH_NOT_FOUND on path args, ghost binaries,
progress parsing end-to-end (fake tool emits yt-dlp-style lines → 100.0% +
detail), tier audit, user-over-bundled merge + malformed schema reporting,
fixture key conformance. App: `ToolsWireTest` (4, incl. byte-identical tool
submit fixture), `FormEngineTest` (4). Suite totals: termux 63/63.
