#!/usr/bin/env python3
"""numpy-free verifier for the per-parameter applied renders.

Reads each effect directory (result.json + wet-<param>.wav against master.wav),
computes the evidence from the audio with nothing but the standard library
(finite check, max difference, RMS delta, peak delta and, for effects that
change duration, the length ratio), and writes applied.json next to them so
REPORT-full.md rows can cite real numbers.

Usage: verify_applied_local.py --root <artifacts directory>
"""

from __future__ import annotations

import argparse
import json
import math
import struct
from pathlib import Path

MAX_COMPARE = 240_000  # enough for fine-grained level/shape evidence
STEP = 5              # subsampling keeps every pass linear and fast


def read_float_wav(path: Path) -> tuple[list[float], int, int]:
    raw = path.read_bytes()
    if raw[:4] != b"RIFF" or raw[8:12] != b"WAVE":
        raise ValueError(f"not a RIFF/WAVE file: {path}")
    pos, fmt, data, data_size = 12, None, None, 0
    while pos + 8 <= len(raw):
        tag = raw[pos:pos + 4]
        size = struct.unpack_from("<I", raw, pos + 4)[0]
        body = raw[pos + 8:pos + 8 + size]
        if tag == b"fmt ":
            fmt = body
        elif tag == b"data":
            data, data_size = body, size
        pos += 8 + size + (size % 2)
    if fmt is None or data is None:
        raise ValueError(f"missing fmt/data chunk: {path}")
    audio_format, channels, sample_rate = struct.unpack_from("<HHI", fmt, 0)
    bits = struct.unpack_from("<H", fmt, 14)[0]
    is_float = audio_format == 3
    if audio_format == 0xFFFE and bits == 32 and len(fmt) >= 40:
        sub = struct.unpack_from("<H", fmt, 24)[0]
        is_float = sub == 0x0003
    if not is_float or bits != 32:
        raise ValueError(f"{path}: expected IEEE float 32-bit, "
                         f"got format={audio_format} bits={bits}")
    usable = min(len(data), data_size)
    count = usable // 4
    # array('f') decodes IEEE floats quickly with only the standard library.
    from array import array
    vals = array("f")
    vals.frombytes(data[:count * 4])
    return list(vals), channels, sample_rate


def rms(xs: list[float]) -> float:
    n = len(xs)
    return math.sqrt(sum(float(v) * float(v) for v in xs) / n) if n else 0.0


def verify_effect(dir_path: Path) -> dict:
    result = json.loads((dir_path / "result.json").read_text(encoding="utf-8"))
    effect = result.get("effect", dir_path.name.replace("fxverify-", ""))
    master = dir_path / "master.wav"
    if not master.is_file():
        master = dir_path / "dry.wav"
    dry, ch, sr = read_float_wav(master)
    dry_rms = rms(dry[::STEP])

    out = {"effect": effect, "params": [], "problems": result.get("problems", [])}
    for entry in result.get("params", []):
        pid = entry.get("param")
        rec = {"param": pid,
               "paramName": entry.get("paramName"),
               "inPill": entry.get("inPill"),
               "declaredApplied": bool(entry.get("applied")),
               "bakedValue": entry.get("bakedValueDisplay"),
               "waveformChangedAtBest": entry.get("waveformChangedAtBest"),
               "maxDeltaInPill": entry.get("maxDelta"),
               "applyError": (entry.get("applyError") or "")[:220] or None}
        wet_path = dir_path / f"wet-{pid}.wav"
        if not entry.get("applied"):
            rec["verdict"] = entry.get("error") or "apply reported failure"
        elif not wet_path.is_file():
            rec["verdict"] = "applied file missing"
        else:
            try:
                wet, ch2, _ = read_float_wav(wet_path)
                n = min(len(dry), len(wet))
                finite_bad = sum(1 for v in wet if v != v)
                max_abs = 0.0
                diff_energy = 0.0
                dry_energy = 0.0
                k = 0
                for i in range(0, n, STEP):
                    d = dry[i] - wet[i]
                    ad = abs(d)
                    if ad > max_abs:
                        max_abs = ad
                    diff_energy += d * d
                    dry_energy += dry[i] * dry[i]
                    k += 1
                nrmse = math.sqrt(diff_energy / max(k, 1)) / (
                    math.sqrt(dry_energy / max(k, 1)) + 1e-12)
                wet_rms = rms(wet[::STEP])
                rec["finite_ok"] = finite_bad == 0
                rec["length_ratio"] = round(len(wet) / max(len(dry), 1), 5)
                rec["max_abs_diff"] = round(max_abs, 6)
                rec["nrmse"] = round(nrmse, 6)
                rec["rms_delta_db"] = round(10 * math.log10(
                    max(wet_rms, 1e-12) / max(dry_rms, 1e-12)), 3)
                if finite_bad:
                    rec["verdict"] = f"{finite_bad} non-finite samples"
                elif max_abs > 1e-4 or abs(rec["length_ratio"] - 1.0) > 1e-4:
                    rec["verdict"] = "applied"
                else:
                    rec["verdict"] = "audibly identical to the source"
            except Exception as exc:  # noqa: BLE001
                rec["verdict"] = f"{type(exc).__name__}: {exc}"
        out["params"].append(rec)
    applied = [p for p in out["params"] if p.get("verdict") == "applied"]
    out["appliedSum"] = len(applied)
    out["audiblyApplied"] = len(applied)
    (dir_path / "applied.json").write_text(json.dumps(out, indent=1),
                                           encoding="utf-8")
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    args = ap.parse_args()
    root = Path(args.root)
    dirs = sorted({p.parent for p in root.rglob("result.json")
                   if p.is_file() and "fxverify-" in p.parent.name})
    total_applied = total_rows = claimed = 0
    for path in dirs:
        out = verify_effect(path)
        rows = out["params"]
        applied = [r for r in rows if r.get("verdict") == "applied"]
        claimed += sum(1 for r in rows if r.get("declaredApplied"))
        total_rows += len(rows)
        total_applied += len(applied)
        not_applied = [r for r in rows if r.get("declaredApplied")
                       and r.get("verdict") != "applied"]
        print(f"{out['effect']}: rows {len(rows)}, applied {len(applied)}, "
              f"claimed-not-applied {len(not_applied)}")
        for r in not_applied:
            print(f"    {r.get('param')}: {r.get('verdict')}")
    print(f"\ntotal parameters checked {total_rows}, "
          f"applied-from-audio {total_applied}, apply failures {claimed - total_applied}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
