# ADR-015 — Service registry: tool-based long-running services (protocol 1.1)

**Status:** accepted (2026-09-11) · **Phase:** Services milestone ·
**Depends on:** ADR-009 (tool registry), PROTOCOL §13 (versioning
governance)

## Context

The roadmap's next milestone after the AI round: background
service/daemon lifecycle management — start, stop, restart, status,
autostart. Termux users run long-running things (http-server, sshd,
syncthing, dev servers); before this milestone they were ordinary jobs
you found in the job list and cancelled.

Constraints:

- TRMX-P/1 is frozen; §13 permits additive minor bumps (protocol 1.1)
  with capability detection — no breaking change to existing clients.
- The bridge is the only execution plane; a "service" feature must not
  become a second way to execute things.
- The registry already owns argv synthesis, validation, policy, probing
  (§7). Jobs already own process lifecycle, cancellation, SSE events,
  reconciliation (§3).

## Decision

**1. A service is a definition that runs a registry tool.**
`~/.trmx/services/<id>.json` holds `{id, name, tool, args, autostart,
created_at}`. `start` submits a normal `type:"tool"` job (full §7.2
synthesis + validation) and binds service→job; `stop` cancels the bound
job; `restart` waits for terminal then resubmits; `status` derives from
the bound job. No new process machinery — services are a *naming and
persistence layer* over jobs.

**2. Idempotent stop, explicit refusals.** Stopping a stopped service
returns 200 (cancel is already idempotent per §3.5) — no client-side
race errors. Delete and definition-replace are refused with
`409 SERVICE_RUNNING` while running; unknown ids get
`SERVICE_NOT_FOUND`.

**3. Autostart at boot, best-effort.** After job reconciliation,
definitions with `autostart: true` are started; per-service failures are
audited and skipped, never fatal. Bindings are in-memory (a bridge
restart re-derives state: old jobs reconcile to LOST, autostart
relaunches).

**4. Capability-gated protocol 1.1.** Routes under `/v1/services` are
additive; `features.service_registry` is the gate (older bridges 404).
Malformed definition files are skipped and reported via
`features.service_errors` — the exact pattern of `tool_schema_errors`.
The request header stays `X-TRMX-Protocol: 1`.

**5. Events reuse the existing bus.** `service.updated` (full status)
on explicit operations, `{"id", "deleted": true}` on delete;
job-driven transitions arrive as ordinary `job.updated`, correlated by
`job_id` — no invasive hooks at the seven job-transition publish sites.

**6. App: a fifth tab, P4 components only.** Services tab (Dns icon,
Outlined/Filled pair) with ServiceCard (vector status dot, Start/Stop/
Restart, autostart FilterChip, delete with confirm), an empty state
that teaches the create path, and an honest capability card when the
bridge predates v0.5.0. Creation reuses the ToolForm: a header action
saves the current validated form as a definition — no new form
machinery.

## Consequences

- `termux/tests/test_services.py` (15 tests) locks CRUD, lifecycle,
  idempotency, autostart-at-boot (fresh bridge), malformed-file
  reporting; `ServicesWireTest` (6 JVM tests) locks the wire shapes
  against the two new normative fixtures.
- Scope deferred (per roadmap): bridge.log viewer, wire job labels,
  per-service metrics — candidates for a later minor.
- Future daemons become service definitions over their tools; the
  runit integration (features.runit) can later supersede job-binding
  for true system-level daemons without changing the client contract.
