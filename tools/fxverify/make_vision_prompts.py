#!/usr/bin/env python3
"""Builds one review prompt per effect for the vision pass.

Each prompt names the exact images to inspect (the screenshots taken as every
parameter value was applied, the frames extracted at those same offsets, and the
filmstrip) and pins the JSON verdict schema, so a vision model can be pointed at
one effect at a time with no ambiguity about what it is looking at.

Usage:
  make_vision_prompts.py --run fx-verify-runs/<id> [--out fx-verify-runs/<id>/vision]
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

SCHEMA = """{
  "effect": "<effect id>",
  "videoReviewed": true|false,
  "imagesReviewed": <int>,
  "uiErrors": [
    {"image": "<file name>", "kind": "crash|anr|toast|dialog|layout|missing-control|other",
     "evidence": "<what is visibly on screen>", "confidence": "high|medium|low"}
  ],
  "waveformFindings": [
    {"image": "<file name>", "observation": "<does the waveform look like the parameter change implies?>",
     "verdict": "expected|unexpected|unclear"}
  ],
  "artifacts": ["<visual artefact that is not an error>"],
  "unknownControls": ["<control whose purpose is not clear from the screens>"],
  "verdict": "clean|minor-issues|problems",
  "summary": "<two sentences, plain language>"
}"""


def build_prompt(effect_dir: Path, run_name: str) -> str | None:
    shots_path = effect_dir / "shots.json"
    result_path = effect_dir / "result.json"
    if not shots_path.is_file():
        return None
    shots = json.loads(shots_path.read_text(encoding="utf-8"))
    result = json.loads(result_path.read_text(encoding="utf-8")) if result_path.is_file() else {}

    images = []
    for shot in shots:
        jpg = effect_dir / shot.get("shot", "")
        step = effect_dir / "frames" / ("step-%03d-%s.png" % (shot.get("index", 0), shot.get("param", "param")))
        images.append({
            "label": f"{shot.get('paramName')} = {shot.get('valueDisplay')}"
                     f"{' (default)' if shot.get('isDefault') else ''}",
            "screenshot": str(jpg) if jpg.is_file() else None,
            "video_frame": str(step) if step.is_file() else None,
        })
    filmstrip = effect_dir / "filmstrip.jpg"
    timeline = sorted((effect_dir / "frames").glob("timeline-*.png"))

    lines = []
    lines.append(f"You are reviewing a screen recording of the Android audio app "
                 f"'TakeOne' while the '{result.get('effect', effect_dir.name)}' effect "
                 f"('{result.get('title', '')}') was applied to a recording.")
    lines.append("")
    lines.append(f"Run: {run_name}. Recording: {effect_dir / (result.get('videoName') or 'video.mp4')}")
    lines.append(f"The sweep applied {result.get('shotCount', len(shots))} parameter states, "
                 f"took a screenshot of each, and recorded video throughout. "
                 f"Degraded run: {result.get('degraded')}. "
                 f"Reported problems: {result.get('problems') or 'none'}.")
    lines.append("")
    lines.append("Read EVERY image below with the read_file tool (they are JPEG/PNG; "
                 "read them one by one, they are small). Each entry pairs the screenshot "
                 "taken at that parameter value with the video frame from the same moment.")
    lines.append("")
    for i, img in enumerate(images, 1):
        parts = [f"{i}. {img['label']}"]
        if img["screenshot"]:
            parts.append(f"screenshot: {img['screenshot']}")
        if img["video_frame"]:
            parts.append(f"video frame: {img['video_frame']}")
        lines.append(" | ".join(parts))
    lines.append("")
    if filmstrip.is_file():
        lines.append(f"Filmstrip of the whole recording (every ~3 s): {filmstrip}")
    if timeline:
        lines.append(f"Timeline frames sampled at 1 fps: {timeline[0].parent}/timeline-*.png "
                     f"({len(timeline)} files)")
    lines.append("")
    lines.append("What to look for:")
    lines.append("- any crash, ANR ('isn't responding'), error toast, red error text, or unexpected dialog")
    lines.append("- controls that are missing, overlapping, clipped, or unreadable")
    lines.append("- the waveform: it should visibly change as parameters that alter the signal are moved, "
                 "and stay stable otherwise; below the waveform the effect panel should show the parameter "
                 "value that is being applied")
    lines.append("- anything else a user would notice as broken")
    lines.append("")
    lines.append("Answer with ONLY a JSON object in exactly this shape (no prose outside it):")
    lines.append(SCHEMA)
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True, help="downloaded run directory")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    run = Path(args.run)
    out = Path(args.out) if args.out else run / "vision"
    out.mkdir(parents=True, exist_ok=True)

    written = 0
    for shots in sorted(run.rglob("shots.json")):
        effect_dir = shots.parent
        prompt = build_prompt(effect_dir, run.name)
        if not prompt:
            continue
        (out / f"{effect_dir.name}.md").write_text(prompt, encoding="utf-8")
        written += 1
    print(f"wrote {written} vision prompts into {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
