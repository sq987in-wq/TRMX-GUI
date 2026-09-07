#!/usr/bin/env bash
# Print the exact RUN_COMMAND intents for every control-plane op
# (docs/CONTROL-PLANE.md §3) as adb commands — lets you prove the control
# plane from a PC in Phase 4 without writing the app first.
#
# Usage: intent_commands.sh [op ...]   (default: all ops)
set -euo pipefail

PREFIX=/data/data/com.termux/files/usr
TRMX=/data/data/com.termux/files/home/.trmx
BASE="${TRMX_BASE:-https://raw.githubusercontent.com/sq987in-wq/TRMX-GUI/main/termux}"
TOKEN="${TRMX_TOKEN:-<paste-app-token>}"

emit() { # op path args...
  local op="$1" path="$2"; shift 2
  local args="$*"
  printf 'adb shell am startservice '
  printf -- '--user 0 -n com.termux/com.termux.app.RunCommandService '
  printf -- '-a com.termux.RUN_COMMAND '
  printf -- '--es com.termux.RUN_COMMAND_PATH "%s" ' "$path"
  # string-array extra: repeat --esa name value,value,...
  printf -- '--esa com.termux.RUN_COMMAND_ARGUMENTS "%s" ' "$(printf '%s,' "$@" | sed 's/,$//')"
  printf -- '--es com.termux.RUN_COMMAND_WORKDIR "/data/data/com.termux/files/home" '
  printf -- '--ez com.termux.RUN_COMMAND_BACKGROUND true '
  printf -- '--es com.termux.RUN_COMMAND_COMMAND_LABEL "TRMX: %s"\n' "$op"
}

OPS="${*:-install_py install pair start stop status enable-boot enable-service}"
for op in $OPS; do
  case "$op" in
    install_py)      emit INSTALL_PY "$PREFIX/bin/pkg" "install" "-y" "python" ;;
    install)         emit INSTALL "$PREFIX/bin/bash" "-c" "curl -fsSL $BASE/install.sh | sh -s -- --base $BASE" ;;
    pair)            emit PAIR "$TRMX/trmx" "pair" "$TOKEN" ;;
    start)           emit START "$TRMX/trmx" "start" ;;
    stop)            emit STOP "$TRMX/trmx" "stop" ;;
    status)          emit STATUS "$TRMX/trmx" "status" ;;
    enable-boot)     emit ENABLE_BOOT "$TRMX/trmx" "enable-boot" ;;
    enable-service)  emit ENABLE_SERVICE "$TRMX/trmx" "enable-service" ;;
    *) echo "unknown op: $op" >&2; exit 2 ;;
  esac
done
echo >&2
echo "# preconditions: allow-external-apps=true in ~/.termux/termux.properties," >&2
echo "#                RUN_COMMAND permission granted to the app, Termux opened once" >&2
