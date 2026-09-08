#!/usr/bin/env python3
"""Spec conformance: the Android sources vs the frozen contracts.

This is the sandbox-side gate for Phase 4+ (no JDK/SDK available here —
ADR-006). It cannot prove the Kotlin compiles; CI (GitHub Actions) does
that. What it proves is that the app cannot silently drift from:

  - docs/CONTROL-PLANE.md §3  — the 8 intent operations (id, path, argv)
  - docs/PROTOCOL.md          — every data-plane route the app calls exists
  - fixtures/v1/              — embedded test payloads are byte-identical
  - AndroidManifest.xml       — permission + package-visibility declarations

Run:  python3 tests/spec_conformance.py     (also runs in GitHub Actions)
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FAILURES: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f" — {detail}" if detail and not ok else ""))
    if not ok:
        FAILURES.append(f"{name}: {detail}")


# --- 1. CONTROL-PLANE.md §3 op table ↔ ControlOps.kt SPECS -----------------

PREFIX = "/data/data/com.termux/files/usr"
TRMX = "/data/data/com.termux/files/home/.trmx"


def doc_ops() -> dict[str, tuple[str, list[str]]]:
    md = (REPO / "docs" / "CONTROL-PLANE.md").read_text("utf-8")
    ops: dict[str, tuple[str, list[str]]] = {}
    for line in md.splitlines():
        m = re.match(r"^\| `([A-Z_]+)` \| `([^`]+)` \| `(\[.*?\])` \|", line)
        if not m:
            continue
        op, path, args = m.group(1), m.group(2), m.group(3).replace(r"\|", "|")
        path = (path.replace("$PREFIX", PREFIX).replace("$TRMX", TRMX))
        ops[op] = (path, json.loads(args))
    return ops


def kotlin_specs() -> dict[str, tuple[str, list[str]]]:
    kt = (REPO / "android" / "app" / "src" / "main" / "java" / "dev" / "trmx" / "gui"
          / "control" / "ControlOps.kt").read_text("utf-8")
    # Kotlin string templates reference the file's own compile-time constants —
    # resolve them the way the compiler does.
    kt = (kt.replace("$TERMUX_PREFIX", PREFIX)
            .replace("$TRMX_HOME", TRMX)
            .replace("$TERMUX_HOME", "/data/data/com.termux/files/home"))
    specs: dict[str, tuple[str, list[str]]] = {}
    for m in re.finditer(
            r'OpSpec\(\s*"([A-Z_]+)",\s*"([^"]+)",\s*listOf\((.*?)\),\s*"',
            kt, re.S):
        op, path, args_src = m.group(1), m.group(2), m.group(3)
        args = re.findall(r'"((?:[^"\\]|\\.)*)"', args_src)
        specs[op] = (path, [a.replace('\\"', '"') for a in args])
    return specs


print("1. control-plane op table (docs/CONTROL-PLANE.md §3 ↔ ControlOps.kt)")
doc, kt = doc_ops(), kotlin_specs()
check("both sides define the same 8 op ids",
      sorted(doc) == sorted(kt) and len(doc) == 8, f"doc={sorted(doc)} kotlin={sorted(kt)}")
for op in sorted(set(doc) & set(kt)):
    d_path, d_args = doc[op]
    k_path, k_args = kt[op]
    check(f"{op}: path matches", d_path == k_path, f"doc={d_path} kt={k_path}")
    check(f"{op}: argv matches", d_args == k_args, f"doc={d_args} kt={k_args}")

kt_src = (REPO / "android" / "app" / "src" / "main" / "java" / "dev" / "trmx" / "gui"
          / "control" / "ControlOps.kt").read_text("utf-8")
specs_block = re.search(r"val SPECS: List<OpSpec> = listOf\((.*)\)\n\n    fun spec",
                        kt_src, re.S).group(1)
check("token placeholder appears only in the PAIR op",
      specs_block.count("<TOKEN>") == 1 and "<TOKEN>" in kotlin_specs()["PAIR"][1][1],
      f"count in SPECS block={specs_block.count('<' + 'TOKEN' + '>')}")

print("2. data-plane routes (BridgeClient.kt ↔ docs/PROTOCOL.md)")
client_kt = (REPO / "android" / "app" / "src" / "main" / "java" / "dev" / "trmx" / "gui"
             / "net" / "BridgeClient.kt").read_text("utf-8")
protocol = (REPO / "docs" / "PROTOCOL.md").read_text("utf-8")
routes = {r.split("?")[0] for r in re.findall(r'"(/v1/[a-zA-Z0-9/?=&$_-]+)"', client_kt)}
# normalize Kotlin path templates ("$jobId") to the spec's {id} spelling
routes = {re.sub(r"\$\w+", "{id}", r) for r in routes}
check("app calls at least the handshake + jobs routes",
      {"/v1/system/info", "/v1/jobs"} <= routes, f"found={sorted(routes)}")
for r in sorted(routes):
    check(f"route {r} is defined in PROTOCOL.md", r in protocol)
check("client sends the protocol handshake header",
      '"X-TRMX-Protocol"' in client_kt and 'PROTOCOL_VERSION = "1"' in client_kt)
check("client sends bearer auth", '"Authorization", "Bearer $token"' in client_kt)
check("optional wire fields are encoded explicitly (env: null etc.)",
      "encodeDefaults = true" in client_kt)

# --- 3. embedded fixture payloads are byte-identical ------------------------

print("3. embedded fixture payloads (BridgeClientTest.kt ↔ fixtures/v1)")
test_kt = (REPO / "android" / "app" / "src" / "test" / "java" / "dev" / "trmx" / "gui"
           / "BridgeClientTest.kt").read_text("utf-8")
blocks = [b.strip() for b in re.findall(r'"""(.*?)"""', test_kt, re.S)]
fixtures = {p.name: p.read_text("utf-8").strip()
            for p in (REPO / "fixtures" / "v1").glob("*.json")}
check("embedded payload block count looks right", len(blocks) >= 8, f"got {len(blocks)}")
for b in blocks:
    byte_matches = [name for name, text in fixtures.items() if text == b]
    json_matches = []
    if not byte_matches:
        try:
            parsed = json.loads(b)
            json_matches = [name for name, text in fixtures.items()
                            if json.loads(text) == parsed]
        except ValueError:
            pass
    check("embedded payload matches a fixture (byte-identical or JSON-equal)",
          len(byte_matches) + len(json_matches) == 1,
          f"byte={byte_matches} json={json_matches}")

# --- 4. manifest declarations ----------------------------------------------

print("4. AndroidManifest.xml declarations")
manifest = (REPO / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text("utf-8")
check("RUN_COMMAND permission requested",
      'android:name="com.termux.permission.RUN_COMMAND"' in manifest)
check("package visibility for com.termux declared",
      '<package android:name="com.termux" />' in manifest)

# the data plane is cleartext HTTP on loopback — targetSdk>=28 blocks that
# unless a network security config permits it (on-device round 1 lesson)
nsc_path = (REPO / "android" / "app" / "src" / "main" / "res" / "xml"
            / "network_security_config.xml")
check("network security config present", nsc_path.is_file())
if nsc_path.is_file():
    nsc = nsc_path.read_text("utf-8")
    check("cleartext permitted for loopback",
          'cleartextTrafficPermitted="true"' in nsc and "127.0.0.1" in nsc)
    check("cleartext scope is loopback-only (no base-config blanket)",
          "<base-config" not in nsc and "10.0.2.2" not in nsc)
    check("manifest references the network security config",
          'android:networkSecurityConfig="@xml/network_security_config"' in manifest)
check("no blanket cleartext fallback in manifest",
      'android:usesCleartextTraffic="true"' not in manifest)

# --- 5. Kotlin comment balance (nested comments!) ---------------------------

print("5. Kotlin block-comment balance (they NEST — see Models.kt history)")
def comment_depth_at_eof(src: str) -> int:
    """Mini lexer: Kotlin block comments nest; strings are not comments."""
    i, depth = 0, 0
    while i < len(src):
        if src.startswith("/*", i):
            depth += 1; i += 2; continue
        if src.startswith("*/", i) and depth > 0:
            depth -= 1; i += 2; continue
        if depth == 0:
            if src.startswith("//", i):
                j = src.find("\n", i)
                i = len(src) if j == -1 else j
                continue
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                i = len(src) if j == -1 else j + 3
                continue
            if src[i] == '"':
                j = i + 1
                while j < len(src) and src[j] != '"':
                    if src[j] == "\\":
                        j += 1
                    j += 1
                i = j + 1
                continue
            if src[i] == "'":
                j = i + 1
                while j < len(src) and src[j] != "'":
                    if src[j] == "\\":
                        j += 1
                    j += 1
                i = j + 1
                continue
        i += 1
    return depth

kt_files = sorted((REPO / "android" / "app" / "src").rglob("*.kt"))
check("kotlin sources present", len(kt_files) > 0)
for f in kt_files:
    depth = comment_depth_at_eof(f.read_text("utf-8"))
    check(f"{f.relative_to(REPO)} comments balanced", depth == 0,
          f"block-comment depth at EOF = {depth} (a glob pattern inside a comment?)")

# --- verdict ----------------------------------------------------------------

print()
if FAILURES:
    print(f"CONFORMANCE FAILED — {len(FAILURES)} problem(s):")
    for f in FAILURES:
        print(f"  - {f}")
    sys.exit(1)
print("CONFORMANCE PASSED — app sources match the frozen contracts.")
