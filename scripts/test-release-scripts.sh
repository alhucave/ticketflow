#!/usr/bin/env bash
# Offline test of the release gate scripts (F-032, DP-040): a fake `gh` returns canned output, so no network and no
# token are needed. It checks the decisions of the scripts (pass, fail, wait while running, any success wins,
# API errors fail closed). The real GitHub queries and jq filters are proven live (see progress/impl_first-release-ghcr.md).
# Run by ./init.sh; also runnable by hand: scripts/test-release-scripts.sh
set -euo pipefail
cd "$(dirname "$0")/.."

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
cat >"$tmp/bin/gh" <<'FAKE'
#!/usr/bin/env bash
# FAKE_MODE selects the canned answer; FAKE_COUNTER counts the calls (for the "running, then finished" case).
n="$(cat "$FAKE_COUNTER" 2>/dev/null || true)"
n="${n:-0}"
echo $((n + 1)) >"$FAKE_COUNTER"
case "$FAKE_MODE" in
  green) echo "completed success" ;;
  rerun-green) printf 'completed failure\ncompleted success\n' ;;
  failed) echo "completed failure" ;;
  none) ;;
  running-then-green) if [ "$n" -lt 2 ]; then echo "in_progress none"; else echo "completed success"; fi ;;
  running-forever) echo "in_progress none" ;;
  api-error) echo "gh: Not Found (HTTP 404)" >&2; exit 1 ;;
  on-main) echo "identical" ;;
  ancestor) echo "behind" ;;
  ahead) echo "ahead" ;;
  diverged) echo "diverged" ;;
esac
FAKE
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH" FAKE_COUNTER="$tmp/counter"

failures=0
# expect <pass|fail> <FAKE_MODE> <script> [extra env assignments...]
expect() {
  local want="$1" mode="$2" script="$3"
  shift 3
  : >"$FAKE_COUNTER"
  local rc=0
  env FAKE_MODE="$mode" "$@" "scripts/$script" o/r abc123 >"$tmp/out" 2>&1 || rc=$?
  if { [ "$want" = pass ] && [ "$rc" -eq 0 ]; } || { [ "$want" = fail ] && [ "$rc" -ne 0 ]; }; then
    echo "ok   $script [$mode] -> $want"
  else
    echo "FAIL $script [$mode]: wanted $want, exit code $rc" >&2
    cat "$tmp/out" >&2
    failures=$((failures + 1))
  fi
}

once=(GATE_WAIT_SECONDS=0)
expect pass green require-green-verify.sh "${once[@]}"
expect pass rerun-green require-green-verify.sh "${once[@]}"
expect fail failed require-green-verify.sh "${once[@]}"
expect fail none require-green-verify.sh "${once[@]}"
expect fail api-error require-green-verify.sh "${once[@]}"
expect fail running-forever require-green-verify.sh GATE_WAIT_SECONDS=2 GATE_POLL_SECONDS=1
expect pass running-then-green require-green-verify.sh GATE_WAIT_SECONDS=30 GATE_POLL_SECONDS=1
expect fail running-then-green require-green-verify.sh GATE_WAIT_SECONDS=0
expect pass on-main require-commit-on-main.sh
expect pass ancestor require-commit-on-main.sh
expect fail ahead require-commit-on-main.sh
expect fail diverged require-commit-on-main.sh
expect fail api-error require-commit-on-main.sh

if [ "$failures" -ne 0 ]; then
  echo "FAIL: $failures release script case(s) failed" >&2
  exit 1
fi
echo "OK: release gate scripts behave as specified"
