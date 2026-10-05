#!/usr/bin/env bash
# Release gate (F-032, DP-040): succeeds only if the given commit has a COMPLETED, SUCCESSFUL `verify` check run
# (the job of .github/workflows/ci.yml) created by GitHub Actions. Anything else fails the release with a message.
#
# Usage: scripts/require-green-verify.sh <owner/repo> <commit-sha>
# Needs the GitHub CLI (`gh`) authenticated through GH_TOKEN (or a local `gh auth login`). In the release workflow the
# job token needs `checks: read` (and `contents: read`).
# Environment: GATE_WAIT_SECONDS (default 1200) = how long to keep polling while a `verify` run is still queued or
# running (typical case: the tag is pushed right after the merge, before the push CI of main has finished);
# GATE_POLL_SECONDS (default 30). Set GATE_WAIT_SECONDS=0 to check once.
#
# Several runs of `verify` can exist for one commit (re-runs, PR + push): ANY completed success is enough, because a
# re-run of a flaky job that finally passed is still a green verification of the same commit.
set -euo pipefail

repo="${1:?usage: scripts/require-green-verify.sh <owner/repo> <commit-sha>}"
sha="${2:?usage: scripts/require-green-verify.sh <owner/repo> <commit-sha>}"
wait_seconds="${GATE_WAIT_SECONDS:-1200}"
poll_seconds="${GATE_POLL_SECONDS:-30}"
check_name="verify"

# One line per matching run: "<status> <conclusion>". --paginate walks every page (per_page=100); the jq filter runs per page.
query_runs() {
  gh api --paginate "repos/${repo}/commits/${sha}/check-runs?check_name=${check_name}&filter=all&per_page=100" \
    --jq '.check_runs[] | select(.name == "'"${check_name}"'" and .app.slug == "github-actions") | "\(.status) \(.conclusion // "none")"'
}

start=$SECONDS
while :; do
  if ! runs="$(query_runs 2>&1)"; then
    echo "::error::Could not read the check runs of ${sha} in ${repo}: ${runs}" >&2
    exit 1
  fi
  if grep -qx 'completed success' <<<"$runs"; then
    echo "OK: commit ${sha} has a successful '${check_name}' check run ($(grep -c . <<<"$runs") run(s) found)"
    exit 0
  fi
  if grep -qE '^(queued|in_progress|waiting|pending|requested) ' <<<"$runs" && [ $((SECONDS - start)) -lt "$wait_seconds" ]; then
    echo "'${check_name}' is still running for ${sha}; checking again in ${poll_seconds}s"
    sleep "$poll_seconds"
    continue
  fi
  break
done

if [ -z "$runs" ]; then
  detail="no '${check_name}' check run exists for this commit (was CI never run on it, or is the tag not on a pushed commit of main?)"
else
  detail="found: $(tr '\n' ';' <<<"$runs")"
fi
echo "::error::Refusing to publish: commit ${sha} has no successful '${check_name}' check run: ${detail}. Tag a commit of main whose CI is green (or re-run the CI of that commit) and push the tag again." >&2
exit 1
