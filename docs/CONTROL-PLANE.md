# TRMX Control Plane — RUN_COMMAND Intent Interface

| | |
|---|---|
| **Status** | Frozen for Phase 4 implementation (Phase 3) |
| **Implements** | Phase 0 report §B.3/B.5 (two-plane architecture), ADR-001 |
| **Consumer** | the Android app's `IntentControlPlane` (Phase 4) |
| **Reference** | Termux wiki: RUN_COMMAND Intent (`com.termux.RUN_COMMAND`) |

The control plane is used **only when the data plane is down**: install, pair,
start, stop, health-probe, and lifecycle setup of the bridge. Everything else
rides TRMX-P/1 over loopback HTTP.

## 1. Preconditions (the user-consent boundary — cannot be automated, by design)

| # | Requirement | How the user satisfies it | App check |
|---|---|---|---|
| 1 | Termux installed (GitHub Releases, same source as any add-ons) and opened once | user installs & launches | package query (`<queries>` for `com.termux`, API 30+) |
| 2 | `allow-external-apps=true` in `~/.termux/termux.properties` | one pasted line + `termux-reload-settings` | health probe still failing after START intent ⇒ guided fix screen |
| 3 | `com.termux.permission.RUN_COMMAND` granted to TRMX-GUI | App Info → Additional permissions (deep-linked from the wizard) | `SecurityException`/permission check on send ⇒ `PERMISSION_REQUIRED` |

Notes: "Draw over other apps" is **not** required — we only use background
commands. `EXTRA_PENDING_INTENT` result callbacks are **not** used in V1; the
app verifies every operation through the data plane instead (simpler, and the
bridge is the source of truth anyway).

## 2. Intent envelope (all operations)

```
Component:  com.termux/com.termux.app.RunCommandService
Action:     com.termux.RUN_COMMAND
Extras:
  com.termux.RUN_COMMAND_PATH        string    absolute executable path (see op table)
  com.termux.RUN_COMMAND_ARGUMENTS   string[]  arguments (argv — never a shell string, op INSTALL excepted)
  com.termux.RUN_COMMAND_WORKDIR     string    /data/data/com.termux/files/home
  com.termux.RUN_COMMAND_BACKGROUND  boolean   true
  com.termux.RUN_COMMAND_COMMAND_LABEL    string  "TRMX: <op>"     (notification label)
  com.termux.RUN_COMMAND_COMMAND_DESCRIPTION string  short what/why  (notification text)
```

Paths on device: `$PREFIX = /data/data/com.termux/files/usr`,
`$TRMX = /data/data/com.termux/files/home/.trmx`.

## 3. Operations

| Op | PATH | ARGUMENTS | App uses it when | Observable success |
|---|---|---|---|---|
| `INSTALL_PY` | `$PREFIX/bin/pkg` | `["install","-y","python"]` | bootstrap, bridge reports python missing | subsequent ops succeed |
| `INSTALL` | `$PREFIX/bin/bash` | `["-c", "curl -fsSL <BASE>/install.sh \| sh -s -- --base <BASE>"]` | bootstrap / upgrade (app passes pinned `--base` + `--sha256` URL) | `$TRMX/trmx-bridge.py` + `$TRMX/trmx` exist (app can't see them; verified by next op + data plane) |
| `PAIR` | `$TRMX/trmx` | `["pair", "<token>"]` | first pairing / re-pairing after unpair | data plane auth starts succeeding |
| `START` | `$TRMX/trmx` | `["start"]` | data plane down, bridge believed stopped | port answers `/v1/system/info` |
| `STOP` | `$TRMX/trmx` | `["stop"]` | user-initiated backend shutdown | port closed |
| `STATUS` | `$TRMX/trmx` | `["status"]` | diagnostics (rare — data-plane probe is preferred) | exit/output |
| `ENABLE_BOOT` | `$TRMX/trmx` | `["enable-boot"]` | user opts into autostart | `~/.termux/boot/trmx-bridge` exists (Termux side) |
| `ENABLE_SERVICE` | `$TRMX/trmx` | `["enable-service"]` | user opts into crash auto-restart (needs termux-services) | runit service dir exists |

Design rules:

- **argv-first**: every op except `INSTALL` executes an executable directly
  with an argument array — there is no shell string to inject into. `INSTALL`
  is the one shell pipeline (curl | sh); its trust anchors are HTTPS to a
  pinned base URL plus SHA256 verification inside the installer, plus the
  app-side pinned digest (Phase 4). The token is never part of any INSTALL
  command string.
- The token travels **only** in the `PAIR` argv and in the HTTP
  `Authorization` header — never in a broadcast, never in a URL.
- `RUN_COMMAND_BACKGROUND=true` always: we never want a foreground session.

## 4. Sequences

**First run (after the three consents):** `INSTALL_PY` → `INSTALL` → `PAIR`
→ `START` → data-plane handshake. The wizard polls `/v1/system/info` (0.5s
interval, 30s timeout) after `START` before declaring success.

**Warm start:** probe `/v1/system/info`; on connection-refused send `START`,
re-probe; on repeated failure escalate to `TERMUX_NOT_RUNNING` /
`BRIDGE_NOT_RUNNING` states with fix guidance.

**Upgrade:** `INSTALL` (installer stops/restarts the bridge itself when it was
running) → handshake (version check) → `PROTOCOL_MISMATCH` handling if needed.

## 5. Error mapping (Phase 0 report §B.7)

| Signal | State | UI action |
|---|---|---|
| `SecurityException` on send / permission not held | `PERMISSION_REQUIRED` | deep link to grant screen |
| Termux package not found | `TERMUX_NOT_RUNNING` | install/open Termux guidance |
| Intent accepted, data plane still down after timeout | `BRIDGE_NOT_RUNNING` (probable `allow-external-apps` missing or install failed) | show the one-liner fix; offer `trmx status` intent |
| Port answers but 401 | `AUTHENTICATION_FAILED` | re-pair wizard |
| Bind failure reported (start intent "succeeded", port never opens, log shows EADDRINUSE) | `PORT_SQUATTED` | change-port flow |

## 6. On-device verification (Phase 3 DoD)

`termux/tests/intent_commands.sh` prints the exact `adb shell am startservice`
commands for every operation — run them from a PC to prove the control plane
without the app. Leak check afterwards:

```
adb logcat -d | grep -i "trmx\|RUN_COMMAND"
```

must not contain the pairing token.
