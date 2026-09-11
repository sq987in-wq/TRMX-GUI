# ADR-014 — AI Schema Builder: trmx-ai wrapper, untrusted LLM output

**Status:** accepted (2026-09-11) · **Phase:** AI round · **Depends on:**
ADR-009 (tool registry), ADR-013 (component foundation)

## Context

The roadmap's AI round targets the highest-leverage automation in the
registry architecture: writing tool schemas by hand is the one task that
keeps "every Termux binary becomes a first-class citizen" from scaling.
The plan (frozen in the session roadmap) was: **NL → LLM job emits schema
→ `~/.trmx/tools/ai-*.json` → registry rescan; LLM output is untrusted.**

Constraints that shaped the decision:

- The bridge stays the execution plane and source of truth; the app never
  gains a second way to run things.
- The registry merge rules (ADR-009) already handle user schemas dropped
  into `~/.trmx/tools/` — including malformed ones (skipped + reported).
- `resolve_binary` already probes `~/.trmx/bin` first, giving tool
  binaries a home that needs no PATH games.
- stdlib-only Python on the Termux side (ADR-002 rule).

## Decision

**1. `trmx-ai` is a Termux-side wrapper, not app code.** One stdlib-only
Python script installed by `install.sh` to `~/.trmx/bin/trmx-ai` (tool
binary location; the bridge and `trmx` CLI stay at the state-dir root).
CLI: `trmx-ai "<description>"` with `--model`, `--dry-run`, `--version`.
Backend: `~/.trmx/ai.json` `{"command": [...], "timeout_s": N}` — the
prompt is appended to `command` as one argv element, no shell. Default
backend `ollama run llama3.2`; any CLI-wrapped LLM works.

**2. The LLM output is untrusted data.** The wrapper: caps accepted
output (64 KB), extracts the first balanced string-aware `{...}` block
(fences/prose tolerated, never eval'd), then validates with the §7.2
structural rules **mirrored from the bridge** (types, argv `{value}`
exactly once, bool argv without `{value}`, enum/path/pattern rules) plus
AI-specific caps (≤ 12 args, ≤ 16 fixed_argv, string length caps). Any
doubt → exit 4, nothing written. The bridge re-validates on scan anyway
(defense in depth: the wrapper is the only thing standing between the
model and disk, the registry the second).

**3. AI-generated tools are pinned to `risk_tier: "confirm"`.** The
model's tier claim is ignored; a human must edit the JSON by hand to
relax it. The RUN-confirmation UX therefore always gates AI tools.

**4. The wrapper never overwrites** an existing `ai-<id>.json` (exit 5)
and writes only inside `~/.trmx/tools/` (atomic write via tmp + rename).

**5. The app drives it as a normal tool job.** The bridge bundles an
`ai-schema-builder` schema (binary `trmx-ai`, positional `description`
arg, optional `--model`), risk tier `safe` — the pipeline is fixed, the
prompt is the user's own words, and the write is confined + validated;
the *generated* tool carries the confirm tier, not the builder. The
Schema Builder screen (P4 components only) submits that job and watches
it via `/v1/events` (the proven `installWatch` pattern): on terminal
status it rescans the registry, so the new tool appears without a bridge
restart.

**6. Same release carries the outdir ergonomics fix** (bridge v0.4.2):
`mkdir: true` on dir args auto-creates missing output directories at
submit (parents too) under §6.1 write semantics, instead of
`404 PATH_NOT_FOUND`. Applied to yt-dlp and aria2c `outdir`; input dirs
(http-server's serve folder) keep the existence check. Motivated by
on-device report: `~/downloads` not existing broke first runs.

## Consequences

- `termux/tests/test_ai_wrapper.py` (17 tests, fake backends — no
  network) + an e2e bridge test (fake `trmx-ai` on PATH → job →
  refresh → registry) lock the contract.
- Users without an LLM backend get a loud, actionable setup hint
  (screen card + wrapper stderr), never a silent dead button.
- Generated schemas are ordinary user schemas: they can be overridden,
  deleted, or hand-edited like any JSON in `~/.trmx/tools/`.
- Future AI rounds (recipe synthesis, chain drafting) reuse the same
  wrapper pattern: untrusted output → strict validation → file → rescan.

---

## Addendum — dual backend: cli + http_api (trmx-ai 0.2.0)

On-device feedback: local-only backends force a choice between gigabytes
of model weights on phone storage and no AI round at all. v0.2.0 adds
`"mode": "http_api"` alongside `"cli"`:

- **Config v2** (`~/.trmx/ai.json`): `mode` + `cli`/`http_api` blocks;
  the v0.1.0 flat form still loads (implicit cli). First run without a
  config writes a documented template (mode cli, 0600) — one flip to
  cloud. Guide: `docs/AI-CONFIG.md`.
- **http_api**: `provider` ∈ openai | groq | gemini | openai_compatible
  (+ `endpoint`, `model`, `api_key`/`api_key_env`, `timeout_s`). Provider
  presets cover both wire shapes (OpenAI chat vs Gemini contents/parts);
  keys resolve from config or the conventional env var and are never
  logged or echoed (URL query keys redacted).
- **stdlib only** (ADR-002): cloud calls use `urllib.request`, not
  `requests`/`curl` — no new dependencies, works on a bare Termux.
- **The trust boundary did not move**: both backends funnel into the
  same `extract_json` → `validate_schema` path (64 KB cap, §7.2 rules,
  confirm-tier pin, no overwrite). Tests pin this: the same malicious
  schema is rejected identically via cli and via http_api.
- Tests: 30 in `test_ai_wrapper.py` — 17 cli (fake CLIs), 12 http_api
  (local `HTTPServer` fakes, both shapes, key handling, HTTP errors),
  2 template bootstrap (written 0600 on first run; `--dry-run` writes
  nothing).
