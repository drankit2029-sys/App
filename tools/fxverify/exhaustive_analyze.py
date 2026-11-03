#!/usr/bin/env python3
"""Analyse an exhaustive Truncate Silence run and write the case report."""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path

import numpy as np
import soundfile as sf


def load(path: Path):
    data, sr = sf.read(path, dtype="float32", always_2d=False)
    if getattr(data, "ndim", 1) > 1:
        data = data[:, 0]
    return data.astype(np.float32), int(sr)


def detect_spans(x: np.ndarray, sr: int, threshold_db: float,
                 min_ms: float) -> list[tuple[int, int]]:
    """Mirror TruncateSilenceProcessor's 10 ms RMS scan."""
    thr = 10.0 ** (threshold_db / 20.0)
    window = max(1, int(round(sr * 0.01)))
    min_silent = max(1, int(round(min_ms * 0.001 * sr)))
    spans: list[tuple[int, int]] = []
    silent_start = None
    for start in range(0, len(x), window):
        end = min(len(x), start + window)
        block = x[start:end]
        rms = math.sqrt(float(np.mean(block * block))) if len(block) else 0.0
        silent = rms < thr
        if silent and silent_start is None:
            silent_start = start
        elif not silent and silent_start is not None:
            if end - silent_start >= min_silent:
                spans.append((silent_start, end))
            silent_start = None
    if silent_start is not None and len(x) - silent_start >= min_silent:
        spans.append((silent_start, len(x)))
    return spans


def expected_frames(x: np.ndarray, sr: int, values: dict, params: dict,
                    targets: dict[int, str]) -> int:
    def natural(pid: str) -> float:
        p = params[pid]
        return values[pid] / float(p.get("div") or 1)

    action = int(values["ts_action"])
    thresh_db = natural("ts_thresh")
    min_ms = natural("ts_min")
    spans = detect_spans(x, sr, thresh_db, min_ms)
    trunc_frames = max(1, int(round(natural("ts_trunc") * 0.001 * sr)))
    comp_ratio = natural("ts_compress") / 100.0
    if len(x) < trunc_frames * 3:
        trunc_frames = max(1, min(len(x) // 8,
                                  int(values["ts_trunc"] * len(x) / 4000)))
    out = len(x)
    for start, end in spans:
        gap = end - start
        if action == 1:
            keep = max(1, int(round(gap * comp_ratio)))
        else:
            keep = trunc_frames
        keep = max(1, min(gap, keep))
        out -= gap - keep
    return out


def speech_rms(x: np.ndarray, sr: int) -> float:
    mask = np.abs(x) > 10.0 ** (-45.0 / 20.0)
    if not mask.any():
        return 0.0
    return float(np.sqrt(np.mean(x[mask] * x[mask])))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True,
                    help="effect folder containing plan.json and outputs")
    ap.add_argument("--plan", required=True)
    args = ap.parse_args()
    root = Path(args.root)
    plan = json.loads(Path(args.plan).read_text(encoding="utf-8"))
    params = {p["id"]: p for p in plan["params"]}
    targets = {int(k): v for k, v in plan.get("targets", {}).items()}
    input_file = root / plan["input"]
    input_data, sr = load(input_file)
    input_rms = speech_rms(input_data, sr)

    results = {}
    results_path = root / "results.json"
    render_errors: list[str] = []
    if results_path.is_file():
        rj = json.loads(results_path.read_text(encoding="utf-8"))
        for r in rj.get("cases", []):
            results[r["id"]] = r
        render_errors = [str(e) for e in rj.get("errors", [])]

    by_id = {c["id"]: c for c in plan["cases"]}
    rows: list[dict] = []
    failures: list[str] = []
    rendered = 0
    invariance_ok = 0
    invariance_bad = 0

    for case in plan["cases"]:
        cid = case["id"]
        kind = case.get("kind", "")
        values = case.get("values", {})
        display = case.get("values_display", {})
        action = int(values.get("ts_action", 0))
        row = {
            "id": cid,
            "kind": kind,
            "active": bool(case.get("active")),
            "render": bool(case.get("render")),
            "param": case.get("param", ""),
            "value_display": case.get("value_display", ""),
            "action": display.get("ts_action", ""),
            "threshold": display.get("ts_thresh", ""),
            "min_silence": display.get("ts_min", ""),
            "truncate": display.get("ts_trunc", ""),
            "compress": display.get("ts_compress", ""),
            "expected_frames": "",
            "actual_frames": "",
            "delta_ms": "",
            "peak": "",
            "speech_delta_db": "",
            "status": "",
            "reason": case.get("reason", ""),
            "output": case.get("output") or "",
            "compare_to": case.get("compare_to", ""),
        }
        if kind == "combination" and not case.get("render"):
            row["status"] = "disabled"
            rows.append(row)
            continue
        if not case.get("render"):
            row["status"] = "not_rendered"
            rows.append(row)
            continue

        output = case.get("output")
        output_path = root / output if output else None
        if output_path is None or not output_path.is_file():
            row["status"] = "FAIL"
            row["reason"] = "missing output"
            failures.append(f"{cid}: missing output {output}")
            rows.append(row)
            continue
        rendered += 1
        out, out_sr = load(output_path)

        if kind == "disabled":
            baseline_id = case.get("compare_to")
            baseline = by_id.get(baseline_id)
            baseline_path = root / baseline["output"] if baseline and baseline.get("output") else None
            if baseline_path is None or not baseline_path.is_file():
                row["status"] = "FAIL"
                row["reason"] = "missing baseline output"
                failures.append(f"{cid}: missing baseline {baseline_id}")
                rows.append(row)
                continue
            base, _ = load(baseline_path)
            n = min(len(base), len(out))
            diff = float(np.max(np.abs(base[:n] - out[:n]))) if n else 0.0
            same = (len(base) == len(out) and diff <= 1.0 / 32768.0)
            row["actual_frames"] = len(out)
            row["delta_ms"] = round(diff * 1000.0, 6)
            row["status"] = "PASS" if same else "FAIL"
            if same:
                invariance_ok += 1
            else:
                invariance_bad += 1
                failures.append(f"{cid}: inactive parameter changed output "
                                f"(max diff {diff})")
            rows.append(row)
            continue

        exp = expected_frames(input_data, sr, values, params, targets)
        delta_ms = (len(out) - exp) * 1000.0 / sr
        peak = float(np.max(np.abs(out))) if len(out) else 0.0
        out_rms = speech_rms(out, sr)
        rms_delta = (20.0 * math.log10((out_rms + 1e-9) /
                                       (input_rms + 1e-9)))
        finite = bool(np.all(np.isfinite(out)))
        # A high threshold can legitimately classify quiet speech as silence,
        # which raises the RMS of the speech that remains. Only a loss of
        # speech level is a failure; positive movement is expected evidence.
        ok = (finite and abs(delta_ms) <= 25.0 and peak <= 1.0
              and rms_delta >= -1.0)
        row.update({
            "expected_frames": exp,
            "actual_frames": len(out),
            "delta_ms": round(delta_ms, 2),
            "peak": round(peak, 5),
            "speech_delta_db": round(rms_delta, 2),
            "status": "PASS" if ok else "FAIL",
        })
        if not ok:
            failures.append(
                f"{cid}: frames {len(out)} vs {exp} ({delta_ms:+.1f} ms), "
                f"peak {peak:.3f}, speech {rms_delta:+.2f} dB, finite={finite}"
            )
        rows.append(row)

    fields = list(rows[0].keys()) if rows else []
    with (root / "analysis.csv").open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    plan_counts = plan.get("counts", {})
    report: list[str] = []
    report.append(f"# Exhaustive FX report — {plan['title']}\n")
    report.append("## Effect inspection\n")
    report.append(f"- Effect id: `{plan['effect']}`")
    report.append(f"- Input: `{plan['input']}` ({plan['input_meta']['duration_ms']} ms, "
                  f"{plan['input_meta']['frames']} frames at {sr} Hz)")
    report.append(f"- Input gaps: " + ", ".join(
        f"{g['length_ms']} ms (noise {g['noise_db']:.0f} dB)"
        for g in plan['input_meta'].get('gaps', [])))
    report.append("- Modes: Action = Truncate / Compress")
    report.append("- Mode-exclusive controls: Truncate To (Truncate only), "
                  "Compress To (Compress only)")
    report.append("")
    report.append("## Parameters\n")
    report.append("| Parameter | Type | Bounds | Default | Test values |")
    report.append("|---|---|---|---|---|")
    for p in plan["params"]:
        values = []
        if p.get("type") == "ENUM":
            values = list(p.get("enums") or [])
        elif p.get("type") == "BOOLEAN":
            values = ["Off", "On"]
        elif p.get("type") == "ACTION":
            values = ["(action)"]
        else:
            div = p.get("div") or 1
            raw = [p["min"], p["def"], p["max"]]
            uniq = []
            for v in raw:
                if v not in uniq:
                    uniq.append(v)
            unit = (p.get("unit") or "").strip()
            values = [f"{v/div:g}{unit}" for v in uniq]
        bounds = f"{p['min']}..{p['max']} raw"
        if p.get("type") not in ("ENUM", "BOOLEAN", "ACTION"):
            div = p.get("div") or 1
            unit = (p.get("unit") or "").strip()
            bounds = f"{p['min']/div:g}..{p['max']/div:g} {unit}"
        report.append(f"| {p['name']} (`{p['id']}`) | {p['type']} | {bounds} "
                      f"| {p['def']} | {', '.join(map(str, values))} |")
    report.append("")
    report.append("## Summary\n")
    report.append(f"- Individual cases: {plan_counts.get('individual', 0)}")
    report.append(f"- Disabled-parameter invariance cases: "
                  f"{plan_counts.get('disabled_invariance', 0)}")
    report.append(f"- Combinations listed: {plan_counts.get('combinations', 0)} "
                  f"({plan_counts.get('active_combinations', 0)} active, "
                  f"{plan_counts.get('disabled_combinations', 0)} disabled)")
    report.append(f"- Rendered outputs: {rendered}")
    report.append(f"- Invariance: {invariance_ok} passed, {invariance_bad} failed")
    report.append(f"- Hard failures: {len(failures)}")
    if render_errors:
        report.append(f"- Renderer errors: {len(render_errors)}")
    report.append("")

    report.append("## Individual parameters\n")
    report.append("| Case | Parameter | Value | Action | Output | Frames | Δ ms | Speech Δ dB | Status |")
    report.append("|---|---|---|---|---|---|---|---|---|")
    for row in rows:
        if row["kind"] != "individual":
            continue
        report.append(
            f"| {row['id']} | {row['param']} | {row['value_display']} "
            f"| {row['action']} | `{row['output']}` | {row['actual_frames']} "
            f"| {row['delta_ms']} | {row['speech_delta_db']} | {row['status']} |")
    report.append("")

    report.append("## Disabled-parameter invariance\n")
    report.append("| Case | Compared to | Output | Max diff | Status |")
    report.append("|---|---|---|---|---|")
    for case, row in zip(plan["cases"], rows):
        if case.get("kind") != "disabled":
            continue
        report.append(
            f"| {row['id']} | {row['compare_to']} | `{row['output']}` "
            f"| {row['delta_ms']} | {row['status']} |")
    report.append("")

    report.append(
        f"## All combinations ({plan_counts.get('active_combinations', 0)} active / "
        f"{plan_counts.get('disabled_combinations', 0)} disabled)\n")
    report.append("| # | Action | Threshold | Min | Truncate | Compress | Status | Output |")
    report.append("|---|---|---|---|---|---|---|---|")
    for case, row in zip(plan["cases"], rows):
        if case.get("kind") != "combination":
            continue
        status = row["status"]
        if status == "disabled":
            status = f"disabled: {row['reason']}"
        report.append(
            f"| {case['id']} | {row['action']} | {row['threshold']} "
            f"| {row['min_silence']} | {row['truncate']} | {row['compress']} "
            f"| {status} | `{row['output'] or ''}` |")
    report.append("")

    report.append("## Notes\n")
    report.append("- The expected-length simulation mirrors the processor's "
                  "10 ms RMS scan; all active cases must match within 25 ms.")
    report.append("- At Threshold = −20 dB the detector may treat quiet "
                  "speech as silence. That raises the RMS of the remaining "
                  "speech; the positive `Speech Δ dB` values are expected and "
                  "are not failures as long as speech is not lost.")
    report.append("")
    report.append("## Failures\n")
    if failures:
        for f in failures:
            report.append(f"- {f}")
    else:
        report.append("None.")
    report.append("")
    (root / "report.md").write_text("\n".join(report), encoding="utf-8")
    print(f"report: {root / 'report.md'}")
    print(f"analysis: {root / 'analysis.csv'}")
    print(f"cases={len(rows)} rendered={rendered} failures={len(failures)} "
          f"invariance_ok={invariance_ok} invariance_bad={invariance_bad}")
    return 1 if failures or render_errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
