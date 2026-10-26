#!/usr/bin/env python3
"""Extracts review frames from every device-sweep recording in a run.

Per effect directory this writes:
  frames/step-NNN-<param>.png  one frame at the exact offset the test logged for
                               each parameter value it applied
  frames/timeline-NNNNN.png    the recording sampled at 1 fps, for anything that
                               happens between parameter changes
  filmstrip.jpg                the whole recording as a contact sheet

Usage: extract_frames.py <run-directory> [--max-timeline N]
"""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path


def run(cmd: list[str]) -> bool:
    return subprocess.run(cmd, capture_output=True).returncode == 0


def find_video(shot_dir: Path) -> Path | None:
    here = sorted(shot_dir.glob("*.mp4"))
    if here:
        return here[0]
    beside = sorted(shot_dir.parent.glob("*.mp4"))
    if beside:
        return beside[0]
    deeper = sorted(shot_dir.parent.glob("*/*.mp4"))
    return deeper[0] if deeper else None


def extract_one(shots_path: Path, max_timeline: int) -> list[str]:
    """One effect directory may now hold one recording per parameter: each step
    entry names the video that owns its change."""
    effect_dir = shots_path.parent
    try:
        shots = json.loads(shots_path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        return [f"{effect_dir.name}: shots.json unreadable ({exc})"]
    if isinstance(shots, dict):
        shots = shots.get("shots", [])

    frames = effect_dir / "frames"
    frames.mkdir(exist_ok=True)
    notes = []
    groups: dict[str, list[dict]] = {}
    for shot in shots:
        key = shot.get("video") or "combined"
        groups.setdefault(key, []).append(shot)
    if not groups:
        notes.append(f"{effect_dir.name}: no shot entries")
        return notes

    for video_name, members in sorted(groups.items()):
        video = (effect_dir / video_name) if video_name != "combined" else None
        if video is None or not video.is_file():
            candidates = sorted(effect_dir.parent.glob("*.mp4"))
            video = candidates[0] if candidates else None
        if video is None:
            notes.append(f"{effect_dir.name}: recording missing for {video_name}")
            continue
        steps = 0
        for shot in members:
            offset = max(0.0, float(shot.get("videoOffsetMs", 0)) / 1000.0)
            name = "step-%s-%s.png" % (shot.get("param", "param"),
                                       shot.get("valueRaw", shot.get("index", 0)))
            target = frames / name
            if target.is_file():
                steps += 1
                continue
            if run(["ffmpeg", "-v", "error", "-y", "-ss", "%.2f" % offset,
                    "-i", str(video), "-frames:v", "1", "-q:v", "3", str(target)]):
                steps += 1

        tag = members[0].get("param", "all")
        strip = effect_dir / f"filmstrip-{tag}.jpg"
        if not strip.is_file():
            run(["ffmpeg", "-v", "error", "-y", "-i", str(video), "-vf",
                 "fps=2,scale=160:-1,tile=4x6", "-frames:v", "1", str(strip)])
        timeline = sorted(frames.glob(f"timeline-{tag}-*.png"))
        if not timeline and max_timeline > 0:
            run(["ffmpeg", "-v", "error", "-y", "-i", str(video), "-vf",
                 f"fps=2,scale=320:-1", "-frames:v", "20",
                 str(frames / f"timeline-{tag}-%02d.png")])
        notes.append(f"{effect_dir.name}/{tag}: {steps} step frames, "
                     f"stripe {'ok' if strip.is_file() else 'failed'}")
    return notes


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("root", help="downloaded run directory")
    ap.add_argument("--max-timeline", type=int, default=20,
                    help="sampled frames per parameter recording")
    args = ap.parse_args()

    root = Path(args.root)
    manifests = sorted(root.rglob("shots.json"))
    if not manifests:
        print(f"no shots.json under {root}")
        return 1
    notes: list[str] = []
    for path in manifests:
        notes.extend(extract_one(path, args.max_timeline))
    for n in notes:
        print(n)
    print(f"processed {len(manifests)} effect manifests")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
