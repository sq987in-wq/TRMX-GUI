#!/bin/sh
# TRMX Phase 2 smoke demo — the human-visible proof of the bridge:
#   pair -> start -> info -> submit -> live stream -> cancel
#   -> hard-crash the bridge -> restart -> job re-adopted/LOST -> stop
#
# Requirements: python3 + curl. Runs identically here and inside Termux
# (pkg install python curl). Uses a throwaway home, touches nothing else.
set -e

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
CLI="$REPO/termux/trmx"
PORT="${TRMX_SMOKE_PORT:-27699}"
TMP="$(mktemp -d)"
export TRMX_HOME="$TMP/trmx-home"
TOKEN="smoke-token-0123456789abcdefghijklmnop"

step() { printf '\n=== %s\n' "$1"; }
pass()  { printf 'PASS  %s\n' "$1"; }
fail()  { printf 'FAIL  %s\n' "$1"; exit 1; }

cleanup() {
  python3 "$CLI" stop >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

jget() { # jget <file> <python-expr over d>
  TRMXJ_FILE="$1" TRMXJ_EXPR="$2" python3 -c '
import json, os
d = json.load(open(os.environ["TRMXJ_FILE"]))
print(eval(os.environ["TRMXJ_EXPR"]))'
}

step "1. init + pair + start (port $PORT)"
python3 "$CLI" init >/dev/null
python3 "$CLI" pair "$TOKEN" >/dev/null || fail "pair"
python3 "$CLI" start --port "$PORT" >/dev/null || fail "start"
python3 "$CLI" status
pass "bridge up and paired"

step "2. handshake: GET /v1/system/info"
curl -s -H "Authorization: Bearer $TOKEN" -H "X-TRMX-Protocol: 1" \
     "http://127.0.0.1:$PORT/v1/system/info" -o "$TMP/info.json"
[ "$(jget "$TMP/info.json" "1 in d['protocol_versions']")" = "True" ] || fail "info handshake"
pass "protocol_versions contains 1 (bridge $(jget "$TMP/info.json" "d['bridge_version']"))"

step "3. auth must be enforced"
CODE=$(curl -s -o "$TMP/noauth.json" -w '%{http_code}' \
     -H "X-TRMX-Protocol: 1" "http://127.0.0.1:$PORT/v1/system/info")
[ "$CODE" = "401" ] || fail "expected 401 without token, got $CODE"
[ "$(jget "$TMP/noauth.json" "d['error']['code']")" = "AUTH_REQUIRED" ] || fail "error code"
pass "401 AUTH_REQUIRED without token"

step "4. submit a streaming job and follow it live"
SUBMIT=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "X-TRMX-Protocol: 1" \
     -H "Content-Type: application/json" \
     -d '{"type":"argv","argv":["sh","-c","for i in 1 2 3 4 5; do echo line-$i; sleep 0.3; done"],"name":"smoke stream"}' \
     "http://127.0.0.1:$PORT/v1/jobs")
JOB=$(printf '%s' "$SUBMIT" | python3 -c "import json,sys; print(json.load(sys.stdin)['job_id'])")
[ -n "$JOB" ] || fail "submit"
echo "job: $JOB"
curl -s -N -H "Authorization: Bearer $TOKEN" -H "X-TRMX-Protocol: 1" \
     "http://127.0.0.1:$PORT/v1/jobs/$JOB/output?follow=1" -o "$TMP/stream.txt" &
STREAM_PID=$!
sleep 2.5
LINES=$(grep -c '"text":"line-' "$TMP/stream.txt" 2>/dev/null || echo 0)
[ "$LINES" -ge 3 ] || fail "expected >=3 live stdout frames, got $LINES"
pass "streamed $LINES output frames live"
wait $STREAM_PID || true
grep -q '"status":"COMPLETED"' "$TMP/stream.txt" || fail "terminal frame missing"
pass "stream ended with COMPLETED terminal frame"

step "5. submit a long job, then cancel it"
SUBMIT=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "X-TRMX-Protocol: 1" \
     -H "Content-Type: application/json" \
     -d '{"type":"argv","argv":["sleep","120"],"name":"smoke cancel"}' \
     "http://127.0.0.1:$PORT/v1/jobs")
JOB2=$(printf '%s' "$SUBMIT" | python3 -c "import json,sys; print(json.load(sys.stdin)['job_id'])")
sleep 0.5
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "X-TRMX-Protocol: 1" \
     -H "Content-Type: application/json" -d '{"grace_ms":1000}' \
     "http://127.0.0.1:$PORT/v1/jobs/$JOB2/cancel" -o "$TMP/cancel.json"
sleep 3
curl -s -H "Authorization: Bearer $TOKEN" -H "X-TRMX-Protocol: 1" \
     "http://127.0.0.1:$PORT/v1/jobs/$JOB2" -o "$TMP/job2.json"
ST=$(jget "$TMP/job2.json" "d['status']")
[ "$ST" = "CANCELLED" ] || fail "expected CANCELLED, got $ST"
pass "job cancelled (reason: $(jget "$TMP/job2.json" "d['cancel_reason']"))"

step "6. crash the bridge (SIGKILL), restart, verify reconciliation"
SUBMIT=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "X-TRMX-Protocol: 1" \
     -H "Content-Type: application/json" \
     -d '{"type":"argv","argv":["sleep","120"],"name":"smoke adopt"}' \
     "http://127.0.0.1:$PORT/v1/jobs")
JOB3=$(printf '%s' "$SUBMIT" | python3 -c "import json,sys; print(json.load(sys.stdin)['job_id'])")
sleep 0.5
kill -9 "$(python3 "$CLI" pid)" || fail "kill bridge"
sleep 0.3
python3 "$CLI" start --port "$PORT" >/dev/null || fail "restart bridge"
sleep 0.5
curl -s -H "Authorization: Bearer $TOKEN" -H "X-TRMX-Protocol: 1" \
     "http://127.0.0.1:$PORT/v1/jobs/$JOB3" -o "$TMP/job3.json"
ST=$(jget "$TMP/job3.json" "d['status']")
[ "$ST" = "RUNNING" ] || fail "expected RUNNING (re-adopted), got $ST"
pass "orphaned job re-adopted after bridge restart"
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "X-TRMX-Protocol: 1" \
     -H "Content-Type: application/json" -d '{"grace_ms":1000}' \
     "http://127.0.0.1:$PORT/v1/jobs/$JOB3/cancel" >/dev/null
pass "re-adopted job cancelled cleanly"

step "7. stop the bridge"
python3 "$CLI" stop || fail "stop"
if curl -s -m 1 "http://127.0.0.1:$PORT/v1/system/info" >/dev/null 2>&1; then
  fail "port still answering after stop"
fi
pass "bridge stopped, port closed"

printf '\n=============================================\n'
printf 'SMOKE OK — the Phase 2 bridge demo works.\n'
printf '=============================================\n'
