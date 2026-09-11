#!/usr/bin/env python3
"""Protocol 1.1 AI-backend-config tests — GET/POST /v1/ai/config.

The wire contract: GET returns a MASKED, normalized view (api_key_set
boolean — the raw key never crosses the API); POST merges onto the
existing ~/.trmx/ai.json (unknown keys like _help survive), validates
strictly, writes atomically at 0600. api_key is tri-state: absent/null
keeps, "" clears, non-empty sets.

Run:  python3 termux/tests/test_ai_config.py   (or tests/run_tests.sh)
"""

from __future__ import annotations

import http.client
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path

import sys  # noqa: E402
from test_bridge import BridgeProc, free_port  # noqa: E402  (shared harness)


class TestAiConfig(unittest.TestCase):

    home: str = ""
    port: int = 0
    bridge: BridgeProc
    token: str = ""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="trmx-aicfg-home-")
        cls.home = os.path.join(cls.tmp, "trmx-home")
        (Path(cls.home) / "tools").mkdir(parents=True)
        (Path(cls.home) / "bridge.json").write_text(json.dumps({}))
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
        return resp.status, json.loads(raw), raw.decode()

    @property
    def file(self) -> Path:
        return Path(self.home) / "ai.json"

    # ---- GET ----------------------------------------------------------------

    def test_01_get_defaults_when_file_absent(self):
        status, body, raw = self.http("GET", "/v1/ai/config")
        self.assertEqual(status, 200, body)
        self.assertFalse(body["exists"])
        self.assertEqual(body["mode"], "cli")
        self.assertEqual(body["cli"]["command"], ["ollama", "run", "llama3.2"])
        self.assertEqual(body["http_api"]["provider"], "groq")
        self.assertFalse(body["http_api"]["api_key_set"])
        self.assertNotIn("api_key\"", raw.replace("api_key_set", ""))  # no raw key field

    def test_02_post_cloud_config_sets_key(self):
        status, body, raw = self.http("POST", "/v1/ai/config", {
            "mode": "http_api",
            "http_api": {"provider": "groq", "model": "llama-3.3-70b-versatile",
                         "api_key": "sk-test-123"}})
        self.assertEqual(status, 200, body)
        self.assertTrue(body["exists"])
        self.assertEqual(body["mode"], "http_api")
        self.assertTrue(body["http_api"]["api_key_set"])
        self.assertNotIn("sk-test-123", raw)                    # masked on the wire
        # the file DOES hold the key, at 0600
        on_disk = self.file.read_text()
        self.assertIn("sk-test-123", on_disk)
        self.assertEqual(self.file.stat().st_mode & 0o777, 0o600)

    def test_03_get_masks_the_key(self):
        status, body, raw = self.http("GET", "/v1/ai/config")
        self.assertEqual(status, 200)
        self.assertTrue(body["http_api"]["api_key_set"])
        self.assertNotIn("sk-test-123", raw)

    def test_04_absent_key_keeps_existing(self):
        status, body, _ = self.http("POST", "/v1/ai/config", {
            "mode": "http_api",
            "http_api": {"provider": "openai", "model": "gpt-4o-mini",
                         "api_key": None}})
        self.assertEqual(status, 200, body)
        self.assertTrue(body["http_api"]["api_key_set"])        # kept
        self.assertEqual(body["http_api"]["provider"], "openai")
        self.assertIn("sk-test-123", self.file.read_text())     # still on disk

    def test_05_empty_string_clears_key(self):
        status, body, _ = self.http("POST", "/v1/ai/config", {
            "http_api": {"api_key": ""}})
        self.assertEqual(status, 200, body)
        self.assertFalse(body["http_api"]["api_key_set"])
        on_disk = json.loads(self.file.read_text())
        self.assertEqual(on_disk["http_api"]["api_key"], "")

    # ---- validation -----------------------------------------------------------

    def test_06_invalid_provider_rejected(self):
        before = self.file.read_text()
        status, body, _ = self.http("POST", "/v1/ai/config", {
            "http_api": {"provider": "gemni"}})
        self.assertEqual(status, 400)
        self.assertEqual(body["error"]["code"], "VALIDATION_FAILED")
        self.assertEqual(self.file.read_text(), before)          # nothing written

    def test_07_invalid_mode_rejected(self):
        status, body, _ = self.http("POST", "/v1/ai/config", {"mode": "pigeon"})
        self.assertEqual(status, 400)
        self.assertIn("mode", body["error"]["message"])

    def test_08_http_api_needs_model(self):
        status, body, _ = self.http("POST", "/v1/ai/config", {
            "mode": "http_api", "http_api": {"model": ""}})
        self.assertEqual(status, 400)
        self.assertIn("model", body["error"]["message"])

    def test_09_openai_compatible_needs_endpoint(self):
        status, body, _ = self.http("POST", "/v1/ai/config", {
            "mode": "http_api",
            "http_api": {"provider": "openai_compatible", "model": "local",
                         "endpoint": ""}})
        self.assertEqual(status, 400)
        self.assertIn("endpoint", body["error"]["message"])

    def test_10_cli_command_validated(self):
        status, body, _ = self.http("POST", "/v1/ai/config", {
            "mode": "cli", "cli": {"command": []}})
        self.assertEqual(status, 400)
        status, body, _ = self.http("POST", "/v1/ai/config", {
            "mode": "cli", "cli": {"command": "ollama run llama3.2"}})
        self.assertEqual(status, 400)

    # ---- legacy / preservation --------------------------------------------------

    def test_11_legacy_flat_file_normalized(self):
        self.file.write_text(json.dumps({"command": ["my-runner", "m1"],
                                         "timeout_s": 60}))
        status, body, _ = self.http("GET", "/v1/ai/config")
        self.assertEqual(status, 200)
        self.assertTrue(body["exists"])
        self.assertEqual(body["mode"], "cli")                    # inferred
        self.assertEqual(body["cli"]["command"], ["my-runner", "m1"])
        self.assertEqual(body["cli"]["timeout_s"], 60)

    def test_12_unknown_keys_survive_a_post(self):
        self.file.write_text(json.dumps({
            "_help": ["docs"], "mode": "cli",
            "cli": {"command": ["ollama", "run", "llama3.2"], "timeout_s": 99},
            "http_api": {"provider": "groq", "model": "m", "api_key": "k",
                         "endpoint": "", "api_key_env": "GROQ_API_KEY",
                         "timeout_s": 55}}))
        status, body, _ = self.http("POST", "/v1/ai/config", {"mode": "http_api"})
        self.assertEqual(status, 200, body)
        on_disk = json.loads(self.file.read_text())
        self.assertEqual(on_disk["_help"], ["docs"])             # preserved
        self.assertEqual(on_disk["cli"]["timeout_s"], 99)        # untouched block
        self.assertEqual(on_disk["http_api"]["timeout_s"], 55)
        self.assertEqual(on_disk["http_api"]["api_key"], "k")    # kept

    def test_13_corrupt_file_fails_loud(self):
        self.file.write_text("{not json")
        status, body, _ = self.http("GET", "/v1/ai/config")
        self.assertEqual(status, 500)
        self.assertIn("not valid JSON", body["error"]["message"])
        # restore a valid file for any later runs
        self.file.unlink()


if __name__ == "__main__":
    unittest.main(verbosity=2)
