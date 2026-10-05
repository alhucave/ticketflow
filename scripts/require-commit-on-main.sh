#!/usr/bin/env bash
# Release gate (F-032, DP-040): succeeds only if the commit is `main` itself or an ancestor of it, so a tag pushed on
# an unmerged branch commit (even one with a green CI) can never publish an image.
#
# Usage: scripts/require-commit-on-main.sh <owner/repo> <commit-sha> [branch]   (branch defaults to main)
# Needs `gh` (GH_TOKEN) and `contents: read`. Uses the compare API: status `identical` (the commit is the tip) or
# `behind` (the commit is an ancestor of the tip) pass; `ahead` and `diverged` do not.
set -euo pipefail

repo="${1:?usage: scripts/require-commit-on-main.sh <owner/repo> <commit-sha> [branch]}"
sha="${2:?usage: scripts/require-commit-on-main.sh <owner/repo> <commit-sha> [branch]}"
branch="${3:-main}"

if ! status="$(gh api "repos/${repo}/compare/${branch}...${sha}" --jq .status 2>&1)"; then
  echo "::error::Could not compare ${sha} with ${branch} in ${repo}: ${status}" >&2
  exit 1
fi
case "$status" in
  identical | behind)
    echo "OK: commit ${sha} is on ${branch} (compare status: ${status})"
    ;;
  *)
    echo "::error::Refusing to publish: commit ${sha} is not on ${branch} (compare status: ${status}). Tag a commit that is already merged into ${branch}." >&2
    exit 1
    ;;
esac
