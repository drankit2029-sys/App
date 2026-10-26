#!/usr/bin/env bash
# Downloads every artifact of an FX verification run into one directory.
# Usage: pull_artifacts.sh <run-id> [destination]
set -euo pipefail

run_id="${1:?usage: pull_artifacts.sh <run-id> [destination]}"
dest="${2:-fx-verify-runs/${run_id}}"
mkdir -p "$dest"

echo "== run ${run_id} =="
gh run view "$run_id" --json displayTitle,status,conclusion,createdAt \
  --jq '"\(.displayTitle)  \(.status)/\(.conclusion)  \(.createdAt)"' || true

gh run download "$run_id" -D "$dest"
echo
echo "== downloaded into $dest =="
du -sh "$dest" 2>/dev/null || true
find "$dest" -maxdepth 1 -mindepth 1 -type d | sort
echo
echo "next: tools/fxverify/extract_frames.sh $dest"
echo "      tools/fxverify/aggregate_report.py --root $dest --report $dest/REPORT.md"
