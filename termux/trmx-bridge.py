#!/usr/bin/env python3
"""trmx-bridge — the TRMX execution-plane daemon (Phase 2 PoC).

Serves a subset of TRMX-P/1 (docs/PROTOCOL.md) on 127.0.0.1:

  GET  /v1/system/info                 handshake / health (PROTOCOL §2.1)
  GET  /v1/system/policy               policy mirror (PROTOCOL §2.2)
  POST /v1/jobs                        submit, type:"argv" only in this PoC (§3.2)
  GET  /v1/jobs                        list / history (§3.3)
  GET  /v1/jobs/{id}                   one job (§3.4)
  POST /v1/jobs/{id}/cancel            graceful→forced process-group kill (§3.5)
  GET  /v1/jobs/{id}/output            SSE stream with replay (§4)
  GET  /v1/events                      global event stream, live only (§5; no
                                      Last-Event-ID replay yet)
  POST /v1/system/bridge               stop / restart (§8)
  GET/POST /v1/services                service definitions + live status (§14, protocol 1.1)
  GET/DELETE /v1/services/{id}         one service / remove definition (§14)
  POST /v1/services/{id}/start|stop|restart|autostart   lifecycle (§14)
  GET/POST /v1/ai/config               AI backend config, masked (protocol 1.1)

Deliberately NOT in this PoC (later phases, see docs/decisions/ADR-004):
  tools/file endpoints, tool-schema argv synthesis, progress regex,
  Last-Event-ID replay, per-source auth backoff (per-connection only),
  request rate limiting, PTY, scheduler.

Rules honored here (ADR-002): stdlib only, loopback-only bind, bearer token
with constant-time compare, argv executed via exec semantics (never a shell),
process groups for cancellation, SQLite WAL as the source of truth,
fail-loud on port conflict.

Dev-mode leniency (non-Termux hosts only, for testing): executables may
resolve from PATH and cwd may be any existing directory. On Termux the
allowlist is $PREFIX/bin + ~/.trmx/bin and cwd must be inside policy roots.
"""

from __future__ import annotations

import argparse
import asyncio
import codecs
import datetime as dt
import hashlib
import hmac
import itertools
import json
import logging
import os
import re
import secrets
import shlex  # noqa: F401  (used by later phases; kept for interface stability)
import shutil
import signal
import sqlite3
import stat as stat_mod
import sys
import time
import urllib.parse
from collections import deque
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

VERSION = "0.5.1"
PROTOCOL_VERSIONS = [1]

LOG = logging.getLogger("trmx-bridge")

TERMINAL_STATES = {"COMPLETED", "FAILED", "CANCELLED", "LOST"}
ACTIVE_STATES = {"QUEUED", "STARTING", "RUNNING", "CANCELLING"}
HTTP_REASONS = {
    200: "OK", 201: "Created", 206: "Partial Content",
    400: "Bad Request", 401: "Unauthorized",
    403: "Forbidden", 404: "Not Found", 405: "Method Not Allowed",
    409: "Conflict", 411: "Length Required", 413: "Payload Too Large",
    415: "Unsupported Media Type", 416: "Range Not Satisfiable",
    422: "Unprocessable Entity", 429: "Too Many Requests",
    500: "Internal Server Error", 503: "Service Unavailable",
}
MAX_HEADER_BYTES = 16384
MAX_JSON_BODY = 1 << 20  # 1 MiB (PROTOCOL §1.2)
MAX_UPLOAD_BODY = 2 << 30            # 2 GiB (PROTOCOL §6.5)
FILES_PAGE_SIZE = 1000               # PROTOCOL §6.2
UPLOAD_CHUNK = 1 << 16               # 64 KiB stream chunks

ENV_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
RESERVED_ENV = {"TRMX_JOB_ID", "TRMX_TOKEN"}


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def to_base36(n: int) -> str:
    digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    out = ""
    while n:
        out = digits[n % 36] + out
        n //= 36
    return out or "0"


def is_termux() -> bool:
    return bool(os.environ.get("TERMUX_VERSION")) or Path("/data/data/com.termux/files/usr").is_dir()


class BridgeError(Exception):
    """Typed error -> PROTOCOL §10 error envelope."""

    def __init__(self, status: int, code: str, message: str, field: str | None = None, details=None):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message
        self.field, self.details = field, details

    def body(self) -> dict:
        err = {"code": self.code, "message": self.message, "field": self.field, "details": self.details}
        return {"error": err}


# --------------------------------------------------------------------------- config

DEFAULT_CONFIG = {
    "port": 27342,
    "max_concurrent_jobs": 4,
    "queue_depth": 32,
    "log_ring_bytes": 2 * 1024 * 1024,
    "cancel_grace_ms": 5000,
}


class Config:
    """~/.trmx/bridge.json — token + tunables. Mode 0600, never logs the token."""

    def __init__(self, path: Path):
        self.path = path
        self.data: dict = {}

    def load(self) -> "Config":
        if self.path.exists():
            try:
                self.data = json.loads(self.path.read_text("utf-8"))
                if not isinstance(self.data, dict):
                    raise ValueError("config root must be an object")
            except Exception as e:  # noqa: BLE001
                raise SystemExit(f"trmx-bridge: unreadable config {self.path}: {e}")
        merged = dict(DEFAULT_CONFIG)
        merged.update(self.data)
        self.data = merged
        return self

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(self.data, f, indent=2)
            f.write("\n")
        os.chmod(self.path, 0o600)

    def __getitem__(self, k):
        return self.data[k]

    def get(self, k, default=None):
        return self.data.get(k, default)

    @property
    def token(self):
        return self.data.get("token")

    def ensure_token(self) -> bool:
        """Generate a token if none exists. Returns True if it generated one."""
        if self.token:
            return False
        self.data["token"] = secrets.token_urlsafe(32)  # 256-bit
        self.data["token_generated"] = True
        self.save()
        return True


# --------------------------------------------------------------------------- store

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT);
CREATE TABLE IF NOT EXISTS jobs(
  n INTEGER PRIMARY KEY,
  job_id TEXT UNIQUE NOT NULL,
  name TEXT, type TEXT, tool TEXT, argv TEXT, script TEXT, cwd TEXT, env TEXT,
  status TEXT, created_at TEXT, started_at TEXT, ended_at TEXT,
  pid INTEGER, pgid INTEGER, exit_code INTEGER, signal INTEGER,
  cancel_requested INTEGER DEFAULT 0, cancel_reason TEXT,
  error_code TEXT, error_message TEXT, timeout_s INTEGER,
  progress_pct REAL, progress_detail TEXT,
  stdout_bytes INTEGER DEFAULT 0, stderr_bytes INTEGER DEFAULT 0,
  log_seq INTEGER DEFAULT 0, log_truncated INTEGER DEFAULT 0,
  idempotency_key TEXT
);
CREATE INDEX IF NOT EXISTS jobs_status ON jobs(status);
CREATE INDEX IF NOT EXISTS jobs_idem ON jobs(idempotency_key);
CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,
                                 ts TEXT, kind TEXT, detail TEXT);
"""

JOB_COLUMNS = [
    "n", "job_id", "name", "type", "tool", "argv", "script", "cwd", "env",
    "status", "created_at", "started_at", "ended_at", "pid", "pgid",
    "exit_code", "signal", "cancel_requested", "cancel_reason",
    "error_code", "error_message", "timeout_s", "progress_pct",
    "progress_detail", "stdout_bytes", "stderr_bytes", "log_seq",
    "log_truncated", "idempotency_key",
]


class Store:
    """SQLite (WAL) — the source of truth for job state (PROTOCOL §1.5, §9)."""

    def __init__(self, path: Path):
        self.db = sqlite3.connect(str(path))
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=NORMAL")
        self.db.executescript(SCHEMA)
        self.db.commit()

    def next_job_number(self) -> int:
        row = self.db.execute("SELECT v FROM meta WHERE k='jobseq'").fetchone()
        n = (int(row["v"]) + 1) if row else 1
        self.db.execute(
            "INSERT INTO meta(k, v) VALUES('jobseq', ?) "
            "ON CONFLICT(k) DO UPDATE SET v=excluded.v", (str(n),))
        self.db.commit()
        return n

    def save_job(self, job: dict) -> None:
        row = {k: job.get(k) for k in JOB_COLUMNS}
        row["argv"] = json.dumps(job["argv"]) if job.get("argv") is not None else None
        row["env"] = json.dumps(job["env"]) if job.get("env") is not None else None
        row["cancel_requested"] = 1 if job.get("cancel_requested") else 0
        row["log_truncated"] = 1 if job.get("log_truncated") else 0
        cols = ",".join(JOB_COLUMNS)
        ph = ",".join("?" for _ in JOB_COLUMNS)
        self.db.execute(
            f"INSERT OR REPLACE INTO jobs({cols}) VALUES({ph})",
            [row[c] for c in JOB_COLUMNS])
        self.db.commit()

    @staticmethod
    def row_to_job(r: sqlite3.Row) -> dict:
        return {
            "job_id": r["job_id"], "name": r["name"], "type": r["type"], "tool": r["tool"],
            "argv": json.loads(r["argv"]) if r["argv"] else None,
            "script": r["script"], "cwd": r["cwd"],
            "env": json.loads(r["env"]) if r["env"] else None,
            "status": r["status"], "created_at": r["created_at"], "started_at": r["started_at"],
            "ended_at": r["ended_at"], "pid": r["pid"], "pgid": r["pgid"],
            "exit_code": r["exit_code"], "signal": r["signal"],
            "cancel_requested": bool(r["cancel_requested"]), "cancel_reason": r["cancel_reason"],
            "error": ({"code": r["error_code"], "message": r["error_message"]}
                      if r["error_code"] else None),
            "timeout_s": r["timeout_s"], "progress_pct": r["progress_pct"],
            "progress_detail": r["progress_detail"],
            "stdout_bytes": r["stdout_bytes"], "stderr_bytes": r["stderr_bytes"],
            "log_seq": r["log_seq"], "log_truncated": bool(r["log_truncated"]),
            "idempotency_key": r["idempotency_key"],
        }

    def get_job(self, job_id: str) -> dict | None:
        r = self.db.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
        return self.row_to_job(r) if r else None

    def list_jobs(self, status: str | None, before_n: int | None, limit: int) -> tuple[list, int | None]:
        q = "SELECT * FROM jobs"
        conds, args = [], []
        if status:
            conds.append("status=?"); args.append(status)
        if before_n is not None:
            conds.append("n<?"); args.append(before_n)
        if conds:
            q += " WHERE " + " AND ".join(conds)
        q += " ORDER BY n DESC LIMIT ?"
        args.append(limit)
        rows = self.db.execute(q, args).fetchall()
        next_cursor = rows[-1]["job_id"] if len(rows) == limit else None
        return [self.row_to_job(r) for r in rows], next_cursor

    def active_jobs(self) -> list[dict]:
        ph = ",".join("?" for _ in ACTIVE_STATES)
        rows = self.db.execute(f"SELECT * FROM jobs WHERE status IN ({ph}) ORDER BY n",
                               tuple(ACTIVE_STATES)).fetchall()
        return [self.row_to_job(r) for r in rows]

    def audit(self, kind: str, detail: str) -> None:
        self.db.execute("INSERT INTO audit(ts, kind, detail) VALUES(?,?,?)",
                        (now_iso(), kind, detail))
        self.db.commit()


# --------------------------------------------------------------------------- frame log

class FrameLog:
    """Per-job append-only JSONL frame store with byte-capped rotation.

    Each line: {"seq": n, "event": "stdout"|"stderr"|"status"|"info", "data": {...}}
    After rotation the first line is {"_min_seq": n} = lowest retained seq,
    which powers the honest `evicted` replay notice (PROTOCOL §4.1).
    PoC note: rotation rewrites the whole file (fine at 2 MiB caps); Phase 5
    will replace this with a proper fixed-size ring if profiling demands it.
    """

    def __init__(self, path: Path, cap_bytes: int):
        self.path = path
        self.cap = max(1024, int(cap_bytes))
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            self._write_lines([{"_min_seq": 1}])

    def _write_lines(self, objs: list) -> None:
        tmp = self.path.with_suffix(".tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            for o in objs:
                f.write(json.dumps(o, separators=(",", ":")) + "\n")
        os.replace(tmp, self.path)

    def min_seq(self) -> int:
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                first = f.readline()
            obj = json.loads(first) if first.strip() else {}
            return int(obj.get("_min_seq", 1))
        except Exception:  # noqa: BLE001
            return 1

    def append(self, seq: int, event: str, data: dict) -> bool:
        """Append one frame. Returns True if the ring rotated (oldest evicted)."""
        with open(self.path, "a", encoding="utf-8") as f:
            f.write(json.dumps({"seq": seq, "event": event, "data": data},
                               separators=(",", ":")) + "\n")
        if self.path.stat().st_size > self.cap:
            self._rotate()
            return True
        return False

    def _rotate(self) -> None:
        with open(self.path, "r", encoding="utf-8") as f:
            lines = f.readlines()
        keep, size = [], 0
        for ln in reversed(lines):
            if "_min_seq" in ln:
                continue
            keep.append(ln)
            size += len(ln)
            if size > self.cap // 2:
                break
        keep.reverse()
        if not keep:
            self._write_lines([{"_min_seq": 1}])
            return
        first = json.loads(keep[0])
        if "seq" not in first:
            self._write_lines([{"_min_seq": 1}])
            return
        objs = [{"_min_seq": first["seq"]}] + [json.loads(l) for l in keep]
        self._write_lines(objs)

    def replay(self, from_seq: int = 0) -> list[dict]:
        out = []
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                    except ValueError:
                        continue
                    if "_min_seq" in obj:
                        continue
                    if obj.get("seq", 0) >= from_seq:
                        out.append(obj)
        except FileNotFoundError:
            pass
        return out


# --------------------------------------------------------------------------- policy

class Policy:
    """Path & executable policy (PROTOCOL §6.1 subset; full hardening in Phase 10).

    STRICT on Termux: executables must resolve inside $PREFIX/bin or
    ~/.trmx/bin; cwd must be an existing directory inside the roots.
    Dev-lenient elsewhere (sandbox/CI): PATH lookup + any existing cwd,
    so the test suite can run on ordinary Linux. See ADR-004.
    """

    def __init__(self, home: Path):
        self.termux = is_termux()
        self.user_home = Path.home()
        self.prefix = Path("/data/data/com.termux/files/usr") if self.termux else None
        self.trmx_bin = home / "bin"
        self.roots = [self.user_home, self.user_home / "storage"]
        self.deny_write = [self.prefix] if self.prefix else []

    def resolve_binary(self, name: str) -> str | None:
        if not name or "/" in name or name.startswith("."):
            return None
        dirs: list[Path] = [self.trmx_bin]
        if self.termux:
            dirs.append(self.prefix / "bin")
        else:
            dirs += [Path(p) for p in os.environ.get("PATH", "").split(":") if p]
        for d in dirs:
            p = d / name
            if p.is_file() and os.access(p, os.X_OK):
                return str(p)
        return None

    # ---- file endpoints (PROTOCOL §6.1) ---------------------------------

    def file_roots(self) -> list[Path]:
        # realpath()-ed at call time (spec: resolved at policy load; we
        # resolve per request — same effect, tolerates dirs appearing later)
        return [r.resolve() for r in self.roots if r.exists()]

    def resolve_in_roots(self, raw: str, for_write: bool = False) -> Path:
        """Expand a user path and enforce §6.1 containment.

        A path is allowed iff its realpath lies under a resolved root. For
        not-yet-existing paths (mkdir/touch/upload dest) the PARENT's
        realpath decides — realpath of a missing leaf is undefined.
        """
        if not isinstance(raw, str) or not raw:
            raise BridgeError(400, "VALIDATION_FAILED", "path must be a non-empty string",
                              field="path")
        expanded = Path(os.path.normpath(os.path.expanduser(raw)))
        if not expanded.is_absolute():
            raise BridgeError(400, "VALIDATION_FAILED",
                              "path must be absolute or ~-prefixed", field="path")
        # containment per §6.1: REALPATH decides (blocks ~/../… escapes and
        # links pointing outside the roots)…
        real = expanded.resolve()
        probe = real if real.exists() else real.parent
        roots = self.file_roots()
        if not any(probe == r or r in probe.parents for r in roots):
            raise BridgeError(403, "PATH_DENIED",
                              f"path outside policy roots: {raw}", field="path")
        if for_write and self.prefix:
            if probe == self.prefix or self.prefix in probe.parents:
                raise BridgeError(403, "PATH_DENIED",
                                  "$PREFIX is never writable via the API", field="path")
        # …but the OPERATION acts on the normalized path itself, so e.g.
        # deleting a symlink removes the LINK, never its target (on-device
        # lesson from the Phase 7 test suite, 2026-09-08)
        return expanded

    def display_path(self, abs_path: Path) -> str:
        try:
            rel = abs_path.relative_to(self.user_home)
            return "~" if str(rel) == "." else "~/" + str(rel)
        except ValueError:
            return str(abs_path)

    def check_cwd(self, raw: str) -> Path:
        expanded = Path(os.path.expanduser(raw)).resolve()
        if not expanded.exists():
            raise BridgeError(404, "PATH_NOT_FOUND", f"cwd does not exist: {raw}", field="cwd")
        if not expanded.is_dir():
            raise BridgeError(400, "NOT_A_DIRECTORY", f"cwd is not a directory: {raw}", field="cwd")
        if self.termux:
            real_roots = [r.resolve() for r in self.roots]
            if not any(expanded == r or r in expanded.parents for r in real_roots):
                raise BridgeError(403, "PATH_DENIED", f"cwd outside policy roots: {raw}", field="cwd")
        return expanded



# --------------------------------------------------------------------------- tool registry


BUNDLED_TOOL_SCHEMAS: list[dict] = [
    # ---- developer / VCS -------------------------------------------------
    {
        "id": "git-clone",
        "name": "Git Clone (VCS)",
        "description": "Clone a repository into a new directory.",
        "binary": "git",
        "pkg": "git",
        "risk_tier": "confirm",          # fetching + hooks = real code from elsewhere
        "progress_regex": r"Receiving objects:\s+(\d+)%",
        "fixed_argv": ["clone", "--progress"],
        "args": [
            {"name": "url", "label": "Repository URL", "type": "url", "required": True,
             "help": "https:// or git:// URL to clone."},
            {"name": "dir", "label": "Target directory", "type": "path", "required": False,
             "help": "Where to clone (created if missing); defaults to the repo name."},
        ],
        "examples": [
            {"label": "Clone a public repo",
             "args": {"url": "https://github.com/octocat/Hello-World"}},
        ],
    },
    # ---- scripting / AI ----------------------------------------------------
    {
        "id": "python-run",
        "name": "Python Runner (scripting & AI)",
        "description": "Run a Python script — utilities, data processing, local model calls, anything.",
        "binary": "python",
        "risk_tier": "confirm",          # arbitrary code execution, by design
        "fixed_argv": [],
        "args": [
            {"name": "script", "label": "Script file", "type": "path", "path_kind": "file",
             "required": True, "help": "The .py file to run (pick it in the file browser)."},
            {"name": "arg", "label": "Script argument", "type": "string", "required": False,
             "help": "Optional single argument passed to the script."},
        ],
        "examples": [],
    },
    {
        "id": "ai-schema-builder",
        "name": "AI Schema Builder",
        "description": "Describe a command-line tool in plain words; the local LLM wrapper (trmx-ai) drafts a validated schema and installs it to ~/.trmx/tools/. Rescan the toolbox afterwards.",
        "binary": "trmx-ai",           # ~/.trmx/bin — resolve_binary probes it first
        "risk_tier": "safe",           # fixed pipeline; the GENERATED tool is pinned to "confirm" by the wrapper (ADR-014)
        "fixed_argv": [],
        "args": [
            {"name": "description", "label": "What should the tool do?", "type": "string",
             "required": True,
             "help": "Name the binary and what it should do, in plain words."},
            {"name": "model", "label": "Model override", "type": "string",
             "required": False, "pattern": r"^[\w.-]{1,64}$",
             "argv": ["--model", "{value}"]},
        ],
        "examples": [],
    },
    # ---- system / network --------------------------------------------------
    {
        "id": "http-server",
        "name": "Local HTTP Server",
        "description": "Serve a folder over HTTP on the phone. Runs until stopped — cancel the job to stop the server.",
        "binary": "python",
        "risk_tier": "safe",
        "progress_regex": r"(Serving HTTP on .+ port \d+)",   # port-binding line → detail
        "fixed_argv": ["-m", "http.server"],
        "args": [
            {"name": "port", "label": "Port", "type": "int", "min": 1024, "max": 65535,
             "default": 8000, "required": False},
            {"name": "dir", "label": "Folder to serve", "type": "path", "path_kind": "dir",
             "default": "~", "required": False},
        ],
        "examples": [
            {"label": "Serve home on 8000", "args": {"port": 8000, "dir": "~"}},
        ],
    },
    # ---- system utility ------------------------------------------------------
    {
        "id": "tar-backup",
        "name": "Archive / Backup (tar.gz)",
        "description": "Create a compressed .tar.gz archive of a file or folder.",
        "binary": "tar",
        "pkg": "tar",
        "risk_tier": "confirm",          # overwrites the archive target
        "fixed_argv": ["-czf"],
        "args": [
            {"name": "archive", "label": "Archive file (.tar.gz)", "type": "path",
             "required": True, "help": "Output archive — created/overwritten."},
            {"name": "source", "label": "What to archive", "type": "path",
             "required": True, "help": "File or folder to pack."},
        ],
        "examples": [],
    },
    # ---- media (kept: real, heavily used tools — but no longer the whole story)
    # yt-dlp: VERBATIM the frozen normative fixture (fixtures/v1/tool.schema.yt-dlp.json)
    {
        "id": "yt-dlp",
        "name": "Video Downloader (yt-dlp)",
        "description": "Download videos from thousands of sites.",
        "binary": "yt-dlp",
        "pkg": "yt-dlp",          # Termux package hint (app-side install button)
        "risk_tier": "safe",
        "progress_regex": r"\[download\]\s+(\d{1,3}(?:\.\d+)?)%",
        "fixed_argv": ["--newline"],
        "args": [
            {"name": "url", "label": "Video URL", "type": "url", "required": True,
             "help": "The page or direct video URL."},
            {"name": "format", "label": "Quality", "type": "enum", "required": False,
             "enum": ["mp4", "mkv", "best"], "default": "mp4", "argv": ["-f", "{value}"]},
            {"name": "audio", "label": "Audio only", "type": "bool", "default": False, "argv": ["-x"]},
            {"name": "rate", "label": "Rate limit", "type": "string", "required": False,
             "pattern": r"^\d+[KM]$", "argv": ["-r", "{value}"]},
            {"name": "outdir", "label": "Output folder", "type": "path", "path_kind": "dir",
             "default": "~/downloads", "argv": ["-P", "{value}"], "mkdir": True},
        ],
        "examples": [
            {"label": "MP4, best quality",
             "args": {"url": "https://example.com/watch?v=xyz", "format": "mp4"}},
        ],
    },
    {
        "id": "ffmpeg",
        "name": "Convert / Transcode (ffmpeg)",
        "description": "Re-encode video and audio files.",
        "binary": "ffmpeg",
        "pkg": "ffmpeg",
        "risk_tier": "confirm",
        "progress_regex": r"time=(\d{2}:\d{2}:\d{2}\.\d{2})",
        "fixed_argv": ["-hide_banner", "-nostdin"],
        "args": [
            {"name": "input", "label": "Input file", "type": "path", "path_kind": "file",
             "required": True, "help": "The file to convert."},
            {"name": "output", "label": "Output file", "type": "path", "path_kind": "file",
             "required": True, "help": "Where to write the result."},
            {"name": "preset", "label": "Speed preset", "type": "enum", "required": False,
             "enum": ["veryfast", "fast", "medium", "slow"], "default": "medium",
             "argv": ["-preset", "{value}"]},
            {"name": "crf", "label": "Quality (CRF)", "type": "int", "required": False,
             "min": 0, "max": 51, "default": 23, "argv": ["-crf", "{value}"]},
            {"name": "scale", "label": "Scale to (e.g. 1280x720)", "type": "string",
             "required": False, "pattern": r"^\d{2,5}x\d{2,5}$", "argv": ["-vf", "scale={value}"]},
            {"name": "audio_only", "label": "Strip video (audio only)", "type": "bool",
             "default": False, "argv": ["-vn"]},
        ],
        "examples": [
            {"label": "Compress to 720p",
             "args": {"input": "~/downloads/in.mp4", "output": "~/downloads/out.mp4",
                      "scale": "1280x720"}},
        ],
    },
    {
        "id": "aria2c",
        "name": "Fast Downloader (aria2c)",
        "description": "Multi-connection downloads over HTTP(S)/FTP.",
        "binary": "aria2c",
        "pkg": "aria2",
        "risk_tier": "safe",
        "progress_regex": r"\((\d{1,3})%\)",
        "fixed_argv": [],
        "args": [
            {"name": "url", "label": "Download URL", "type": "url", "required": True},
            {"name": "outdir", "label": "Output folder", "type": "path", "path_kind": "dir",
             "default": "~/downloads", "argv": ["-d", "{value}"], "mkdir": True},
            {"name": "connections", "label": "Connections per server", "type": "int",
             "required": False, "min": 1, "max": 16, "default": 4,
             "argv": ["-x", "{value}"]},
            {"name": "filename", "label": "Save as (optional)", "type": "string",
             "required": False, "pattern": r"^[^/\\]+$", "argv": ["-o", "{value}"]},
        ],
        "examples": [
            {"label": "Quick fetch",
             "args": {"url": "https://example.com/file.zip"}},
        ],
    },
]

TOOL_ARG_TYPES = ("string", "int", "float", "bool", "enum", "path", "url")


class ToolRegistry:
    """Bundled + user tool schemas (PROTOCOL §7.2) and availability probing.

    Merge precedence: user schemas (~/.trmx/tools/*.json, alphabetical)
    override bundled schemas with the same id. Malformed user schemas are
    skipped and reported via system/info.features.tool_schema_errors;
    a malformed BUNDLED schema is our bug and fails loud.
    """

    def __init__(self, home: Path, policy: Policy):
        self.home = home
        self.policy = policy
        self.schema_errors: list[str] = []
        self.schemas: dict[str, dict] = {}
        self._probe: dict[str, dict] = {}
        self._load()

    # -- loading ---------------------------------------------------------

    def _load(self) -> None:
        self.schema_errors = []
        merged: dict[str, dict] = {}
        sources: list[tuple[str, dict]] = [("bundled", s) for s in BUNDLED_TOOL_SCHEMAS]
        udir = self.home / "tools"
        if udir.is_dir():
            for p in sorted(udir.glob("*.json")):
                try:
                    sources.append(("user:" + p.name, json.loads(p.read_text(encoding="utf-8"))))
                except Exception as e:  # noqa: BLE001
                    self.schema_errors.append(f"user:{p.name}: not valid JSON: {e}")
        for origin, schema in sources:
            try:
                self._validate_schema(schema)
            except Exception as e:  # noqa: BLE001
                if origin == "bundled":
                    raise
                self.schema_errors.append(f"{origin}: {e}")
                continue
            merged[schema["id"]] = schema
        self.schemas = merged

    @staticmethod
    def _validate_schema(schema: dict) -> None:
        if not isinstance(schema, dict):
            raise ValueError("schema must be a JSON object")
        tid = schema.get("id")
        if not isinstance(tid, str) or not tid or "/" in tid:
            raise ValueError("id must be a non-empty string without '/'")
        binary = schema.get("binary")
        if not isinstance(binary, str) or not binary or "/" in binary or binary.startswith("."):
            raise ValueError("binary must be a bare executable name")
        if schema.get("risk_tier", "safe") not in ("safe", "confirm", "destructive"):
            raise ValueError("risk_tier must be safe|confirm|destructive")
        fixed = schema.get("fixed_argv", [])
        if not isinstance(fixed, list) or not all(isinstance(x, str) for x in fixed):
            raise ValueError("fixed_argv must be a list of strings")
        args = schema.get("args", [])
        if not isinstance(args, list):
            raise ValueError("args must be a list")
        names = set()
        for a in args:
            if not isinstance(a, dict) or not isinstance(a.get("name"), str) or not a["name"]:
                raise ValueError("each arg needs a non-empty name")
            if a["name"] in names:
                raise ValueError(f"duplicate arg name '{a['name']}'")
            names.add(a["name"])
            t = a.get("type", "string")
            if t not in TOOL_ARG_TYPES:
                raise ValueError(f"arg '{a['name']}': bad type '{t}'")
            tmpl = a.get("argv")
            if tmpl is not None and (not isinstance(tmpl, list)
                                     or not all(isinstance(x, str) for x in tmpl)):
                raise ValueError(f"arg '{a['name']}': argv must be a list of strings")
            if t == "enum" and not isinstance(a.get("enum"), list) or t == "enum" and not a.get("enum"):
                raise ValueError(f"arg '{a['name']}': enum needs a non-empty enum list")
            if t == "bool":
                if tmpl and any("{value}" in x for x in tmpl):
                    raise ValueError(f"bool arg '{a['name']}': argv may not contain {{value}}")
            elif tmpl is not None:
                if sum(x.count("{value}") for x in tmpl) != 1:
                    raise ValueError(f"arg '{a['name']}': argv must contain {{value}} exactly once")
            if "mkdir" in a:
                if a.get("path_kind") != "dir":
                    raise ValueError(f"arg '{a['name']}': mkdir requires path_kind 'dir'")
                if not isinstance(a["mkdir"], bool):
                    raise ValueError(f"arg '{a['name']}': mkdir must be a boolean")
            if a.get("pattern"):
                try:
                    re.compile(a["pattern"])
                except re.error as e:
                    raise ValueError(f"arg '{a['name']}': bad pattern: {e}") from e
        if schema.get("progress_regex"):
            try:
                re.compile(schema["progress_regex"])
            except re.error as e:
                raise ValueError(f"bad progress_regex: {e}") from e

    # -- access ----------------------------------------------------------

    def get(self, tool_id: str) -> dict | None:
        return self.schemas.get(tool_id)

    def list_status(self) -> list[dict]:
        return [self._status(t) for t in sorted(self.schemas)]

    def _status(self, tid: str) -> dict:
        p = self._probe.get(tid, {})
        return {"schema": self.schemas[tid],
                "installed": bool(p.get("installed")),
                "version": p.get("version")}

    async def ensure_probed(self) -> list[dict]:
        for tid in sorted(self.schemas):
            if tid not in self._probe:
                await self._probe_one(tid)
        return self.list_status()

    async def refresh(self) -> list[dict]:
        # re-read bundled + user schema files first: a schema dropped into
        # ~/.trmx/tools/ (by hand, or by an AI schema builder) appears after
        # a refresh — no bridge restart needed (ADR-009 addendum).
        self._load()
        for tid in sorted(self.schemas):
            await self._probe_one(tid)
        return self.list_status()

    async def _probe_one(self, tid: str) -> dict:
        schema = self.schemas[tid]
        binary = self.policy.resolve_binary(schema["binary"])
        version = None
        if binary:
            version = await self._capture_version(binary)
        self._probe[tid] = {"installed": binary is not None, "version": version}
        return self._probe[tid]

    async def _capture_version(self, binary: str) -> str | None:
        """`<binary> --version`, first line, 10 s cap (PROTOCOL §7.1)."""
        try:
            proc = await asyncio.create_subprocess_exec(
                binary, "--version",
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
            try:
                out, _ = await asyncio.wait_for(proc.communicate(), timeout=10)
            except asyncio.TimeoutError:
                proc.kill()
                await proc.wait()
                return None
            lines = out.decode("utf-8", "replace").strip().splitlines()
            return lines[0][:120] if lines else None
        except Exception:  # noqa: BLE001
            return None

    # -- argv synthesis (PROTOCOL §7.2 rules, bridge-enforced) ----------

    def synth_argv(self, schema: dict, args) -> list[str]:
        if args is None:
            args = {}
        if not isinstance(args, dict):
            raise BridgeError(400, "ARG_INVALID", "args must be an object", field="args")
        known = {a["name"]: a for a in schema.get("args", [])}
        for k in args:
            if k not in known:
                raise BridgeError(400, "ARG_INVALID", f"unknown arg '{k}'", field=k)
        tokens: list[str] = []
        for a in schema.get("args", []):
            name = a["name"]
            if name not in args:
                if a.get("required"):
                    raise BridgeError(400, "ARG_INVALID", f"required arg '{name}' is missing",
                                      field=name)
                continue
            value = self._validate_arg(a, args[name])
            if a.get("type") == "bool" and value is False:
                continue                # bool False -> contributes nothing (§7.2)
            tokens.extend(self._argv_tokens(a, value))
        return tokens

    def _validate_arg(self, a: dict, value) -> object:
        name, t = a["name"], a.get("type", "string")

        def bad(msg: str) -> None:
            raise BridgeError(400, "ARG_INVALID", f"arg '{name}': {msg}", field=name)

        if t == "bool":
            if not isinstance(value, bool):
                bad("must be true or false")
            return value
        if isinstance(value, bool):
            bad("must not be a boolean")
        if t == "int":
            if not isinstance(value, int):
                bad("must be an integer")
            if ("min" in a and value < a["min"]) or ("max" in a and value > a["max"]):
                bad(f"must be between {a.get('min')} and {a.get('max')}")
            return value
        if t == "float":
            if not isinstance(value, (int, float)):
                bad("must be a number")
            v = float(value)
            if ("min" in a and v < a["min"]) or ("max" in a and v > a["max"]):
                bad(f"must be between {a.get('min')} and {a.get('max')}")
            return v
        if t == "enum":
            if value not in a.get("enum", []):
                bad(f"must be one of {a.get('enum')}")
            return value
        if not isinstance(value, str) or value == "":
            bad("must be a non-empty string")
        if t == "url":
            u = urllib.parse.urlparse(value)
            if u.scheme not in ("http", "https") or not u.netloc:
                bad("must be an http(s):// URL")
        if a.get("pattern") and not re.fullmatch(a["pattern"], value):
            bad(f"must match pattern {a['pattern']}")
        if t == "path":
            return self._resolve_path_arg(a, value)
        return value

    def _resolve_path_arg(self, a: dict, raw: str) -> str:
        """Path args are resolved against the §6.1 policy and substituted as
        ABSOLUTE paths — argv is exec'd without a shell, so '~' would never
        expand (ADR-009).

        `mkdir: true` (§7.2, dir args only — output folders like yt-dlp's
        -P / aria2c's -d): a missing directory is CREATED (parents too,
        `mkdir -p` semantics) instead of failing PATH_NOT_FOUND. Creation
        resolves with WRITE semantics, so it stays inside the §6.1 writable
        roots; input dirs (e.g. http-server's serve folder) keep the
        existence check."""
        name = a["name"]
        auto_mkdir = bool(a.get("mkdir"))
        try:
            resolved = self.policy.resolve_in_roots(
                raw, for_write=auto_mkdir or a.get("path_kind") != "dir")
        except BridgeError as e:
            raise BridgeError(e.status, e.code, f"arg '{name}': {e.message}", field=name) from e
        if a.get("path_kind") == "dir" and not resolved.is_dir():
            if not auto_mkdir:
                raise BridgeError(404, "PATH_NOT_FOUND",
                                  f"arg '{name}': directory does not exist: {raw}", field=name)
            try:
                resolved.mkdir(parents=True, exist_ok=True)
            except OSError as e:
                raise BridgeError(400, "VALIDATION_FAILED",
                                  f"arg '{name}': cannot create directory {raw}: {e}",
                                  field=name) from e
        return str(resolved)

    @staticmethod
    def _argv_tokens(a: dict, value) -> list[str]:
        tmpl = a.get("argv")
        if not tmpl:                     # positional: the value itself
            return [str(value)]
        if a.get("type") == "bool":
            return list(tmpl)            # tokens iff true
        return [tok.replace("{value}", str(value)) for tok in tmpl]


# --------------------------------------------------------------------------- event bus

class EventBus:
    """Fan-out for the global /v1/events stream (PROTOCOL §5)."""

    def __init__(self):
        self.subs: set[asyncio.Queue] = set()
        self.counter = itertools.count(1)

    def subscribe(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue()
        self.subs.add(q)
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        self.subs.discard(q)

    def publish(self, event: str, data: dict) -> None:
        for q in list(self.subs):
            try:
                q.put_nowait((next(self.counter), event, data))
            except asyncio.QueueFull:
                pass  # slow subscriber: job state remains queryable in SQLite


# --------------------------------------------------------------------------- job runtime

class JobRuntime:
    """In-memory live state for one job (frames, followers, process handles)."""

    def __init__(self, job_id: str, frames: FrameLog, seq: int = 0):
        self.job_id = job_id
        self.frames = frames
        self.seq = seq
        self.followers: set[asyncio.Queue] = set()
        self.proc: asyncio.subprocess.Process | None = None
        self.adopted = False
        self.tasks: set[asyncio.Task] = set()
        self.pumps: list[asyncio.Task] = []
        self.stdout_bytes = 0
        self.stderr_bytes = 0
        self.log_truncated = False
        self._last_persist = 0.0
        self._frames_since_persist = 0


# --------------------------------------------------------------------------- job manager

class JobManager:
    """Owns the job lifecycle (PROTOCOL §3.1) — spawn, stream, cancel, reconcile."""

    def __init__(self, home: Path, store: Store, config: Config, policy: Policy,
                 bus: EventBus, registry: ToolRegistry):
        self.home = home
        self.store = store
        self.config = config
        self.policy = policy
        self.bus = bus
        self.registry = registry
        self.runtimes: dict[str, JobRuntime] = {}
        self.running: set[str] = set()
        self.queue: deque[str] = deque()
        self.idem: dict[str, tuple[str, float]] = {}
        self.started_at = time.time()

    # -- helpers ---------------------------------------------------------

    def _log_path(self, job_id: str) -> Path:
        return self.home / "logs" / job_id / "frames.jsonl"

    def _runtime(self, job_id: str, seq: int = 0) -> JobRuntime:
        if job_id not in self.runtimes:
            cap = int(self.config.get("log_ring_bytes", DEFAULT_CONFIG["log_ring_bytes"]))
            self.runtimes[job_id] = JobRuntime(
                job_id, FrameLog(self._log_path(job_id), cap), seq)
        return self.runtimes[job_id]

    def get_job(self, job_id: str) -> dict | None:
        job = self.store.get_job(job_id)
        if job:
            self._overlay_runtime(job)
        return job

    def _overlay_runtime(self, job: dict) -> None:
        """Overlay live in-memory counters onto a DB row (they persist lazily)."""
        rt = self.runtimes.get(job["job_id"])
        if rt:
            job["stdout_bytes"] = rt.stdout_bytes or job["stdout_bytes"]
            job["stderr_bytes"] = rt.stderr_bytes or job["stderr_bytes"]
            job["log_seq"] = max(job["log_seq"], rt.seq)
            job["log_truncated"] = job["log_truncated"] or rt.log_truncated

    # -- submission ------------------------------------------------------

    async def submit(self, payload: dict) -> tuple[dict, bool]:
        if not isinstance(payload, dict):
            raise BridgeError(400, "VALIDATION_FAILED", "body must be a JSON object")
        jtype = payload.get("type", "argv")
        tool_id = None
        if jtype == "tool":
            # PROTOCOL §7.2: argv is synthesized from the schema, then runs
            # through the SAME allowlist/size checks as a raw argv submit.
            tool_id = payload.get("tool")
            if not isinstance(tool_id, str) or not tool_id:
                raise BridgeError(400, "VALIDATION_FAILED",
                                  "tool submits need a tool id", field="tool")
            schema = self.registry.get(tool_id)
            if schema is None:
                raise BridgeError(400, "TOOL_UNKNOWN",
                                  f"no tool schema with id '{tool_id}'", field="tool")
            argv = [schema["binary"]] + list(schema.get("fixed_argv", [])) + \
                self.registry.synth_argv(schema, payload.get("args"))
        elif jtype != "argv":
            raise BridgeError(400, "VALIDATION_FAILED",
                              f"type '{jtype}' is not implemented (only \"argv\"/\"tool\")",
                              field="type")
        name = payload.get("name") or "job"
        if not isinstance(name, str) or not (1 <= len(name) <= 200):
            raise BridgeError(400, "VALIDATION_FAILED", "name must be 1..200 chars", field="name")
        if jtype != "tool":   # tool argv is synthesized above (§7.2)
            argv = payload.get("argv")
            if (not isinstance(argv, list) or not (1 <= len(argv) <= 256)
                    or not all(isinstance(a, str) for a in argv)):
                raise BridgeError(400, "VALIDATION_FAILED",
                                  "argv must be an array of 1..256 strings", field="argv")
        if any(len(a) > 8192 for a in argv) or sum(len(a) for a in argv) > 65536:
            raise BridgeError(400, "VALIDATION_FAILED", "argv exceeds size caps", field="argv")
        binary = self.policy.resolve_binary(argv[0])
        if binary is None:
            raise BridgeError(400, "VALIDATION_FAILED",
                              f"executable not found in allowlist: '{argv[0]}'", field="argv[0]")
        argv = [binary] + list(argv[1:])
        cwd = self.policy.check_cwd(payload.get("cwd") or "~")
        env = payload.get("env")
        if env is not None:
            if (not isinstance(env, dict) or len(env) > 32
                    or not all(isinstance(k, str) and isinstance(v, str) for k, v in env.items())):
                raise BridgeError(400, "VALIDATION_FAILED",
                                  "env must be an object of <=32 string->string", field="env")
            bad = [k for k in env if not ENV_NAME_RE.match(k)]
            reserved = [k for k in env if k in RESERVED_ENV]
            if bad or reserved:
                raise BridgeError(400, "VALIDATION_FAILED",
                                  f"invalid/reserved env keys: {bad + reserved}", field="env")
        timeout_s = payload.get("timeout_s")
        if timeout_s is not None and (not isinstance(timeout_s, int) or timeout_s < 1
                                      or timeout_s > 2_592_000):
            raise BridgeError(400, "VALIDATION_FAILED",
                              "timeout_s must be an int in 1..2592000", field="timeout_s")
        idem = payload.get("idempotency_key")
        if idem is not None and (not isinstance(idem, str) or len(idem) > 128):
            raise BridgeError(400, "VALIDATION_FAILED",
                              "idempotency_key must be a string <=128 chars", field="idempotency_key")
        if idem:
            hit = self.idem.get(idem)
            if hit and time.time() - hit[1] < 86400:
                job = self.store.get_job(hit[0])
                if job:
                    return job, True
        if len(self.queue) >= int(self.config["queue_depth"]):
            raise BridgeError(429, "QUEUE_FULL",
                              f"job queue is full ({self.config['queue_depth']} pending)")

        n = self.store.next_job_number()
        job_id = f"J-{to_base36(n)}"
        job = {
            "job_id": job_id, "name": name, "type": jtype, "tool": tool_id,
            "argv": argv, "script": None, "cwd": str(cwd), "env": env,
            "status": "QUEUED", "created_at": now_iso(), "started_at": None, "ended_at": None,
            "pid": None, "pgid": None, "exit_code": None, "signal": None,
            "cancel_requested": False, "cancel_reason": None, "error": None,
            "timeout_s": timeout_s, "progress_pct": None, "progress_detail": None,
            "stdout_bytes": 0, "stderr_bytes": 0, "log_seq": 0, "log_truncated": False,
            "idempotency_key": idem,
        }
        self.store.save_job(job)
        self._runtime(job_id)
        if idem:
            self.idem[idem] = (job_id, time.time())
        if tool_id:
            tier = (self.registry.get(tool_id) or {}).get("risk_tier", "safe")
            self.store.audit("job.submit",
                             f"{job_id} tool={tool_id} tier={tier} {shlex_join(argv)}")
        else:
            self.store.audit("job.submit", f"{job_id} {shlex_join(argv)}")
        self.bus.publish("job.updated", job)
        self.queue.append(job_id)
        await self._try_start_next()
        return job, False

    # -- start / pump / finalize ----------------------------------------

    async def _try_start_next(self) -> None:
        while self.queue and len(self.running) < int(self.config["max_concurrent_jobs"]):
            job_id = self.queue.popleft()
            await self._start(job_id)

    async def _start(self, job_id: str) -> None:
        job = self.store.get_job(job_id)
        if not job or job["status"] != "QUEUED":
            return
        job["status"] = "STARTING"
        self.store.save_job(job)
        self.bus.publish("job.updated", job)
        rt = self._runtime(job_id)
        env = dict(os.environ)
        env["TRMX_JOB_ID"] = job_id
        if job["env"]:
            env.update(job["env"])
        try:
            proc = await asyncio.create_subprocess_exec(
                *job["argv"], stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE, stdin=asyncio.subprocess.DEVNULL,
                cwd=job["cwd"], env=env, start_new_session=True)
        except FileNotFoundError:
            self._finalize(job_id, "FAILED",
                           error={"code": "ENOENT", "message": f"executable not found: {job['argv'][0]}"})
            return
        except PermissionError:
            self._finalize(job_id, "FAILED",
                           error={"code": "EACCES", "message": f"not executable: {job['argv'][0]}"})
            return
        except Exception as e:  # noqa: BLE001
            LOG.exception("spawn failed for %s", job_id)
            self._finalize(job_id, "FAILED",
                           error={"code": "SPAWN_ERROR", "message": str(e)})
            return
        rt.proc = proc
        self.running.add(job_id)
        job.update(status="RUNNING", started_at=now_iso(), pid=proc.pid, pgid=proc.pid)
        self.store.save_job(job)
        self._emit_status(job)
        self.bus.publish("job.updated", job)
        t_out = asyncio.create_task(self._pump(job_id, proc.stdout, "stdout"))
        t_err = asyncio.create_task(self._pump(job_id, proc.stderr, "stderr"))
        rt.pumps = [t_out, t_err]
        rt.tasks.update(rt.pumps)
        rt.tasks.add(asyncio.create_task(self._wait_exit(job_id, proc)))
        if job["timeout_s"]:
            rt.tasks.add(asyncio.create_task(self._timeout_watch(job_id, job["timeout_s"])))

    async def _pump(self, job_id: str, stream, kind: str) -> None:
        rt = self.runtimes.get(job_id)
        if rt is None:
            return
        dec = codecs.getincrementaldecoder("utf-8")("replace")
        # §7.2 progress: parse COMPLETE lines (stdout AND stderr — git/ffmpeg
        # report progress on stderr) against the tool's progress_regex; update
        # the job + emit job.updated on change. \r counts as a line break:
        # progress bars (wget/git style) overwrite the line with carriage
        # returns and would otherwise never produce a "complete" line.
        on_line = self._progress_parser(job_id)
        buf = ""
        try:
            while True:
                chunk = await stream.read(65536)
                if not chunk:
                    break
                if kind == "stdout":
                    rt.stdout_bytes += len(chunk)
                else:
                    rt.stderr_bytes += len(chunk)
                text = dec.decode(chunk)
                if text:
                    self._emit(job_id, kind, {"job_id": job_id, "text": text})
                    if on_line is not None:
                        buf += text.replace("\r", "\n")
                        *lines, buf = buf.split("\n")
                        for ln in lines:
                            on_line(ln)
            tail = dec.decode(b"", final=True)
            if tail:
                self._emit(job_id, kind, {"job_id": job_id, "text": tail})
                if on_line is not None:
                    # a final line without a trailing newline still counts
                    for ln in (buf + tail.replace("\r", "\n")).split("\n"):
                        on_line(ln)
                    buf = ""
        except Exception:  # noqa: BLE001
            LOG.exception("output pump failed for %s", job_id)

    def _progress_parser(self, job_id: str):
        """Build a per-line progress callback, or None for non-tool jobs."""
        job = self.store.get_job(job_id)
        if not job or not job.get("tool"):
            return None
        schema = self.registry.get(job["tool"])
        rx_s = schema.get("progress_regex") if schema else None
        if not rx_s:
            return None
        rx = re.compile(rx_s)
        state = {"pct": None, "detail": None}

        def on_line(line: str) -> None:
            m = rx.search(line)
            if not m:
                return
            # numeric first group -> percent; non-numeric (a port-binding or
            # key=value line) -> detail-only update, pct untouched (§7.2: the
            # matched line populates progress_detail regardless)
            try:
                pct = min(max(float(m.group(1)), 0.0), 100.0)
            except (IndexError, ValueError):
                pct = None
            detail = line.strip()[:200]
            if pct == state["pct"] and detail == state["detail"]:
                return
            state["pct"], state["detail"] = pct, detail
            current = self.store.get_job(job_id)
            if current and current.get("status") == "RUNNING":
                if pct is not None:
                    current["progress_pct"] = round(pct, 1)
                current["progress_detail"] = detail
                self.store.save_job(current)
                self._emit_status(current)
        return on_line

    async def _wait_exit(self, job_id: str, proc) -> None:
        rc = await proc.wait()
        self.running.discard(job_id)
        # Drain output pipes BEFORE finalizing so no frame ever lands after
        # the terminal status frame (ordering guarantee, PROTOCOL §4.1).
        rt = self.runtimes.get(job_id)
        if rt and rt.pumps:
            try:
                await asyncio.wait_for(asyncio.gather(*rt.pumps), timeout=10.0)
            except asyncio.TimeoutError:
                LOG.warning("output pumps for %s did not drain in 10s", job_id)
        current = self.store.get_job(job_id)
        if current and current.get("cancel_requested"):
            # cancelled by user or timeout -> CANCELLED (PROTOCOL §3.1)
            self._finalize(job_id, "CANCELLED",
                           reason=current.get("cancel_reason") or "user",
                           exit_code=(rc if rc is not None and rc >= 0 else None),
                           signal=(-rc if rc is not None and rc < 0 else None))
        elif rc == 0:
            self._finalize(job_id, "COMPLETED", exit_code=0)
        elif rc is not None and rc < 0:
            self._finalize(job_id, "FAILED", signal=-rc)
        else:
            self._finalize(job_id, "FAILED", exit_code=rc)

    def _emit(self, job_id: str, event: str, data: dict) -> None:
        rt = self.runtimes.get(job_id)
        if rt is None:
            return
        rt.seq += 1
        data = dict(data)
        data["seq"] = rt.seq
        data["job_id"] = job_id
        try:
            rotated = rt.frames.append(rt.seq, event, data)
            if rotated and not rt.log_truncated:
                rt.log_truncated = True
                self._emit_info(rt, {"type": "truncating"})
        except Exception:  # noqa: BLE001
            LOG.exception("frame append failed for %s", job_id)
        for q in list(rt.followers):
            q.put_nowait((event, data))
        rt._frames_since_persist += 1
        now = time.time()
        if rt._frames_since_persist >= 16 or now - rt._last_persist > 1.0:
            rt._last_persist, rt._frames_since_persist = now, 0
            job = self.store.get_job(job_id)
            if job:
                job.update(stdout_bytes=rt.stdout_bytes, stderr_bytes=rt.stderr_bytes,
                           log_seq=rt.seq, log_truncated=rt.log_truncated)
                self.store.save_job(job)

    def _emit_info(self, rt: JobRuntime, payload: dict) -> None:
        rt.seq += 1
        data = dict(payload)
        data["seq"] = rt.seq
        data["job_id"] = rt.job_id
        rt.frames.append(rt.seq, "info", data)
        for q in list(rt.followers):
            q.put_nowait(("info", data))

    def _emit_status(self, job: dict) -> None:
        self._emit(job["job_id"], "status", {
            "status": job["status"], "exit_code": job.get("exit_code"),
            "signal": job.get("signal"), "error": job.get("error"),
            "ended_at": job.get("ended_at"),
            "progress_pct": job.get("progress_pct"),
            "progress_detail": job.get("progress_detail"),
        })

    def _finalize(self, job_id: str, status: str, error: dict | None = None,
                  reason: str | None = None, exit_code=None, signal=None) -> dict:
        job = self.store.get_job(job_id)
        if not job:
            raise BridgeError(404, "JOB_NOT_FOUND", f"no such job: {job_id}")
        if job["status"] in TERMINAL_STATES:
            return job
        job.update(status=status, ended_at=now_iso())
        if error is not None:
            job["error"] = error
        if reason is not None:
            job["cancel_reason"] = reason
        if exit_code is not None:
            job["exit_code"] = exit_code
        if signal is not None:
            job["signal"] = signal
        rt = self.runtimes.get(job_id)
        if rt:
            job.update(stdout_bytes=rt.stdout_bytes, stderr_bytes=rt.stderr_bytes,
                       log_seq=max(job["log_seq"], rt.seq), log_truncated=rt.log_truncated)
        self.store.save_job(job)
        self.running.discard(job_id)
        if rt:
            self._emit_status(job)
        self.bus.publish("job.updated", job)
        LOG.info("job %s -> %s%s", job_id, status, f" ({reason})" if reason else "")
        asyncio.get_running_loop().create_task(self._try_start_next())
        return job

    # -- cancel / timeout ------------------------------------------------

    async def cancel(self, job_id: str, grace_ms: int | None, force: bool) -> dict:
        job = self.get_job(job_id)
        if not job:
            raise BridgeError(404, "JOB_NOT_FOUND", f"no such job: {job_id}")
        if job["status"] in TERMINAL_STATES:
            return job  # idempotent (PROTOCOL §3.5)
        grace_ms = grace_ms if grace_ms is not None else int(self.config["cancel_grace_ms"])
        grace_ms = max(0, min(int(grace_ms), 60000))
        if job["status"] == "QUEUED":
            try:
                self.queue.remove(job_id)
            except ValueError:
                pass
            job["cancel_requested"] = True
            self.store.save_job(job)
            return self._finalize(job_id, "CANCELLED", reason="user")
        job.update(status="CANCELLING", cancel_requested=True, cancel_reason="user")
        self.store.save_job(job)
        self._emit_status(job)
        self.bus.publish("job.updated", job)
        rt = self.runtimes.get(job_id)
        pgid = job["pgid"]
        if pgid:
            sig = signal.SIGKILL if force else signal.SIGTERM
            LOG.info("cancel %s: killpg(%s, %s)", job_id, pgid, sig.name)
            self._signal_pgid(pgid, sig)
            if not force:
                asyncio.get_running_loop().create_task(self._grace(job_id, pgid, grace_ms))
        return self.get_job(job_id) or job

    async def _grace(self, job_id: str, pgid: int, grace_ms: int) -> None:
        await asyncio.sleep(grace_ms / 1000.0)
        job = self.store.get_job(job_id)
        if job and job["status"] == "CANCELLING" and job["pgid"] == pgid:
            LOG.info("grace expired for %s: killpg(%s, SIGKILL)", job_id, pgid)
            self._signal_pgid(pgid, signal.SIGKILL)

    def _signal_pgid(self, pgid: int, sig: int) -> None:
        try:
            os.killpg(pgid, sig)
        except (ProcessLookupError, PermissionError, OSError):
            pass

    async def _timeout_watch(self, job_id: str, timeout_s: int) -> None:
        await asyncio.sleep(timeout_s)
        job = self.store.get_job(job_id)
        if not job or job["status"] in TERMINAL_STATES:
            return
        if job["status"] == "QUEUED":
            try:
                self.queue.remove(job_id)
            except ValueError:
                pass
            job["cancel_requested"] = True
            self.store.save_job(job)
            self._finalize(job_id, "CANCELLED", reason="timeout")
            return
        job.update(status="CANCELLING", cancel_requested=True, cancel_reason="timeout")
        self.store.save_job(job)
        self._emit_status(job)
        self.bus.publish("job.updated", job)
        if job["pgid"]:
            LOG.info("timeout %s: killpg(%s, SIGTERM)", job_id, job["pgid"])
            self._signal_pgid(job["pgid"], signal.SIGTERM)
            asyncio.get_running_loop().create_task(
                self._grace(job_id, job["pgid"], int(self.config["cancel_grace_ms"])))

    # -- restart reconciliation (PROTOCOL §3.1 LOST, §4.1 reattached) -----

    async def reconcile(self) -> None:
        for job in self.store.active_jobs():
            job_id = job["job_id"]
            rt = self._runtime(job_id, seq=job["log_seq"])
            if job["status"] == "QUEUED":
                self.queue.append(job_id)
                continue
            alive = False
            if job["pgid"]:
                try:
                    os.killpg(job["pgid"], 0)
                    alive = True
                except OSError:
                    alive = False
            if alive:
                rt.adopted = True
                self.running.add(job_id)
                job["status"] = "CANCELLING" if job["cancel_requested"] else "RUNNING"
                self.store.save_job(job)
                self._emit_info(rt, {"type": "reattached"})
                self.bus.publish("job.updated", job)
                rt.tasks.add(asyncio.create_task(self._probe_adopted(job_id)))
                LOG.info("reconcile: re-adopted %s (pgid %s)", job_id, job["pgid"])
            else:
                LOG.info("reconcile: %s LOST (pgid %s gone)", job_id, job["pgid"])
                self._finalize(job_id, "LOST")
        await self._try_start_next()

    async def _probe_adopted(self, job_id: str) -> None:
        while True:
            await asyncio.sleep(1.0)
            job = self.store.get_job(job_id)
            if not job or job["status"] in TERMINAL_STATES:
                return
            if not job["pgid"]:
                self._finalize(job_id, "LOST")
                return
            try:
                os.killpg(job["pgid"], 0)
            except OSError:
                if job["cancel_requested"]:
                    self._finalize(job_id, "CANCELLED")
                else:
                    self._finalize(job_id, "LOST")
                return


def shlex_join(parts: list[str]) -> str:
    return " ".join(parts)


# =========================================================================== HTTP layer

class Request:
    __slots__ = ("method", "path", "query", "headers", "body", "keep_alive",
                 "content_length")

    def __init__(self, method, path, query, headers, body, keep_alive,
                 content_length=None):
        self.method, self.path, self.query = method, path, query
        self.headers, self.body, self.keep_alive = headers, body, keep_alive
        self.content_length = content_length


class RawStream:
    """Marker: handler wants the raw writer (binary download, keep-alive ok)."""

    def __init__(self, fn):
        self.fn = fn  # async fn(writer, keep_alive) -> None


class UploadStream:
    """Marker: upload — body is still on the wire; fn streams it to disk."""

    def __init__(self, fn):
        self.fn = fn  # async fn(reader, writer, keep_alive) -> None


class JsonResponse:
    def __init__(self, obj, status=200, extra_headers=None):
        self.obj, self.status, self.extra_headers = obj, status, extra_headers or []


class SseStream:
    """Marker: handler wants the raw writer for an SSE response (Connection: close)."""

    def __init__(self, fn):
        self.fn = fn  # async fn(writer) -> None


async def sse_frame(writer, event: str, eid, data: dict) -> None:
    payload = json.dumps(data, separators=(",", ":"))
    writer.write(f"event: {event}\nid: {eid}\ndata: {payload}\n\n".encode("utf-8"))
    await writer.drain()


# --------------------------------------------------------------------------- service registry (protocol 1.1)

SERVICE_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")


class ServiceRegistry:
    """Named long-running services (protocol 1.1, PROTOCOL §14).

    A service is a PERSISTENT DEFINITION (tool + fixed args + autostart
    flag, ~/.trmx/services/<id>.json) bound at runtime to the job it last
    started. Lifecycle = job lifecycle: start submits a normal tool job,
    stop cancels it (idempotent, §3.5), restart waits for terminal then
    resubmits, status derives from the bound job. Tool-based definitions
    reuse the whole §7.2 synthesis/validation pipeline — services are NOT
    a second execution path (ADR-015).
    """

    def __init__(self, home: Path, registry: ToolRegistry, manager: JobManager,
                 store: Store, bus: EventBus):
        self.home = home
        self.registry = registry
        self.manager = manager
        self.store = store
        self.bus = bus
        self.defs: dict[str, dict] = {}
        self.bindings: dict[str, str] = {}      # service id -> last started job id
        self.errors: list[str] = []
        self._load()

    def _load(self) -> None:
        self.defs, self.errors = {}, []
        sdir = self.home / "services"
        sdir.mkdir(parents=True, exist_ok=True)
        for p in sorted(sdir.glob("*.json")):
            try:
                d = json.loads(p.read_text(encoding="utf-8"))
                self._validate_def(d)
            except Exception as e:  # noqa: BLE001
                self.errors.append(f"{p.name}: {e}")
                continue
            self.defs[d["id"]] = d

    def _validate_def(self, d: dict) -> None:
        if not isinstance(d, dict):
            raise ValueError("definition must be a JSON object")
        sid = d.get("id")
        if not isinstance(sid, str) or not SERVICE_ID_RE.fullmatch(sid) \
                or sid.startswith("."):
            raise ValueError("id must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        if not isinstance(d.get("name"), str) or not (1 <= len(d["name"]) <= 80):
            raise ValueError("name must be 1..80 chars")
        tool = d.get("tool")
        if not isinstance(tool, str) or not tool:
            raise ValueError("tool is required")
        schema = self.registry.get(tool)
        if schema is None:
            raise BridgeError(400, "TOOL_UNKNOWN",
                              f"no tool schema with id '{tool}'", field="tool")
        args = d.get("args", {})
        if not isinstance(args, dict):
            raise ValueError("args must be an object")
        # dry-run the §7.2 synthesis: catches missing required args,
        # bad types, enum/path violations — the same ARG_INVALID errors
        self.registry.synth_argv(schema, args)
        if not isinstance(d.get("autostart", False), bool):
            raise ValueError("autostart must be a boolean")

    def _write_def(self, d: dict) -> None:
        path = self.home / "services" / f"{d['id']}.json"
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(d, indent=2, ensure_ascii=False) + "\n",
                       encoding="utf-8")
        os.replace(tmp, path)

    # -- queries ----------------------------------------------------------

    def status(self, sid: str) -> dict | None:
        d = self.defs.get(sid)
        if d is None:
            return None
        job_id = self.bindings.get(sid)
        job = self.store.get_job(job_id) if job_id else None
        state = "running" if (job and job["status"] in ACTIVE_STATES) else "stopped"
        return {
            "id": d["id"], "name": d["name"], "tool": d["tool"],
            "args": d.get("args", {}),
            "autostart": bool(d.get("autostart", False)),
            "created_at": d.get("created_at"),
            "state": state,
            "job_id": job_id if state == "running" else None,
            "last_job_id": job_id,
            "last_status": job["status"] if job else None,
            "last_exit_code": job["exit_code"] if job else None,
        }

    def list_status(self) -> list[dict]:
        return [self.status(sid) for sid in sorted(self.defs)]

    # -- mutations ----------------------------------------------------------

    def create(self, body: dict) -> dict:
        d = dict(body)
        try:
            self._validate_def(d)
        except ValueError as e:
            raise BridgeError(400, "VALIDATION_FAILED", str(e),
                              field="definition") from e
        sid = d["id"]
        if sid in self.defs and self.status(sid)["state"] == "running":
            raise BridgeError(409, "SERVICE_RUNNING",
                              f"service '{sid}' is running — stop it before replacing it")
        clean = {"id": sid, "name": d["name"], "tool": d["tool"],
                 "args": d.get("args", {}), "autostart": bool(d.get("autostart", False)),
                 "created_at": now_iso()}
        self._write_def(clean)
        self.defs[sid] = clean          # binding (last job) survives a replace
        self.store.audit("service.create", f"{sid} (tool {clean['tool']})")
        st = self.status(sid)
        self.bus.publish("service.updated", st)
        return st

    def delete(self, sid: str) -> dict:
        if sid not in self.defs:
            raise BridgeError(404, "SERVICE_NOT_FOUND", f"no service with id '{sid}'")
        if self.status(sid)["state"] == "running":
            raise BridgeError(409, "SERVICE_RUNNING",
                              f"service '{sid}' is running — stop it before deleting it")
        (self.home / "services" / f"{sid}.json").unlink(missing_ok=True)
        del self.defs[sid]
        self.bindings.pop(sid, None)
        self.store.audit("service.delete", sid)
        self.bus.publish("service.updated", {"id": sid, "deleted": True})
        return {"ok": True, "id": sid}

    async def start(self, sid: str) -> dict:
        d = self.defs.get(sid)
        if d is None:
            raise BridgeError(404, "SERVICE_NOT_FOUND", f"no service with id '{sid}'")
        if self.status(sid)["state"] == "running":
            raise BridgeError(409, "SERVICE_RUNNING",
                              f"service '{sid}' is already running "
                              f"(job {self.bindings.get(sid)})")
        job, _ = await self.manager.submit({"name": f"service: {d['name']}",
                                            "type": "tool", "tool": d["tool"],
                                            "args": d.get("args", {}), "cwd": "~"})
        self.bindings[sid] = job["job_id"]
        self.store.audit("service.start", f"{sid} -> {job['job_id']}")
        st = self.status(sid)
        self.bus.publish("service.updated", st)
        return st

    async def stop(self, sid: str) -> dict:
        if sid not in self.defs:
            raise BridgeError(404, "SERVICE_NOT_FOUND", f"no service with id '{sid}'")
        job_id = self.bindings.get(sid)
        if job_id:
            # cancel is idempotent on terminal jobs (§3.5) — stopping a
            # stopped service stays 200, never a client-side race error
            await self.manager.cancel(job_id, None, False)
        self.store.audit("service.stop", f"{sid} (job {job_id or 'none'})")
        st = self.status(sid)
        self.bus.publish("service.updated", st)
        return st

    async def restart(self, sid: str) -> dict:
        if sid not in self.defs:
            raise BridgeError(404, "SERVICE_NOT_FOUND", f"no service with id '{sid}'")
        job_id = self.bindings.get(sid)
        if job_id:
            await self.manager.cancel(job_id, None, False)
            deadline = time.time() + 30
            while time.time() < deadline:
                job = self.store.get_job(job_id)
                if not job or job["status"] in TERMINAL_STATES:
                    break
                await asyncio.sleep(0.1)
            else:
                raise BridgeError(500, "INTERNAL",
                                  f"service '{sid}' did not stop within 30s")
        self.store.audit("service.restart", sid)
        return await self.start(sid)

    def set_autostart(self, sid: str, enabled: bool) -> dict:
        d = self.defs.get(sid)
        if d is None:
            raise BridgeError(404, "SERVICE_NOT_FOUND", f"no service with id '{sid}'")
        d["autostart"] = enabled
        self._write_def(d)
        self.store.audit("service.autostart", f"{sid} = {enabled}")
        st = self.status(sid)
        self.bus.publish("service.updated", st)
        return st

    async def autostart_boot(self) -> None:
        """Launch autostart services after boot reconciliation. Best-effort
        per service: a failure is audited and skipped, never fatal."""
        for sid, d in sorted(self.defs.items()):
            if not d.get("autostart"):
                continue
            try:
                if self.status(sid)["state"] == "running":
                    continue
                await self.start(sid)
            except BridgeError as e:
                self.store.audit("service.autostart", f"{sid} FAILED: {e.message}")


# --------------------------------------------------------------------------- AI backend config (protocol 1.1)

# Mirrors trmx-ai's defaults/template (docs/AI-CONFIG.md). The bridge never
# interprets the config — it only reads/validates/writes the file for the
# app; trmx-ai remains the sole consumer.
AI_PROVIDERS = ("openai", "groq", "gemini", "openai_compatible")
AI_DEFAULT_CONFIG = {
    "mode": "cli",
    "cli": {"command": ["ollama", "run", "llama3.2"], "timeout_s": 180},
    "http_api": {"provider": "groq", "endpoint": "", "api_key": "",
                 "api_key_env": "GROQ_API_KEY", "model": "llama-3.3-70b-versatile",
                 "timeout_s": 120},
}


class BridgeApp:
    """HTTP server + routing + auth for TRMX-P/1 (PoC subset)."""

    def __init__(self, home: Path, port: int, config: Config):
        self.home, self.port, self.config = home, port, config
        self.store = Store(home / "jobs.db")
        self.policy = Policy(home)
        self.registry = ToolRegistry(home, self.policy)
        self.bus = EventBus()
        self.manager = JobManager(home, self.store, config, self.policy, self.bus,
                                  self.registry)
        self.services = ServiceRegistry(home, self.registry, self.manager,
                                        self.store, self.bus)
        self.started = time.time()
        self._upload_seq = itertools.count(1)
        self.routes = [
            ("GET", re.compile(r"^/v1/system/info$"), self.h_system_info),
            ("GET", re.compile(r"^/v1/system/policy$"), self.h_system_policy),
            ("POST", re.compile(r"^/v1/jobs$"), self.h_jobs_post),
            ("GET", re.compile(r"^/v1/jobs$"), self.h_jobs_list),
            ("GET", re.compile(r"^/v1/jobs/([A-Za-z0-9-]+)$"), self.h_job_get),
            ("POST", re.compile(r"^/v1/jobs/([A-Za-z0-9-]+)/cancel$"), self.h_job_cancel),
            ("GET", re.compile(r"^/v1/jobs/([A-Za-z0-9-]+)/output$"), self.h_job_output),
            ("GET", re.compile(r"^/v1/files$"), self.h_files_list),
            ("POST", re.compile(r"^/v1/files$"), self.h_files_ops),
            ("GET", re.compile(r"^/v1/files/content$"), self.h_file_download),
            ("PUT", re.compile(r"^/v1/files/content$"), self.h_file_upload),
            ("GET", re.compile(r"^/v1/tools$"), self.h_tools_list),
            ("GET", re.compile(r"^/v1/tools/([A-Za-z0-9._-]+)$"), self.h_tool_get),
            ("POST", re.compile(r"^/v1/tools/refresh$"), self.h_tools_refresh),
            ("GET", re.compile(r"^/v1/events$"), self.h_events),
            ("POST", re.compile(r"^/v1/system/bridge$"), self.h_bridge_ctl),
            # services (protocol 1.1, PROTOCOL §14)
            ("GET", re.compile(r"^/v1/services$"), self.h_services_list),
            ("POST", re.compile(r"^/v1/services$"), self.h_services_create),
            ("GET", re.compile(r"^/v1/services/([A-Za-z0-9._-]+)$"), self.h_service_get),
            ("DELETE", re.compile(r"^/v1/services/([A-Za-z0-9._-]+)$"), self.h_service_delete),
            ("POST", re.compile(r"^/v1/services/([A-Za-z0-9._-]+)/start$"), self.h_service_start),
            ("POST", re.compile(r"^/v1/services/([A-Za-z0-9._-]+)/stop$"), self.h_service_stop),
            ("POST", re.compile(r"^/v1/services/([A-Za-z0-9._-]+)/restart$"), self.h_service_restart),
            ("POST", re.compile(r"^/v1/services/([A-Za-z0-9._-]+)/autostart$"), self.h_service_autostart),
            # AI backend config (protocol 1.1; the file is trmx-ai's, see docs/AI-CONFIG.md)
            ("GET", re.compile(r"^/v1/ai/config$"), self.h_ai_config_get),
            ("POST", re.compile(r"^/v1/ai/config$"), self.h_ai_config_post),
        ]

    # -- connection handling ----------------------------------------------

    async def handle_conn(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        failures = 0
        try:
            while True:
                try:
                    req = await self.read_request(reader, writer)
                except BridgeError as e:
                    await self.send_error(writer, e, keep_alive=False)
                    break
                if req is None:
                    break
                try:
                    result = await self.dispatch(req)
                except BridgeError as e:
                    if e.code in ("AUTH_REQUIRED", "AUTH_INVALID"):
                        failures += 1
                        self.store.audit("auth.fail", f"{e.code} path={req.path} n={failures}")
                        if failures >= 5:  # per-connection backoff (PROTOCOL §1.3 subset)
                            await asyncio.sleep(min(2 ** (failures - 5) * 2, 60))
                    else:
                        failures = 0
                    # an upload body may still be unread on the wire — the
                    # connection cannot be reused after an error
                    keep = (req.keep_alive and req.content_length is None
                            and e.status < 500 and e.status not in (400, 413))
                    await self.send_error(writer, e, keep_alive=keep)
                    if not keep:
                        break
                    continue
                failures = 0
                if isinstance(result, SseStream):
                    try:
                        await self.send_sse_head(writer)
                        writer.write(b"retry: 3000\n\n")
                        await writer.drain()
                        await result.fn(writer)
                    except (ConnectionResetError, BrokenPipeError, OSError):
                        pass
                    break  # stream consumed the connection
                if isinstance(result, RawStream):
                    try:
                        keep_open = await result.fn(writer, req.keep_alive)
                    except (ConnectionResetError, BrokenPipeError, OSError):
                        break
                    if not (keep_open and req.keep_alive):
                        break
                    continue
                if isinstance(result, UploadStream):
                    try:
                        keep_open = await result.fn(reader, writer, req.keep_alive)
                    except (ConnectionResetError, BrokenPipeError, OSError):
                        break
                    if not (keep_open and req.keep_alive):
                        break
                    continue
                await self.send_json(writer, result, keep_alive=req.keep_alive)
                if not req.keep_alive:
                    break
        except (ConnectionResetError, BrokenPipeError, OSError, asyncio.IncompleteReadError):
            pass
        finally:
            with_stop(writer)

    async def read_request(self, reader, writer) -> Request | None:
        try:
            head = await reader.readuntil(b"\r\n\r\n")
        except asyncio.IncompleteReadError as e:
            if not e.partial:
                return None
            raise BridgeError(400, "VALIDATION_FAILED", "truncated request head")
        except asyncio.LimitOverrunError:
            raise BridgeError(400, "VALIDATION_FAILED", "headers too large")
        text = head.decode("iso-8859-1")
        lines = text.split("\r\n")
        parts = lines[0].split(" ")
        if len(parts) != 3 or not parts[2].startswith("HTTP/1."):
            raise BridgeError(400, "VALIDATION_FAILED", "malformed request line")
        method, target = parts[0], parts[1]
        headers: dict[str, str] = {}
        for ln in lines[1:]:
            if not ln:
                continue
            k, _, v = ln.partition(":")
            headers[k.strip().lower()] = v.strip()
        te = headers.get("transfer-encoding", "")
        if te and te.lower() not in ("identity",):
            raise BridgeError(400, "VALIDATION_FAILED",
                              "transfer-encoding not supported; send Content-Length")
        keep_alive = headers.get("connection", "").lower() != "close"
        u = urlsplit(target)
        query = {k: v[0] for k, v in parse_qs(u.query, keep_blank_values=True).items()}
        # PROTOCOL §6.5: uploads stream to disk — never buffer the body.
        if method.upper() == "PUT" and unquote(u.path) == "/v1/files/content":
            if "content-length" not in headers:
                raise BridgeError(411, "LENGTH_REQUIRED",
                                  "upload requires Content-Length")
            try:
                n = int(headers["content-length"])
            except ValueError:
                raise BridgeError(400, "VALIDATION_FAILED", "bad Content-Length")
            if n < 0 or n > MAX_UPLOAD_BODY:
                raise BridgeError(413, "PAYLOAD_TOO_LARGE",
                                  f"upload exceeds {MAX_UPLOAD_BODY} bytes")
            # 100-continue is sent by the upload handler AFTER path validation
            return Request("PUT", unquote(u.path), query, headers, b"",
                           keep_alive, content_length=n)
        body = b""
        if "content-length" in headers:
            try:
                n = int(headers["content-length"])
            except ValueError:
                raise BridgeError(400, "VALIDATION_FAILED", "bad Content-Length")
            if n > MAX_JSON_BODY:
                raise BridgeError(413, "PAYLOAD_TOO_LARGE",
                                  f"body exceeds {MAX_JSON_BODY} bytes")
            if headers.get("expect", "").lower() == "100-continue":
                writer.write(b"HTTP/1.1 100 Continue\r\n\r\n")
                await writer.drain()
            if n:
                try:
                    body = await reader.readexactly(n)
                except asyncio.IncompleteReadError:
                    raise BridgeError(400, "VALIDATION_FAILED", "truncated body")
        return Request(method.upper(), unquote(u.path), query, headers, body, keep_alive)

    async def dispatch(self, req: Request):
        # PROTOCOL §1.1: never a browser client
        if "origin" in req.headers:
            raise BridgeError(403, "ORIGIN_DENIED", "requests with an Origin header are rejected")
        proto = req.headers.get("x-trmx-protocol")
        if proto is None:
            raise BridgeError(400, "PROTOCOL_MISMATCH",
                              "missing X-TRMX-Protocol header",
                              details={"supported": PROTOCOL_VERSIONS})
        try:
            major = int(proto.split(".")[0])
        except ValueError:
            major = -1
        if major != 1:
            raise BridgeError(400, "PROTOCOL_MISMATCH",
                              f"unsupported protocol major '{proto}'",
                              details={"supported": PROTOCOL_VERSIONS})
        m = re.match(r"^Bearer\s+(.+)$", req.headers.get("authorization", ""))
        token = m.group(1) if m else None
        if not token:
            raise BridgeError(401, "AUTH_REQUIRED", "missing bearer token")
        if not hmac.compare_digest(self.config.token or "", token):
            raise BridgeError(401, "AUTH_INVALID", "invalid token")
        path_matched = False
        for method, rx, handler in self.routes:
            m = rx.match(req.path)
            if m:
                path_matched = True
                if method == req.method:
                    return await handler(req, m)
        if path_matched:
            raise BridgeError(405, "METHOD_NOT_ALLOWED",
                              f"{req.method} not allowed on {req.path}")
        raise BridgeError(404, "NOT_FOUND", f"no such route: {req.path}")

    # -- response helpers --------------------------------------------------

    async def send_json(self, writer, resp: JsonResponse, keep_alive: bool) -> None:
        body = json.dumps(resp.obj, separators=(",", ":")).encode("utf-8")
        reason = HTTP_REASONS.get(resp.status, "OK")
        head = (f"HTTP/1.1 {resp.status} {reason}\r\n"
                f"Content-Type: application/json\r\n"
                f"Content-Length: {len(body)}\r\n"
                f"Connection: {'keep-alive' if keep_alive else 'close'}\r\n")
        for k, v in resp.extra_headers:
            head += f"{k}: {v}\r\n"
        head += "\r\n"
        writer.write(head.encode("iso-8859-1") + body)
        await writer.drain()

    async def send_error(self, writer, e: BridgeError, keep_alive: bool) -> None:
        await self.send_json(writer, JsonResponse(e.body(), e.status), keep_alive)

    async def send_sse_head(self, writer) -> None:
        writer.write(b"HTTP/1.1 200 OK\r\n"
                     b"Content-Type: text/event-stream\r\n"
                     b"Cache-Control: no-cache\r\n"
                     b"Connection: close\r\n\r\n")
        await writer.drain()

    def parse_json_body(self, req: Request) -> dict:
        ctype = req.headers.get("content-type", "")
        if ctype and ctype.split(";")[0].strip().lower() not in ("application/json", ""):
            raise BridgeError(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json")
        if not req.body:
            return {}
        try:
            obj = json.loads(req.body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            raise BridgeError(400, "VALIDATION_FAILED", "malformed JSON body")
        if not isinstance(obj, dict):
            raise BridgeError(400, "VALIDATION_FAILED", "body must be a JSON object")
        return obj

    # -- handlers ------------------------------------------------------------

    async def h_system_info(self, req, m):
        try:
            loadavg = [round(x, 2) for x in os.getloadavg()]
        except Exception:  # noqa: BLE001
            loadavg = [0.0, 0.0, 0.0]
        mem_total = mem_free = 0
        try:
            info = {}
            with open("/proc/meminfo") as f:
                for line in f:
                    k, _, v = line.partition(":")
                    info[k] = int(v.strip().split()[0]) // 1024
            mem_total, mem_free = info.get("MemTotal", 0), info.get("MemAvailable", 0)
        except Exception:  # noqa: BLE001
            pass
        du = shutil.disk_usage(self.home)
        return JsonResponse({
            "bridge_version": VERSION,
            "protocol_versions": PROTOCOL_VERSIONS,
            "uptime_s": int(time.time() - self.started),
            "now": now_iso(),
            "load": {"jobs_running": len(self.manager.running),
                     "jobs_queued": len(self.manager.queue), "loadavg": loadavg},
            "memory": {"total_mb": mem_total, "free_mb": mem_free},
            "storage": {"home_free_mb": du.free // (1024 * 1024),
                        "shared_available": (Path.home() / "storage").exists()},
            "termux": {"home": str(Path.home()),
                       "prefix": (str(self.policy.prefix) if self.policy.prefix else None)},
            "caps": {"max_concurrent_jobs": int(self.config["max_concurrent_jobs"]),
                     "queue_depth": int(self.config["queue_depth"]),
                     "log_ring_mb": round(int(self.config["log_ring_bytes"]) / (1024 * 1024), 2)},
            "features": {"termux_api": shutil.which("termux-notification") is not None,
                         "runit": shutil.which("sv") is not None,
                         "scheduler": False,
                         "tool_schema_errors": list(self.registry.schema_errors),
                         "service_registry": True,          # protocol 1.1 (§14)
                         "service_errors": list(self.services.errors)},
            "tools_detected": len(self.registry.schemas)
        })

    async def h_system_policy(self, req, m):
        return JsonResponse({
            "roots": ["~", "~/storage"],
            "deny_write": (["$PREFIX"] if self.policy.prefix else []),
            "log_ring_mb": round(int(self.config["log_ring_bytes"]) / (1024 * 1024), 2),
            "max_concurrent_jobs": int(self.config["max_concurrent_jobs"]),
            "queue_depth": int(self.config["queue_depth"]),
            "cancel_grace_ms": int(self.config["cancel_grace_ms"]),
        })

    # -- tools (PROTOCOL §7) ------------------------------------------------

    async def h_tools_list(self, req, m):
        tools = await self.registry.ensure_probed()
        return JsonResponse({"tools": tools})

    async def h_tool_get(self, req, m):
        tid = m.group(1)
        if self.registry.get(tid) is None:
            raise BridgeError(404, "NOT_FOUND", f"no tool schema with id '{tid}'")
        await self.registry._probe_one(tid)
        return JsonResponse(self.registry._status(tid))

    async def h_tools_refresh(self, req, m):
        tools = await self.registry.refresh()
        self.store.audit("tools.refresh", f"{len(tools)} tools probed")
        return JsonResponse({"tools": tools})

    async def h_jobs_post(self, req, m):
        payload = self.parse_json_body(req)
        job, replayed = await self.manager.submit(payload)
        headers = [("Location", f"/v1/jobs/{job['job_id']}")]
        if replayed:
            headers.append(("Idempotent-Replay", "true"))
        return JsonResponse({"job_id": job["job_id"], "status": job["status"]},
                            201, headers)

    async def h_jobs_list(self, req, m):
        status = req.query.get("status")
        if status is not None and status not in (ACTIVE_STATES | TERMINAL_STATES):
            raise BridgeError(400, "VALIDATION_FAILED", f"unknown status filter: {status}",
                              field="status")
        try:
            limit = int(req.query.get("limit", "50"))
        except ValueError:
            raise BridgeError(400, "VALIDATION_FAILED", "limit must be an int", field="limit")
        if not (1 <= limit <= 200):
            raise BridgeError(400, "VALIDATION_FAILED", "limit must be 1..200", field="limit")
        before_n = None
        before = req.query.get("before")
        if before is not None:
            row = self.store.db.execute("SELECT n FROM jobs WHERE job_id=?", (before,)).fetchone()
            if not row:
                raise BridgeError(400, "VALIDATION_FAILED", "unknown cursor", field="before")
            before_n = row["n"]
        jobs, next_cursor = self.store.list_jobs(status, before_n, limit)
        for j in jobs:
            self.manager._overlay_runtime(j)
        return JsonResponse({"jobs": jobs, "next_cursor": next_cursor})

    async def h_job_get(self, req, m):
        job = self.manager.get_job(m.group(1))
        if not job:
            raise BridgeError(404, "JOB_NOT_FOUND", f"no such job: {m.group(1)}")
        return JsonResponse(job)

    async def h_job_cancel(self, req, m):
        body = self.parse_json_body(req)
        grace_ms = body.get("grace_ms")
        if grace_ms is not None and (not isinstance(grace_ms, int) or not (0 <= grace_ms <= 60000)):
            raise BridgeError(400, "VALIDATION_FAILED", "grace_ms must be int 0..60000",
                              field="grace_ms")
        force = body.get("force", False)
        if not isinstance(force, bool):
            raise BridgeError(400, "VALIDATION_FAILED", "force must be a boolean", field="force")
        job = await self.manager.cancel(m.group(1), grace_ms, force)
        return JsonResponse(job)

    # ---- files (PROTOCOL §6) ----------------------------------------------

    FILES_OPS = {"mkdir", "touch", "rename", "move", "copy", "delete"}

    def _file_entry(self, p: Path) -> dict:
        st = p.lstat()
        mode = stat_mod.filemode(st.st_mode)
        if stat_mod.S_ISLNK(st.st_mode):
            ftype = "symlink"
            try:
                target = self.policy.display_path(Path(os.path.realpath(p)))
            except OSError:
                target = os.readlink(p)
        elif stat_mod.S_ISDIR(st.st_mode):
            ftype, target = "dir", None
        elif stat_mod.S_ISREG(st.st_mode):
            ftype, target = "file", None
        else:
            ftype, target = "other", None
        mtime = dt.datetime.fromtimestamp(st.st_mtime, dt.timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%SZ")
        return {"name": p.name, "type": ftype, "size": st.st_size,
                "mtime": mtime, "mode": mode, "target": target}

    async def h_files_list(self, req, m):
        raw = req.query.get("path", "~")
        try:
            offset = int(req.query.get("offset", "0"))
        except ValueError:
            raise BridgeError(400, "VALIDATION_FAILED", "offset must be an int", field="offset")
        if offset < 0:
            raise BridgeError(400, "VALIDATION_FAILED", "offset must be >= 0", field="offset")
        real = self.policy.resolve_in_roots(raw)
        if not real.exists():
            raise BridgeError(404, "PATH_NOT_FOUND", f"no such path: {raw}")
        display = self.policy.display_path(real)
        if req.query.get("stat", "0") == "1":
            return JsonResponse({"path": display, "entry": self._file_entry(real)})
        if not real.is_dir():
            raise BridgeError(400, "NOT_A_DIRECTORY",
                              f"not a directory (use stat=1 for the entry itself): {raw}")
        try:
            entries = sorted(real.iterdir(), key=lambda e: e.name)
        except OSError as e:
            raise BridgeError(403, "PATH_DENIED", f"cannot list directory: {e}")
        page = entries[offset:offset + FILES_PAGE_SIZE]
        out = {"path": display, "entries": [self._file_entry(e) for e in page]}
        if offset + FILES_PAGE_SIZE < len(entries):
            out["next_offset"] = offset + FILES_PAGE_SIZE
        return JsonResponse(out)

    async def h_files_ops(self, req, m):
        body = self.parse_json_body(req)
        op = body.get("op")
        if op not in self.FILES_OPS:
            raise BridgeError(400, "VALIDATION_FAILED",
                              f"op must be one of {sorted(self.FILES_OPS)}", field="op")
        raw = body.get("path")
        real = self.policy.resolve_in_roots(raw, for_write=True)

        if op == "mkdir":
            recursive = bool(body.get("recursive", False))
            if real.exists():
                raise BridgeError(409, "PATH_EXISTS", f"already exists: {raw}")
            if not real.parent.is_dir():
                raise BridgeError(404, "PATH_NOT_FOUND", f"parent missing: {raw}")
            if recursive:
                os.makedirs(real)
            else:
                os.mkdir(real)
            return JsonResponse({"ok": True})

        if op == "touch":
            if real.exists():
                os.utime(real, None)
            else:
                if not real.parent.is_dir():
                    raise BridgeError(404, "PATH_NOT_FOUND", f"parent missing: {raw}")
                with open(real, "xb"):
                    pass
            return JsonResponse({"ok": True})

        if op == "rename":
            new_name = body.get("new_name")
            if (not isinstance(new_name, str) or not new_name or "/" in new_name
                    or new_name in (".", "..")):
                raise BridgeError(400, "VALIDATION_FAILED",
                                  "new_name must be a bare name (no slashes)", field="new_name")
            if not os.path.lexists(real):
                raise BridgeError(404, "PATH_NOT_FOUND", f"no such path: {raw}")
            dest = real.parent / new_name
            if os.path.lexists(dest):
                raise BridgeError(409, "PATH_EXISTS", f"destination exists: {new_name}")
            os.rename(real, dest)
            return JsonResponse({"ok": True})

        if op in ("move", "copy"):
            dest_dir_raw = body.get("dest_dir")
            dest_dir = self.policy.resolve_in_roots(dest_dir_raw, for_write=True)
            if not dest_dir.is_dir():
                raise BridgeError(404, "PATH_NOT_FOUND", f"dest_dir missing: {dest_dir_raw}",
                                  field="dest_dir")
            if not os.path.lexists(real):
                raise BridgeError(404, "PATH_NOT_FOUND", f"no such path: {raw}")
            dest = dest_dir / real.name
            if os.path.lexists(dest):
                raise BridgeError(409, "PATH_EXISTS", f"destination exists: {dest.name}")
            if op == "move":
                shutil.move(str(real), str(dest))
                return JsonResponse({"ok": True, "entries_moved": 1})
            if real.is_dir():
                shutil.copytree(real, dest)
                moved = len(os.listdir(dest))
            else:
                shutil.copy2(real, dest)
                moved = 1
            return JsonResponse({"ok": True, "entries_moved": moved})

        if op == "delete":
            if not os.path.lexists(real):
                raise BridgeError(404, "PATH_NOT_FOUND", f"no such path: {raw}")
            if real.is_dir() and not real.is_symlink():
                if body.get("recursive"):
                    if not body.get("confirm"):
                        raise BridgeError(400, "CONFIRM_REQUIRED",
                                          "recursive delete of a directory requires confirm:true")
                    shutil.rmtree(real)
                else:
                    if os.listdir(real):
                        raise BridgeError(409, "PATH_NOT_EMPTY",   # §10 registry status
                                          "directory not empty (recursive:true + confirm:true)")
                    os.rmdir(real)
            else:
                os.unlink(real)
            return JsonResponse({"ok": True})

        raise BridgeError(500, "INTERNAL", "unreachable")

    async def h_file_download(self, req, m):
        raw = req.query.get("path")
        if not raw:
            raise BridgeError(400, "VALIDATION_FAILED", "path query parameter required",
                              field="path")
        real = self.policy.resolve_in_roots(raw)
        if not real.exists():
            raise BridgeError(404, "PATH_NOT_FOUND", f"no such path: {raw}")
        if real.is_dir():
            raise BridgeError(400, "NOT_A_FILE", "cannot download a directory")
        size = real.stat().st_size
        status, start, length, content_range = 200, 0, size, None
        range_header = req.headers.get("range")
        if range_header:
            rm = re.match(r"^bytes=(\d*)-(\d*)$", range_header.strip())
            ok = bool(rm) and (rm.group(1) or rm.group(2))
            if ok:
                if rm.group(1):
                    a = int(rm.group(1))
                    b = int(rm.group(2)) if rm.group(2) else size - 1
                    ok = a < size and a <= b
                    if ok:
                        b = min(b, size - 1)
                        start, length = a, b - a + 1
                else:
                    n = int(rm.group(2))
                    if n == 0 or size == 0:
                        ok = False
                    else:
                        start = max(0, size - n)
                        length = size - start
            if not ok:
                async def bad_range(writer, keep_alive):
                    body = json.dumps({"error": {"code": "RANGE_NOT_SATISFIABLE",
                                                 "message": f"bad Range: {range_header}"}},
                                      separators=(",", ":")).encode()
                    head = ("HTTP/1.1 416 Range Not Satisfiable\r\n"
                            "Content-Type: application/json\r\n"
                            f"Content-Range: bytes */{size}\r\n"
                            f"Content-Length: {len(body)}\r\n"
                            f"Connection: {'keep-alive' if keep_alive else 'close'}\r\n\r\n")
                    writer.write(head.encode("iso-8859-1") + body)
                    await writer.drain()
                    return True
                return RawStream(bad_range)
            status = 206
            content_range = f"bytes {start}-{start + length - 1}/{size}"

        async def run(writer, keep_alive):
            head = (f"HTTP/1.1 {status} {HTTP_REASONS.get(status, 'OK')}\r\n"
                    "Content-Type: application/octet-stream\r\n"
                    f"Content-Length: {length}\r\n"
                    "Accept-Ranges: bytes\r\n")
            if content_range:
                head += f"Content-Range: {content_range}\r\n"
            head += f"Connection: {'keep-alive' if keep_alive else 'close'}\r\n\r\n"
            writer.write(head.encode("iso-8859-1"))
            await writer.drain()
            with open(real, "rb") as f:
                f.seek(start)
                remaining = length
                while remaining > 0:
                    chunk = f.read(min(UPLOAD_CHUNK, remaining))
                    if not chunk:
                        break
                    writer.write(chunk)
                    await writer.drain()
                    remaining -= len(chunk)
            return True
        return RawStream(run)

    async def h_file_upload(self, req, m):
        raw = req.query.get("path")
        if not raw:
            raise BridgeError(400, "VALIDATION_FAILED", "path query parameter required",
                              field="path")
        overwrite = req.query.get("overwrite", "0") == "1"
        sha_want = req.headers.get("x-trmx-sha256")
        if sha_want is not None and not re.match(r"^[0-9a-f]{64}$", sha_want):
            raise BridgeError(400, "VALIDATION_FAILED",
                              "X-TRMX-Sha256 must be 64 lowercase hex chars", field="sha256")
        real = self.policy.resolve_in_roots(raw, for_write=True)
        if real.exists():
            if not overwrite:
                raise BridgeError(409, "PATH_EXISTS",
                                  f"already exists (overwrite=1 to replace): {raw}")
            if real.is_dir():
                raise BridgeError(400, "NOT_A_FILE", "cannot upload over a directory")
        if not real.parent.is_dir():
            raise BridgeError(404, "PATH_NOT_FOUND", f"parent directory missing: {raw}")
        total = req.content_length or 0
        expect_continue = req.headers.get("expect", "").lower() == "100-continue"
        app = self

        async def run(reader, writer, keep_alive):
            if expect_continue:
                writer.write(b"HTTP/1.1 100 Continue\r\n\r\n")
                await writer.drain()
            tmp = real.parent / f".trmx-upload-{os.getpid()}-{next(app._upload_seq)}"
            hasher = hashlib.sha256() if sha_want else None
            try:
                with open(tmp, "wb") as f:
                    remaining = total
                    while remaining > 0:
                        chunk = await reader.read(min(UPLOAD_CHUNK, remaining))
                        if not chunk:
                            tmp.unlink(missing_ok=True)
                            raise ConnectionError("client disconnected mid-upload")
                        f.write(chunk)
                        if hasher:
                            hasher.update(chunk)
                        remaining -= len(chunk)
                    f.flush()
                    os.fsync(f.fileno())
                if hasher and hasher.hexdigest() != sha_want:
                    tmp.unlink(missing_ok=True)
                    await app.send_json(
                        writer,
                        JsonResponse({"error": {"code": "CHECKSUM_MISMATCH",
                                               "message": "X-TRMX-Sha256 does not match body"}},
                                     422),
                        keep_alive=False)
                    return False
                os.replace(tmp, real)
                app.store.audit("file.upload", f"{real.name} {total}B")
                await app.send_json(writer, JsonResponse({"ok": True}), keep_alive=keep_alive)
                return True
            except Exception:
                tmp.unlink(missing_ok=True)
                raise
        return UploadStream(run)

    async def h_job_output(self, req, m):
        job_id = m.group(1)
        job = self.manager.get_job(job_id)
        if not job:
            raise BridgeError(404, "JOB_NOT_FOUND", f"no such job: {job_id}")
        params = req.query
        stream_filter = params.get("stream", "both")
        if stream_filter not in ("stdout", "stderr", "both"):
            raise BridgeError(400, "VALIDATION_FAILED",
                              "stream must be stdout|stderr|both", field="stream")
        try:
            from_seq = int(params.get("from_seq", "0"))
        except ValueError:
            raise BridgeError(400, "VALIDATION_FAILED", "from_seq must be an int", field="from_seq")
        if from_seq < 0:
            raise BridgeError(400, "VALIDATION_FAILED", "from_seq must be >= 0", field="from_seq")
        follow = params.get("follow", "1") != "0"

        manager = self.manager
        rt = manager.runtimes.get(job_id)
        log = rt.frames if rt else FrameLog(manager._log_path(job_id),
                                            int(self.config["log_ring_bytes"]))

        def keep(ev: str) -> bool:
            return stream_filter == "both" or ev in ("status", "info") or ev == stream_filter

        q: asyncio.Queue | None = None
        if follow and rt is not None and job["status"] not in TERMINAL_STATES:
            q = asyncio.Queue()
            rt.followers.add(q)

        async def run(writer):
            last_sent = from_seq - 1
            start_seq = max(1, from_seq)  # from_seq=0 means "from the beginning" (= seq 1)
            min_seq = log.min_seq()
            if start_seq < min_seq:
                # frames [start_seq, min_seq-1] were evicted — say so honestly
                await sse_frame(writer, "info", min_seq - 1,
                                {"job_id": job_id, "seq": min_seq - 1, "type": "evicted",
                                 "resume_from_seq": min_seq})
            for f in log.replay(from_seq):
                if keep(f["event"]):
                    await sse_frame(writer, f["event"], f["seq"], f["data"])
                    last_sent = max(last_sent, f["seq"])
            if q is not None:
                while True:
                    try:
                        ev, data = await asyncio.wait_for(q.get(), timeout=2.0)
                    except asyncio.TimeoutError:
                        cur = manager.get_job(job_id)
                        if cur and cur["status"] in TERMINAL_STATES:
                            for f in log.replay(last_sent + 1):  # heal any final race
                                if keep(f["event"]):
                                    await sse_frame(writer, f["event"], f["seq"], f["data"])
                                    last_sent = max(last_sent, f["seq"])
                            break
                        # probe the client: a dead peer must not hold a follower slot
                        writer.write(b": ping\n\n")
                        await writer.drain()
                        continue
                    if data.get("seq", 0) <= last_sent:
                        continue
                    if not keep(ev):
                        continue
                    await sse_frame(writer, ev, data["seq"], data)
                    last_sent = data["seq"]
                    if ev == "status" and data.get("status") in TERMINAL_STATES:
                        break
            else:
                for f in log.replay(last_sent + 1):
                    if keep(f["event"]):
                        await sse_frame(writer, f["event"], f["seq"], f["data"])
                        last_sent = max(last_sent, f["seq"])

        async def cleanup():
            if q is not None and rt is not None:
                rt.followers.discard(q)

        async def fn(writer):
            try:
                await run(writer)
            finally:
                await cleanup()

        return SseStream(fn)

    async def h_events(self, req, m):
        bus = self.bus

        async def fn(writer):
            q = bus.subscribe()
            lock = asyncio.Lock()
            main_task = asyncio.current_task()

            async def heartbeat():
                try:
                    while True:
                        await asyncio.sleep(15)
                        async with lock:
                            writer.write(b": ping\n\n")
                            await writer.drain()
                except (ConnectionResetError, BrokenPipeError, OSError):
                    if main_task:
                        main_task.cancel()  # peer went away; stop waiting for events

            hb = asyncio.get_running_loop().create_task(heartbeat())
            try:
                while True:
                    eid, ev, data = await q.get()
                    async with lock:
                        await sse_frame(writer, ev, eid, data)
            except (ConnectionResetError, BrokenPipeError, OSError, asyncio.CancelledError):
                pass
            finally:
                hb.cancel()
                bus.unsubscribe(q)

        return SseStream(fn)

    # -- services (protocol 1.1, PROTOCOL §14) -------------------------------

    async def h_services_list(self, req, m):
        return JsonResponse({"services": self.services.list_status()})

    async def h_services_create(self, req, m):
        body = self.parse_json_body(req)
        st = self.services.create(body)
        return JsonResponse(st, 201, [("Location", f"/v1/services/{st['id']}")])

    async def h_service_get(self, req, m):
        st = self.services.status(m.group(1))
        if st is None:
            raise BridgeError(404, "SERVICE_NOT_FOUND",
                              f"no service with id '{m.group(1)}'")
        return JsonResponse(st)

    async def h_service_delete(self, req, m):
        return JsonResponse(self.services.delete(m.group(1)))

    async def h_service_start(self, req, m):
        return JsonResponse(await self.services.start(m.group(1)))

    async def h_service_stop(self, req, m):
        return JsonResponse(await self.services.stop(m.group(1)))

    async def h_service_restart(self, req, m):
        return JsonResponse(await self.services.restart(m.group(1)))

    async def h_service_autostart(self, req, m):
        body = self.parse_json_body(req)
        enabled = body.get("enabled")
        if not isinstance(enabled, bool):
            raise BridgeError(400, "VALIDATION_FAILED",
                              "body must be {\"enabled\": boolean}", field="enabled")
        return JsonResponse(self.services.set_autostart(m.group(1), enabled))

    # -- AI backend config (protocol 1.1) --------------------------------------
    # GET/POST ~/.trmx/ai.json for the app's native config UI. The raw API
    # key NEVER crosses this API: responses carry api_key_set only. POST
    # merges onto the existing file (unknown keys like _help survive),
    # validates strictly, writes atomically at 0600.

    def _ai_config_path(self) -> Path:
        return self.home / "ai.json"

    def _ai_read_raw(self) -> dict | None:
        p = self._ai_config_path()
        if not p.is_file():
            return None
        try:
            raw = json.loads(p.read_text(encoding="utf-8"))
        except Exception as e:  # noqa: BLE001
            raise BridgeError(500, "INTERNAL",
                              f"~/.trmx/ai.json is not valid JSON ({e}) — fix or "
                              "remove the file in Termux") from e
        if not isinstance(raw, dict):
            raise BridgeError(500, "INTERNAL", "~/.trmx/ai.json must be a JSON object")
        return raw

    def _ai_config_view(self, raw: dict, exists: bool) -> dict:
        """Masked, normalized view for the wire (legacy flat form folded in)."""
        mode = raw.get("mode")
        if mode not in ("cli", "http_api"):
            mode = "http_api" if ("http_api" in raw or "provider" in raw) else "cli"
        cli = raw.get("cli") if isinstance(raw.get("cli"), dict) else {}
        command = cli.get("command") or raw.get("command") \
            or AI_DEFAULT_CONFIG["cli"]["command"]
        http = raw.get("http_api") if isinstance(raw.get("http_api"), dict) else {}
        d = AI_DEFAULT_CONFIG["http_api"]
        try:
            c_timeout = int(cli.get("timeout_s", raw.get("timeout_s", 180)) or 180)
            h_timeout = int(http.get("timeout_s", d["timeout_s"]) or d["timeout_s"])
        except (TypeError, ValueError):
            c_timeout, h_timeout = 180, 120
        return {
            "exists": exists, "mode": mode,
            "cli": {"command": [str(x) for x in command], "timeout_s": c_timeout},
            "http_api": {
                "provider": http.get("provider") or d["provider"],
                "endpoint": str(http.get("endpoint", d["endpoint"]) or ""),
                "model": str(http.get("model", d["model"]) or ""),
                "api_key_set": bool(http.get("api_key")),
                "api_key_env": str(http.get("api_key_env", d["api_key_env"]) or ""),
                "timeout_s": h_timeout,
            },
        }

    def _ai_validate_final(self, merged: dict) -> None:
        mode = merged.get("mode")
        if mode is None:
            mode = "http_api" if ("http_api" in merged or "provider" in merged) else "cli"
        elif mode not in ("cli", "http_api"):
            raise BridgeError(400, "VALIDATION_FAILED",
                              "mode must be 'cli' or 'http_api'", field="mode")
        cli = merged.get("cli")
        if cli is not None:
            if not isinstance(cli, dict):
                raise BridgeError(400, "VALIDATION_FAILED", "cli must be an object", field="cli")
            cmd = cli.get("command")
            if not isinstance(cmd, list) or not cmd or \
                    not all(isinstance(x, str) and x for x in cmd):
                raise BridgeError(400, "VALIDATION_FAILED",
                                  "cli.command must be a non-empty list of strings",
                                  field="cli.command")
            t = cli.get("timeout_s")
            if t is not None and (not isinstance(t, (int, float)) or t <= 0):
                raise BridgeError(400, "VALIDATION_FAILED",
                                  "cli.timeout_s must be positive", field="cli.timeout_s")
        http = merged.get("http_api")
        if http is not None:
            if not isinstance(http, dict):
                raise BridgeError(400, "VALIDATION_FAILED",
                                  "http_api must be an object", field="http_api")
            if http.get("provider") is not None and http["provider"] not in AI_PROVIDERS:
                raise BridgeError(400, "VALIDATION_FAILED",
                                  f"http_api.provider must be one of "
                                  f"{', '.join(AI_PROVIDERS)}", field="http_api.provider")
            for k in ("endpoint", "model", "api_key", "api_key_env"):
                if http.get(k) is not None and not isinstance(http[k], str):
                    raise BridgeError(400, "VALIDATION_FAILED",
                                      f"http_api.{k} must be a string", field=f"http_api.{k}")
            t = http.get("timeout_s")
            if t is not None and (not isinstance(t, (int, float)) or t <= 0):
                raise BridgeError(400, "VALIDATION_FAILED",
                                  "http_api.timeout_s must be positive",
                                  field="http_api.timeout_s")
        if mode == "http_api":
            http = http or {}
            if not (http.get("model") or "").strip():
                raise BridgeError(400, "VALIDATION_FAILED",
                                  "http_api needs a model", field="http_api.model")
            provider = http.get("provider") or AI_DEFAULT_CONFIG["http_api"]["provider"]
            if provider == "openai_compatible" and not (http.get("endpoint") or "").strip():
                raise BridgeError(400, "VALIDATION_FAILED",
                                  "provider 'openai_compatible' needs an endpoint",
                                  field="http_api.endpoint")

    async def h_ai_config_get(self, req, m):
        raw = self._ai_read_raw()
        if raw is None:
            return JsonResponse(self._ai_config_view({}, False))
        return JsonResponse(self._ai_config_view(raw, True))

    async def h_ai_config_post(self, req, m):
        body = self.parse_json_body(req)
        raw = self._ai_read_raw()
        merged = dict(raw) if raw else {}

        def merge_block(name: str, updates: dict) -> None:
            cur = dict(merged.get(name) or {})
            for k, v in updates.items():
                if v is None:
                    continue          # explicit null = keep existing (wire convention)
                cur[k] = v
            merged[name] = cur

        if body.get("mode") is not None:
            merged["mode"] = body["mode"]
        if body.get("cli") is not None:
            if not isinstance(body["cli"], dict):
                raise BridgeError(400, "VALIDATION_FAILED", "cli must be an object", field="cli")
            merge_block("cli", body["cli"])
        if body.get("http_api") is not None:
            if not isinstance(body["http_api"], dict):
                raise BridgeError(400, "VALIDATION_FAILED",
                                  "http_api must be an object", field="http_api")
            block = dict(body["http_api"])
            # api_key tri-state: absent/null = keep, "" = clear, non-empty = set
            if block.get("api_key") is None:
                block.pop("api_key", None)
            merge_block("http_api", block)

        self._ai_validate_final(merged)

        path = self._ai_config_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(".ai.json.tmp")
        tmp.write_text(json.dumps(merged, indent=2, ensure_ascii=False) + "\n",
                       encoding="utf-8")
        os.replace(tmp, path)
        os.chmod(path, 0o600)
        view = self._ai_config_view(merged, True)
        self.store.audit("ai.config",
                         f"mode={view['mode']} provider={view['http_api']['provider']}")
        return JsonResponse(view)

    async def h_bridge_ctl(self, req, m):
        body = self.parse_json_body(req)
        action = body.get("action")
        if action == "reload_policy":
            self.config.load()
            return JsonResponse({"ok": True})
        if action in ("stop", "restart"):
            self.bus.publish("bridge.stopping",
                             {"reason": action, "resume_hint_s": 5})
            code = 0 if action == "stop" else 42

            async def die():
                await asyncio.sleep(0.3)
                LOG.info("bridge exiting (%s), code=%s", action, code)
                os._exit(code)

            asyncio.get_running_loop().create_task(die())
            return JsonResponse({"ok": True})
        raise BridgeError(400, "VALIDATION_FAILED",
                          "action must be stop|restart|reload_policy", field="action")

    # -- serve ----------------------------------------------------------------

    async def serve(self) -> None:
        await self.manager.reconcile()
        # protocol 1.1: autostart services launch after reconciliation —
        # best-effort per service (see ServiceRegistry.autostart_boot)
        await self.services.autostart_boot()
        server = await asyncio.start_server(self.handle_conn, "127.0.0.1", self.port)
        LOG.info("trmx-bridge %s listening on 127.0.0.1:%s (home=%s)",
                 VERSION, self.port, self.home)
        async with server:
            await server.serve_forever()


def with_stop(writer) -> None:
    try:
        writer.close()
        try:
            asyncio.get_running_loop().create_task(writer.wait_closed())
        except RuntimeError:
            pass
    except Exception:  # noqa: BLE001
        pass


def main() -> None:
    import errno

    ap = argparse.ArgumentParser(prog="trmx-bridge",
                                 description="TRMX execution-plane daemon (TRMX-P/1 subset)")
    ap.add_argument("--home", default=os.environ.get("TRMX_HOME", str(Path.home() / ".trmx")),
                    help="state directory (default: $TRMX_HOME or ~/.trmx)")
    ap.add_argument("--port", type=int, default=None,
                    help="override port for this run (not persisted)")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s")

    home = Path(args.home).expanduser()
    (home / "logs").mkdir(parents=True, exist_ok=True)
    (home / "tools").mkdir(parents=True, exist_ok=True)
    (home / "bin").mkdir(parents=True, exist_ok=True)
    (home / "services").mkdir(parents=True, exist_ok=True)

    config = Config(home / "bridge.json").load()
    if args.port:
        config.data["port"] = args.port  # runtime override only
    if config.ensure_token():
        LOG.warning("no token in config — generated a new one (masked '%s…'); "
                    "run `trmx token --show` to view it", (config.token or "")[:4])

    pidfile = home / "bridge.pid"

    def _stop(signum, frame):  # noqa: ANN001
        try:
            pidfile.unlink()
        except FileNotFoundError:
            pass
        LOG.info("received signal %s, exiting", signum)
        os._exit(0)

    signal.signal(signal.SIGTERM, _stop)
    pidfile.write_text(str(os.getpid()))

    port = int(config["port"])
    app = BridgeApp(home, port, config)
    try:
        asyncio.run(app.serve())
    except OSError as e:
        if e.errno == errno.EADDRINUSE:
            # Fail loud on port conflict — never silently re-bind (ADR-002).
            print(f"trmx-bridge: port {port} is already in use — refusing to start. "
                  f"Change 'port' in {config.path} or stop the other process.",
                  file=sys.stderr)
            sys.exit(78)
        raise


if __name__ == "__main__":
    main()
