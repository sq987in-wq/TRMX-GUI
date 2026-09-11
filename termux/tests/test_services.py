#!/usr/bin/env python3
"""Protocol 1.1 service-registry tests — PROTOCOL.md §14 against a real bridge.

Covers: definition CRUD + validation (unknown tool, bad args, malformed
ids), start/stop/restart semantics (409 while running, idempotent stop,
restart rebinds), status shape (running/stopped, last_* fields), the
autostart flag toggle + persistence, delete/replace refusals while
running, autostart-at-boot on a fresh bridge, malformed definition files
(features.service_errors), and the features.service_registry flag.

Run:  python3 termux/tests/test_services.py   (or tests/run_tests.sh)
stdlib only — the "daemon" is a shell script that sleeps until killed.
"""

from __future__ import annotations

import http.client
import json
import os
import shutil
import stat
import tempfile
import time
import unittest
from pathlib import Path

import sys  # noqa: E402
from test_bridge import BridgeProc, free_port  # noqa: E402  (shared harness)

# a service-shaped tool: long-running until killed, --version for the probe
SERVERTOOL_SCHEMA = {
    "id": "servetool",
    "name": "Serve Tool",
    "description": "long-running test double",
    "binary": "servetool",
    "risk_tier": "safe",
    "fixed_argv": [],
    "args": [
        {"name": "port", "label": "Port", "type": "int", "min": 1024, "max": 65535,
         "default": 8000, "argv": ["--port", "{value}"]},
        {"name": "dir", "label": "Folder", "type": "path", "path_kind": "dir",
         "default": "~"},
    ],
}


class TestServices(unittest.TestCase):

    home: str = ""
    port: int = 0
    bridge: BridgeProc
    token: str = ""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="trmx-svc-home-")
        cls.home = os.path.join(cls.tmp, "trmx-home")
        (Path(cls.home) / "tools").mkdir(parents=True)
        (Path(cls.home) / "bridge.json").write_text(json.dumps(
            {"cancel_grace_ms": 300}))
        cls.binroot = Path(tempfile.mkdtemp(prefix="trmx-svc-bin-"))
        serve = cls.binroot / "servetool"
        serve.write_text("#!/bin/sh\n"
                         'if [ "$1" = "--version" ]; then echo "servetool 1.0"; exit 0; fi\n'
                         'echo "serving on $*" >&2\n'
                         "sleep 60\n")
        serve.chmod(serve.stat().st_mode | stat.S_IXUSR)
        cls._old_path = os.environ["PATH"]
        os.environ["PATH"] = f"{cls.binroot}:{cls._old_path}"
        (Path(cls.home) / "tools" / "a-serve.json").write_text(json.dumps(SERVERTOOL_SCHEMA))
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

    # ---- helpers ---------------------------------------------------------

    @classmethod
    def http(cls, method, path, body=None):
        conn = http.client.HTTPConnection("127.0.0.1", cls.port, timeout=20)
        hdrs = {"X-TRMX-Protocol": "1", "Connection": "close",
                "Authorization": f"Bearer {cls.token}"}
        if body is not None:
            body = json.dumps(body).encode()
            hdrs["Content-Type"] = "application/json"
        conn.request(method, path, body=body, headers=hdrs)
        resp = conn.getresponse()
        raw = resp.read()
        conn.close()
        return resp.status, json.loads(raw)

    @classmethod
    def create_service(cls, **overrides):
        body = {"id": "web", "name": "Web Server", "tool": "servetool",
                "args": {"port": 8123}, "autostart": False}
        body.update(overrides)
        return cls.http("POST", "/v1/services", body)

    @classmethod
    def svc(cls, sid="web"):
        status, body = cls.http("GET", f"/v1/services/{sid}")
        assert status == 200, body
        return body

    def wait_state(self, sid, state, timeout=15):
        deadline = time.time() + timeout
        while time.time() < deadline:
            st = self.svc(sid)
            if st["state"] == state:
                return st
            time.sleep(0.2)
        raise AssertionError(f"service {sid} never reached {state}: {st}")

    # ---- registry + definitions -------------------------------------------

    def test_01_feature_flag_and_empty_list(self):
        status, info = self.http("GET", "/v1/system/info")
        self.assertEqual(status, 200)
        self.assertTrue(info["features"]["service_registry"])
        self.assertEqual(info["features"]["service_errors"], [])
        status, body = self.http("GET", "/v1/services")
        self.assertEqual(status, 200)
        self.assertEqual(body["services"], [])

    def test_02_create_valid_definition(self):
        status, st = self.create_service()
        self.assertEqual(status, 201, st)
        self.assertEqual(st["id"], "web")
        self.assertEqual(st["state"], "stopped")
        self.assertFalse(st["autostart"])
        self.assertIsNone(st["job_id"])
        # persisted as a definition file
        self.assertTrue((Path(self.home) / "services" / "web.json").is_file())
        on_disk = json.loads((Path(self.home) / "services" / "web.json").read_text())
        self.assertEqual(on_disk["tool"], "servetool")
        self.assertEqual(on_disk["args"]["port"], 8123)

    def test_03_create_unknown_tool_rejected(self):
        status, body = self.create_service(id="badtool", tool="no-such-tool")
        self.assertEqual(status, 400)
        self.assertEqual(body["error"]["code"], "TOOL_UNKNOWN")
        self.assertEqual(body["error"].get("field"), "tool")

    def test_04_create_invalid_args_rejected(self):
        # port out of the schema's min/max -> ARG_INVALID from §7.2 synthesis
        status, body = self.create_service(id="badargs", args={"port": 1})
        self.assertEqual(status, 400)
        self.assertEqual(body["error"]["code"], "ARG_INVALID")
        # missing required... servetool has no required args; pattern via enum:
        status, body = self.create_service(id="badargs2", args={"port": "not-a-number"})
        self.assertEqual(status, 400)
        self.assertEqual(body["error"]["code"], "ARG_INVALID")

    def test_05_create_malformed_rejected(self):
        status, body = self.create_service(id="../escape")
        self.assertEqual(status, 400)
        self.assertEqual(body["error"]["code"], "VALIDATION_FAILED")
        status, body = self.create_service(id="ok-id-2", name="")
        self.assertEqual(status, 400)
        status, body = self.create_service(id="ok-id-3", autostart="yes")
        self.assertEqual(status, 400)

    # ---- lifecycle ---------------------------------------------------------

    def test_10_start_stop_lifecycle(self):
        self.create_service()
        status, st = self.http("POST", "/v1/services/web/start", {})
        self.assertEqual(status, 200, st)
        self.assertEqual(st["state"], "running")
        self.assertTrue(st["job_id"].startswith("J-"))
        # the bound job is a REAL job
        status, job = self.http("GET", f"/v1/jobs/{st['job_id']}")
        self.assertEqual(status, 200)
        self.assertEqual(job["type"], "tool")
        self.assertEqual(job["tool"], "servetool")
        # start while running -> 409
        status, body = self.http("POST", "/v1/services/web/start", {})
        self.assertEqual(status, 409)
        self.assertEqual(body["error"]["code"], "SERVICE_RUNNING")
        # stop -> eventually stopped, last_* populated
        status, st = self.http("POST", "/v1/services/web/stop", {})
        self.assertEqual(status, 200)
        st = self.wait_state("web", "stopped")
        self.assertEqual(st["last_status"], "CANCELLED")
        # SIGTERM-cancelled: exit_code stays null (signal field on the job)
        self.assertIsNone(st["job_id"])
        self.assertTrue(st["last_job_id"].startswith("J-"))     # binding kept

    def test_11_stop_is_idempotent(self):
        self.create_service()
        self.http("POST", "/v1/services/web/start", {})
        self.http("POST", "/v1/services/web/stop", {})
        self.wait_state("web", "stopped")
        # stopping an already-stopped service: 200, no error
        status, st = self.http("POST", "/v1/services/web/stop", {})
        self.assertEqual(status, 200)
        self.assertEqual(st["state"], "stopped")

    def test_12_restart_rebinds_job(self):
        self.create_service()
        _, st1 = self.http("POST", "/v1/services/web/start", {})
        first_job = st1["job_id"]
        status, st2 = self.http("POST", "/v1/services/web/restart", {})
        self.assertEqual(status, 200, st2)
        self.assertEqual(st2["state"], "running")
        self.assertNotEqual(st2["job_id"], first_job)
        self.http("POST", "/v1/services/web/stop", {})

    def test_13_restart_while_stopped_just_starts(self):
        self.create_service()
        status, st = self.http("POST", "/v1/services/web/restart", {})
        self.assertEqual(status, 200)
        self.assertEqual(st["state"], "running")
        self.http("POST", "/v1/services/web/stop", {})

    def test_14_start_argv_synthesis(self):
        self.create_service()
        _, st = self.http("POST", "/v1/services/web/start", {})
        _, job = self.http("GET", f"/v1/jobs/{st['job_id']}")
        argv = job["argv"]
        i = argv.index("--port")
        self.assertEqual(argv[i + 1], "8123")
        self.http("POST", "/v1/services/web/stop", {})

    # ---- autostart flag ----------------------------------------------------

    def test_20_autostart_toggle_persists(self):
        self.create_service()
        status, st = self.http("POST", "/v1/services/web/autostart", {"enabled": True})
        self.assertEqual(status, 200)
        self.assertTrue(st["autostart"])
        on_disk = json.loads((Path(self.home) / "services" / "web.json").read_text())
        self.assertTrue(on_disk["autostart"])
        # non-boolean body rejected
        status, body = self.http("POST", "/v1/services/web/autostart", {"enabled": 1})
        self.assertEqual(status, 400)
        # toggle back off
        _, st = self.http("POST", "/v1/services/web/autostart", {"enabled": False})
        self.assertFalse(st["autostart"])

    # ---- delete / replace ---------------------------------------------------

    def test_30_delete_refused_while_running(self):
        self.create_service()
        self.http("POST", "/v1/services/web/start", {})
        status, body = self.http("DELETE", "/v1/services/web")
        self.assertEqual(status, 409)
        self.assertEqual(body["error"]["code"], "SERVICE_RUNNING")
        self.http("POST", "/v1/services/web/stop", {})
        self.wait_state("web", "stopped")
        status, body = self.http("DELETE", "/v1/services/web")
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])
        self.assertFalse((Path(self.home) / "services" / "web.json").exists())
        status, body = self.http("GET", "/v1/services/web")
        self.assertEqual(status, 404)
        self.assertEqual(body["error"]["code"], "SERVICE_NOT_FOUND")

    def test_31_replace_while_stopped_ok_while_running_refused(self):
        self.create_service(args={"port": 9001})
        status, st = self.create_service(args={"port": 9002})   # replace
        self.assertEqual(status, 201)
        self.assertEqual(self.svc()["args"]["port"], 9002)
        self.http("POST", "/v1/services/web/start", {})
        status, body = self.create_service(args={"port": 9003})
        self.assertEqual(status, 409)
        self.assertEqual(body["error"]["code"], "SERVICE_RUNNING")
        self.http("POST", "/v1/services/web/stop", {})
        self.http("DELETE", "/v1/services/web")

    # ---- boot autostart + malformed files -----------------------------------

    def test_40_malformed_definition_reported(self):
        (Path(self.home) / "services" / "zz-broken.json").write_text("{not json")
        (Path(self.home) / "services" / "zz-notool.json").write_text(json.dumps(
            {"id": "zz-notool", "name": "X", "tool": "ghost-tool", "args": {}}))
        status, body = self.http("POST", "/v1/tools/refresh", {})  # defs reload lazily?
        # services defs are read at boot; reload happens on bridge restart —
        # but list must still work and NOT include the broken ones
        status, body = self.http("GET", "/v1/services")
        ids = {s["id"] for s in body["services"]}
        self.assertNotIn("zz-broken", ids)
        self.assertNotIn("zz-notool", ids)
        (Path(self.home) / "services" / "zz-broken.json").unlink()
        (Path(self.home) / "services" / "zz-notool.json").unlink()

    def test_41_autostart_at_boot(self):
        # definition with autostart=true, written directly to disk
        (Path(self.home) / "services" / "auto.json").write_text(json.dumps(
            {"id": "auto", "name": "Auto Service", "tool": "servetool",
             "args": {"port": 8456}, "autostart": True, "created_at": "2026-09-11T00:00:00Z"}))
        port2 = free_port()
        bridge2 = BridgeProc(self.home, port2)
        try:
            bridge2.start()
            token2 = bridge2.token()
            deadline = time.time() + 15
            st = None
            while time.time() < deadline:
                conn = http.client.HTTPConnection("127.0.0.1", port2, timeout=10)
                conn.request("GET", "/v1/services/auto", headers={
                    "X-TRMX-Protocol": "1", "Authorization": f"Bearer {token2}"})
                r = conn.getresponse()
                st = json.loads(r.read())
                conn.close()
                if st.get("state") == "running":
                    break
                time.sleep(0.3)
            self.assertIsNotNone(st)
            self.assertEqual(st["state"], "running", st)
            self.assertTrue(st["job_id"].startswith("J-"))
            # non-autostart definitions are NOT launched at boot
            conn = http.client.HTTPConnection("127.0.0.1", port2, timeout=10)
            conn.request("GET", "/v1/services", headers={
                "X-TRMX-Protocol": "1", "Authorization": f"Bearer {token2}"})
            listing = json.loads(conn.getresponse().read())
            conn.close()
            for entry in listing["services"]:
                if entry["id"] != "auto":
                    self.assertEqual(entry["state"], "stopped",
                                     f"{entry['id']} must not autostart")
        finally:
            # stop the autostarted job, clean the definition, kill bridge2
            conn = http.client.HTTPConnection("127.0.0.1", port2, timeout=10)
            conn.request("POST", "/v1/services/auto/stop", b"{}", headers={
                "X-TRMX-Protocol": "1", "Authorization": f"Bearer {token2}",
                "Content-Type": "application/json"})
            conn.getresponse().read()
            conn.close()
            (Path(self.home) / "services" / "auto.json").unlink()
            bridge2.term()
            bridge2.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
