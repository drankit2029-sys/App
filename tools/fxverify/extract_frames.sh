#!/usr/bin/env bash
# Extracts review frames from every device-sweep video in a downloaded run.
#
# Two sets come out of each effect directory:
#   frames/step-*.png  - one frame per parameter value, at the exact offset the
#                        test logged when it applied that value
#   frames/timeline-*  - a low-rate sweep of the whole recording, for spotting
#                        anything that happens between parameter changes
#
# Usage: extract_frames.sh <run-directory>
set -euo pipefail

root="${1:?usage: extract_frames.sh <run-directory>}"
found=0
# Android's sh has no /dev/fd, so the shot manifests go through a temp file
# rather than process substitution.
list="$(mktemp)"

find "$root" -name 'shots.json' | sort > "$list"
while IFS= read -r shots; do
  dir="$(dirname "$shots")"
  video="$(find "$dir" "$dir/.." -maxdepth 2 -name '*.mp4' 2>/dev/null | head -1 || true)"
  if [ -z "$video" ]; then
    echo "no video beside $shots"
    continue
  fi
  found=$((found + 1))
  mkdir -p "$dir/frames"
  python3 - "$shots" "$video" "$dir/frames" <<'PY'
import json, pathlib, subprocess, sys

shots_path, video, out_dir = sys.argv[1], sys.argv[2], pathlib.Path(sys.argv[3])
data = json.loads(pathlib.Path(shots_path).read_text(encoding="utf-8"))
if isinstance(data, dict):
    data = data.get("shots", [])
for shot in data:
    offset = max(0.0, float(shot.get("videoOffsetMs", 0)) / 1000.0)
    name = "step-%03d-%s.png" % (shot.get("index", 0), shot.get("param", "param"))
    target = out_dir / name
    if target.is_file():
        continue
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-ss", "%.2f" % offset, "-i", video,
         "-frames:v", "1", "-q:v", "3", str(target)], check=False)
print("%s: %d step frames" % (pathlib.Path(video).name, len(data)))
PY

  ffmpeg -v error -y -i "$video" -vf "fps=1,scale=480:-1" \
    "$dir/frames/timeline-%05d.png" 2>/dev/null || true
  ffmpeg -v error -y -i "$video" -vf "fps=1/3,scale=200:-1,tile=6x5" \
    -frames:v 1 "$dir/filmstrip.jpg" 2>/dev/null || true
  echo "$dir: frames + filmstrip written"
done < "$list"
rm -f "$list"

echo "processed $found effect recordings"
