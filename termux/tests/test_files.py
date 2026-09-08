#!/usr/bin/env python3
"""Phase 7 file-endpoint tests — PROTOCOL.md §6 against a real bridge.

Covers: §6.1 path policy (realpath containment), §6.2 list/stat + paging,
§6.3 operations incl. the recursive-delete confirm interlock, §6.4 ranged
download, §6.5 streaming atomic upload (sha256 verify, overwrite, 411).

Run:  python3 termux/tests/test_files.py   (or tests/run_tests.sh)
stdlib only (unittest, http.client, socket) — ADR-002 rule.
"""

from __future__ import annotations

import http.client
import json
import os
import socket
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys_imported = False
import sys  # noqa: E402

from test_bridge import BridgeProc, free_port  # noqa: E402  (shared harness)


class TestFiles(unittest.TestCase):
    """Owns its own bridge; file fixtures live under the REAL user home
    (the §6.1 roots are [~ , ~/storage] — same policy on CI and Termux)."""

    home: str = ""
    port: int = 0
    bridge: BridgeProc
    token: str = ""
    files_root: Path  # the throwaway fixture tree under Path.home()

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="trmx-files-home-")
        cls.home = os.path.join(cls.tmp, "trmx-home")
        os.makedirs(cls.home, exist_ok=True)
        (Path(cls.home) / "bridge.json").write_text(json.dumps({}))
        cls.port = free_port()
        cls.bridge = BridgeProc(cls.home, cls.port)
        cls.bridge.start()
        cls.token = cls.bridge.token()
        # fixture tree under the real home so the path policy is exercised
        cls.files_root = Path(tempfile.mkdtemp(prefix="trmx-files-", dir=str(Path.home())))
        (cls.files_root / "music").mkdir()
        (cls.files_root / "notes.txt").write_bytes(b"hello trmx files\n")
        (cls.files_root / "music" / "track.mp3").write_bytes(b"\x00\x01\x02" * 1000)
        (cls.files_root / "latest.txt").symlink_to(cls.files_root / "notes.txt")

    @classmethod
    def tearDownClass(cls):
        cls.bridge.term()
        cls.bridge.close()
        import shutil
        shutil.rmtree(cls.files_root, ignore_errors=True)
        shutil.rmtree(cls.tmp, ignore_errors=True)

    # ---- helpers ---------------------------------------------------------

    @classmethod
    def http(cls, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", cls.port, timeout=15)
        hdrs = {"X-TRMX-Protocol": "1", "Connection": "close",
                "Authorization": f"Bearer {cls.token}"}
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
    def put_raw(cls, path, data: bytes, sha256: str | None = None, set_length=True):
        """Raw PUT with exact header control (for upload edge cases)."""
        conn = http.client.HTTPConnection("127.0.0.1", cls.port, timeout=15)
        hdrs = {"X-TRMX-Protocol": "1", "Connection": "close",
                "Authorization": f"Bearer {cls.token}"}
        if sha256:
            hdrs["X-TRMX-Sha256"] = sha256
        if set_length:
            hdrs["Content-Length"] = str(len(data))
        conn.request("PUT", path, body=data if set_length else None, headers=hdrs)
        resp = conn.getresponse()
        raw = resp.read()
        conn.close()
        return resp.status, dict(resp.getheaders()), raw

    def q(self, rel: str) -> str:
        """Query path for a fixture entry, in the wire's ~-prefixed form."""
        rel_home = str(self.files_root.relative_to(Path.home()))
        return f"~/{rel_home}/{rel}"

    # ---- §6.1 path policy --------------------------------------------------

    def test_01_traversal_denied(self):
        status, _, raw = self.http("GET", "/v1/files?path=/etc/passwd")
        self.assertEqual(status, 403, raw)
        self.assertEqual(json.loads(raw)["error"]["code"], "PATH_DENIED")
        status, _, raw = self.http("GET", "/v1/files?path=~/../../etc")
        self.assertIn(status, (403, 404), raw)  # resolves outside roots either way
        if status == 403:
            self.assertEqual(json.loads(raw)["error"]["code"], "PATH_DENIED")

    def test_02_missing_path_404(self):
        status, _, raw = self.http("GET", "/v1/files?path=~/trmx-files-nope-xyz")
        self.assertEqual(status, 404, raw)
        self.assertEqual(json.loads(raw)["error"]["code"], "PATH_NOT_FOUND")

    # ---- §6.2 list / stat ---------------------------------------------------

    def test_03_list_shapes_match_contract(self):
        status, _, raw = self.http("GET", f"/v1/files?path={self.q('')[:-1]}")
        # q('') ends with '/'; strip it for the dir itself
        self.assertEqual(status, 200, raw)
        obj = json.loads(raw)
        self.assertTrue(obj["path"].endswith(self.files_root.name), obj["path"])
        by_name = {e["name"]: e for e in obj["entries"]}
        self.assertIn("notes.txt", by_name)
        self.assertIn("music", by_name)
        self.assertIn("latest.txt", by_name)
        note = by_name["notes.txt"]
        self.assertEqual(set(note), {"name", "type", "size", "mtime", "mode", "target"})
        self.assertEqual(note["type"], "file")
        self.assertEqual(note["size"], len(b"hello trmx files\n"))
        self.assertTrue(note["mtime"].endswith("Z") and "T" in note["mtime"])
        self.assertTrue(note["mode"].startswith("-"))
        self.assertEqual(by_name["music"]["type"], "dir")
        sym = by_name["latest.txt"]
        self.assertEqual(sym["type"], "symlink")
        self.assertTrue(sym["target"].endswith("notes.txt"), sym)

    def test_04_stat_returns_the_entry_itself(self):
        status, _, raw = self.http("GET", f"/v1/files?path={self.q('notes.txt')}&stat=1")
        self.assertEqual(status, 200, raw)
        obj = json.loads(raw)
        self.assertEqual(obj["entry"]["name"], "notes.txt")
        self.assertEqual(obj["entry"]["type"], "file")

    def test_05_paging_at_1000(self):
        page_dir = self.files_root / "page"
        page_dir.mkdir()
        for i in range(1005):
            (page_dir / f"f{i:04d}").write_bytes(b"x")
        try:
            rel = str(page_dir.relative_to(Path.home()))
            status, _, raw = self.http("GET", f"/v1/files?path=~/{rel}")
            obj = json.loads(raw)
            self.assertEqual(status, 200)
            self.assertEqual(len(obj["entries"]), 1000)
            self.assertEqual(obj["next_offset"], 1000)
            status, _, raw = self.http("GET", f"/v1/files?path=~/{rel}&offset=1000")
            obj2 = json.loads(raw)
            self.assertEqual(len(obj2["entries"]), 5)
            self.assertNotIn("next_offset", obj2)
        finally:
            import shutil
            shutil.rmtree(page_dir)

    # ---- §6.3 operations ------------------------------------------------------

    def test_10_mkdir_touch_rename(self):
        status, _, raw = self.http("POST", "/v1/files", {"op": "mkdir", "path": self.q("newdir")})
        self.assertEqual(status, 200, raw)
        self.assertEqual(json.loads(raw), {"ok": True})
        # exists -> 409
        status, _, raw = self.http("POST", "/v1/files", {"op": "mkdir", "path": self.q("newdir")})
        self.assertEqual(status, 409)
        self.assertEqual(json.loads(raw)["error"]["code"], "PATH_EXISTS")
        # missing parent -> 404
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "mkdir", "path": self.q("nope/child")})
        self.assertEqual(status, 404)
        # touch + rename
        self.http("POST", "/v1/files", {"op": "touch", "path": self.q("newdir/a.txt")})
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "rename", "path": self.q("newdir/a.txt"),
                                    "new_name": "b.txt"})
        self.assertEqual(status, 200, raw)
        self.assertTrue((self.files_root / "newdir" / "b.txt").exists())
        # bad new_name -> 400
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "rename", "path": self.q("newdir/b.txt"),
                                    "new_name": "evil/../x"})
        self.assertEqual(status, 400)

    def test_11_move_copy(self):
        self.http("POST", "/v1/files", {"op": "mkdir", "path": self.q("d1")})
        self.http("POST", "/v1/files", {"op": "mkdir", "path": self.q("d2")})
        self.http("POST", "/v1/files", {"op": "touch", "path": self.q("d1/x.txt")})
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "copy", "path": self.q("d1/x.txt"),
                                    "dest_dir": self.q("d2")})
        self.assertEqual(status, 200, raw)
        self.assertEqual(json.loads(raw)["entries_moved"], 1)
        self.assertTrue((self.files_root / "d2" / "x.txt").exists())
        self.http("POST", "/v1/files", {"op": "mkdir", "path": self.q("d3")})
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "move", "path": self.q("d1/x.txt"),
                                    "dest_dir": self.q("d3")})
        self.assertEqual(status, 200, raw)
        self.assertFalse((self.files_root / "d1" / "x.txt").exists())
        self.assertTrue((self.files_root / "d3" / "x.txt").exists())
        # dest exists -> 409
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "copy", "path": self.q("d2/x.txt"),
                                    "dest_dir": self.q("d2")})
        self.assertEqual(status, 409)
        # dest_dir missing -> 404
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "move", "path": self.q("d2/x.txt"),
                                    "dest_dir": self.q("missing-dir")})
        self.assertEqual(status, 404)

    def test_12_delete_interlock(self):
        self.http("POST", "/v1/files", {"op": "mkdir", "path": self.q("todelete")})
        self.http("POST", "/v1/files", {"op": "touch", "path": self.q("todelete/child.txt")})
        # non-recursive delete of non-empty dir -> PATH_NOT_EMPTY
        status, _, raw = self.http("POST", "/v1/files", {"op": "delete", "path": self.q("todelete")})
        self.assertEqual(status, 409)   # §10 registry: PATH_NOT_EMPTY is 409
        self.assertEqual(json.loads(raw)["error"]["code"], "PATH_NOT_EMPTY")
        # recursive without confirm -> CONFIRM_REQUIRED
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "delete", "path": self.q("todelete"), "recursive": True})
        self.assertEqual(status, 400)
        self.assertEqual(json.loads(raw)["error"]["code"], "CONFIRM_REQUIRED")
        # with confirm -> gone
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "delete", "path": self.q("todelete"),
                                    "recursive": True, "confirm": True})
        self.assertEqual(status, 200, raw)
        self.assertFalse((self.files_root / "todelete").exists())
        # symlink delete removes the link, not the target
        status, _, raw = self.http("POST", "/v1/files", {"op": "delete", "path": self.q("latest.txt")})
        self.assertEqual(status, 200, raw)
        self.assertTrue((self.files_root / "notes.txt").exists())

    def test_13_write_outside_roots_denied(self):
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "mkdir", "path": "/etc/trmx-evil"})
        self.assertEqual(status, 403, raw)
        self.assertEqual(json.loads(raw)["error"]["code"], "PATH_DENIED")

    # ---- §6.4 download ----------------------------------------------------------

    def test_20_download_full(self):
        status, hdrs, raw = self.http("GET", f"/v1/files/content?path={self.q('notes.txt')}")
        self.assertEqual(status, 200, raw)
        self.assertEqual(raw, b"hello trmx files\n")
        self.assertEqual(hdrs.get("Content-Type"), "application/octet-stream")
        self.assertEqual(hdrs.get("Accept-Ranges"), "bytes")
        self.assertEqual(int(hdrs.get("Content-Length", -1)), len(raw))

    def test_21_download_ranges(self):
        base = f"/v1/files/content?path={self.q('music/track.mp3')}"
        status, hdrs, raw = self.http("GET", base, headers={"Range": "bytes=0-9"})
        self.assertEqual(status, 206)
        self.assertEqual(hdrs.get("Content-Range"), f"bytes 0-9/3000")
        self.assertEqual(raw, b"\x00\x01\x02" * 3 + b"\x00")
        status, hdrs, raw = self.http("GET", base, headers={"Range": "bytes=2990-"})
        self.assertEqual(status, 206)
        self.assertEqual(len(raw), 10)
        status, hdrs, raw = self.http("GET", base, headers={"Range": "bytes=-10"})
        self.assertEqual(status, 206)
        self.assertEqual(len(raw), 10)
        # beyond the end
        status, hdrs, raw = self.http("GET", base, headers={"Range": "bytes=9999-"})
        self.assertEqual(status, 416)
        self.assertEqual(hdrs.get("Content-Range"), "bytes */3000")
        # malformed
        status, _, _raw = self.http("GET", base, headers={"Range": "bytes=5-2"})
        self.assertEqual(status, 416)

    def test_22_download_dir_rejected(self):
        status, _, raw = self.http("GET", f"/v1/files/content?path={self.q('music')}")
        self.assertEqual(status, 400)
        self.assertEqual(json.loads(raw)["error"]["code"], "NOT_A_FILE")

    # ---- §6.5 upload ------------------------------------------------------------

    def test_30_upload_atomic_with_checksum(self):
        import hashlib
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "mkdir", "path": self.q("uploads")})
        self.assertEqual(status, 200, raw)
        data = b"uploaded bytes " * 100
        dest = self.q("uploads/new.bin")
        status, _, raw = self.put_raw(
            f"/v1/files/content?path={dest}", data,
            sha256=hashlib.sha256(data).hexdigest())
        self.assertEqual(status, 200, raw)
        self.assertEqual(json.loads(raw), {"ok": True})
        self.assertEqual((self.files_root / "uploads" / "new.bin").read_bytes(), data)
        # no partial-file or temp litter
        self.assertEqual([p.name for p in (self.files_root / "uploads").iterdir()], ["new.bin"])

    def test_31_upload_overwrite_rules(self):
        dest = self.q("notes.txt")
        status, _, raw = self.put_raw(f"/v1/files/content?path={dest}", b"zzz")
        self.assertEqual(status, 409, raw)
        self.assertEqual(json.loads(raw)["error"]["code"], "PATH_EXISTS")
        status, _, raw = self.put_raw(f"/v1/files/content?path={dest}&overwrite=1", b"zzz")
        self.assertEqual(status, 200, raw)
        self.assertEqual((self.files_root / "notes.txt").read_bytes(), b"zzz")

    def test_32_upload_checksum_mismatch(self):
        dest = self.q("bad.bin")
        status, _, raw = self.put_raw(
            f"/v1/files/content?path={dest}", b"data",
            sha256="0" * 64)
        self.assertEqual(status, 422, raw)
        self.assertEqual(json.loads(raw)["error"]["code"], "CHECKSUM_MISMATCH")
        self.assertFalse((self.files_root / "bad.bin").exists())

    def test_33_upload_requires_content_length(self):
        s = socket.create_connection(("127.0.0.1", self.port), timeout=10)
        req = (f"PUT /v1/files/content?path={self.q('nolen.bin')} HTTP/1.1\r\n"
               f"Host: t\r\nAuthorization: Bearer {self.token}\r\n"
               "X-TRMX-Protocol: 1\r\nConnection: close\r\n\r\n")
        s.sendall(req.encode())
        data = s.recv(65536).decode("iso-8859-1")
        s.close()
        self.assertIn(" 411 ", data.split("\r\n")[0], data[:200])

    def test_34_upload_missing_parent_404(self):
        status, _, raw = self.put_raw(f"/v1/files/content?path={self.q('no-dir/x.bin')}", b"x")
        self.assertEqual(status, 404, raw)


if __name__ == "__main__":
    print(f"bridge under test: {Path(BridgeProc.__module__ and '.') or ''}"
          f"{Path(HERE).parent / 'trmx-bridge.py'}")
    unittest.main(verbosity=2)
