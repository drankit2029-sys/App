#!/usr/bin/env python3
"""Independent verification of the converted containers.

Reads the manifest the device format test wrote, then checks every produced file
with ffprobe/ffmpeg rather than trusting the app's own validateFinal: container
and codec identity, sample rate, channel count, duration, bitrate sanity, a full
decode with no errors, and a fidelity comparison against the source clip.

Usage:
  verify_formats.py --dir fx-verify-runs/<id>/fx-formats
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import metrics as M  # noqa: E402

EXPECTED = {
    "wav16":  {"codec": "pcm_s16le", "containers": {"wav"}, "lossless": "pcm16"},
    "wav24":  {"codec": "pcm_s24le", "containers": {"wav"}, "lossless": "pcm24"},
    "wav32":  {"codec": "pcm_f32le", "containers": {"wav"}, "lossless": "float"},
    "flac":   {"codec": "flac",      "containers": {"flac"}, "lossless": "float"},
    "m4a":    {"codec": "aac",       "containers": {"mov", "mp4", "m4a"},
               "lossless": None},
    "opus":   {"codec": "opus",      "containers": {"ogg", "matroska", "webm"},
               "lossless": None},
    "amr_nb": {"codec": "amr_nb",    "containers": {"mov", "mp4", "3gp"},
               "lossless": None, "sample_rate": 8000, "channels": 1},
    "amr_wb": {"codec": "amr_wb",    "containers": {"mov", "mp4", "3gp"},
               "lossless": None, "sample_rate": 16000, "channels": 1},
}


def ffprobe(path: Path) -> dict:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_format", "-show_streams",
         "-of", "json", str(path)],
        capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


def decode_errors(path: Path) -> str:
    out = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-f", "null", "-"],
        capture_output=True, text=True)
    return out.stderr.strip()


def decoded(path: Path, sample_rate: int) -> np.ndarray:
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / "dec.wav"
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-i", str(path),
             "-acodec", "pcm_f32le", "-ar", str(sample_rate), str(wav)],
            check=True, capture_output=True)
        data, _ = M.read_float_wav(wav)
        return data[:, 0] if data.shape[1] == 1 else data.mean(axis=1)


def band_energies(x: np.ndarray, sr: int) -> dict:
    spec = np.fft.rfft(x * np.hanning(len(x)))
    power = np.abs(spec) ** 2
    freqs = np.fft.rfftfreq(len(x), 1.0 / sr)
    out = {}
    for centre in (250, 500, 1000, 2000, 4000, 8000):
        lo, hi = centre / math.sqrt(2), centre * math.sqrt(2)
        mask = (freqs >= lo) & (freqs < hi)
        out[centre] = 10 * math.log10(max(float(power[mask].sum()), 1e-12))
    return out


def verify_one(entry: dict, work: Path, source: np.ndarray, source_sr: int,
               source_duration: float) -> dict:
    try:
        return _verify_one(entry, work, source, source_sr, source_duration)
    except Exception as exc:  # noqa: BLE001 - a failed check is a result
        return {"id": entry.get("id", "?"), "file": entry.get("file"),
                "checks": [], "status": "failed",
                "problems": [f"verification threw {type(exc).__name__}: {exc}"]}


def _verify_one(entry: dict, work: Path, source: np.ndarray, source_sr: int,
                source_duration: float) -> dict:
    result = {"id": entry["id"], "file": entry.get("file"),
              "checks": [], "problems": []}
    if entry.get("skipped"):
        result["status"] = "skipped"
        result["checks"].append({"check": "encoder availability",
                                 "ok": True, "detail": entry["skipped"]})
        return result
    if entry.get("error"):
        result["status"] = "failed"
        result["problems"].append(f"conversion threw {entry['error']}")
        return result

    path = work / entry["file"]
    if not path.is_file():
        result["status"] = "failed"
        result["problems"].append("produced file is missing")
        return result

    fmt_id = entry["effectiveId"]
    expect = EXPECTED.get(fmt_id)
    try:
        probe = ffprobe(path)
    except subprocess.CalledProcessError as exc:
        result["status"] = "failed"
        result["problems"].append(f"ffprobe failed: {exc.stderr[:200]}")
        return result

    streams = [s for s in probe.get("streams", []) if s.get("codec_type") == "audio"]
    if not streams:
        result["status"] = "failed"
        result["problems"].append("no audio stream in the container")
        return result
    stream = streams[0]
    container = probe.get("format", {}).get("format_name", "")
    duration = float(probe.get("format", {}).get("duration", 0.0) or 0.0)
    bit_rate = float(probe.get("format", {}).get("bit_rate", 0.0) or 0.0)

    def check(name: str, ok: bool, detail: str):
        result["checks"].append({"check": name, "ok": bool(ok), "detail": detail})
        if not ok:
            result["problems"].append(f"{name}: {detail}")

    if expect:
        check("container", any(c in container.split(",") for c in expect["containers"]),
              f"format_name={container}")
        check("codec", stream.get("codec_name") == expect["codec"],
              f"codec_name={stream.get('codec_name')} expected {expect['codec']}")
        want_sr = expect.get("sample_rate", source_sr)
        check("sample_rate", int(stream.get("sample_rate", 0)) == want_sr,
              f"{stream.get('sample_rate')} expected {want_sr}")
        want_ch = expect.get("channels", entry.get("inputChannels", 1))
        check("channels", int(stream.get("channels", 0)) == want_ch,
              f"{stream.get('channels')} expected {want_ch}")
    else:
        check("container", bool(container), f"format_name={container}")

    tolerance = 0.05 if (expect or {}).get("lossless") else 0.2
    if fmt_id.startswith("amr"):
        tolerance = 0.6
    check("duration", abs(duration - source_duration) <= tolerance,
          f"{duration:.3f}s vs source {source_duration:.3f}s (tol {tolerance})")

    errors = decode_errors(path)
    check("decodes_cleanly", errors == "", errors[:200] if errors else "no errors")

    samples = decoded(path, int(stream.get("sample_rate", source_sr)))
    rms = float(np.sqrt(np.mean(samples ** 2))) if samples.size else 0.0
    check("not_silent", rms > 1e-3, f"decoded RMS {rms:.6f}")

    frames = min(len(samples), len(source))
    if frames:
        a, b = source[:frames], samples[:frames]
        if a.std() > 1e-9 and b.std() > 1e-9:
            corr = float(np.corrcoef(a, b)[0, 1])
        else:
            corr = 0.0
        nrmse = float(np.sqrt(np.mean((a - b) ** 2)) / (np.sqrt(np.mean(a ** 2)) + 1e-12))
        kind = (expect or {}).get("lossless")
        if kind == "float":
            check("fidelity", corr > 0.9999 and nrmse < 1e-4,
                  f"corr={corr:.6f} nrmse={nrmse:.2e} (lossless float)")
        elif kind in ("pcm16", "pcm24"):
            limit = 2.0e-3 if kind == "pcm16" else 1.0e-4
            check("fidelity", corr > 0.999 and nrmse < limit,
                  f"corr={corr:.6f} nrmse={nrmse:.2e} (limit {limit:.0e})")
        else:
            # Lossy codecs reorder phase freely, so waveform correlation means
            # nothing; band energies are the honest comparison, and the bands a
            # codec cannot carry are excluded rather than punished.
            src_bands = band_energies(a, source_sr)
            out_bands = band_energies(b, int(stream.get("sample_rate", source_sr)))
            ceiling = {"amr_nb": 3500.0, "amr_wb": 8000.0}.get(fmt_id, 16000.0)
            limit = {"amr_nb": 16.0}.get(fmt_id, 12.0)
            worst = max(abs(out_bands[c] - src_bands[c])
                        for c in src_bands if float(c) <= ceiling)
            check("spectral_similarity", worst <= limit,
                  f"worst band delta {worst:.1f} dB (bands ≤ {ceiling:.0f} Hz, "
                  f"limit {limit:.1f} dB)")

    if bit_rate:
        nominal = entry.get("bytes", 0) * 8 / max(duration, 1e-3)
        check("bitrate_sane", 0.4 * nominal <= bit_rate <= 2.5 * nominal,
              f"bit_rate={bit_rate:.0f} vs measured {nominal:.0f}")

    result["status"] = "ok" if not result["problems"] else "failed"
    result["probe"] = {
        "container": container,
        "codec": stream.get("codec_name"),
        "sample_rate": stream.get("sample_rate"),
        "channels": stream.get("channels"),
        "duration": duration,
        "bit_rate": bit_rate,
    }
    return result


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True, help="directory holding formats.json")
    ap.add_argument("--out", default=None, help="where to write the verdict JSON")
    args = ap.parse_args()

    work = Path(args.dir)
    payload: dict = {"source_duration_s": 0.0, "results": [], "failed": [],
                     "skipped": []}
    out = Path(args.out) if args.out else work / "verify_formats.json"
    try:
        manifest = json.loads((work / "formats.json").read_text(encoding="utf-8"))
        source_path = work / manifest.get("input", "input.wav")
        source_data, source_sr = M.read_float_wav(source_path)
        source = source_data[:, 0] if source_data.shape[1] == 1 else source_data.mean(axis=1)
        source_duration = len(source) / source_sr
        results = [verify_one(entry, work, source, source_sr, source_duration)
                   for entry in manifest["results"]]
        payload = {"source_duration_s": source_duration, "results": results,
                   "failed": [r["id"] for r in results if r["status"] == "failed"],
                   "skipped": [r["id"] for r in results if r["status"] == "skipped"]}
    except Exception as exc:  # noqa: BLE001 - report rather than crash
        payload["error"] = f"{type(exc).__name__}: {exc}"
        print(f"verification could not run: {payload['error']}")

    for r in payload["results"]:
        flag = {"ok": "PASS", "skipped": "SKIP", "failed": "FAIL"}[r["status"]]
        print(f"[{flag}] {r['id']:<8} {r.get('file', ''):<24} "
              f"{r.get('probe', {}).get('codec', ''):<10} "
              f"{r.get('probe', {}).get('container', '')}")
        for problem in r["problems"]:
            print(f"        {problem}")

    out.write_text(json.dumps(payload, indent=1), encoding="utf-8")
    print(f"\nfailed: {payload['failed']}  skipped: {payload['skipped']}")
    return 1 if payload["failed"] or payload.get("error") else 0


if __name__ == "__main__":
    raise SystemExit(main())
