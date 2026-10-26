#!/usr/bin/env python3
"""Self-test for the verification harness itself.

Runs before the expensive matrices: builds a small synthetic shard (dry/wet pairs
with known, deliberate defects) and asserts that the verdict engine reaches the
expected conclusion in each case. A bug in the analyzer would otherwise show up
as "everything passed", which is the one failure mode this whole harness exists
to avoid.

Usage: selftest.py
"""

from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import analyze as A  # noqa: E402
import metrics as M  # noqa: E402

SR = 48000
FRAMES = SR  # one second is enough to exercise every rule


def tone(frames: int = FRAMES, hz: float = 1000.0, amp: float = 0.3) -> np.ndarray:
    t = np.arange(frames) / SR
    return (np.sin(2 * np.pi * hz * t) * amp).reshape(-1, 1)


def write_case(work: Path, cases: list[dict], dry: np.ndarray,
               name: str, wet: np.ndarray, **meta) -> dict:
    dry_path = work / f"dry-{name}.wav"
    wet_path = work / f"wet-{name}.wav"
    M.write_float_wav(dry_path, dry, SR)
    M.write_float_wav(wet_path, wet, SR)
    case = {
        "id": name,
        "kind": "param",
        "param": "amp_gain",
        "paramName": "Gain",
        "type": "FLOAT",
        "unit": " dB",
        "valueRaw": 600,
        "valueDisplay": "+6.0 dB",
        "defaultRaw": 0,
        "isDefaultValue": False,
        "probe": "tone",
        "mode": "plain",
        "channels": 1,
        "inFrames": int(dry.shape[0]),
        "outFrames": int(wet.shape[0]),
        "dry": dry_path.name,
        "wet": wet_path.name,
        "offlineOnly": False,
    }
    case.update(meta)
    cases.append(case)
    return case


def main() -> int:
    expectations = A.load_json(Path(__file__).resolve().parent / "expectations.json")
    failures: list[str] = []

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        cases: list[dict] = []
        dry = tone()

        # 1. A plain +6 dB gain must read as changed with the level expectation met.
        write_case(work, cases, dry, "gain", dry * 2.0)
        # 2. A bit-identical render must read as no change.
        write_case(work, cases, dry, "same", dry.copy())
        # 3. A NaN in the render is a hard failure.
        nan = dry.copy()
        nan[10, 0] = np.nan
        write_case(work, cases, dry, "nan", nan,
                   inFrames=FRAMES + 1, outFrames=FRAMES + 1)
        # 4. An in-place effect that changed the length is a hard failure.
        write_case(work, cases, dry, "length", dry[: FRAMES // 2].copy())
        # 5. An effect that silences a loud signal is a hard failure.
        write_case(work, cases, dry, "silent", np.zeros_like(dry))
        # 6. An offline effect is allowed to change the length.
        write_case(work, cases, dry, "offline", dry[: FRAMES // 2].copy(),
                   offlineOnly=True)
        # 7. A loud but finite peak is a finding, not corruption.
        write_case(work, cases, dry, "explode", dry * 60.0)
        # 7b. A numeric blow-up is corruption: the DSP diverged.
        write_case(work, cases, dry, "blowup", dry * 5000.0)
        # 8. A reversed render must satisfy the bespoke reverse anchor.
        write_case(work, cases, dry, "tl_action_1_tone", dry[::-1].copy())
        cases[-1]["param"] = "tl_action"
        cases[-1]["type"] = "ENUM"
        # 9. A five-point gain series must be monotone in level.
        for i, gain in enumerate([0.5, 1.0, 2.0, 4.0, 8.0]):
            write_case(work, cases, dry, f"series_amp_gain_{i}", dry * gain,
                       kind="series", param="amp_gain", seriesIndex=i,
                       valueRaw=int(gain * 100))
        # 10. A 0 dB gain must be bit-transparent.
        write_case(work, cases, dry, "amp_gain_0_tone", dry.copy(), valueRaw=0)
        # 11. A channel swap must exchange left and right.
        left = tone(hz=440.0)
        right = tone(hz=660.0)
        stereo = np.hstack([left, right])
        write_case(work, cases, stereo, "st_swap_1_stereo",
                   np.hstack([right, left]),
                   param="st_swap", type="BOOLEAN", unit="", valueRaw=1,
                   probe="stereo", channels=2, inFrames=FRAMES, outFrames=FRAMES)

        manifest = {
            "effect": "selftest",
            "title": "Harness self-test",
            "sampleRate": SR,
            "frames": FRAMES,
            "offlineOnly": False,
            "requiresLearnedProfile": False,
            "primaryParam": "amp_gain",
            "paramCount": 1,
            "caseCount": len(cases),
            "rendered": len(cases),
            "probes": [{"name": "tone", "channels": 1, "frames": FRAMES,
                        "file": "dry-tone.wav", "description": "synthetic tone"}],
            "cases": cases,
            "errors": [],
        }
        (work / "cases.json").write_text(json.dumps(manifest), encoding="utf-8")

        out = A.analyse_dir(work, expectations, keep_anomalies=False)
        verdicts = {v["case"]: v["verdict"] for v in out["verdicts"]}

        def expect(case: str, verdict: str):
            got = verdicts.get(case)
            if got != verdict:
                failures.append(f"{case}: expected {verdict}, got {got}")

        expect("gain", "single_point_ok")
        expect("same", "no_change")
        expect("nan", "fail")
        expect("length", "fail")
        expect("blowup", "fail")
        if verdicts.get("offline") == "fail":
            failures.append("offline: a length-changing offline render must not fail")
        if verdicts.get("silent") == "fail":
            failures.append("silent: muting at an extreme setting is a finding, not corruption")
        if verdicts.get("explode") == "fail":
            failures.append("explode: a loud but finite peak is a finding, not corruption")
        silent_case = next(v for v in out["verdicts"] if v["case"] == "silent")
        if not any("silent" in w for w in silent_case["warns"]):
            failures.append("silent: the silence was not reported as a finding")
        loud_case = next(v for v in out["verdicts"] if v["case"] == "explode")
        if not any("above full scale" in w for w in loud_case["warns"]):
            failures.append("explode: the over-scale peak was not reported as a finding")

        series = {s["param"]: s for s in out["series"]}
        amp = series.get("amp_gain")
        if not amp or amp["verdict"] != "monotone_ok":
            failures.append(f"series: expected monotone_ok, got {amp}")
        elif abs(amp["rho"] - 1.0) > 1e-6:
            failures.append(f"series: expected perfect rank correlation, got {amp['rho']}")

        anchors = {a["id"]: a for a in out["anchors"]}
        if anchors.get("amplify_gain_lifts_level", {}).get("verdict") != "ok":
            failures.append(f"anchor amplify_gain_lifts_level did not fire: "
                            f"{anchors.get('amplify_gain_lifts_level')}")
        reverse = anchors.get("timeline_reverse_mirrors")
        if reverse is None or reverse.get("verdict") != "ok":
            failures.append(f"anchor timeline_reverse_mirrors not met: {reverse}")
        identity = anchors.get("amplify_is_transparent_at_zero")
        if identity is None or identity.get("verdict") != "ok":
            failures.append(f"anchor amplify_is_transparent_at_zero not met: {identity}")
        swap = anchors.get("stereo_swap_exchanges_channels")
        if swap is None or swap.get("verdict") != "ok":
            failures.append(f"anchor stereo_swap_exchanges_channels not met: {swap}")

    if failures:
        print("SELFTEST FAILED:")
        for f in failures:
            print(" -", f)
        return 1
    print("selftest: verdicts, series and anchors all behave as expected")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
