#!/usr/bin/env python3
"""Phase 2 bridge tests — drive a real trmx-bridge subprocess over TCP.

Covers the Phase 2 DoD (Phase 0 report §H): submit → live stream → cancel →
restart → reconcile, plus the §J failure matrix subset reachable without
Android: auth failures, malformed requests, replay, eviction, queueing,
idempotency, and contract-shape checks against fixtures/v1/.

Run:  python3 termux/tests/test_bridge.py   (or tests/run_tests.sh)
stdlib only (unittest, socket, http.client) — ADR-002 rule.
"""

from __future__ import annotations

import http.client
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
BRIDGE = HERE.parent / "trmx-bridge.py"
FIXTURES = HERE.parents[1] / "fixtures" / "v1"

TERMINAL = {"COMPLETED", "FAILED", "CANCELLED", "LOST"}


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class BridgeProc:
    """A trmx-bridge subprocess bound to a test home + port."""

    def __init__(self, home: str, port: int):
        self.home, self.port = home, port
        self.proc: subprocess.Popen | None = None
        self.logpath = Path(home) / "test-bridge-stdout.log"

    def start(self) -> None:
        logf = open(self.logpath, "ab")
        self._logf = logf
        self.proc = subprocess.Popen(
            [sys.executable, str(BRIDGE), "--home", self.home, "--port", str(self.port)],
            stdout=logf, stderr=logf, stdin=subprocess.DEVNULL)
        deadline = time.time() + 15
        while time.time() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=0.3):
                    # verify the port is served by OUR child, not a phantom
                    pidfile = Path(self.home) / "bridge.pid"
                    if pidfile.exists():
                        serving = int(pidfile.read_text().strip())
                        if serving != self.proc.pid:
                            raise AssertionError(
                                f"port {self.port} is served by pid {serving}, but our child is "
                                f"{self.proc.pid} — a previous bridge survived! log:\n"
                                + self.log_tail())
                    return
            except OSError:
                if self.proc.poll() is not None:
                    break
                time.sleep(0.05)
        raise AssertionError(
            f"bridge did not start (rc={self.proc.poll() if self.proc else '?'}); log:\n"
            + self.log_tail())

    def log_tail(self, n: int = 40) -> str:
        try:
            return "\n".join(self.logpath.read_text("utf-8", errors="replace").splitlines()[-n:])
        except OSError:
            return "<no log>"

    def token(self) -> str:
        cfg = json.loads((Path(self.home) / "bridge.json").read_text("utf-8"))
        return cfg["token"]

    def kill9(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.send_signal(signal.SIGKILL)
            self.proc.wait()
        self.close()
        time.sleep(0.2)
        try:
            with socket.create_connection(("127.0.0.1", self.port), timeout=0.3):
                raise AssertionError(
                    f"kill9: our child {self.proc.pid if self.proc else '?'} was killed but "
                    f"port {self.port} still answers — a phantom bridge is alive. log:\n"
                    + self.log_tail())
        except (ConnectionRefusedError, OSError):
            pass  # port closed as expected

    def close(self) -> None:
        logf = getattr(self, "_logf", None)
        if logf:
            logf.close()

    def term(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.send_signal(signal.SIGTERM)
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()
        self.close()


class SSEClient:
    """Minimal SSE reader over a raw socket (Connection: close streams)."""

    def __init__(self, port: int, path: str, token: str, extra_headers: dict | None = None):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=10)
        req = (f"GET {path} HTTP/1.1\r\nHost: 127.0.0.1\r\n"
               f"Authorization: Bearer {token}\r\nX-TRMX-Protocol: 1\r\n"
               f"Connection: close\r\n")
        for k, v in (extra_headers or {}).items():
            req += f"{k}: {v}\r\n"
        req += "\r\n"
        self.sock.sendall(req.encode())
        self.buf = b""
        while b"\r\n\r\n" not in self.buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise AssertionError("connection closed before headers")
            self.buf += chunk
        head, self.buf = self.buf.split(b"\r\n\r\n", 1)
        self.status = int(head.split(b" ", 2)[1])
        self.headers = head.decode("iso-8859-1")

    def frames(self, timeout: float):
        """Yield (event, id, data) tuples until close or timeout."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            while b"\n\n" in self.buf:
                block, self.buf = self.buf.split(b"\n\n", 1)
                ev = eid = None
                data = None
                for line in block.decode("utf-8", errors="replace").split("\n"):
                    if line.startswith("event: "):
                        ev = line[7:]
                    elif line.startswith("id: "):
                        eid = line[4:]
                    elif line.startswith("data: "):
                        data = json.loads(line[6:])
                if ev is not None or data is not None:
                    yield ev, eid, data
            self.sock.settimeout(max(0.1, deadline - time.time()))
            try:
                chunk = self.sock.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                return
            self.buf += chunk

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


class TestBridge(unittest.TestCase):
    home: str = ""
    port: int = 0
    bridge: BridgeProc
    token: str = ""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="trmx-test-")
        cls.home = os.path.join(cls.tmp, "trmx-home")
        os.makedirs(cls.home, exist_ok=True)
        # small ring so the eviction test can trigger rotation cheaply
        (Path(cls.home) / "bridge.json").write_text(json.dumps({"log_ring_bytes": 1500}))
        cls.port = free_port()
        cls.bridge = BridgeProc(cls.home, cls.port)
        cls.bridge.start()
        cls.token = cls.bridge.token()

    @classmethod
    def tearDownClass(cls):
        # kill lingering job children FIRST (bridge must be alive to list them)
        try:
            for job in cls._list_all():
                if job["status"] in ("RUNNING", "CANCELLING", "STARTING"):
                    try:
                        os.killpg(job["pgid"], signal.SIGKILL)
                    except (OSError, TypeError):
                        pass
        except OSError:
            pass
        cls.bridge.term()
        cls.bridge.close()
        # keep artifacts for post-mortem debugging (tiny; overwritten each run)
        try:
            art = Path("/tmp/trmx-last-run")
            art.mkdir(parents=True, exist_ok=True)
            for f in Path(cls.home).glob("jobs.db*"):
                shutil.copy2(f, art / f.name)
            logp = Path(cls.home) / "test-bridge-stdout.log"
            if logp.exists():
                shutil.copy2(logp, art / "bridge.log")
        except OSError:
            pass
        shutil.rmtree(cls.tmp, ignore_errors=True)

    # ---- helpers -------------------------------------------------------

    @classmethod
    def http(cls, method, path, body=None, token="default", headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", cls.port, timeout=15)
        hdrs = {"X-TRMX-Protocol": "1", "Connection": "close"}
        if token == "default":
            hdrs["Authorization"] = f"Bearer {cls.token}"
        elif token:
            hdrs["Authorization"] = f"Bearer {token}"
        if body is not None:
            body = json.dumps(body).encode()
            hdrs["Content-Type"] = "application/json"
        hdrs.update(headers or {})
        conn.request(method, path, body=body, headers=hdrs)
        resp = conn.getresponse()
        raw = resp.read()
        conn.close()
        return resp.status, dict(resp.getheaders()), raw

    @classmethod
    def submit(cls, argv, **kw) -> str:
        payload = {"type": "argv", "argv": argv, "name": kw.pop("name", "test job")}
        payload.update(kw)
        status, _, raw = cls.http("POST", "/v1/jobs", payload)
        assert status == 201, (status, raw)
        return json.loads(raw)["job_id"]

    @classmethod
    def wait_status(cls, job_id, statuses, timeout=20):
        job = None
        deadline = time.time() + timeout
        while time.time() < deadline:
            _, _, raw = cls.http("GET", f"/v1/jobs/{job_id}")
            job = json.loads(raw)
            if job["status"] in statuses:
                return job
            time.sleep(0.1)
        raise AssertionError(f"{job_id} never reached {statuses}; last={json.dumps(job)}")

    @classmethod
    def _list_all(cls):
        _, _, raw = cls.http("GET", "/v1/jobs?limit=200")
        return json.loads(raw)["jobs"]

    # ---- 01..02: handshake & auth --------------------------------------

    def test_01_system_info_contract(self):
        status, _, raw = self.http("GET", "/v1/system/info")
        self.assertEqual(status, 200)
        info = json.loads(raw)
        fixture = json.loads((FIXTURES / "system.info.response.json").read_text())
        self.assertEqual(set(info.keys()), set(fixture.keys()),
                         "system/info shape must match the fixture exactly")
        self.assertIn(1, info["protocol_versions"])

    def test_02_auth_and_guards(self):
        s, _, raw = self.http("GET", "/v1/system/info", token=None)
        self.assertEqual((s, json.loads(raw)["error"]["code"]), (401, "AUTH_REQUIRED"))
        s, _, raw = self.http("GET", "/v1/system/info", token="wrong-token-1234567890abcdef")
        self.assertEqual((s, json.loads(raw)["error"]["code"]), (401, "AUTH_INVALID"))
        s, _, raw = self.http("GET", "/v1/system/info",
                              headers={"Origin": "https://evil.example"})
        self.assertEqual((s, json.loads(raw)["error"]["code"]), (403, "ORIGIN_DENIED"))
        s, _, raw = self.http("GET", "/v1/system/info", headers={"X-TRMX-Protocol": "2"})
        self.assertEqual((s, json.loads(raw)["error"]["code"]), (400, "PROTOCOL_MISMATCH"))
        # missing protocol header entirely
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request("GET", "/v1/system/info",
                     headers={"Authorization": f"Bearer {self.token}", "Connection": "close"})
        resp = conn.getresponse()
        body = json.loads(resp.read())
        conn.close()
        self.assertEqual((resp.status, body["error"]["code"]), (400, "PROTOCOL_MISMATCH"))

    def test_02b_malformed(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request("POST", "/v1/jobs", body=b"{not json",
                     headers={"Authorization": f"Bearer {self.token}",
                              "X-TRMX-Protocol": "1", "Connection": "close"})
        resp = conn.getresponse()
        body = json.loads(resp.read())
        conn.close()
        self.assertEqual((resp.status, body["error"]["code"]), (400, "VALIDATION_FAILED"))
        s, _, raw = self.http("GET", "/v1/nosuchroute")
        self.assertEqual((s, json.loads(raw)["error"]["code"]), (404, "NOT_FOUND"))
        s, _, raw = self.http("DELETE", "/v1/jobs")
        self.assertEqual((s, json.loads(raw)["error"]["code"]), (405, "METHOD_NOT_ALLOWED"))

    def test_02c_submit_validation(self):
        s, _, raw = self.http("POST", "/v1/jobs", {"type": "argv", "argv": ["definitely-not-a-binary-xyz"]})
        self.assertEqual((s, json.loads(raw)["error"]["code"]), (400, "VALIDATION_FAILED"))
        s, _, raw = self.http("POST", "/v1/jobs", {"type": "shell", "script": "echo hi"})
        self.assertEqual(json.loads(raw)["error"]["code"], "VALIDATION_FAILED")
        s, _, raw = self.http("POST", "/v1/jobs", {"type": "argv", "argv": ["sh"], "cwd": "/no/such/dir"})
        self.assertEqual((s, json.loads(raw)["error"]["code"]), (404, "PATH_NOT_FOUND"))
        s, _, raw = self.http("POST", "/v1/jobs", {"type": "argv", "argv": [], "name": "x"})
        self.assertEqual(json.loads(raw)["error"]["code"], "VALIDATION_FAILED")

    # ---- 03..04: lifecycle ---------------------------------------------

    def test_03_completed_job_contract(self):
        job_id = self.submit(["sh", "-c", "echo hello; echo oops 1>&2"], name="ok job")
        job = self.wait_status(job_id, TERMINAL)
        self.assertEqual(job["status"], "COMPLETED")
        self.assertEqual(job["exit_code"], 0)
        fixture = json.loads((FIXTURES / "jobs.get.response.json").read_text())
        self.assertEqual(set(job.keys()), set(fixture.keys()),
                         "job object shape must match the fixture exactly")
        self.assertGreater(job["stdout_bytes"], 0)
        self.assertGreater(job["stderr_bytes"], 0)
        # replay stdout and check content
        sse = SSEClient(self.port, f"/v1/jobs/{job_id}/output?stream=stdout&follow=0", self.token)
        self.assertEqual(sse.status, 200)
        texts = [d["text"] for e, i, d in sse.frames(5) if e == "stdout"]
        sse.close()
        self.assertIn("hello\n", "".join(texts))

    def test_04_failed_job(self):
        job_id = self.submit(["sh", "-c", "exit 3"], name="fail job")
        job = self.wait_status(job_id, TERMINAL)
        self.assertEqual(job["status"], "FAILED")
        self.assertEqual(job["exit_code"], 3)
        self.assertIsNone(job["error"])

    def test_04b_signal_death(self):
        job_id = self.submit(["sh", "-c", "kill -9 $$"], name="signal job")
        job = self.wait_status(job_id, TERMINAL)
        self.assertEqual(job["status"], "FAILED")
        self.assertEqual(job["signal"], 9)

    # ---- 05..06: streaming & replay ------------------------------------

    def test_05_stream_follow_live(self):
        script = "for i in 1 2 3; do echo out$i; echo err$i >&2; sleep 0.25; done"
        job_id = self.submit(["sh", "-c", script], name="stream job")
        sse = SSEClient(self.port, f"/v1/jobs/{job_id}/output?follow=1", self.token)
        self.assertEqual(sse.status, 200)
        frames = list(sse.frames(20))
        sse.close()
        seqs = [d["seq"] for e, i, d in frames]
        self.assertEqual(seqs, sorted(seqs), "seq must be monotonic")
        statuses = [d for e, i, d in frames if e == "status"]
        self.assertEqual(statuses[-1]["status"], "COMPLETED",
                         "stream must end with the terminal status frame")
        self.assertEqual(statuses[0]["status"], "RUNNING")
        out = "".join(d["text"] for e, i, d in frames if e == "stdout")
        err = "".join(d["text"] for e, i, d in frames if e == "stderr")
        for i in (1, 2, 3):
            self.assertIn(f"out{i}\n", out)
            self.assertIn(f"err{i}\n", err)

    def test_06_replay_from_seq(self):
        script = "printf a; sleep 0.2; printf b; sleep 0.2; printf c"
        job_id = self.submit(["sh", "-c", script], name="replay job")
        self.wait_status(job_id, TERMINAL)
        sse = SSEClient(self.port, f"/v1/jobs/{job_id}/output?follow=0", self.token)
        all_frames = list(sse.frames(5))
        sse.close()
        self.assertGreaterEqual(len(all_frames), 5)  # 3 stdout + 2 status at least
        from_seq = 3
        sse = SSEClient(self.port,
                        f"/v1/jobs/{job_id}/output?from_seq={from_seq}&follow=0", self.token)
        tail = list(sse.frames(5))
        sse.close()
        self.assertTrue(all(d["seq"] >= from_seq for e, i, d in tail))
        self.assertEqual(tail[0][2]["seq"], from_seq, "replay must start exactly at from_seq")
        # only the terminal status frame is >= seq 3 (RUNNING was seq 1)
        self.assertEqual(sum(1 for e, i, d in tail if e == "status"), 1)

    # ---- 07..09: cancel, timeout, idempotency --------------------------

    def test_07_cancel(self):
        job_id = self.submit(["sleep", "30"], name="cancel me")
        self.wait_status(job_id, {"RUNNING"})
        t0 = time.time()
        s, _, raw = self.http("POST", f"/v1/jobs/{job_id}/cancel",
                              {"grace_ms": 1500, "force": False})
        self.assertEqual(s, 200)
        self.assertEqual(json.loads(raw)["status"], "CANCELLING")
        job = self.wait_status(job_id, {"CANCELLED"}, timeout=10)
        self.assertLess(time.time() - t0, 8)
        self.assertEqual(job["cancel_reason"], "user")
        self.assertIn(job["signal"], (signal.SIGTERM, signal.SIGKILL))
        # idempotent cancel on terminal job
        s, _, raw = self.http("POST", f"/v1/jobs/{job_id}/cancel", {})
        self.assertEqual(s, 200)
        self.assertEqual(json.loads(raw)["status"], "CANCELLED")

    def test_08_timeout(self):
        job_id = self.submit(["sleep", "20"], name="timeout job", timeout_s=1)
        job = self.wait_status(job_id, {"CANCELLED"}, timeout=10)
        self.assertEqual(job["cancel_reason"], "timeout")

    def test_09_idempotency(self):
        key = "idem-" + str(time.time_ns())
        j1 = self.submit(["echo", "one"], idempotency_key=key)
        self.wait_status(j1, TERMINAL)
        s, headers, raw = self.http("POST", "/v1/jobs",
                                    {"type": "argv", "argv": ["echo", "two"],
                                     "idempotency_key": key})
        self.assertEqual(s, 201)
        self.assertEqual(json.loads(raw)["job_id"], j1)
        self.assertEqual(headers.get("Idempotent-Replay"), "true")

    # ---- 10: queueing ----------------------------------------------------

    def test_10_queue_and_concurrency(self):
        ids = [self.submit(["sleep", "20"], name=f"q{i}") for i in range(5)]
        deadline = time.time() + 10
        while time.time() < deadline:
            jobs = {j["job_id"]: j["status"] for j in self._list_all()
                    if j["job_id"] in ids}
            running = sum(1 for s in jobs.values() if s == "RUNNING")
            queued = sum(1 for s in jobs.values() if s == "QUEUED")
            if running == 4 and queued == 1:
                break
            time.sleep(0.1)
        else:
            self.fail(f"expected 4 RUNNING + 1 QUEUED, got {jobs}")
        for jid in ids:
            self.http("POST", f"/v1/jobs/{jid}/cancel", {"grace_ms": 500})
        for jid in ids:
            self.wait_status(jid, {"CANCELLED"}, timeout=15)

    # ---- 11: restart reconciliation (the DoD demo) ----------------------

    def test_11a_restart_adopt_then_cancel(self):
        job_id = self.submit(["sleep", "60"], name="adopt me")
        running = self.wait_status(job_id, {"RUNNING"})
        pgid = running["pgid"]
        self.bridge.kill9()                      # simulate a hard crash
        os.killpg(pgid, 0)                       # child survived the crash
        TestBridge.bridge = BridgeProc(self.home, self.port)
        TestBridge.bridge.start()
        job = self.wait_status(job_id, {"RUNNING", "CANCELLING"})
        self.assertEqual(job["status"], "RUNNING", "orphaned job must be re-adopted")
        # stream replay shows the reattach notice
        sse = SSEClient(self.port, f"/v1/jobs/{job_id}/output?follow=1", self.token)
        infos = [d for e, i, d in sse.frames(3) if e == "info"]
        sse.close()
        self.assertTrue(any(d.get("type") == "reattached" for d in infos),
                        f"expected a reattached info frame, got {infos}")
        s, _, _ = self.http("POST", f"/v1/jobs/{job_id}/cancel", {"grace_ms": 1000})
        self.assertEqual(s, 200)
        job = self.wait_status(job_id, {"CANCELLED"}, timeout=15)
        self.assertEqual(job["cancel_reason"], "user")

    def test_11b_restart_lost(self):
        job_id = self.submit(["sleep", "60"], name="lose me")
        running = self.wait_status(job_id, {"RUNNING"})
        self.bridge.kill9()
        os.killpg(running["pgid"], signal.SIGKILL)   # the job died with the bridge
        TestBridge.bridge = BridgeProc(self.home, self.port)
        TestBridge.bridge.start()
        job = self.wait_status(job_id, {"LOST"})
        self.assertEqual(job["status"], "LOST")

    # ---- 12: ring eviction ------------------------------------------------

    def test_12_ring_eviction_is_honest(self):
        job_id = self.submit(["sh", "-c", "seq 1 400"], name="spew")
        job = self.wait_status(job_id, TERMINAL)
        self.assertTrue(job["log_truncated"], "ring cap (1500B) must have been hit")
        sse = SSEClient(self.port, f"/v1/jobs/{job_id}/output?from_seq=1&follow=0", self.token)
        frames = list(sse.frames(10))
        sse.close()
        info = [d for e, i, d in frames if e == "info"]
        self.assertTrue(info and info[0].get("type") == "evicted"
                        and info[0]["resume_from_seq"] > 1,
                        f"expected an evicted notice first, got: {frames[:2]}")

    # ---- 13: global events ------------------------------------------------

    def test_13_events_stream(self):
        sse = SSEClient(self.port, "/v1/events", self.token)
        self.assertEqual(sse.status, 200)
        job_id = self.submit(["echo", "events"], name="events job")
        seen = None
        for ev, eid, data in sse.frames(15):
            if ev == "job.updated" and data.get("job_id") == job_id:
                seen = (ev, data)
                if data["status"] in TERMINAL:
                    break
        sse.close()
        self.assertIsNotNone(seen, "job.updated events must reach /v1/events")
        self.assertEqual(seen[1]["status"], "COMPLETED")

    # ---- 14: list / pagination --------------------------------------------

    def test_14_list_pagination_and_filter(self):
        a = self.submit(["echo", "a"], name="page a")
        b = self.submit(["echo", "b"], name="page b")
        self.wait_status(a, TERMINAL)
        self.wait_status(b, TERMINAL)
        s, _, raw = self.http("GET", "/v1/jobs?limit=2")
        page1 = json.loads(raw)
        self.assertEqual(len(page1["jobs"]), 2)
        self.assertIsNotNone(page1["next_cursor"])
        s, _, raw = self.http("GET", f"/v1/jobs?limit=200&before={page1['next_cursor']}")
        page2 = json.loads(raw)
        self.assertTrue(all(j["job_id"] != page1["jobs"][0]["job_id"] for j in page2["jobs"]))
        s, _, raw = self.http("GET", "/v1/jobs?status=COMPLETED&limit=200")
        done = json.loads(raw)["jobs"]
        self.assertTrue(all(j["status"] == "COMPLETED" for j in done))
        self.assertGreaterEqual(len(done), 2)

    # ---- 90: trmx CLI lifecycle (isolated home) ---------------------------

    def test_90_cli_lifecycle(self):
        tmp = tempfile.mkdtemp(prefix="trmx-cli-")
        home = os.path.join(tmp, "home")
        env = dict(os.environ, TRMX_HOME=home)
        cli = str(HERE.parent / "trmx")
        port = free_port()
        try:
            r = subprocess.run([sys.executable, cli, "init"], env=env,
                               capture_output=True, text=True, timeout=30)
            self.assertEqual(r.returncode, 0, r.stderr)
            token = "cli-test-token-0123456789abcdef"
            r = subprocess.run([sys.executable, cli, "pair", token], env=env,
                               capture_output=True, text=True, timeout=30)
            self.assertEqual(r.returncode, 0, r.stderr)
            # double-pair must be refused
            r = subprocess.run([sys.executable, cli, "pair", "another-token-9876543210abcd"],
                               env=env, capture_output=True, text=True, timeout=30)
            self.assertNotEqual(r.returncode, 0, "re-pair without unpair must fail")
            r = subprocess.run([sys.executable, cli, "start", "--port", str(port)], env=env,
                               capture_output=True, text=True, timeout=60)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertTrue(port_open(port))
            # API works with the paired token
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
            conn.request("GET", "/v1/system/info",
                         headers={"Authorization": f"Bearer {token}",
                                  "X-TRMX-Protocol": "1", "Connection": "close"})
            resp = conn.getresponse()
            self.assertEqual(resp.status, 200)
            conn.close()
            r = subprocess.run([sys.executable, cli, "stop"], env=env,
                               capture_output=True, text=True, timeout=60)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertFalse(port_open(port))
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


def port_open(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.4):
            return True
    except OSError:
        return False


if __name__ == "__main__":
    unittest.main(verbosity=2)
