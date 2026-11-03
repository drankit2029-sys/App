#!/usr/bin/env python3
"""Build the deterministic audio input for an exhaustive effect profile.

The profile describes alternating speech/silence spans. Speech is taken from
the committed Kokoro fixture; gaps may be pure silence or a low-level noise
floor so level-sensitive controls have something to detect.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

try:
    import soundfile as sf
except Exception as exc:  # pragma: no cover - exercised only without deps
    raise SystemExit("soundfile is required: pip install soundfile") from exc


def build_input(profile: dict, repo_root: Path, effect_dir: Path) -> dict:
    spec = profile["input"]
    src = repo_root / spec["source"]
    if not src.is_file():
        raise SystemExit(f"speech fixture missing: {src}")

    data, sr = sf.read(src, dtype="float32", always_2d=False)
    if data.ndim > 1:
        data = data[:, 0]
    if int(sr) != int(spec["sample_rate"]):
        raise SystemExit(
            f"fixture sample rate {sr} != profile {spec['sample_rate']}"
        )

    spans = [int(x) for x in spec["spans_ms"]]
    gap_noise = [float(x) for x in spec.get("gap_noise_db", [])]
    src_pos = int(spec.get("source_offset_ms", 0) * sr / 1000)
    rng = np.random.default_rng(0x5441)
    parts: list[np.ndarray] = []
    gaps: list[dict] = []
    pos = 0
    gap_index = 0

    for i, ms in enumerate(spans):
        n = max(1, int(round(sr * ms / 1000.0)))
        if i % 2 == 0:
            idx = (np.arange(n) + src_pos) % len(data)
            parts.append(data[idx].copy())
            src_pos = (src_pos + n) % len(data)
        else:
            db = gap_noise[gap_index] if gap_index < len(gap_noise) else -120.0
            if db <= -120.0:
                gap = np.zeros(n, dtype=np.float32)
            else:
                amp = float(10.0 ** (db / 20.0))
                gap = (rng.standard_normal(n) * amp).astype(np.float32)
            parts.append(gap)
            gaps.append({
                "index": gap_index,
                "start_frame": pos,
                "end_frame": pos + n,
                "length_ms": ms,
                "noise_db": db,
            })
            gap_index += 1
        pos += n

    out = np.concatenate(parts).astype(np.float32)
    effect_dir.mkdir(parents=True, exist_ok=True)
    out_path = effect_dir / spec["file"]
    sf.write(out_path, out, int(sr), subtype="FLOAT")

    meta = {
        "file": spec["file"],
        "sample_rate": int(sr),
        "frames": int(len(out)),
        "duration_ms": round(len(out) * 1000.0 / sr, 1),
        "source": spec["source"],
        "spans_ms": spans,
        "gaps": gaps,
        "peak": float(np.max(np.abs(out))) if len(out) else 0.0,
        "rms": float(np.sqrt(np.mean(out * out))) if len(out) else 0.0,
    }
    (effect_dir / "input_meta.json").write_text(
        json.dumps(meta, indent=2) + "\n", encoding="utf-8"
    )
    print(f"input: {out_path} ({meta['duration_ms']} ms, {len(out)} frames)")
    return meta
