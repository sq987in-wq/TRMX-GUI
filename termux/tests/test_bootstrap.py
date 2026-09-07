#!/usr/bin/env python3
"""Phase 3 bootstrap test suite — install.sh + trmx lifecycle commands.

Everything runs against a throwaway HOME / TRMX_HOME (and, where needed,
throwaway TRMX_PREFIX / TRMX_BOOT_DIR), so the suite is safe on dev hosts
and on Termux itself. No network: install.sh is exercised in --source mode
only (the remote path differs solely in how files reach the staging dir).

Covers (ADR-005 / docs/CONTROL-PLANE.md):
  - fresh install, checksum-verified, correct layout + manifest
  - tampered payload -> loud refusal, zero partial state
  - idempotent reinstall with .old backups
  - upgrade while bridge is running -> stop, replace, restart
  - missing SHA256SUMS -> explicit UNVERIFIED warning (honesty)
  - trmx enable-boot / enable-service (incl. missing termux-services)
  - intent_commands.sh emits all 8 control-plane ops in valid adb form
"""

from __future__ import annotations

import json
import os
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
TERMUX = REPO / "termux"
BRIDGE_PORT = 27666  # deliberately not 27342 (used by test_bridge.py)

BRIDGE_VERSION = re.search(r'^VERSION = "([^"]+)"',
                           (TERMUX / "trmx-bridge.py").read_text("utf-8"), re.M).group(1)


def port_open(port: int, timeout: float = 0.4) -> bool:
    import socket
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout):
            return True
    except OSError:
        return False


class BootstrapTest(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls):
        cls.sandbox = Path(tempfile.mkdtemp(prefix="trmx-bootstrap-"))
        cls.home = cls.sandbox / "home"
        cls.home.mkdir()
        cls.trmx_home = cls.home / ".trmx"
        cls.prefix = cls.sandbox / "prefix"
        cls.boot_dir = cls.home / ".termux" / "boot"

    @classmethod
    def tearDownClass(cls):
        cls.stop_bridge()
        shutil.rmtree(cls.sandbox, ignore_errors=True)
        print(f"\n(bootstrap test artifacts removed; sandbox was {cls.sandbox})",
              file=sys.stderr)

    @classmethod
    def stop_bridge(cls):
        if cls.trmx_home.exists():
            subprocess.run([str(cls.trmx_home / "trmx"), "stop"],
                           env=cls.base_env(), capture_output=True, timeout=30)
        # belt-and-braces: anything left from the pidfile gets SIGKILLed
        pidfile = cls.trmx_home / "bridge.pid"
        if pidfile.exists():
            try:
                pid = int(pidfile.read_text().strip())
                # guard against PID recycling: only kill if it is still our bridge
                cmdline = Path(f"/proc/{pid}/cmdline").read_bytes()
                if b"trmx-bridge.py" in cmdline:
                    os.kill(pid, signal.SIGKILL)
            except (ValueError, ProcessLookupError, PermissionError, OSError):
                pass

    @classmethod
    def base_env(cls):
        env = dict(os.environ)
        env.update(HOME=str(cls.home), TRMX_HOME=str(cls.trmx_home),
                   TRMX_PREFIX=str(cls.prefix), TRMX_BOOT_DIR=str(cls.boot_dir))
        return env

    # -- helpers ---------------------------------------------------------

    def install(self, source=TERMUX, env=None, timeout=120):
        return subprocess.run(
            [str(TERMUX / "install.sh"), "--source", str(source)],
            env=env or self.base_env(), capture_output=True, text=True, timeout=timeout)

    def trmx(self, *args, env=None, timeout=60):
        return subprocess.run(
            [str(self.trmx_home / "trmx"), *args],
            env=env or self.base_env(), capture_output=True, text=True, timeout=timeout)

    def assert_file_mode(self, path: Path, expect: int):
        got = stat.S_IMODE(path.stat().st_mode)
        self.assertEqual(got, expect, f"{path}: mode {oct(got)} != {oct(expect)}")

    # -- install.sh ------------------------------------------------------

    def test_01_fresh_install_verified(self):
        r = self.install()
        self.assertEqual(r.returncode, 0, f"stdout:\n{r.stdout}\nstderr:\n{r.stderr}")
        self.assertIn("checksums verified", r.stdout)
        for name in ("trmx", "trmx-bridge.py"):
            p = self.trmx_home / name
            self.assertTrue(p.is_file(), f"{p} missing")
            self.assert_file_mode(p, 0o755)
        for d in ("logs", "tools", "bin"):
            self.assertTrue((self.trmx_home / d).is_dir(), f"{d}/ missing")
        self.assertFalse(list(self.trmx_home.glob("*.old")), "fresh install must not create backups")
        self.assertFalse(list(self.trmx_home.glob(".staging.*")), "staging dir leaked")
        # manifest hashes must match what is on disk
        manifest = json.loads((self.trmx_home / "installed.json").read_text())
        import hashlib
        for name, digest in manifest["files"].items():
            actual = hashlib.sha256((self.trmx_home / name).read_bytes()).hexdigest()
            self.assertEqual(digest, actual, f"manifest hash mismatch for {name}")
        self.assertIn("installed_at", manifest)

    def test_02_tampered_source_refused_no_partial_state(self):
        # a second throwaway TRMX_HOME so the good install from test_01 stays intact
        evil_home = self.sandbox / "evil" / ".trmx"
        evil_source = self.sandbox / "evil-source"
        shutil.copytree(TERMUX, evil_source,
                        ignore=shutil.ignore_patterns("__pycache__", "tests"))
        with (evil_source / "trmx-bridge.py").open("a") as f:
            f.write("# tampered\n")  # SHA256SUMS deliberately NOT regenerated
        env = dict(self.base_env(), TRMX_HOME=str(evil_home))
        r = self.install(source=evil_source, env=env)
        self.assertNotEqual(r.returncode, 0, "tampered install must fail")
        self.assertIn("SHA256 verification failed", r.stderr)
        leftovers = list(evil_home.iterdir()) if evil_home.exists() else []
        self.assertEqual(leftovers, [], "refusal left files behind (partial install!)")

    def test_03_reinstall_idempotent_with_backup(self):
        r = self.install()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("checksums verified", r.stdout)
        old_bridge = self.trmx_home / "trmx-bridge.py.old"
        old_cli = self.trmx_home / "trmx.old"
        self.assertTrue(old_bridge.is_file() and old_cli.is_file(), ".old backups missing")
        # same source -> backup content == current content
        self.assertEqual(old_bridge.read_bytes(),
                         (self.trmx_home / "trmx-bridge.py").read_bytes())
        # manifest still valid
        manifest = json.loads((self.trmx_home / "installed.json").read_text())
        self.assertIn("trmx", manifest["files"])

    def test_04_missing_sums_installs_unverified_with_warning(self):
        nosums = self.sandbox / "nosums-source"
        shutil.copytree(TERMUX, nosums,
                        ignore=shutil.ignore_patterns("__pycache__", "tests"))
        (nosums / "SHA256SUMS").unlink()
        target = self.sandbox / "nosums" / ".trmx"
        env = dict(self.base_env(), TRMX_HOME=str(target))
        r = self.install(source=nosums, env=env)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("UNVERIFIED", r.stdout)

    def test_05_upgrade_restarts_running_bridge(self):
        # start the bridge from the test_01 install on a private port
        r = self.trmx("start", "--port", str(BRIDGE_PORT))
        self.assertEqual(r.returncode, 0, f"start failed:\n{r.stdout}\n{r.stderr}")
        self.assertTrue(port_open(BRIDGE_PORT))
        pid_before = int(self.trmx("pid").stdout.strip())
        # re-run the installer: it must notice, stop, replace, restart
        r = self.install()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("bridge upgraded and restarted", r.stdout)
        deadline = time.time() + 15
        while time.time() < deadline and not port_open(BRIDGE_PORT):
            time.sleep(0.2)
        self.assertTrue(port_open(BRIDGE_PORT), "bridge did not come back after upgrade")
        pid_after = int(self.trmx("pid").stdout.strip())
        self.assertNotEqual(pid_before, pid_after, "bridge was not restarted")
        # and it is still our bridge: authenticated handshake works end-to-end
        token = json.loads((self.trmx_home / "bridge.json").read_text())["token"]
        info = subprocess.run(
            [sys.executable, "-", str(BRIDGE_PORT), token], capture_output=True,
            text=True, timeout=10,
            input="import http.client,json,sys\n"
                  "c=http.client.HTTPConnection('127.0.0.1',int(sys.argv[1]),timeout=3)\n"
                  "c.request('GET','/v1/system/info',headers={'Authorization':'Bearer '+sys.argv[2],'X-TRMX-Protocol':'1'})\n"
                  "r=c.getresponse();print(r.status);print(r.read().decode())\n")
        self.assertTrue(info.stdout.startswith("200\n"), info.stdout + info.stderr)
        self.assertIn(BRIDGE_VERSION, info.stdout)

    # -- trmx lifecycle commands -----------------------------------------

    def test_06_trmx_version(self):
        r = self.trmx("version")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("0.3.0", r.stdout)          # CLI
        self.assertIn(BRIDGE_VERSION, r.stdout)   # bridge

    def test_07_enable_boot(self):
        r = self.trmx("enable-boot")
        self.assertEqual(r.returncode, 0, r.stderr)
        script = self.boot_dir / "trmx-bridge"
        self.assertTrue(script.is_file())
        self.assert_file_mode(script, 0o755)
        text = script.read_text()
        self.assertTrue(text.startswith(f"#!{self.prefix}/bin/sh\n"), text)
        self.assertIn(f'export TRMX_HOME="{self.trmx_home}"', text)
        self.assertIn(f'exec "{self.trmx_home}/trmx" start', text)
        # sanity: the generated script is valid sh
        subprocess.run(["sh", "-n", str(script)], check=True)

    def test_08_enable_service_needs_termux_services(self):
        # prefix/ has no var/service yet -> loud failure with guidance
        r = self.trmx("enable-service")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("termux-services", r.stderr)

    def test_09_enable_service_creates_runit_run(self):
        svdir = self.prefix / "var" / "service"
        svdir.mkdir(parents=True)
        r = self.trmx("enable-service")
        self.assertEqual(r.returncode, 0, r.stderr)
        run = svdir / "trmx-bridge" / "run"
        self.assertTrue(run.is_file())
        self.assert_file_mode(run, 0o755)
        text = run.read_text()
        self.assertTrue(text.startswith(f"#!{self.prefix}/bin/sh\n"), text)
        self.assertIn(f'exec python3 "{self.trmx_home}/trmx-bridge.py"', text)
        subprocess.run(["sh", "-n", str(run)], check=True)

    # -- control-plane intent reference ----------------------------------

    def test_10_intent_commands_cover_all_ops(self):
        r = subprocess.run([str(TERMUX / "tests" / "intent_commands.sh")],
                           capture_output=True, text=True, timeout=30)
        self.assertEqual(r.returncode, 0, r.stderr)
        lines = [ln for ln in r.stdout.splitlines() if ln.startswith("adb shell")]
        self.assertEqual(len(lines), 8, f"expected 8 ops, got:\n{r.stdout}")
        joined = "\n".join(lines)
        self.assertIn("com.termux/com.termux.app.RunCommandService", joined)
        self.assertIn("-a com.termux.RUN_COMMAND", joined)
        self.assertIn("com.termux.RUN_COMMAND_BACKGROUND true", joined)
        # every op targets the right executable (argv-first, CONTROL-PLANE.md §3)
        by_op = {}
        for ln in lines:
            m = re.search(r'RUN_COMMAND_PATH "([^"]+)"', ln)
            label = re.search(r'COMMAND_LABEL "TRMX: ([A-Za-z_]+)"', ln).group(1)
            by_op[label] = m.group(1)
        prefix = "/data/data/com.termux/files/usr"
        trmx = "/data/data/com.termux/files/home/.trmx/trmx"
        self.assertEqual(by_op["INSTALL_PY"], f"{prefix}/bin/pkg")
        self.assertEqual(by_op["INSTALL"], f"{prefix}/bin/bash")
        self.assertEqual(by_op["PAIR"], trmx)
        self.assertEqual(by_op["START"], trmx)
        self.assertEqual(by_op["STOP"], trmx)
        self.assertEqual(by_op["STATUS"], trmx)
        self.assertEqual(by_op["ENABLE_BOOT"], trmx)
        self.assertEqual(by_op["ENABLE_SERVICE"], trmx)
        # argv sanity: START is exactly ["start"], no shell string anywhere
        start_line = next(ln for ln in lines if "TRMX: START" in ln)
        self.assertIn('RUN_COMMAND_ARGUMENTS "start"', start_line)
        # token placeholder must NOT be an obvious literal leak in other ops
        for ln in lines:
            if "TRMX: PAIR" not in ln:
                self.assertNotIn("paste-app-token", ln)


    # -- remote (fetch) mode, exercised network-free via file:// base -----

    def test_11_remote_mode_fetch_and_verify(self):
        # the app-facing flow (CONTROL-PLANE.md op INSTALL) is: fetch from a
        # base URL + verify against SHA256SUMS. file:// exercises exactly
        # that code path without network access.
        target = self.sandbox / "remote" / ".trmx"
        env = dict(self.base_env(), TRMX_HOME=str(target))
        r = subprocess.run(
            [str(TERMUX / "install.sh"), "--base", f"file://{TERMUX}"],
            env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(r.returncode, 0, f"{r.stdout}\n{r.stderr}")
        self.assertIn("checksums verified", r.stdout)
        self.assertTrue((target / "trmx").is_file())

    def test_12_remote_mode_tampered_refused(self):
        tampered = self.sandbox / "remote-evil-source"
        shutil.copytree(TERMUX, tampered,
                        ignore=shutil.ignore_patterns("__pycache__", "tests"))
        with (tampered / "trmx").open("a") as f:
            f.write("# tampered\n")
        target = self.sandbox / "remote-evil" / ".trmx"
        env = dict(self.base_env(), TRMX_HOME=str(target))
        r = subprocess.run(
            [str(TERMUX / "install.sh"), "--base", f"file://{tampered}"],
            env=env, capture_output=True, text=True, timeout=120)
        self.assertNotEqual(r.returncode, 0, "tampered remote install must fail")
        self.assertIn("SHA256 verification failed", r.stderr)
        leftovers = list(target.iterdir()) if target.exists() else []
        self.assertEqual(leftovers, [], "refusal left files behind (partial install!)")


    def test_13_relative_source_path(self):
        # regression: `sh termux/install.sh --source termux` from the repo
        # root must work — the checksum check runs inside the staging dir,
        # where a relative source path would not resolve
        target = self.sandbox / "rel" / ".trmx"
        env = dict(self.base_env(), TRMX_HOME=str(target))
        r = subprocess.run(
            ["sh", "termux/install.sh", "--source", "termux"],
            cwd=REPO, env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(r.returncode, 0, f"{r.stdout}\n{r.stderr}")
        self.assertIn("checksums verified", r.stdout)
        self.assertTrue((target / "trmx").is_file())


if __name__ == "__main__":
    print(f"bridge under test: v{BRIDGE_VERSION}, source={TERMUX}")
    unittest.main(verbosity=2)
