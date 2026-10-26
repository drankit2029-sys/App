#!/usr/bin/env python3
"""Verdict engine for the TakeOne FX verification harness.

Reads the case manifests the JVM shards write, measures every render against
its dry reference, and produces per-case verdicts: universal invariants that
must hold for any audio processor, the directional expectations that are known
to be true for a given control, monotonicity evidence from the five-point
series, and the hand-authored anchors in expectations.json.

Usage:
  analyze.py --cases app/build/fxverify/<effect> --compact
  analyze.py --all --root fx-verify-runs/<run-id> --report REPORT.md
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import metrics as M  # noqa: E402

HERE = Path(__file__).resolve().parent
DEFAULT_EXPECTATIONS = HERE / "expectations.json"

SERIES_METRICS = [
    "cmp.nrmse", "cmp.rms_delta_db", "cmp.centroid_delta_hz",
    "cmp.rolloff_delta_hz", "cmp.flatness_delta", "cmp.length_ratio",
    "cmp.corr", "cmp.band_delta_db.63", "cmp.band_delta_db.250",
    "cmp.band_delta_db.1000", "cmp.band_delta_db.4000",
    "cmp.band_delta_db.8000", "wet.crest_db", "wet.rms_db",
    "cmp.wet_probe.impulse_tail_db_delta", "cmp.wet_probe.gap_floor_db_delta",
    "cmp.wet_probe.hum_energy_db_delta", "cmp.wet_probe.sibilance_db_delta",
    "cmp.wet_probe.thd_db_delta", "cmp.wet_probe.fundamental_hz",
    "cmp.wet_probe.plosive_energy_db_delta",
]


def load_json(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def dotted(record: dict, key: str):
    """Reads 'cmp.band_delta_db.8000' style keys out of a nested record."""
    node = record
    parts = key.split(".")
    for i, part in enumerate(parts):
        if isinstance(node, dict) and part in node:
            node = node[part]
        else:
            # band keys contain dots themselves, rejoin the remainder
            rest = ".".join(parts[i:])
            if isinstance(node, dict) and rest in node:
                return node[rest]
            return None
    return node


def silence_ratio_of(x: np.ndarray, sr: int, start_frac: float) -> float:
    a = int(len(x) * start_frac)
    tail = x[a:]
    if tail.size == 0:
        return 1.0
    rms = float(np.sqrt(np.mean(tail ** 2)))
    return rms


# ------------------------------------------------------------------- invariants

def invariant_checks(case: dict, rec: dict, expectations: dict) -> tuple[list, list, list]:
    fails, warns, infos = [], [], []
    inv = expectations["invariants"]
    effect = case.get("effect", "")
    wet_basic = rec["wet"]

    if case.get("error"):
        fails.append(f"render threw {case['error']}")
    if case.get("skipped"):
        infos.append(f"skipped: {case['skipped']}")

    if wet_basic["nonfinite"] > 0:
        fails.append(f"{wet_basic['nonfinite']} non-finite samples in the render")

    peak = wet_basic["peak"]
    if peak > inv["hard_peak_fail"]:
        fails.append(f"peak {peak:.3f} exceeds the absurdity guard")
    elif peak > inv["max_wet_peak"] + 1e-3 and effect not in expectations["clipping_allowed"]:
        warns.append(f"peak {peak:.3f} above full scale for a non-clipping effect")

    if not case.get("offlineOnly", False):
        if case.get("outFrames") != case.get("inFrames"):
            fails.append(f"in-place effect changed length "
                         f"{case.get('inFrames')} -> {case.get('outFrames')}")

    cmp_rec = rec["cmp"]
    dry_rms_db = rec["dry"]["rms_db"]
    wet_rms_db = wet_basic["rms_db"]
    if (dry_rms_db > -40.0 and wet_rms_db < inv["silence_floor_db"]
            and effect not in expectations["silence_allowed"]):
        fails.append(f"render is silent (wet {wet_rms_db:.1f} dBFS from dry {dry_rms_db:.1f})")

    if case.get("isDefaultValue") and case.get("kind") == "param":
        if cmp_rec["max_abs_diff"] > inv["neutral_identity_tolerance"]:
            infos.append("default value is not a no-op (expected for some effects)")

    return fails, warns, infos


# ------------------------------------------------------------ expectation rules

def inferred_expectation(case: dict) -> tuple[str, float, str] | None:
    """The directional claim that holds for this control by construction."""
    name = f"{case.get('paramName', '')} {case.get('param', '')}".lower()
    unit = (case.get("unit") or "").strip().lower()
    ptype = case.get("type", "")

    if any(k in name for k in ("freq", "cutoff", "f1", "f2", "center")):
        return ("cmp.centroid_delta_hz", 20.0, "a frequency control must move the spectral centroid")
    if unit == "db" or any(k in name for k in (
            "gain", "level", "amp", "vol", "makeup", "target", "ceiling",
            "thresh", "floor", "range", "depth")):
        return ("cmp.rms_delta_db", 0.5, "a level-style control must change loudness")
    if any(k in name for k in ("rate", "speed", "tempo", "percent", "factor", "repeats")):
        return ("cmp.length_ratio", 0.02, "a time-style control must change render length")
    if any(k in name for k in (
            "mix", "wet", "dry", "amount", "intensity", "sens", "reduction",
            "drive", "decay", "size", "time", "delay", "release", "attack",
            "hold", "width", "pan", "phase", "q", "smooth")):
        return ("cmp.nrmse", 0.02, "an amount-style control must change the signal")
    if ptype in ("BOOLEAN", "ENUM"):
        return ("cmp.nrmse", 0.01, "switching this option must change the signal")
    return None


def evaluate_case(case: dict, rec: dict, expectations: dict) -> dict:
    fails, warns, infos = invariant_checks(case, rec, expectations)
    cmp_rec = rec["cmp"]
    changed = (cmp_rec["max_abs_diff"] > 1e-6
               or abs(cmp_rec["length_delta_frames"]) > 0)

    expected = inferred_expectation(case)
    verdict = "ok"
    evidence = {}
    if not changed:
        # Nothing moved, so there is no expectation to judge: this is either a
        # neutral setting or a control that did not reach the audio at all.
        verdict = "no_change"
        if not case.get("isDefaultValue"):
            warns.append("no measurable change from this control")
    elif expected:
        key, min_abs, why = expected
        value = dotted(rec, key)
        evidence = {"expectation": why, "metric": key,
                    "value": None if value is None else round(float(value), 4),
                    "min_abs": min_abs}
        if value is None:
            verdict = "weak"
        elif abs(float(value)) >= min_abs:
            verdict = "single_point_ok"
        else:
            verdict = "expectation_not_met"
            warns.append(f"{why}: |{key}| = {abs(float(value)):.4f} < {min_abs}")
    else:
        verdict = "weak"

    if fails:
        verdict = "fail"

    return {
        "case": case.get("id"),
        "kind": case.get("kind"),
        "param": case.get("param"),
        "value": case.get("valueRaw"),
        "valueDisplay": case.get("valueDisplay"),
        "probe": case.get("probe"),
        "mode": case.get("mode"),
        "changed": bool(changed),
        "metrics": {
            "nrmse": round(cmp_rec["nrmse"], 6),
            "corr": round(cmp_rec["corr"], 6),
            "max_abs_diff": round(cmp_rec["max_abs_diff"], 8),
            "rms_delta_db": round(cmp_rec["rms_delta_db"], 4),
            "peak_delta_db": round(cmp_rec["peak_delta_db"], 4),
            "centroid_delta_hz": round(cmp_rec["centroid_delta_hz"], 3),
            "length_ratio": round(cmp_rec["length_ratio"], 6),
            "wet_peak_db": round(rec["wet"]["peak_db"], 3),
            "wet_rms_db": round(rec["wet"]["rms_db"], 3),
            "wet_crest_db": round(rec["wet"]["crest_db"], 3),
            "wet_transients": rec["wet"]["transients"],
            "wet_clip_ratio": round(rec["wet"]["clip_ratio"], 6),
        },
        "verdict": verdict,
        "evidence": evidence,
        "fails": fails,
        "warns": warns,
        "infos": infos,
    }


# ----------------------------------------------------------------- series pass

def evaluate_series(cases: list[dict], records: dict) -> list[dict]:
    grouped: dict[tuple, list] = {}
    for case in cases:
        if case.get("kind") != "series":
            continue
        grouped.setdefault((case["param"], case["probe"]), []).append(case)

    out = []
    for (param, probe), members in sorted(grouped.items()):
        members.sort(key=lambda c: c["seriesIndex"])
        values = [float(m["valueRaw"]) for m in members]
        best = None
        for metric in SERIES_METRICS:
            series_values = []
            ok = True
            for m in members:
                v = dotted(records[m["id"]], metric)
                if v is None or not math.isfinite(float(v)):
                    ok = False
                    break
                series_values.append(float(v))
            if not ok or len(series_values) < 3:
                continue
            spread = max(series_values) - min(series_values)
            if spread <= 0:
                continue
            rho = M.spearman(values, series_values)
            if best is None or abs(rho) > abs(best["rho"]):
                best = {"metric": metric, "rho": rho, "spread": spread,
                        "first": series_values[0], "last": series_values[-1]}
        entry = {
            "param": param,
            "probe": probe,
            "values": members[0].get("paramName", param),
            "points": [m["valueRaw"] for m in members],
            "verdict": "monotone_ok" if best and abs(best["rho"]) >= 0.9
                       else "monotone_weak",
        }
        if best:
            entry.update({
                "metric": best["metric"],
                "rho": round(best["rho"], 3),
                "spread": round(best["spread"], 4),
                "delta": round(best["last"] - best["first"], 4),
            })
        out.append(entry)
    return out


# --------------------------------------------------------------------- anchors

def run_series_anchors(expectations: dict, series: list[dict]) -> list[dict]:
    index = {(s["param"], s["probe"]): s for s in series}
    results = []
    for anchor in expectations.get("series_anchors", []):
        entry = {"id": anchor["id"], "why": anchor["why"], "kind": "series"}
        match = index.get((anchor["seriesParam"], anchor.get("probe")))
        if not match:
            entry.update({"verdict": "missing", "detail": "series not rendered"})
            results.append(entry)
            continue
        if match.get("metric") != anchor["metric"]:
            entry.update({
                "verdict": "not_met",
                "detail": f"strongest monotone metric was {match.get('metric')} "
                          f"(rho {match.get('rho')}), not {anchor['metric']}",
            })
            results.append(entry)
            continue
        rho = match.get("rho", 0.0)
        delta = match.get("delta", 0.0)
        direction_ok = (rho > 0) if anchor["direction"] == "increasing" else (rho < 0)
        magnitude_ok = abs(delta) >= anchor["min_abs"]
        entry.update({
            "verdict": "ok" if direction_ok and magnitude_ok else "not_met",
            "metric": match.get("metric"),
            "rho": rho,
            "delta": delta,
            "required": f"{anchor['direction']} by >= {anchor['min_abs']}",
        })
        results.append(entry)
    return results


def _load_pair(case_dir: Path, case: dict):
    dry, sr = M.read_float_wav(case_dir / case["dry"])
    wet, _ = M.read_float_wav(case_dir / case["wet"])
    return dry, wet, sr


def run_bespoke_anchors(expectations: dict, case_dir: Path,
                        by_id: dict, records: dict) -> list[dict]:
    results = []
    for anchor in expectations.get("bespoke_anchors", []):
        entry = {"id": anchor["id"], "why": anchor["why"], "kind": "bespoke"}
        case = by_id.get(anchor["case"])
        if not case or not case.get("wet"):
            entry.update({"verdict": "missing",
                          "detail": f"case {anchor['case']} not found"})
            results.append(entry)
            continue
        rec = records[case["id"]]
        check = anchor["check"]
        ok, detail = False, ""
        try:
            if check == "peak_below":
                peak_db = rec["wet"]["peak_db"]
                limit = anchor["limit_db"] + anchor["tolerance_db"]
                ok = peak_db <= limit
                detail = f"peak {peak_db:.2f} dBFS vs limit {limit:.2f} dBFS"
            elif check == "gap_floor_lower":
                delta = dotted(rec, "cmp.wet_probe.gap_floor_db_delta")
                ok = delta is not None and delta <= -anchor["min_db"]
                detail = f"gap floor moved {delta:.2f} dB"
            elif check == "hum_lower":
                delta = dotted(rec, "cmp.wet_probe.hum_energy_db_delta")
                ok = delta is not None and delta <= -anchor["min_db"]
                detail = f"hum energy moved {delta:.2f} dB"
            elif check == "transients_fewer":
                ok = rec["wet"]["transients"] < rec["dry"]["transients"]
                detail = f"transients {rec['dry']['transients']} -> {rec['wet']['transients']}"
            elif check == "clip_ratio_lower":
                ok = rec["wet"]["clip_ratio"] < rec["dry"]["clip_ratio"]
                detail = f"clip ratio {rec['dry']['clip_ratio']:.4f} -> {rec['wet']['clip_ratio']:.4f}"
            elif check == "shorter":
                ok = rec["wet"]["frames"] < rec["dry"]["frames"]
                detail = f"frames {rec['dry']['frames']} -> {rec['wet']['frames']}"
            elif check == "tail_silent":
                dry, wet, sr = _load_pair(case_dir, case)
                tail = float(np.sqrt(np.mean(wet[int(len(wet) * 0.9):] ** 2)))
                ok = 20 * math.log10(max(tail, 1e-12)) <= -60.0
                detail = f"tail RMS {20 * math.log10(max(tail, 1e-12)):.1f} dBFS"
            elif check in ("reverse_corr", "invert_corr"):
                dry, wet, sr = _load_pair(case_dir, case)
                frames = min(len(dry), len(wet))
                ref = dry[:frames, 0][::-1] if check == "reverse_corr" else -dry[:frames, 0]
                test = wet[:frames, 0]
                corr = float(np.corrcoef(ref, test)[0, 1]) if test.std() > 1e-9 else 0.0
                ok = corr >= anchor["min_corr"]
                detail = f"correlation with the expected render {corr:.3f}"
            elif check == "interchannel_up":
                dry, wet, sr = _load_pair(case_dir, case)
                corr = M.stereo_metrics(wet).get("interchannel_corr", 0.0)
                ok = corr >= anchor["min_corr"]
                detail = f"inter-channel correlation {corr:.3f}"
            elif check == "rms_near_db":
                rms_db = rec["wet"]["rms_db"]
                ok = abs(rms_db - anchor["target_db"]) <= anchor["tolerance_db"]
                detail = f"RMS {rms_db:.2f} dBFS vs target {anchor['target_db']:.2f}"
            elif check == "channels_swapped":
                dry, wet, sr = _load_pair(case_dir, case)
                frames = min(len(dry), len(wet))
                if dry.shape[1] < 2 or wet.shape[1] < 2:
                    ok, detail = False, "stereo pair required"
                else:
                    left_to_right = float(np.corrcoef(dry[:frames, 0], wet[:frames, 1])[0, 1])
                    right_to_left = float(np.corrcoef(dry[:frames, 1], wet[:frames, 0])[0, 1])
                    ok = (left_to_right >= anchor["min_corr"]
                          and right_to_left >= anchor["min_corr"])
                    detail = (f"L->R {left_to_right:.3f}, R->L {right_to_left:.3f}")
            elif check == "identity":
                diff = rec["cmp"]["max_abs_diff"]
                ok = diff < 1e-6
                detail = f"max |wet - dry| = {diff:.3e}"
            elif check == "not_silent":
                ok = rec["wet"]["rms_db"] > -60.0
                detail = f"RMS {rec['wet']['rms_db']:.2f} dBFS"
            else:
                detail = f"unknown check {check}"
        except Exception as exc:  # noqa: BLE001 - a failed check is a result
            ok, detail = False, f"{type(exc).__name__}: {exc}"
        entry.update({"verdict": "ok" if ok else "not_met", "detail": detail})
        results.append(entry)
    return results


# ------------------------------------------------------------------------ main

def analyse_dir(case_dir: Path, expectations: dict, keep_anomalies: bool) -> dict:
    manifest = load_json(case_dir / "cases.json")
    effect = manifest["effect"]
    cases = manifest["cases"]
    records: dict[str, dict] = {}
    dry_cache: dict[str, tuple] = {}
    anomalies = []

    for case in cases:
        if not case.get("wet"):
            records[case["id"]] = {"error": case.get("error", "not rendered")}
            continue
        probe = case["probe"]
        # Cache by the reference file itself, not by probe name: the same probe
        # is reused across cases, but a manifest may legitimately point two cases
        # at different references.
        dry_key = case["dry"]
        if dry_key not in dry_cache:
            dry_cache[dry_key] = M.read_float_wav(case_dir / case["dry"])
        dry, sr = dry_cache[dry_key]
        wet, _ = M.read_float_wav(case_dir / case["wet"])
        case = dict(case, effect=effect)
        rec = {
            "dry": M.basic_metrics(dry, sr),
            "wet": M.basic_metrics(wet, sr),
            "cmp": M.compare(dry, wet, sr, probe),
        }
        rec["verdict"] = evaluate_case(case, rec, expectations)
        records[case["id"]] = rec
        # Keep the render itself only when the case failed or missed its
        # expectation: everything else is already summarised by the metrics.
        if rec["verdict"]["verdict"] in ("fail", "expectation_not_met"):
            anomalies.append(case["id"])

    verdicts = [records[c["id"]]["verdict"] for c in cases
                if isinstance(records.get(c["id"]), dict)
                and "verdict" in records[c["id"]]]
    series = evaluate_series(cases, {k: v for k, v in records.items() if "wet" in v})
    by_id = {c["id"]: dict(c, effect=effect) for c in cases}
    anchors = run_series_anchors(expectations, series)
    anchors += run_bespoke_anchors(expectations, case_dir, by_id, records)

    summary = {
        "effect": effect,
        "title": manifest.get("title"),
        "cases": len(cases),
        "rendered": manifest.get("rendered"),
        "errors": manifest.get("errors", []),
        "fails": sum(1 for v in verdicts if v["verdict"] == "fail"),
        "expectation_not_met": sum(1 for v in verdicts if v["verdict"] == "expectation_not_met"),
        "single_point_ok": sum(1 for v in verdicts if v["verdict"] == "single_point_ok"),
        "weak": sum(1 for v in verdicts if v["verdict"] == "weak"),
        "no_change": sum(1 for v in verdicts if v["verdict"] == "no_change"),
        "series_ok": sum(1 for s in series if s["verdict"] == "monotone_ok"),
        "series_total": len(series),
        "anchors_ok": sum(1 for a in anchors if a["verdict"] == "ok"),
        "anchors_total": len(anchors),
        "params_covered": len({v["param"] for v in verdicts}),
    }

    out = {
        "summary": summary,
        "verdicts": verdicts,
        "series": series,
        "anchors": anchors,
        "anomalies": anomalies,
        "metrics": {
            cid: {
                "cmp": {k: v for k, v in rec["cmp"].items()
                        if not isinstance(v, dict) or k in ("wet_probe", "dry_probe")},
                "wet": rec["wet"],
                "dry": {"peak_db": rec["dry"]["peak_db"], "rms_db": rec["dry"]["rms_db"],
                        "transients": rec["dry"]["transients"],
                        "clip_ratio": rec["dry"]["clip_ratio"],
                        "frames": rec["dry"]["frames"]},
            } for cid, rec in records.items() if "wet" in rec
        },
    }

    with open(case_dir / "verdicts.json", "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=1)
    with open(case_dir / "metrics.json", "w", encoding="utf-8") as fh:
        json.dump(out["metrics"], fh, indent=1)

    if keep_anomalies and anomalies:
        dest = case_dir / "anomalies"
        dest.mkdir(exist_ok=True)
        for cid in anomalies:
            src = case_dir / "wet" / f"{cid}.wav"
            if src.is_file():
                shutil.copy2(src, dest / src.name)

    print(f"{effect}: {summary['cases']} cases | fails {summary['fails']} | "
          f"expectation_not_met {summary['expectation_not_met']} | "
          f"ok {summary['single_point_ok']} | weak {summary['weak']} | "
          f"no_change {summary['no_change']} | "
          f"series {summary['series_ok']}/{summary['series_total']} | "
          f"anchors {summary['anchors_ok']}/{summary['anchors_total']}")
    for anchor in anchors:
        if anchor["verdict"] != "ok":
            print(f"  anchor {anchor['verdict']}: {anchor['id']} - "
                  f"{anchor.get('detail', anchor.get('required', ''))}")
    if summary["fails"]:
        for v in verdicts:
            if v["verdict"] == "fail":
                print(f"  FAIL {v['case']}: {v['fails']}")
    return out


def write_report(root: Path, summaries: list[dict], report_path: Path) -> None:
    lines = ["# FX verification report", "",
             f"Effects analysed: {len(summaries)}", "",
             "| effect | cases | fail | expectation not met | 1-point ok | weak | no change | series | anchors |",
             "|---|---|---|---|---|---|---|---|---|"]
    for s in sorted(summaries, key=lambda s: s["effect"]):
        lines.append(
            f"| {s['effect']} | {s['cases']} | {s['fails']} | "
            f"{s['expectation_not_met']} | {s['single_point_ok']} | {s['weak']} | "
            f"{s['no_change']} | {s['series_ok']}/{s['series_total']} | "
            f"{s['anchors_ok']}/{s['anchors_total']} |")
    total_fails = sum(s["fails"] for s in summaries)
    lines += ["", f"Total hard failures: {total_fails}", ""]
    if total_fails:
        lines.append("## Failures")
        for s in summaries:
            if s["fails"]:
                lines.append(f"### {s['effect']}")
                for e in s["errors"]:
                    lines.append(f"- error: {e}")
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"report written to {report_path}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", action="append", default=[],
                    help="a shard directory containing cases.json")
    ap.add_argument("--root", default=".", help="run root for --all aggregation")
    ap.add_argument("--all", action="store_true",
                    help="analyse every shard directory under --root")
    ap.add_argument("--compact", action="store_true",
                    help="copy anomalies and delete the wet renders afterwards")
    ap.add_argument("--expectations", default=str(DEFAULT_EXPECTATIONS))
    ap.add_argument("--report", default=None)
    args = ap.parse_args()

    expectations = load_json(Path(args.expectations))
    dirs = [Path(p) for p in args.cases]
    root = Path(args.root)
    if args.all:
        dirs += sorted({p.parent for p in root.glob("*/cases.json")})

    if not dirs:
        print("nothing to analyse: pass --cases or --all", file=sys.stderr)
        return 2

    summaries = []
    for case_dir in dirs:
        if not (case_dir / "cases.json").is_file():
            print(f"skipping {case_dir}: no cases.json", file=sys.stderr)
            continue
        out = analyse_dir(case_dir, expectations, keep_anomalies=True)
        summaries.append(out["summary"])
        if args.compact:
            wet_dir = case_dir / "wet"
            if wet_dir.is_dir():
                shutil.rmtree(wet_dir)

    if args.report:
        write_report(root, summaries, Path(args.report))
    return 1 if any(s["fails"] for s in summaries) else 0


if __name__ == "__main__":
    raise SystemExit(main())
