"""Metric library for the TakeOne FX verification harness.

Reads the app's 32-bit float WAVs directly (no soundfile dependency) and
computes the measurements the verdicts are based on. Everything here is a pure
function of (samples, sample_rate, probe) so a verdict can always be traced
back to numbers.
"""

from __future__ import annotations

import math
import struct
import subprocess
import tempfile
from pathlib import Path

import numpy as np

OCTAVE_CENTRES = [31.5, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
EPS = 1e-12


# --------------------------------------------------------------------- WAV I/O

def read_float_wav(path: str | Path) -> tuple[np.ndarray, int]:
    """Reads a 32-bit float WAV into [frames, channels] float64."""
    raw = Path(path).read_bytes()
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
    if audio_format != 3 or bits != 32:
        raise ValueError(
            f"{path}: expected IEEE float 32-bit, got format={audio_format} bits={bits}")
    usable = min(len(data), data_size)
    samples = np.frombuffer(data[:usable - (usable % 4)], dtype="<f4").astype(np.float64)
    frames = len(samples) // channels
    return samples[:frames * channels].reshape(frames, channels), sample_rate


def write_float_wav(path: str | Path, data: np.ndarray, sample_rate: int) -> None:
    data = np.atleast_2d(data)
    if data.shape[0] == 1 and data.shape[1] > 1:
        data = data.T
    frames, channels = data.shape
    payload = data.astype("<f4").tobytes()
    header = b"RIFF" + struct.pack("<I", 36 + len(payload)) + b"WAVEfmt "
    header += struct.pack("<IHHIIHH", 16, 3, channels, sample_rate,
                          sample_rate * channels * 4, channels * 4, 32)
    header += b"data" + struct.pack("<I", len(payload))
    Path(path).write_bytes(header + payload)


def decode_to_float_wav(path: str | Path, sample_rate: int = 48000) -> tuple[np.ndarray, int]:
    """Decodes any container to float WAV via ffmpeg (used for lossy formats)."""
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "decoded.wav"
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-i", str(path),
             "-acodec", "pcm_f32le", "-ar", str(sample_rate), str(out)],
            check=True, capture_output=True)
        return read_float_wav(out)


# ------------------------------------------------------------------ basic stats

def _db(x: float) -> float:
    return 10.0 * math.log10(max(float(x), EPS))


def _peak_db(x: float) -> float:
    return 20.0 * math.log10(max(float(x), EPS))


def basic_metrics(x: np.ndarray, sr: int) -> dict:
    mono = x.mean(axis=1) if x.shape[1] > 1 else x[:, 0]
    frames = x.shape[0]
    peak = float(np.max(np.abs(x))) if frames else 0.0
    rms = float(np.sqrt(np.mean(x ** 2))) if frames else 0.0
    out = {
        "frames": frames,
        "channels": x.shape[1],
        "sample_rate": sr,
        "duration_s": frames / sr if sr else 0.0,
        "peak": peak,
        "peak_db": _peak_db(peak),
        "rms": rms,
        "rms_db": _db(rms ** 2),
        "crest_db": _peak_db(peak) - _db(rms ** 2) if rms > 0 else 0.0,
        "dc_offset": float(np.mean(mono)) if frames else 0.0,
        "clip_ratio": float(np.mean(np.abs(x) >= 0.999)) if frames else 0.0,
        "nonfinite": int(np.count_nonzero(~np.isfinite(x))),
        "max_abs": peak,
    }
    block = max(1, sr // 100)
    nblocks = max(1, frames // block)
    trimmed = x[:nblocks * block]
    if trimmed.size:
        block_rms = np.sqrt((trimmed ** 2).reshape(nblocks, -1).mean(axis=1))
        silent = block_rms < 1e-3
        out["silence_ratio"] = float(np.mean(silent))
        longest = run = 0
        for s in silent:
            run = run + 1 if s else 0
            longest = max(longest, run)
        out["longest_silence_s"] = longest * block / sr
    else:
        out["silence_ratio"] = 1.0
        out["longest_silence_s"] = 0.0
    if frames > 1:
        d = np.abs(np.diff(mono))
        med = float(np.median(d)) if d.size else 0.0
        thresh = max(1e-4, 8.0 * med)
        above = d > thresh
        gap = max(1, int(0.01 * sr))
        count, last = 0, -gap - 1
        for i in np.nonzero(above)[0]:
            if i - last > gap:
                count += 1
            last = i
        out["transients"] = count
        out["max_step"] = float(np.max(d))
    else:
        out["transients"] = 0
        out["max_step"] = 0.0
    return out


def spectral_metrics(x: np.ndarray, sr: int) -> dict:
    mono = x.mean(axis=1) if x.shape[1] > 1 else x[:, 0]
    n = len(mono)
    if n == 0:
        return {"centroid_hz": 0.0, "rolloff95_hz": 0.0, "flatness": 0.0,
                "bands_db": {str(c): -240.0 for c in OCTAVE_CENTRES}}
    win = np.hanning(n)
    spec = np.fft.rfft(mono * win)
    power = np.abs(spec) ** 2
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    total = float(power.sum()) + EPS
    centroid = float((freqs * power).sum() / total)
    cumulative = np.cumsum(power)
    rolloff_idx = int(np.searchsorted(cumulative, 0.95 * cumulative[-1]))
    rolloff = float(freqs[min(rolloff_idx, len(freqs) - 1)])
    pos = power[power > 0]
    flatness = float(np.exp(np.mean(np.log(pos))) / (np.mean(pos) + EPS)) if pos.size else 0.0
    bands = {}
    for centre in OCTAVE_CENTRES:
        lo, hi = centre / math.sqrt(2), centre * math.sqrt(2)
        mask = (freqs >= lo) & (freqs < hi)
        bands[str(centre)] = _db(float(power[mask].sum() / total))
    return {"centroid_hz": centroid, "rolloff95_hz": rolloff,
            "flatness": flatness, "bands_db": bands}


def stereo_metrics(x: np.ndarray) -> dict:
    if x.shape[1] < 2:
        return {}
    left, right = x[:, 0], x[:, 1]
    if left.std() < EPS or right.std() < EPS:
        corr = 0.0
    else:
        corr = float(np.corrcoef(left, right)[0, 1])
    mid = (left + right) / 2.0
    side = (left - right) / 2.0
    return {
        "interchannel_corr": corr,
        "side_mid_db": _db(float(np.mean(side ** 2))) - _db(float(np.mean(mid ** 2))),
    }


# --------------------------------------------------------------- probe-specific

def _band_energy_db(x: np.ndarray, sr: int, lo: float, hi: float) -> float:
    mono = x.mean(axis=1) if x.shape[1] > 1 else x[:, 0]
    n = len(mono)
    if n == 0:
        return -240.0
    spec = np.fft.rfft(mono * np.hanning(n))
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    mask = (freqs >= lo) & (freqs < hi)
    return _db(float((np.abs(spec[mask]) ** 2).sum()))


def _peak_frequency(x: np.ndarray, sr: int, lo: float, hi: float) -> float:
    mono = x.mean(axis=1) if x.shape[1] > 1 else x[:, 0]
    n = len(mono)
    if n == 0:
        return 0.0
    spec = np.abs(np.fft.rfft(mono * np.hanning(n)))
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    mask = (freqs >= lo) & (freqs <= hi)
    if not mask.any():
        return 0.0
    idx = np.argmax(spec[mask])
    return float(freqs[mask][idx])


def probe_metrics(x: np.ndarray, sr: int, probe: str) -> dict:
    """Metrics that only make sense for the probe the case was rendered on."""
    out: dict = {}
    n = x.shape[0]
    if probe == "impulse":
        tails = []
        for hit in (0.5, 3.5, 6.5, 9.5):
            start = int((hit + 0.05) * sr)
            end = min(n, int((hit + 2.0) * sr))
            if start < end:
                tails.append(_db(float(np.mean(x[start:end] ** 2))))
        out["impulse_tail_db"] = float(np.mean(tails)) if tails else -240.0
    elif probe == "hum":
        out["hum_energy_db"] = _db(
            sum(10 ** (_band_energy_db(x, sr, f - 3, f + 3) / 10.0)
                for f in (50.0, 100.0, 150.0, 200.0)))
    elif probe == "gaps":
        windows = [(2.05, 2.95), (6.05, 7.45), (10.05, 10.55)]
        vals = []
        for lo, hi in windows:
            a, b = int(lo * sr), min(n, int(hi * sr))
            if a < b:
                vals.append(_db(float(np.mean(x[a:b] ** 2))))
        out["gap_floor_db"] = float(np.mean(vals)) if vals else -240.0
    elif probe == "sibilant":
        out["sibilance_db"] = _band_energy_db(x, sr, 5500, 9500)
        out["body_db"] = _band_energy_db(x, sr, 700, 1400)
    elif probe == "tone":
        out["fundamental_hz"] = _peak_frequency(x, sr, 200, 2000)
        fundamental = out["fundamental_hz"]
        if fundamental > 0:
            band = _band_energy_db(x, sr, fundamental - 40, fundamental + 40)
            total = _db(float(np.sum(x ** 2)))
            out["thd_db"] = total - band
    elif probe == "stereo":
        out.update(stereo_metrics(x))
    elif probe == "clipped":
        out["clip_ratio"] = float(np.mean(np.abs(x) >= 0.95))
        out["odd_harmonic_db"] = _band_energy_db(x, sr, 1100, 1300)
    elif probe == "plosive":
        windows = [(1.0, 1.35), (3.0, 3.35), (5.0, 5.35), (7.0, 7.35)]
        vals = []
        for lo, hi in windows:
            a, b = int(lo * sr), min(n, int(hi * sr))
            if a < b:
                vals.append(_db(float(np.mean(x[a:b] ** 2))))
        out["plosive_energy_db"] = float(np.mean(vals)) if vals else -240.0
    return out


# ------------------------------------------------------------------ comparisons

def compare(dry: np.ndarray, wet: np.ndarray, sr: int, probe: str) -> dict:
    frames = min(dry.shape[0], wet.shape[0])
    d = dry[:frames]
    w = wet[:frames]
    diff = w - d
    dry_energy = float(np.sum(d ** 2))
    out = {
        "length_delta_frames": int(wet.shape[0] - dry.shape[0]),
        "length_ratio": wet.shape[0] / dry.shape[0] if dry.shape[0] else 0.0,
        "max_abs_diff": float(np.max(np.abs(diff))) if frames else 0.0,
        "rms_diff": float(np.sqrt(np.mean(diff ** 2))) if frames else 0.0,
        "nrmse": float(np.sqrt(np.sum(diff ** 2)) / math.sqrt(dry_energy + EPS)),
        "rms_delta_db": _db(float(np.mean(w ** 2))) - _db(float(np.mean(d ** 2))) if frames else 0.0,
        "peak_delta_db": _peak_db(float(np.max(np.abs(w)))) - _peak_db(float(np.max(np.abs(d)))) if frames else 0.0,
    }
    if frames and d.std() > EPS and w.std() > EPS:
        out["corr"] = float(np.corrcoef(d.mean(axis=1), w.mean(axis=1))[0, 1])
    else:
        out["corr"] = 1.0 if out["max_abs_diff"] < 1e-6 else 0.0
    dry_spec = spectral_metrics(d, sr)
    wet_spec = spectral_metrics(w, sr)
    out["centroid_delta_hz"] = wet_spec["centroid_hz"] - dry_spec["centroid_hz"]
    out["rolloff_delta_hz"] = wet_spec["rolloff95_hz"] - dry_spec["rolloff95_hz"]
    out["flatness_delta"] = wet_spec["flatness"] - dry_spec["flatness"]
    out["band_delta_db"] = {
        band: wet_spec["bands_db"][band] - dry_spec["bands_db"][band]
        for band in dry_spec["bands_db"]
    }
    dry_probe = probe_metrics(d, sr, probe)
    wet_probe = probe_metrics(w, sr, probe)
    for key, value in wet_probe.items():
        if isinstance(value, (int, float)) and key in dry_probe:
            out[f"{key}_delta"] = float(value) - float(dry_probe[key])
    out["wet_probe"] = wet_probe
    out["dry_probe"] = dry_probe
    return out


def spearman(values: list[float], metric: list[float]) -> float:
    """Rank correlation, implemented directly so numpy is the only dependency."""
    n = len(values)
    if n < 3:
        return 0.0

    def ranks(v: list[float]) -> np.ndarray:
        order = np.argsort(v)
        r = np.empty(n, dtype=float)
        r[order] = np.arange(n, dtype=float)
        return r

    a, b = ranks(values), ranks(metric)
    a, b = a - a.mean(), b - b.mean()
    denom = math.sqrt(float((a ** 2).sum()) * float((b ** 2).sum()))
    return float((a * b).sum() / denom) if denom > EPS else 0.0
