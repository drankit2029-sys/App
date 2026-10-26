#!/usr/bin/env python3
"""Merges every tier of the FX verification into one report.

Consumes what the CI jobs produced: per-effect verdicts.json from the DSP matrix,
result.json + peaks.json + shots.json from the device sweeps, and
verify_formats.json from the conversion tier. Also performs the waveform check
itself, by comparing the app's own dry and wet peak arrays.

Usage:
  aggregate_report.py --root fx-verify-runs/<id> --report fx-verify-runs/<id>/REPORT.md
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np


def load(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001 - a missing artifact is a finding, not a crash
        return None


def corr(a, b) -> float:
    a, b = np.asarray(a, dtype=float), np.asarray(b, dtype=float)
    if a.size == 0 or b.size == 0:
        return 0.0
    a, b = a[:min(len(a), len(b))], b[:min(len(a), len(b))]
    if a.std() < 1e-12 or b.std() < 1e-12:
        return 0.0
    return float(np.corrcoef(a, b)[0, 1])


def waveform_check(peaks: dict) -> dict:
    dry = np.asarray(peaks.get("dry", []), dtype=float)
    wet = np.asarray(peaks.get("wet", []), dtype=float)
    n = min(len(dry), len(wet))
    dry, wet = dry[:n], wet[:n]
    if n == 0:
        return {"verdict": "missing", "detail": "no peak arrays"}
    delta = wet - dry
    changed = int(np.count_nonzero(np.abs(delta) > 1e-3))
    return {
        "verdict": "changed" if changed else "unchanged",
        "bars": n,
        "bars_changed": changed,
        "bars_changed_pct": round(100.0 * changed / n, 1),
        "max_bar_delta": round(float(np.max(np.abs(delta))), 4),
        "mean_abs_delta": round(float(np.mean(np.abs(delta))), 4),
        "peak_correlation": round(corr(dry, wet), 4),
    }


def video_state(path: Path, result: dict) -> dict:
    """A recording is only evidence if it was finalised: screenrecord writes the
    moov atom when it stops, so a truncated file is reported rather than assumed."""
    import subprocess
    name = result.get("videoName")
    if not name:
        return {"present": False, "detail": "no recording was started"}
    video = path / name
    if not video.is_file():
        return {"present": False, "detail": f"{name} missing"}
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", str(video)],
        capture_output=True, text=True)
    duration = probe.stdout.strip()
    valid = probe.returncode == 0 and duration not in ("", "N/A")
    return {
        "present": True,
        "valid": valid,
        "bytes": video.stat().st_size,
        "duration_s": round(float(duration), 2) if valid else None,
        "detail": "" if valid else (probe.stderr.strip()[:120] or "not finalised"),
    }


def device_summary(path: Path) -> dict:
    result = load(path / "result.json")
    if result is None:
        return {"effect": path.name, "status": "missing", "detail": "no result.json"}
    peaks = load(path / "peaks.json")
    waveforms = waveform_check(peaks) if peaks else {"verdict": "missing"}
    logcat = result.get("logcatErrors") or {}
    raw_log = (logcat.get("raw") or "").strip()
    return {
        "effect": result.get("effect", path.name),
        "status": "ok",
        "degraded": bool(result.get("degraded")),
        "shots": result.get("shotCount", 0),
        "params_driven": len(result.get("driven", [])),
        "notes": result.get("notes", []),
        "problems": result.get("problems", []),
        "recorded": bool(result.get("recorded")),
        "video": video_state(path, result),
        "waveform": waveforms,
        "logcat_errors": raw_log[:600],
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--report", default=None)
    ap.add_argument("--json-out", default=None)
    args = ap.parse_args()

    root = Path(args.root)
    dsp_dirs = sorted({p.parent for p in root.rglob("verdicts.json")})
    device_dirs = sorted({p.parent for p in root.rglob("result.json")})
    format_files = sorted(root.rglob("verify_formats.json"))

    dsp = [load(d / "verdicts.json") for d in dsp_dirs]
    dsp = [d for d in dsp if d]
    device = [device_summary(d) for d in device_dirs]
    formats = [load(f) for f in format_files]
    formats = [f for f in formats if f]

    device = [d for d in device if d.get("status") != "missing" or d.get("detail")]
    by_effect = {}
    for job in dsp:
        by_effect[job["summary"]["effect"]] = job["summary"]

    lines = ["# TakeOne FX verification report", ""]
    lines.append(f"DSP shards analysed: {len(dsp)} | device sweeps: {len(device)} | "
                 f"format verifications: {len(formats)}")
    lines.append("")

    # ---- DSP matrix -----------------------------------------------------------
    lines.append("## Parameter matrix (off-device DSP)")
    lines.append("")
    lines.append("| effect | params | cases | hard fail | blow-up | "
                 "non-finite | expectation not met | single-point ok | weak | "
                 "findings | series monotone | anchors |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|---|---|")
    total_fails = 0
    for effect in sorted(by_effect):
        s = by_effect[effect]
        total_fails += s["fails"]
        lines.append(
            f"| {effect} | {s.get('params_covered', 0)} | {s['cases']} | "
            f"{s['fails']} | {s.get('blow_ups', 0)} | {s.get('nonfinite', 0)} | "
            f"{s['expectation_not_met']} | {s['single_point_ok']} | "
            f"{s['weak']} | {s.get('findings', 0)} | "
            f"{s['series_ok']}/{s['series_total']} | "
            f"{s['anchors_ok']}/{s['anchors_total']} |")
    lines.append("")

    # ---- DSP findings ---------------------------------------------------------
    divergent = [s for s in by_effect.values()
                 if s.get("blow_ups") or s.get("nonfinite")]
    lines.append("### DSP findings worth reading")
    if divergent:
        lines.append("")
        lines.append("These renders went numerically unstable (divergence or "
                     "non-finite samples) at a setting inside the control's own range:")
        for s in sorted(divergent, key=lambda s: s["effect"]):
            lines.append(f"- **{s['effect']}**: {s.get('blow_ups', 0)} divergent, "
                         f"{s.get('nonfinite', 0)} with non-finite samples")
    else:
        lines.append("")
        lines.append("No divergent or non-finite renders.")
    noisy = sorted((s for s in by_effect.values()
                    if s.get("findings") and not (s.get("blow_ups") or s.get("nonfinite"))),
                   key=lambda s: -s["findings"])
    if noisy:
        lines.append("")
        lines.append("Effects whose extremes produce a finding (silence or over-scale "
                     "output), all of which are plausible at the end of a control's range "
                     "and are listed so they can be read rather than assumed:")
        lines.append("")
        lines.append("| effect | findings | silent | over-scale |")
        lines.append("|---|---|---|---|")
        for s in noisy[:15]:
            lines.append(f"| {s['effect']} | {s['findings']} | {s.get('silent', 0)} | "
                         f"{s.get('above_full_scale', 0)} |")
    lines.append("")

    # ---- anchors --------------------------------------------------------------
    failing_anchors = []
    for job in dsp:
        for anchor in job.get("anchors", []):
            if anchor["verdict"] != "ok":
                failing_anchors.append((job["summary"]["effect"], anchor))
    lines.append(f"### Anchors")
    lines.append(f"Physical assertions passed: "
                 f"{sum(1 for job in dsp for a in job.get('anchors', []) if a['verdict'] == 'ok')}"
                 f"/{sum(len(job.get('anchors', [])) for job in dsp)}")
    if failing_anchors:
        lines.append("")
        for effect, anchor in failing_anchors:
            lines.append(f"- **{effect} / {anchor['id']}**: {anchor['verdict']} - "
                         f"{anchor.get('detail', anchor.get('required', ''))}")
    lines.append("")

    # ---- device tier ----------------------------------------------------------
    lines.append("## Device sweeps (real UI, video, waveform)")
    lines.append("")
    lines.append("| effect | param states | shots | video | video length | "
                 "degraded | waveform | bars changed | peak corr | problems |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|")
    for d in sorted(device, key=lambda d: d.get("effect", "")):
        wf = d.get("waveform", {})
        vid = d.get("video", {}) if isinstance(d.get("video"), dict) else {}
        lines.append(
            f"| {d.get('effect')} | {d.get('params_driven', 0)} | {d.get('shots', 0)} | "
            f"{'ok' if vid.get('valid') else ('missing' if not vid.get('present') else 'not finalised')} | "
            f"{vid.get('duration_s') if vid.get('duration_s') is not None else '-'} | "
            f"{'yes' if d.get('degraded') else 'no'} | {wf.get('verdict', '?')} | "
            f"{wf.get('bars_changed_pct', '')}% | {wf.get('peak_correlation', '')} | "
            f"{len(d.get('problems', []))} |")
    lines.append("")
    flagged = [d for d in device if d.get("degraded") or d.get("problems")
               or (d.get("waveform", {}).get("verdict") == "unchanged")]
    if flagged:
        lines.append("### Device findings")
        for d in flagged:
            lines.append(f"- **{d.get('effect')}**"
                         f"{' (degraded)' if d.get('degraded') else ''}"
                         f"{' (waveform unchanged)' if d.get('waveform', {}).get('verdict') == 'unchanged' else ''}")
            for p in d.get("problems", []):
                lines.append(f"  - {p}")
            if d.get("logcat_errors"):
                lines.append(f"  - logcat: {d['logcat_errors'].splitlines()[0][:200]}")
        lines.append("")

    # ---- formats --------------------------------------------------------------
    lines.append("## Container conversion")
    lines.append("")
    for payload in formats:
        lines.append(f"Source duration {payload.get('source_duration_s', 0):.3f}s")
        lines.append("")
        lines.append("| format | file | container | codec | rate | channels | "
                     "duration | status |")
        lines.append("|---|---|---|---|---|---|---|---|")
        for r in payload.get("results", []):
            p = r.get("probe", {})
            lines.append(f"| {r['id']} | {r.get('file') or '-'} | "
                         f"{p.get('container', '-')} | {p.get('codec', '-')} | "
                         f"{p.get('sample_rate', '-')} | {p.get('channels', '-')} | "
                         f"{p.get('duration', 0):.3f} | {r['status']} |")
        lines.append("")
        for r in payload.get("results", []):
            for problem in r.get("problems", []):
                lines.append(f"- {r['id']}: {problem}")
        lines.append("")

    # ---- verdict --------------------------------------------------------------
    device_problems = sum(len(d.get("problems", [])) for d in device)
    format_failed = sum(len(f.get("failed", [])) for f in formats)
    lines.append("## Verdict")
    lines.append("")
    lines.append(f"- Hard failures in the DSP matrix: **{total_fails}**")
    lines.append(f"- Anchors not met: **{len(failing_anchors)}**")
    lines.append(f"- Device sweeps with reported problems: **{device_problems}**")
    lines.append(f"- Formats failing verification: **{format_failed}**")

    report_path = Path(args.report) if args.report else root / "REPORT.md"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"report: {report_path}")
    print(f"dsp shards {len(dsp)}, device sweeps {len(device)}, formats {len(formats)}, "
          f"hard fails {total_fails}, anchors failed {len(failing_anchors)}, "
          f"device problems {device_problems}, format failures {format_failed}")

    if args.json_out:
        Path(args.json_out).write_text(json.dumps({
            "dsp": by_effect, "device": device, "formats": formats,
            "hard_fails": total_fails, "anchors_failed": len(failing_anchors),
        }, indent=1), encoding="utf-8")
    return 1 if (total_fails or format_failed) else 0


if __name__ == "__main__":
    raise SystemExit(main())
