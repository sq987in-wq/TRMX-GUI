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
import sys
import time
from collections import deque
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

VERSION = "0.2.0"
PROTOCOL_VERSIONS = [1]

LOG = logging.getLogger("trmx-bridge")

TERMINAL_STATES = {"COMPLETED", "FAILED", "CANCELLED", "LOST"}
ACTIVE_STATES = {"QUEUED", "STARTING", "RUNNING", "CANCELLING"}
HTTP_REASONS = {
    200: "OK", 201: "Created", 400: "Bad Request", 401: "Unauthorized",
    403: "Forbidden", 404: "Not Found", 405: "Method Not Allowed",
    409: "Conflict", 411: "Length Required", 413: "Payload Too Large",
    415: "Unsupported Media Type", 416: "Range Not Satisfiable",
    422: "Unprocessable Entity", 429: "Too Many Requests",
    500: "Internal Server Error", 503: "Service Unavailable",
}
MAX_HEADER_BYTES = 16384
MAX_JSON_BODY = 1 << 20  # 1 MiB (PROTOCOL §1.2)

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

    def __init__(self, home: Path, store: Store, config: Config, policy: Policy, bus: EventBus):
        self.home = home
        self.store = store
        self.config = config
        self.policy = policy
        self.bus = bus
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
        if jtype != "argv":
            raise BridgeError(400, "VALIDATION_FAILED",
                              f"type '{jtype}' is not implemented in the Phase 2 PoC "
                              "(only type:\"argv\")", field="type")
        name = payload.get("name") or "job"
        if not isinstance(name, str) or not (1 <= len(name) <= 200):
            raise BridgeError(400, "VALIDATION_FAILED", "name must be 1..200 chars", field="name")
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
            "job_id": job_id, "name": name, "type": "argv", "tool": None,
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
            tail = dec.decode(b"", final=True)
            if tail:
                self._emit(job_id, kind, {"job_id": job_id, "text": tail})
        except Exception:  # noqa: BLE001
            LOG.exception("output pump failed for %s", job_id)

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
    __slots__ = ("method", "path", "query", "headers", "body", "keep_alive")

    def __init__(self, method, path, query, headers, body, keep_alive):
        self.method, self.path, self.query = method, path, query
        self.headers, self.body, self.keep_alive = headers, body, keep_alive


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


class BridgeApp:
    """HTTP server + routing + auth for TRMX-P/1 (PoC subset)."""

    def __init__(self, home: Path, port: int, config: Config):
        self.home, self.port, self.config = home, port, config
        self.store = Store(home / "jobs.db")
        self.policy = Policy(home)
        self.bus = EventBus()
        self.manager = JobManager(home, self.store, config, self.policy, self.bus)
        self.started = time.time()
        self.routes = [
            ("GET", re.compile(r"^/v1/system/info$"), self.h_system_info),
            ("GET", re.compile(r"^/v1/system/policy$"), self.h_system_policy),
            ("POST", re.compile(r"^/v1/jobs$"), self.h_jobs_post),
            ("GET", re.compile(r"^/v1/jobs$"), self.h_jobs_list),
            ("GET", re.compile(r"^/v1/jobs/([A-Za-z0-9-]+)$"), self.h_job_get),
            ("POST", re.compile(r"^/v1/jobs/([A-Za-z0-9-]+)/cancel$"), self.h_job_cancel),
            ("GET", re.compile(r"^/v1/jobs/([A-Za-z0-9-]+)/output$"), self.h_job_output),
            ("GET", re.compile(r"^/v1/events$"), self.h_events),
            ("POST", re.compile(r"^/v1/system/bridge$"), self.h_bridge_ctl),
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
                    keep = req.keep_alive and e.status < 500 and e.status not in (400, 413)
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
        u = urlsplit(target)
        query = {k: v[0] for k, v in parse_qs(u.query, keep_blank_values=True).items()}
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
                         "scheduler": False},
            "tools_detected": 0,  # tool registry arrives in Phase 9
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
