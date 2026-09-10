#!/usr/bin/env python3
"""Phase 9 tool-registry tests — PROTOCOL.md §7 against a real bridge.

Covers: §7.1 list/get/refresh (+ probing via PATH), §7.2 schema merge
(user overrides bundled; malformed user schemas skipped + reported),
argv synthesis rules (positional value, enum/bool/pattern/int bounds,
path resolution + §6.1 policy), TOOL_UNKNOWN/ARG_INVALID/PATH_* rejections,
progress_regex -> progress_pct/progress_detail on job + job.updated, and
the risk-tier audit line.

Run:  python3 termux/tests/test_tools.py   (or tests/run_tests.sh)
stdlib only (unittest, http.client, socket) — ADR-002 rule.
"""

from __future__ import annotations

import http.client
import json
import os
import shutil
import sqlite3
import stat
import tempfile
import time
import unittest
from pathlib import Path

import sys  # noqa: E402
from test_bridge import BridgeProc, free_port  # noqa: E402  (shared harness)

FAKETOOL_SCHEMA = {
    "id": "faketool",
    "name": "Fake Downloader",
    "description": "test double shaped like yt-dlp",
    "binary": "faketool",
    "risk_tier": "confirm",
    "fixed_argv": ["--newline"],
    "args": [
        {"name": "url", "label": "URL", "type": "url", "required": True},
        {"name": "format", "label": "Quality", "type": "enum",
         "enum": ["mp4", "mkv", "best"], "argv": ["-f", "{value}"]},
        {"name": "audio", "label": "Audio only", "type": "bool", "argv": ["-x"]},
        {"name": "rate", "label": "Rate", "type": "string",
         "pattern": r"^\d+[KM]$", "argv": ["-r", "{value}"]},
        {"name": "tries", "label": "Tries", "type": "int", "min": 1, "max": 9,
         "argv": ["-t", "{value}"]},
        {"name": "outdir", "label": "Out dir", "type": "path", "path_kind": "dir",
         "argv": ["-P", "{value}"]},
    ],
}

FAKEGIT_SCHEMA = {
    "id": "fakegit",
    "name": "Fake Git",
    "description": "git-style progress on STDERR with \\r overwrites",
    "binary": "fakegit",
    "risk_tier": "confirm",
    "progress_regex": r"Receiving objects:\s+(\d+)%",
    "args": [],
}

FAKESERVER_SCHEMA = {
    "id": "fakeserver",
    "name": "Fake Server",
    "description": "prints a port-binding line, no percent",
    "binary": "fakeserver",
    "risk_tier": "safe",
    "progress_regex": r"(Serving HTTP on .+ port \d+)",
    "args": [],
}

FAKEPROG_SCHEMA = {
    "id": "fakeprog",
    "name": "Fake Progress",
    "description": "emits yt-dlp-style progress lines",
    "binary": "fakeprog",
    "risk_tier": "safe",
    "progress_regex": r"\[download\]\s+(\d{1,3}(?:\.\d+)?)%",
    "args": [],
}


class TestTools(unittest.TestCase):
    """Owns its own bridge; fake binaries on PATH; path fixtures under the
    REAL user home (the §6.1 roots are [~ , ~/storage])."""

    home: str = ""
    port: int = 0
    bridge: BridgeProc
    token: str = ""
    outdir_abs: Path

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="trmx-tools-home-")
        cls.home = os.path.join(cls.tmp, "trmx-home")
        (Path(cls.home) / "tools").mkdir(parents=True)
        (Path(cls.home) / "bridge.json").write_text(json.dumps({}))
        # fake binaries on PATH (dev-lenient policy resolves via PATH)
        cls.binroot = Path(tempfile.mkdtemp(prefix="trmx-tools-bin-"))
        fake = cls.binroot / "faketool"
        fake.write_text("#!/bin/sh\n"
                        'if [ "$1" = "--version" ]; then echo "faketool 1.2.3"; exit 0; fi\n'
                        'echo "argv: $@"\n')
        fgit = cls.binroot / "fakegit"
        fgit.write_text("#!/bin/sh\n"
                        'if [ "$1" = "--version" ]; then echo "fakegit 2.43.0"; exit 0; fi\n'
                        'printf "Receiving objects:  45%% (9/20)\\rReceiving objects: 100%% (20/20), done.\\n" >&2\n'
                        'sleep 0.3\n'
                        'echo "clone finished"\n')
        fserver = cls.binroot / "fakeserver"
        fserver.write_text("#!/bin/sh\n"
                           'if [ "$1" = "--version" ]; then echo "fakeserver 3.11"; exit 0; fi\n'
                           'echo "Serving HTTP on 0.0.0.0 port 8000 (http://0.0.0.0:8000/) ..."\n'
                           "sleep 0.3\n"
                           'echo "server done"\n')
        prog = cls.binroot / "fakeprog"
        prog.write_text("#!/bin/sh\n"
                        'echo "[download]   5.0% of 10.00MiB"\n'
                        "sleep 0.3\n"
                        'echo "[download]  50.0% of 10.00MiB"\n'
                        "sleep 0.3\n"
                        'echo "[download] 100.0% of 10.00MiB"\n'
                        'echo "done"\n')
        for f in (fake, prog, fgit, fserver):
            f.chmod(f.stat().st_mode | stat.S_IXUSR)
        cls._old_path = os.environ["PATH"]
        os.environ["PATH"] = f"{cls.binroot}:{cls._old_path}"
        # user schemas (loaded at bridge start)
        (Path(cls.home) / "tools" / "a-fake.json").write_text(json.dumps(FAKETOOL_SCHEMA))
        (Path(cls.home) / "tools" / "b-prog.json").write_text(json.dumps(FAKEPROG_SCHEMA))
        (Path(cls.home) / "tools" / "c-ghost.json").write_text(json.dumps(
            {"id": "ghosttool", "name": "ghost", "binary": "no-such-binary-xyz",
             "args": []}))
        (Path(cls.home) / "tools" / "d-git.json").write_text(json.dumps(FAKEGIT_SCHEMA))
        (Path(cls.home) / "tools" / "e-server.json").write_text(json.dumps(FAKESERVER_SCHEMA))
        # output dir fixture under the real home
        cls.outdir_abs = Path(tempfile.mkdtemp(prefix="trmx-tools-out-", dir=str(Path.home())))
        cls.port = free_port()
        cls.bridge = BridgeProc(cls.home, cls.port)
        cls.bridge.start()
        cls.token = cls.bridge.token()

    @classmethod
    def tearDownClass(cls):
        cls.bridge.term()
        cls.bridge.close()
        os.environ["PATH"] = cls._old_path
        shutil.rmtree(cls.tmp, ignore_errors=True)
        shutil.rmtree(cls.binroot, ignore_errors=True)
        shutil.rmtree(cls.outdir_abs, ignore_errors=True)

    # ---- helpers ---------------------------------------------------------

    @classmethod
    def http(cls, method, path, body=None):
        conn = http.client.HTTPConnection("127.0.0.1", cls.port, timeout=15)
        hdrs = {"X-TRMX-Protocol": "1", "Connection": "close",
                "Authorization": f"Bearer {cls.token}"}
        if body is not None:
            body = json.dumps(body).encode()
            hdrs["Content-Type"] = "application/json"
        conn.request(method, path, body=body, headers=hdrs)
        resp = conn.getresponse()
        raw = resp.read()
        conn.close()
        return resp.status, dict(resp.getheaders()), raw

    @classmethod
    def submit(cls, payload):
        status, _, raw = cls.http("POST", "/v1/jobs", payload)
        return status, json.loads(raw)

    @classmethod
    def wait_job(cls, job_id, timeout=20):
        deadline = time.time() + timeout
        job = {}
        while time.time() < deadline:
            _, _, raw = cls.http("GET", f"/v1/jobs/{job_id}")
            job = json.loads(raw)
            if job.get("status") in ("COMPLETED", "FAILED", "CANCELLED", "LOST"):
                return job
            time.sleep(0.2)
        raise AssertionError(f"job {job_id} did not finish: {job}")

    def outdir_wire(self) -> str:
        return "~/" + str(self.outdir_abs.relative_to(Path.home()))

    # ---- §7.1 registry endpoints -----------------------------------------

    def test_01_list_shape_and_probe(self):
        status, _, raw = self.http("GET", "/v1/tools")
        self.assertEqual(status, 200, raw)
        tools = {t["schema"]["id"]: t for t in json.loads(raw)["tools"]}
        # bundled seven (media trio + git/python/http-server/tar) + user schemas
        for tid in ("yt-dlp", "ffmpeg", "aria2c", "git-clone", "python-run",
                    "http-server", "tar-backup", "faketool", "fakeprog"):
            self.assertIn(tid, tools)
        self.assertIn("installed", tools["faketool"])
        self.assertIn("version", tools["faketool"])
        self.assertTrue(tools["faketool"]["installed"])
        self.assertEqual(tools["faketool"]["version"], "faketool 1.2.3")
        # conformance: response items carry exactly the fixture's ToolStatus
        # keys (fixtures/v1/tools.list.response.json, added Phase 9)
        fx = json.loads((Path(__file__).resolve().parents[2]
                         / "fixtures/v1/tools.list.response.json").read_text())
        fx_keys = set(fx["tools"][0])
        for t in tools.values():
            self.assertEqual(set(t), fx_keys, t)

    def test_02_get_single_and_404(self):
        status, _, raw = self.http("GET", "/v1/tools/ffmpeg")
        self.assertEqual(status, 200, raw)
        obj = json.loads(raw)
        self.assertEqual(obj["schema"]["binary"], "ffmpeg")
        self.assertIn("installed", obj)
        status, _, raw = self.http("GET", "/v1/tools/nope-tool")
        self.assertEqual(status, 404, raw)
        self.assertEqual(json.loads(raw)["error"]["code"], "NOT_FOUND")

    def test_03_refresh(self):
        status, _, raw = self.http("POST", "/v1/tools/refresh", body={})
        self.assertEqual(status, 200, raw)
        tools = json.loads(raw)["tools"]
        # 7 bundled + 5 user (faketool, fakeprog, ghosttool, fakegit, fakeserver)
        self.assertEqual(len(tools), 12)

    # ---- §7.2 submit: synthesis -------------------------------------------

    def test_10_happy_path_synthesis(self):
        status, job = self.submit({
            "name": "fake download", "type": "tool", "tool": "faketool",
            "args": {"url": "https://example.com/watch?v=xyz", "format": "mp4",
                     "audio": False, "tries": 3, "outdir": self.outdir_wire()},
        })
        self.assertEqual(status, 201, job)
        self.assertEqual(job["status"], "QUEUED")
        done = self.wait_job(job["job_id"])   # full job via GET
        self.assertEqual(done["status"], "COMPLETED", done)
        self.assertEqual(done["exit_code"], 0)
        self.assertEqual(done["type"], "tool")
        self.assertEqual(done["tool"], "faketool")
        argv = done["argv"]
        self.assertTrue(argv[0].endswith("faketool"), argv)
        self.assertEqual(argv[1], "--newline")                       # fixed_argv
        self.assertIn("https://example.com/watch?v=xyz", argv)       # positional url
        i = argv.index("-f")
        self.assertEqual(argv[i + 1], "mp4")                         # enum
        i = argv.index("-t")
        self.assertEqual(argv[i + 1], "3")                           # int
        i = argv.index("-P")
        self.assertEqual(argv[i + 1], str(self.outdir_abs))          # path -> ABSOLUTE
        self.assertNotIn("-x", argv)                                 # bool False -> absent

    def test_11_bool_true_and_defaults(self):
        status, job = self.submit({
            "name": "audio", "type": "tool", "tool": "faketool",
            "args": {"url": "https://x.example/a", "audio": True},
        })
        self.assertEqual(status, 201, job)
        done = self.wait_job(job["job_id"])
        self.assertIn("-x", done["argv"])

    def test_12_tool_unknown(self):
        status, raw = self.submit({"name": "x", "type": "tool", "tool": "nope",
                                   "args": {}})
        self.assertEqual(status, 400)
        self.assertEqual(raw["error"]["code"], "TOOL_UNKNOWN")

    def test_13_arg_invalid_cases(self):
        base = {"name": "x", "type": "tool", "tool": "faketool"}
        cases = [
            ({"url": "not a url"}, "must be an http(s)"),          # url type
            ({}, "required arg 'url' is missing"),                     # required
            ({"url": "https://x.example/a", "format": "avi"}, "must be one of"),  # enum
            ({"url": "https://x.example/a", "rate": "500X"}, "must match pattern"),  # pattern
            ({"url": "https://x.example/a", "tries": 99}, "between 1 and 9"),      # int max
            ({"url": "https://x.example/a", "tries": "3"}, "must be an integer"),
            ({"url": "https://x.example/a", "audio": "yes"}, "must be true or false"),
            ({"url": "https://x.example/a", "bogus": 1}, "unknown arg 'bogus'"),
        ]
        for args, needle in cases:
            status, raw = self.submit({**base, "args": args})
            self.assertEqual(status, 400, (args, raw))
            self.assertEqual(raw["error"]["code"], "ARG_INVALID", args)
            self.assertIn(needle, raw["error"]["message"], args)

    def test_14_path_args_policy(self):
        base = {"name": "x", "type": "tool", "tool": "faketool",
                "args": {"url": "https://x.example/a"}}
        status, raw = self.submit({**base, "args": {**base["args"], "outdir": "/etc"}})
        self.assertEqual(status, 403, raw)
        self.assertEqual(raw["error"]["code"], "PATH_DENIED")
        status, raw = self.submit({**base, "args": {**base["args"],
                                                    "outdir": "~/_nope_dir_xyz"}})
        self.assertEqual(status, 404, raw)
        self.assertEqual(raw["error"]["code"], "PATH_NOT_FOUND")

    def test_15_binary_must_resolve(self):
        # ghost schema was present at bridge start (registry loads at boot)
        status, _, raw = self.http("GET", "/v1/tools/ghosttool")
        self.assertEqual(status, 200, raw)
        self.assertFalse(json.loads(raw)["installed"])
        status, raw = self.submit({"name": "x", "type": "tool", "tool": "ghosttool",
                                   "args": {}})
        self.assertEqual(status, 400, raw)
        self.assertEqual(raw["error"]["code"], "VALIDATION_FAILED")

    def test_16_progress_regex_updates_job(self):
        status, job = self.submit({"name": "prog", "type": "tool", "tool": "fakeprog",
                                   "args": {}})
        self.assertEqual(status, 201, job)
        done = self.wait_job(job["job_id"])
        self.assertEqual(done["status"], "COMPLETED", done)
        self.assertEqual(done["progress_pct"], 100.0, done)
        self.assertIn("100.0%", done["progress_detail"], done)

    def test_16b_stderr_progress_and_cr_lines(self):
        """git-style tools report progress on stderr with \\r overwrites —
        the generic parser must see the FINAL value."""
        status, job = self.submit({"name": "git", "type": "tool", "tool": "fakegit",
                                   "args": {}})
        self.assertEqual(status, 201, job)
        done = self.wait_job(job["job_id"])
        self.assertEqual(done["status"], "COMPLETED", done)
        self.assertEqual(done["progress_pct"], 100.0, done)
        self.assertIn("100%", done["progress_detail"], done)

    def test_16c_port_binding_line_becomes_detail_only(self):
        """Non-numeric capture groups (port bindings, key=value metrics)
        update progress_detail without inventing a percent."""
        status, job = self.submit({"name": "srv", "type": "tool", "tool": "fakeserver",
                                   "args": {}})
        self.assertEqual(status, 201, job)
        done = self.wait_job(job["job_id"])
        self.assertEqual(done["status"], "COMPLETED", done)
        self.assertIsNone(done["progress_pct"], done)
        self.assertIn("port 8000", done["progress_detail"], done)

    def test_18_refresh_reloads_schema_files(self):
        """A schema dropped into ~/.trmx/tools/ appears after refresh — no
        bridge restart (the drop-in / AI-schema-builder hook)."""
        tools_dir = Path(self.home) / "tools"
        (tools_dir / "f-late.json").write_text(json.dumps(
            {"id": "late-tool", "name": "Late", "binary": "no-such-bin-late",
             "args": []}))
        try:
            status, _, raw = self.http("POST", "/v1/tools/refresh", body={})
            self.assertEqual(status, 200, raw)
            ids = {t["schema"]["id"] for t in json.loads(raw)["tools"]}
            self.assertIn("late-tool", ids)
        finally:
            (tools_dir / "f-late.json").unlink()
        status, _, raw = self.http("POST", "/v1/tools/refresh", body={})
        ids = {t["schema"]["id"] for t in json.loads(raw)["tools"]}
        self.assertNotIn("late-tool", ids)

    def test_17_tier_audited(self):
        status, job = self.submit({"name": "audit", "type": "tool", "tool": "faketool",
                                   "args": {"url": "https://x.example/a"}})
        self.assertEqual(status, 201, job)
        self.wait_job(job["job_id"])
        db = sqlite3.connect(os.path.join(self.home, "jobs.db"))
        try:
            rows = db.execute(
                "SELECT detail FROM audit WHERE kind='job.submit' AND detail LIKE ?",
                (f"%{job['job_id']}%",)).fetchall()
        finally:
            db.close()
        self.assertTrue(rows, "audit row missing")
        self.assertIn("tool=faketool tier=confirm", rows[0][0], rows)


class TestRegistryOverrides(unittest.TestCase):
    """User schemas override bundled; malformed ones are skipped + reported."""

    home: str = ""
    port: int = 0
    bridge: BridgeProc
    token: str = ""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="trmx-ovr-home-")
        cls.home = os.path.join(cls.tmp, "trmx-home")
        tools = Path(cls.home) / "tools"
        tools.mkdir(parents=True)
        (Path(cls.home) / "bridge.json").write_text(json.dumps({}))
        # overrides bundled yt-dlp (same id)
        (tools / "a-override.json").write_text(json.dumps(
            {"id": "yt-dlp", "name": "MY yt-dlp", "binary": "yt-dlp",
             "risk_tier": "safe", "args": []}))
        # malformed: enum without list
        (tools / "b-broken.json").write_text(json.dumps(
            {"id": "broken", "binary": "sh", "args": [{"name": "x", "type": "enum"}]}))
        # not even JSON
        (tools / "c-garbage.json").write_text("{not json")
        cls.port = free_port()
        cls.bridge = BridgeProc(cls.home, cls.port)
        cls.bridge.start()
        cls.token = cls.bridge.token()

    @classmethod
    def tearDownClass(cls):
        cls.bridge.term()
        cls.bridge.close()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    @classmethod
    def http(cls, method, path, body=None):
        conn = http.client.HTTPConnection("127.0.0.1", cls.port, timeout=15)
        hdrs = {"X-TRMX-Protocol": "1", "Connection": "close",
                "Authorization": f"Bearer {cls.token}"}
        if body is not None:
            body = json.dumps(body).encode()
            hdrs["Content-Type"] = "application/json"
        conn.request(method, path, body=body, headers=hdrs)
        resp = conn.getresponse()
        raw = resp.read()
        conn.close()
        return resp.status, dict(resp.getheaders()), raw

    def test_20_user_overrides_bundled(self):
        _, _, raw = self.http("GET", "/v1/tools")
        tools = {t["schema"]["id"]: t for t in json.loads(raw)["tools"]}
        self.assertEqual(tools["yt-dlp"]["schema"]["name"], "MY yt-dlp")
        self.assertEqual(tools["yt-dlp"]["schema"]["args"], [])

    def test_21_malformed_reported_and_skipped(self):
        tools = {t["schema"]["id"] for t in json.loads(self.http("GET", "/v1/tools")[2])["tools"]}
        self.assertNotIn("broken", tools)
        _, _, raw = self.http("GET", "/v1/system/info")
        errs = json.loads(raw)["features"]["tool_schema_errors"]
        self.assertTrue(errs, "tool_schema_errors must be non-empty")
        self.assertTrue(any("b-broken" in e for e in errs), errs)
        self.assertTrue(any("c-garbage" in e for e in errs), errs)
        self.assertEqual(json.loads(raw)["tools_detected"], len(tools))

    def test_22_bundled_others_survive(self):
        tools = {t["schema"]["id"] for t in json.loads(self.http("GET", "/v1/tools")[2])["tools"]}
        self.assertIn("ffmpeg", tools)
        self.assertIn("aria2c", tools)


if __name__ == "__main__":
    unittest.main(verbosity=2)
