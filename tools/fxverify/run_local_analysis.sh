#!/usr/bin/env bash
# One-shot local analysis for a finished FX verification run:
# download every artifact, extract review frames, build the vision prompts and
# merge the verdicts into one report.
#
# Usage: run_local_analysis.sh <run-id> [destination]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
run_id="${1:?usage: run_local_analysis.sh <run-id> [destination]}"
dest="${2:-$repo/fx-verify-runs/$run_id}"

echo "== 1/4 downloading artifacts for run $run_id =="
"$here/pull_artifacts.sh" "$run_id" "$dest"

echo
echo "== 2/4 extracting review frames =="
"$here/extract_frames.sh" "$dest"

echo
echo "== 3/4 building vision prompts =="
python3 "$here/make_vision_prompts.py" --run "$dest"

echo
echo "== 4/4 merging verdicts =="
python3 "$here/aggregate_report.py" --root "$dest" \
        --report "$dest/REPORT.md" --json-out "$dest/combined.json" || true

echo
echo "report:    $dest/REPORT.md"
echo "prompts:   $dest/vision/*.md"
echo "next:      run one vision sub-agent per prompt (see tools/fxverify/vision_review.md)"
