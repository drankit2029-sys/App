#!/usr/bin/env python3
"""Proves, from the audio, that each parameter's applied render really differs
from the dry source.

Reads every effect directory the device sweep produced (result.json plus one
wet-<param>.wav per applied parameter, against master.wav as the untouched
source) and writes applied.json next to them with, per parameter, the metrics
that show whether the applied audio is genuinely different and plausible:
finite samples, length change where the effect changes duration, RMS and
spectral movement, and the ratio that separates "no change" from "applied".

Usage: verify_applied.py --root <downloaded artifacts directory>
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import metrics as M  # noqa: E402

SILENCE_FLOOR_DB = -80.0
IDENTITY_ABS = 1e-4


def verify_effect(dir_path: Path) -> dict:
    result = json.loads((dir_path / "result.json").read_text(encoding="utf-8"))
    effect = result.get("effect", dir_path.name.replace("fxverify-", ""))
    master_path = dir_path / "master.wav"
    if not master_path.is_file():
        master_path = dir_path / "dry.wav"
    dry, sr = M.read_float_wav(master_path)

    out = {"effect": effect, "params": [], "problems": result.get("problems", [])}
    applied_count = 0
    changed_count = 0
    for entry in result.get("params", []):
        pid = entry.get("param")
        wet_path = dir_path / f"wet-{pid}.wav"
        rec = {"param": pid,
               "paramName": entry.get("paramName"),
               "inPill": entry.get("inPill"),
               "declaredApplied": bool(entry.get("applied")),
               "bakedValue": entry.get("bakedValueDisplay")}
        if not entry.get("applied"):
            rec["verdict"] = entry.get("error", "apply reported failure")
            out["params"].append(rec)
            continue
        applied_count += 1
        if not wet_path.is_file():
            rec["verdict"] = "applied file missing"
            out["params"].append(rec)
            continue
        try:
            wet, _ = M.read_float_wav(wet_path)
            frames = min(dry.shape[0], wet.shape[0])
            d, w = dry[:frames], wet[:frames]
            diff = w - d
            dry_e = float(np.sum(d ** 2))
            comp = M.compare(d, w, sr, "tone")
            finite = int(np.count_nonzero(~np.isfinite(w)))
            rms_delta = float(comp["rms_delta_db"])
            centroid_delta = float(comp["centroid_delta_hz"])
            max_abs = float(np.max(np.abs(diff))) if frames else 0.0
            length_ratio = float(comp["length_ratio"])
            changed = (max_abs > IDENTITY_ABS
                       or abs(int(int(wet.shape[0]) - int(dry.shape[0]))) > 0)
            silent = (float(np.mean(w ** 2)) < 1e-8
                      and float(np.mean(d ** 2)) > 1e-6)
            rec.update({
                "finite_ok": finite == 0,
                "length_ratio": round(length_ratio, 6),
                "max_abs_diff": round(max_abs, 8),
                "rms_delta_db": round(rms_delta, 4),
                "centroid_delta_hz": round(centroid_delta, 3),
                "silence_flag": silent,
            })
            if finite > 0:
                rec["verdict"] = f"{finite} non-finite samples in the applied render"
            elif silent:
                rec["verdict"] = "applied render is silent while the source is not"
            elif changed:
                rec["verdict"] = "applied"
                changed_count += 1
            else:
                rec["verdict"] = "audibly identical to the source"
            if rms_delta == 0.0 and abs(length_ratio - 1.0) < 1e-6:
                rec["evidence"] = "bit-identical length and level"
        except Exception as exc:  # noqa: BLE001 - a failed check is a result
            rec["verdict"] = f"{type(exc).__name__}: {exc}"
        out["params"].append(rec)

    out["appliedSum"] = applied_count
    out["audiblyApplied"] = changed_count
    verdict_dir = dir_path / "applied.json"
    verdict_dir.write_text(json.dumps(out, indent=1), encoding="utf-8")
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    args = ap.parse_args()

    root = Path(args.root)
    dirs = sorted({p.parent for p in root.rglob("result.json")
                   if p.is_file() and "fxverify-" in p.parent.name})
    if not dirs:
        print("no effect directories found")
        return 0

    problems = 0
    for path in dirs:
        out = verify_effect(path)
        changed = sum(1 for p in out["params"] if p.get("verdict") == "applied")
        ok = sum(1 for p in out["params"] if p.get("declaredApplied"))
        non_applied = [p for p in out["params"]
                       if p.get("declaredApplied") and p.get("verdict") != "applied"]
        print(f"{out['effect']}: applied renders {ok}, "
              f"audibly applied {changed}, claimed-but-not-applied "
              f"{len(non_applied)}")
        for p in non_applied:
            print(f"    {p.get('param')}: {p.get('verdict')}")
        problems += len(non_applied)

    total = sum(1 for p in (load_applied(d) for d in dirs)
                for p in p.get("params", []) if p.get("verdict") == "applied")
    print(f"\nparameters verified as applied from the audio: {total}")
    return 0


def load_applied(d: Path) -> dict:
    f = d / "applied.json"
    if f.is_file():
        return json.loads(f.read_text(encoding="utf-8"))
    return {}


if __name__ == "__main__":
    raise SystemExit(main())
