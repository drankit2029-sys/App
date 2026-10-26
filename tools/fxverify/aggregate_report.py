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


def load(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001 - a missing artifact is a finding, not a crash
        return None


def corr(a, b) -> float:
    """Pearson correlation over the shared prefix; lists, so the aggregator
    needs nothing but the standard library to run anywhere."""
    n = min(len(a), len(b))
    if n < 2:
        return 0.0
    xs = [float(v) for v in a[:n]]
    ys = [float(v) for v in b[:n]]
    mx, my = sum(xs) / n, sum(ys) / n
    dx = [v - mx for v in xs]
    dy = [v - my for v in ys]
    num = sum(p * q for p, q in zip(dx, dy))
    den = math.sqrt(sum(p * p for p in dx) * sum(q * q for q in dy))
    return num / den if den > 1e-12 else 0.0


def waveform_check(peaks: dict) -> dict:
    dry = [float(v) for v in peaks.get("dry", [])]
    wet = [float(v) for v in peaks.get("wet", [])]
    n = min(len(dry), len(wet))
    if n == 0:
        return {"verdict": "missing", "detail": "no peak arrays"}
    delta = [wet[i] - dry[i] for i in range(n)]
    changed = sum(1 for d in delta if abs(d) > 1e-3)
    return {
        "verdict": "changed" if changed else "unchanged",
        "bars": n,
        "bars_changed": changed,
        "bars_changed_pct": round(100.0 * changed / n, 1),
        "max_bar_delta": round(max(abs(d) for d in delta), 4),
        "mean_abs_delta": round(sum(abs(d) for d in delta) / n, 4),
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
        # The recording is pulled beside the effect directory, not inside it.
        video = path.parent / name
    if not video.is_file():
        found = list(path.parent.glob("*.mp4")) + list(path.glob("*.mp4"))
        video = found[0] if found else video
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


def device_parameter_rows(path: Path) -> list[dict]:
    """One row per parameter, since the run's unit of evidence is a parameter."""
    result = load(path / "result.json")
    if result is None:
        return []
    applied = load(path / "applied.json") or {}
    applied_by_param = {p.get("param"): p
                        for p in applied.get("params", [])}
    effect = result.get("effect", path.name.replace("fxverify-", ""))
    rows = []
    for entry in result.get("params", []):
        pid = entry.get("param")
        peaks = load(path / f"peaks-{pid}.json")
        wf = waveform_check(peaks) if peaks else {"verdict": "missing"}
        rec = applied_by_param.get(pid, {})
        rows.append({
            "effect": effect,
            "param": pid,
            "paramName": entry.get("paramName"),
            "inPill": entry.get("inPill"),
            "control": entry.get("control"),
            "steps": entry.get("steps"),
            "bakedValue": entry.get("bakedValueDisplay"),
            "declaredApplied": bool(entry.get("applied")),
            "appliedVerdict": rec.get("verdict"),
            "rms_delta_db": rec.get("rms_delta_db"),
            "max_abs_diff": rec.get("max_abs_diff"),
            "length_ratio": rec.get("length_ratio"),
            "maxDelta": entry.get("maxDelta"),
            "waveform": wf,
            "video": video_state(path.parent, {"videoName": entry.get("video")}),
            "problems": [p for p in (result.get("problems") or [])
                         if str(p).startswith(f"{pid}:")],
        })
    return rows


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", action="append", required=True,
                    help="run directory to aggregate; repeat for several roots")
    ap.add_argument("--report", default=None)
    ap.add_argument("--json-out", default=None)
    args = ap.parse_args()

    roots = [Path(r) for r in args.root]
    dsp_dirs = sorted({p.parent for r in roots for p in r.rglob("verdicts.json")})
    device_dirs = sorted({p.parent for r in roots for p in r.rglob("result.json")
                          if p.is_file()})
    format_files = sorted({p for r in roots for p in r.rglob("verify_formats.json")})

    dsp = [load(d / "verdicts.json") for d in dsp_dirs]
    dsp = [d for d in dsp if d]
    device = []
    for d in device_dirs:
        device.extend(device_parameter_rows(d))
    formats = [load(f) for f in format_files]
    formats = [f for f in formats if f]

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
    passed = evaluated = missing = 0
    for job in dsp:
        for anchor in job.get("anchors", []):
            if anchor["verdict"] == "missing":
                missing += 1
            elif anchor["verdict"] == "ok":
                passed += 1
                evaluated += 1
            else:
                evaluated += 1
                failing_anchors.append((job["summary"]["effect"], anchor))
    lines.append("### Anchors")
    lines.append(f"Physical assertions: {passed} passed of {evaluated} evaluated "
                 f"({missing} not applicable to the effect in question)")
    if failing_anchors:
        lines.append("")
        for effect, anchor in failing_anchors:
            lines.append(f"- **{effect} / {anchor['id']}**: {anchor['verdict']} - "
                         f"{anchor.get('detail', anchor.get('required', ''))}")
    lines.append("")

    # ---- device tier: one row per parameter -----------------------------------
    lines.append("## Device swings through the panel (one parameter at a time)")
    lines.append("")
    lines.append("| effect | parameter | edited in | control | dial states "
                 "captured | waveform in pill | bars changed | applied via "
                 "Apply path | audio after apply | RMS delta dB | length ratio |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|---|")
    for d in sorted(device, key=lambda d: (d.get("effect", ""), d.get("param", ""))):
        wf = d.get("waveform", {})
        line = (f"| {d.get('effect')} | {d.get('param')} ({d.get('paramName')}) | "
                f"{d.get('inPill')} | {d.get('control')} | {d.get('steps', 0)} | "
                f"{wf.get('verdict', '?')} | "
                f"{wf.get('bars_changed_pct', '-')}% | "
                f"{'yes' if d.get('declaredApplied') else 'no'} ("
                f"{d.get('bakedValue')}) | "
                f"{d.get('appliedVerdict', '-')} | "
                f"{d.get('rms_delta_db', '-')} | "
                f"{d.get('length_ratio', '-') if d.get('length_ratio') else '-'} |")
        lines.append(line)
    lines.append("")
    flagged = [d for d in device if d.get("problems")
               or d.get("appliedVerdict") not in (None, "applied")]
    if flagged:
        lines.append("### Device findings")
        for d in flagged:
            for p in d.get("problems", []):
                lines.append(f"- **{d.get('effect')}/{d.get('param')}**: {p}")
            if d.get("declaredApplied") and d.get("appliedVerdict") != "applied":
                lines.append(f"- **{d.get('effect')}/{d.get('param')}**: "
                             f"the apply path returned success but the audio reads "
                             f"\"{d.get('appliedVerdict')}\" "
                             f"(max|diff={d.get('max_abs_diff')})")
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
