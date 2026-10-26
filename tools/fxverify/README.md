# FX verification harness

Exhaustive verification that every parameter of every effect in the rack changes
the audio the way it is supposed to, that the UI behaves while it happens, and
that the app's container conversion is correct.

## What runs where

| Tier | Where | What it proves |
|---|---|---|
| 0 `FxParamInventoryTest` | JVM | The parameter set is read from the processors themselves, and the CI matrix matches it (`check_drift.py`) |
| 1 `FxParamMatrixDumpTest` + `analyze.py` | JVM, one shard per effect | Every parameter at min/default/max (every enum label, both boolean states), rendered against a probe that can actually show that parameter, measured against its dry reference |
| 2 `FxUiSweepDeviceTest` | emulator | The real panel driven through every parameter value, screen-recorded, one JPEG per value, plus the app's own waveform peaks before and after the bake |
| 3 `FormatMatrixDeviceTest` + `verify_formats.py` | emulator | The uploaded 32-bit float WAV converted into every container, then checked with ffprobe/ffmpeg instead of trusting the app |
| 4 `aggregate_report.py` | anywhere | Merges all of the above into one report, including the waveform check |

The DSP layer is pure Java, so tier 1 needs no emulator; that is what makes the
parameter matrix affordable. Emulators are used only where MediaCodec or a real
UI is required.

## Layout of the artifacts

```
fx-verify-runs/<run id>/
  fx-params/params.json, effect_ids.txt
  fx-metrics-<effect>/verdicts.json, metrics.json, anomalies/*.wav
  fx-video-<effect>/fxverify-<effect>/shot-*.jpg, shots.json, result.json,
                    peaks.json, dry.wav, wet.wav, input.wav
  fx-video-<effect>/fx-<effect>.mp4
  fx-formats/fxverify-formats/fmt-*.<ext>, formats.json, verify_formats.json
  vision/<effect>.md          <- prompts for the vision pass
```

## Local run

```bash
# off-device matrix for one effect (needs an Android SDK; see the repo's setup)
FX_EFFECT=reverb sh ./gradlew :app:testDebugUnitTest --tests '*FxParamMatrixDumpTest'
pip install numpy
python3 tools/fxverify/analyze.py --cases app/build/fxverify/reverb

# after a CI run
tools/fxverify/pull_artifacts.sh <run-id>
tools/fxverify/extract_frames.sh fx-verify-runs/<run-id>
python3 tools/fxverify/make_vision_prompts.py --run fx-verify-runs/<run-id>
python3 tools/fxverify/aggregate_report.py --root fx-verify-runs/<run-id>
python3 tools/fxverify/selftest.py        # checks the checker
```

## CI

`.github/workflows/fx-verify.yml`, dispatched manually:

- `smoke_only` — one effect end to end, to validate capture before a full sweep
- `skip_dsp` / `skip_device` — run only one half
- `keep_all_wavs` — upload the ~10k renders (large); by default the renders are
  measured on the runner and only the metrics plus the flagged files come back

The matrix lists every effect id explicitly and `check_drift.py` fails the run
if the code grows an effect the matrix does not cover.

## Evidence rules

- A verdict is always backed by numbers: every case records the metric it moved,
  by how much, and the threshold it had to clear.
- Hard failures are reserved for corruption: non-finite samples, an in-place
  effect changing the length, an unintended silence, a runaway peak, a thrown
  render.
- "Changed but no directional claim" is reported as *weak*, never as a pass.
- Physical anchors (limiter ceilings, gate closure, hum removal, pitch and
  tempo ratios, reverse/invert identity) are asserted explicitly, and the
  harness self-test proves the anchors can fail.
