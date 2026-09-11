#!/usr/bin/env python3
"""AI-round tests — the trmx-ai wrapper against FAKE backends (no network).

The wrapper's contract (ADR-014): description -> LLM CLI -> UNTRUSTED
output -> strict §7.2 validation (mirroring the bridge) -> ~/.trmx/tools/
ai-<id>.json, never overwriting, risk_tier pinned to "confirm".

Run:  python3 termux/tests/test_ai_wrapper.py   (or tests/run_tests.sh)
stdlib only — the "LLM" is a shell script that cats a fixed payload.
"""

from __future__ import annotations

import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
TRMX_AI = REPO / "termux" / "trmx-ai"

GOOD_SCHEMA = {
    "id": "video-grab",
    "name": "Video Grabber",
    "description": "Downloads a video with video-grab.",
    "binary": "video-grab",
    "risk_tier": "safe",
    "fixed_argv": ["--newline"],
    "progress_regex": r"\[download\]\s+(\d{1,3})%",
    "args": [
        {"name": "url", "label": "URL", "type": "url", "required": True},
        {"name": "quality", "label": "Quality", "type": "enum",
         "enum": ["best", "720p"], "default": "best", "argv": ["-q", "{value}"]},
        {"name": "outdir", "label": "Output folder", "type": "path",
         "path_kind": "dir", "default": "~/downloads",
         "argv": ["-P", "{value}"], "mkdir": True},
    ],
    "examples": [{"label": "Best", "args": {"url": "https://x/v"}}],
}


def make_backend(out: str, exit_code: int = 0) -> Path:
    """A fake LLM CLI: prints `out`, exits `exit_code`. The payload goes
    through a FILE — shell-quoting fenced markdown safely is hopeless."""
    d = Path(tempfile.mkdtemp(prefix="trmx-ai-fake-"))
    (d / "payload").write_text(out, encoding="utf-8")
    f = d / "fake-llm"
    f.write_text(f"#!/bin/sh\ncat {d / 'payload'}\nexit {exit_code}\n")
    f.chmod(f.stat().st_mode | stat.S_IXUSR)
    return f


class FakeHome:
    """Temp TRMX_HOME with ai.json pointing at a fake backend."""

    def __init__(self, backend: Path):
        self.dir = Path(tempfile.mkdtemp(prefix="trmx-ai-home-"))
        (self.dir / "ai.json").write_text(
            json.dumps({"command": [str(backend)], "timeout_s": 30}))

    def run(self, *args: str) -> subprocess.CompletedProcess:
        env = dict(os.environ, TRMX_HOME=str(self.dir))
        return subprocess.run([sys.executable, str(TRMX_AI), *args],
                              capture_output=True, text=True, env=env, timeout=60)

    def cleanup(self):
        shutil.rmtree(self.dir, ignore_errors=True)


class TestTrmxAi(unittest.TestCase):

    def setUp(self):
        self._homes: list[FakeHome] = []

    def tearDown(self):
        for h in self._homes:
            h.cleanup()

    def home(self, out: str, exit_code: int = 0) -> FakeHome:
        h = FakeHome(make_backend(out, exit_code))
        self._homes.append(h)
        return h

    def tools_dir(self, h: FakeHome) -> Path:
        return h.dir / "tools"

    # ---- happy path -----------------------------------------------------

    def test_01_writes_validated_schema(self):
        h = self.home(json.dumps(GOOD_SCHEMA))
        r = h.run("download a video with video-grab")
        self.assertEqual(r.returncode, 0, r.stderr)
        dest = self.tools_dir(h) / "ai-video-grab.json"
        self.assertTrue(dest.is_file(), r.stderr)
        written = json.loads(dest.read_text())
        self.assertEqual(written["id"], "video-grab")
        self.assertEqual(written["binary"], "video-grab")
        # argv synthesis rules survive
        q = next(a for a in written["args"] if a["name"] == "quality")
        self.assertEqual(q["argv"], ["-q", "{value}"])
        o = next(a for a in written["args"] if a["name"] == "outdir")
        self.assertTrue(o["mkdir"])
        self.assertEqual(o["path_kind"], "dir")
        # and it would pass the BRIDGE's own validator shape (spot check)
        self.assertIn(written["risk_tier"], ("safe", "confirm", "destructive"))

    def test_02_risk_tier_forced_to_confirm(self):
        h = self.home(json.dumps(GOOD_SCHEMA))     # model claims "safe"
        r = h.run("download a video with video-grab")
        self.assertEqual(r.returncode, 0, r.stderr)
        written = json.loads((self.tools_dir(h) / "ai-video-grab.json").read_text())
        self.assertEqual(written["risk_tier"], "confirm",
                         "AI-generated tools are confirm-tier until a human edits")

    def test_03_fences_and_prose_tolerated(self):
        payload = "Sure! Here is the schema:\n```json\n" + json.dumps(GOOD_SCHEMA) + \
                  "\n```\nHope that helps!"
        h = self.home(payload)
        r = h.run("download a video with video-grab")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue((self.tools_dir(h) / "ai-video-grab.json").is_file())

    def test_04_id_slugified(self):
        s = dict(GOOD_SCHEMA, id="My Cool Tool!!")
        h = self.home(json.dumps(s))
        r = h.run("whatever")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue((self.tools_dir(h) / "ai-my-cool-tool.json").is_file())

    def test_05_dry_run_writes_nothing(self):
        h = self.home(json.dumps(GOOD_SCHEMA))
        r = h.run("--dry-run", "download a video with video-grab")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('"id": "video-grab"', r.stdout)
        self.assertFalse(self.tools_dir(h).exists())

    # ---- untrusted output rejected loudly -------------------------------

    def _rejected(self, payload: str, needle: str = ""):
        h = self.home(payload)
        r = h.run("download a video with video-grab")
        self.assertEqual(r.returncode, 4, r.stderr)
        self.assertIn(needle, r.stderr)
        self.assertFalse(self.tools_dir(h).exists(),
                         "nothing may be written on rejection")

    def test_06_prose_only_rejected(self):
        self._rejected("I cannot do that, sorry.")

    def test_07_bad_arg_type_rejected(self):
        s = json.loads(json.dumps(GOOD_SCHEMA))
        s["args"][0]["type"] = "wibble"
        self._rejected(json.dumps(s), "bad type")

    def test_08_argv_value_twice_rejected(self):
        s = json.loads(json.dumps(GOOD_SCHEMA))
        s["args"][1]["argv"] = ["-q", "{value}", "{value}"]
        self._rejected(json.dumps(s), "exactly once")

    def test_09_mkdir_on_file_rejected(self):
        s = json.loads(json.dumps(GOOD_SCHEMA))
        s["args"][2]["path_kind"] = "file"
        self._rejected(json.dumps(s), "mkdir requires path_kind 'dir'")

    def test_10_slashed_binary_rejected(self):
        s = json.loads(json.dumps(GOOD_SCHEMA))
        s["binary"] = "/bin/sh"
        self._rejected(json.dumps(s), "bare executable")

    def test_11_too_many_args_rejected(self):
        s = json.loads(json.dumps(GOOD_SCHEMA))
        s["args"] = [{"name": f"a{i}", "type": "string"} for i in range(13)]
        self._rejected(json.dumps(s), "at most 12")

    def test_12_unbalanced_braces_rejected(self):
        self._rejected(json.dumps(GOOD_SCHEMA)[:-1], "unbalanced")

    # ---- overwrite / backend failures -----------------------------------

    def test_13_no_overwrite(self):
        h = self.home(json.dumps(GOOD_SCHEMA))
        self.assertEqual(h.run("download a video with video-grab").returncode, 0)
        r = h.run("download a video with video-grab")
        self.assertEqual(r.returncode, 5, r.stderr)
        self.assertIn("refusing to overwrite", r.stderr)

    def test_14_backend_nonzero_exit(self):
        h = self.home("whatever", exit_code=7)
        r = h.run("download a video with video-grab")
        self.assertEqual(r.returncode, 3, r.stderr)

    def test_15_backend_missing(self):
        h = FakeHome(Path("/no/such/fake-llm"))
        self._homes.append(h)
        r = h.run("download a video with video-grab")
        self.assertEqual(r.returncode, 3, r.stderr)
        self.assertIn("not found", r.stderr)

    def test_16_short_description_usage_error(self):
        h = self.home(json.dumps(GOOD_SCHEMA))
        r = h.run("dl")
        self.assertEqual(r.returncode, 2, r.stderr)

    def test_17_model_override_reaches_backend(self):
        # fake ollama: captures argv, prints a schema for any prompt
        d = Path(tempfile.mkdtemp(prefix="trmx-ai-ollama-"))
        cap = d / "argv.json"
        (d / "schema").write_text(json.dumps(GOOD_SCHEMA), encoding="utf-8")
        fb = d / "ollama"
        fb.write_text("#!/bin/sh\n"
                      f"printf '%s' \"$*\" > {cap}\n"
                      f"cat {d / 'schema'}\n")
        fb.chmod(fb.stat().st_mode | stat.S_IXUSR)
        h = FakeHome(fb)
        self._homes.append(h)
        (h.dir / "ai.json").write_text(
            json.dumps({"command": ["ollama", "run", "llama3.2"]}))
        os.environ["PATH"] = f"{d}:{os.environ['PATH']}"
        try:
            r = h.run("--model", "qwen2.5", "download a video with video-grab")
            self.assertEqual(r.returncode, 0, r.stderr)
            sent = cap.read_text()
            self.assertIn("qwen2.5", sent.split()[:3], sent[:80])
            self.assertIn("video-grab", sent)          # prompt embedded
        finally:
            os.environ["PATH"] = os.environ["PATH"].split(":", 1)[1]


if __name__ == "__main__":
    unittest.main(verbosity=2)
