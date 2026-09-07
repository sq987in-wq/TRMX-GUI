#!/bin/sh
# TRMX installer — Phase 3 (docs/decisions/ADR-005).
#
# Makes "the backend exists" a one-tap operation: the Android app sends a
# RUN_COMMAND intent that runs exactly this script inside Termux
# (docs/CONTROL-PLANE.md op INSTALL).
#
# Modes:
#   install.sh                      remote: fetch files from BASE (default: GitHub raw @ REF)
#   install.sh --source DIR         local:  install from a checkout (dev / tests)
#   install.sh --base URL           override remote base (e.g. a release tag)
#   install.sh --sha256 FILE        override checksum file
#
# Properties:
#   - idempotent (safe to re-run; keeps one .old backup per file)
#   - SHA256-verified against SHA256SUMS when available
#   - stops the bridge before upgrading and restarts it afterwards
#   - fails loud on any error; never leaves partial files in place (staging + mv)
#
# Trust anchor honesty (ADR-005): SHA256SUMS protects the download path
# (corruption, partial mirrors). The cryptographic anchor for releases will be
# the Android app embedding the pinned hash of what it asks Termux to install
# (Phase 4). Release tagging regenerates SHA256SUMS via gen_checksums.sh.

set -eu

REF="main"
DEFAULT_BASE="https://raw.githubusercontent.com/sq987in-wq/TRMX-GUI/$REF/termux"
FILES="trmx-bridge.py trmx"
HOME_DIR="${TRMX_HOME:-$HOME/.trmx}"

SOURCE=""
BASE="$DEFAULT_BASE"
SUMFILE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --source) SOURCE="${2:?}"; shift 2 ;;
    --base)   BASE="${2:?}"; shift 2 ;;
    --sha256) SUMFILE="${2:?}"; shift 2 ;;
    *) printf 'install.sh: unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done

say() { printf 'install.sh: %s\n' "$*"; }
die() { printf 'install.sh: FAIL: %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "curl not found (Termux: pkg install curl)"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum not found"

# --- was the bridge running? (restart after upgrade) -----------------------
WAS_RUNNING=0
if [ -x "$HOME_DIR/trmx" ]; then
  if "$HOME_DIR/trmx" status >/dev/null 2>&1; then WAS_RUNNING=1; fi
fi
if [ "$WAS_RUNNING" = 1 ]; then
  say "bridge is running — stopping for upgrade"
  "$HOME_DIR/trmx" stop >/dev/null 2>&1 || true
fi

# --- stage + verify ---------------------------------------------------------
if [ -n "$SOURCE" ]; then
  # resolve to an absolute path: the checksum check below runs inside $STAGE,
  # where a relative --source would no longer resolve
  [ -d "$SOURCE" ] || die "source dir not found: $SOURCE"
  SOURCE="$(cd "$SOURCE" && pwd)"
fi
mkdir -p "$HOME_DIR"
STAGE="$(mktemp -d "$HOME_DIR/.staging.XXXXXX")" || die "cannot create staging dir"
trap 'rm -rf "$STAGE"' EXIT INT TERM

if [ -n "$SOURCE" ]; then
  for f in $FILES; do
    [ -f "$SOURCE/$f" ] || die "missing $SOURCE/$f"
    cp "$SOURCE/$f" "$STAGE/$f"
  done
  if [ -z "$SUMFILE" ] && [ -f "$SOURCE/SHA256SUMS" ]; then SUMFILE="$SOURCE/SHA256SUMS"; fi
else
  for f in $FILES; do
    curl -fsSL "$BASE/$f" -o "$STAGE/$f" || die "fetch failed: $BASE/$f"
  done
  if [ -z "$SUMFILE" ]; then
    if curl -fsSL "$BASE/SHA256SUMS" -o "$STAGE/SHA256SUMS" 2>/dev/null; then
      SUMFILE="$STAGE/SHA256SUMS"
    fi
  fi
fi

if [ -n "$SUMFILE" ]; then
  PATTERN="$(printf '%s\n' $FILES | sed 's/\./\\./g' | paste -sd'|' -)"
  # shellcheck disable=SC2164
  ( cd "$STAGE" && grep -E " ($PATTERN)\$" "$SUMFILE" | sha256sum -c - ) \
    || die "SHA256 verification failed — refusing to install"
  say "checksums verified"
else
  say "WARNING: no SHA256SUMS found — installing UNVERIFIED (dev mode)"
fi

# --- install atomically -----------------------------------------------------
mkdir -p "$HOME_DIR/logs" "$HOME_DIR/tools" "$HOME_DIR/bin"
for f in $FILES; do
  if [ -f "$HOME_DIR/$f" ]; then cp "$HOME_DIR/$f" "$HOME_DIR/$f.old"; fi
  mv "$STAGE/$f" "$HOME_DIR/$f"
done
chmod 755 "$HOME_DIR/trmx" "$HOME_DIR/trmx-bridge.py"

# --- manifest ---------------------------------------------------------------
python3 - "$HOME_DIR" <<'PYEOF'
import hashlib, json, os, sys, time
home = sys.argv[1]
files = ["trmx-bridge.py", "trmx"]
manifest = {
    "installed_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "files": {
        f: hashlib.sha256(open(os.path.join(home, f), "rb").read()).hexdigest()
        for f in files
    },
}
old = {}
mpath = os.path.join(home, "installed.json")
if os.path.exists(mpath):
    try:
        old = json.load(open(mpath))
    except ValueError:
        pass
old.update(manifest)
with open(mpath, "w") as fh:
    json.dump(old, fh, indent=2)
    fh.write("\n")
PYEOF

if [ "$WAS_RUNNING" = 1 ]; then
  "$HOME_DIR/trmx" start || die "restart after upgrade failed (see ~/.trmx/bridge.log)"
  say "bridge upgraded and restarted"
else
  say "installed — start with: ~/.trmx/trmx start"
fi
say "done"
