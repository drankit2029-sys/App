# Exhaustive FX report — Truncate Silence

## Effect inspection

- Effect id: `truncsil`
- Input: `Input_truncsil.wav` (14400.0 ms, 691200 frames at 48000 Hz)
- Input gaps: 1200 ms (noise -55 dB), 6000 ms (noise -120 dB), 1200 ms (noise -120 dB)
- Modes: Action = Truncate / Compress
- Mode-exclusive controls: Truncate To (Truncate only), Compress To (Compress only)

## Parameters

| Parameter | Type | Bounds | Default | Test values |
|---|---|---|---|---|
| Threshold (`ts_thresh`) | FLOAT | -80..-20 dB | -4000 | -80dB, -40dB, -20dB |
| Minimum Silence (`ts_min`) | INT | 50..5000 ms | 400 | 50ms, 400ms, 5000ms |
| Action (`ts_action`) | ENUM | 0..1 raw | 0 | Truncate Detected Silence, Compress Excess Silence |
| Truncate To (`ts_trunc`) | INT | 10..2000 ms | 150 | 10ms, 150ms, 2000ms |
| Compress To (`ts_compress`) | INT | 5..90 % | 35 | 5%, 35%, 90% |

## Summary

- Individual cases: 14
- Disabled-parameter invariance cases: 4
- Combinations listed: 162 (54 active, 108 disabled)
- Rendered outputs: 72
- Invariance: 4 passed, 0 failed
- Hard failures: 0

## Individual parameters

| Case | Parameter | Value | Action | Output | Frames | Δ ms | Speech Δ dB | Status |
|---|---|---|---|---|---|---|---|---|
| ind_ts_thresh_-8000 | ts_thresh | -80dB | Truncate Detected Silence | `Output_truncsil_ts_thresh_-80dB.wav` | 286080 | 0.0 | 0.01 | PASS |
| ind_ts_thresh_-4000 | ts_thresh | -40dB | Truncate Detected Silence | `Output_truncsil_ts_thresh_-40dB.wav` | 211200 | 0.0 | 0.03 | PASS |
| ind_ts_thresh_-2000 | ts_thresh | -20dB | Truncate Detected Silence | `Output_truncsil_ts_thresh_-20dB.wav` | 96000 | 0.0 | 1.48 | PASS |
| ind_ts_min_50 | ts_min | 50ms | Truncate Detected Silence | `Output_truncsil_ts_min_50ms.wav` | 211200 | 0.0 | 0.03 | PASS |
| ind_ts_min_400 | ts_min | 400ms | Truncate Detected Silence | `Output_truncsil_ts_min_400ms.wav` | 211200 | 0.0 | 0.03 | PASS |
| ind_ts_min_5000 | ts_min | 5000ms | Truncate Detected Silence | `Output_truncsil_ts_min_5000ms.wav` | 409920 | 0.0 | 0.01 | PASS |
| ind_ts_action_0 | ts_action | Truncate Detected Silence | Truncate Detected Silence | `Output_truncsil_ts_action_Truncate_Detected_Silence.wav` | 211200 | 0.0 | 0.03 | PASS |
| ind_ts_action_1 | ts_action | Compress Excess Silence | Compress Excess Silence | `Output_truncsil_ts_action_Compress_Excess_Silence.wav` | 360480 | 0.0 | 0.03 | PASS |
| ind_ts_trunc_10 | ts_trunc | 10ms | Truncate Detected Silence | `Output_truncsil_ts_trunc_10ms.wav` | 184320 | 0.0 | 0.05 | PASS |
| ind_ts_trunc_150 | ts_trunc | 150ms | Truncate Detected Silence | `Output_truncsil_ts_trunc_150ms.wav` | 211200 | 0.0 | 0.03 | PASS |
| ind_ts_trunc_2000 | ts_trunc | 2000ms | Truncate Detected Silence | `Output_truncsil_ts_trunc_2000ms.wav` | 491040 | 0.0 | 0.01 | PASS |
| ind_ts_compress_5 | ts_compress | 5% | Compress Excess Silence | `Output_truncsil_ts_compress_5pct.wav` | 207840 | 0.0 | 0.03 | PASS |
| ind_ts_compress_35 | ts_compress | 35% | Compress Excess Silence | `Output_truncsil_ts_compress_35pct.wav` | 360480 | 0.0 | 0.03 | PASS |
| ind_ts_compress_90 | ts_compress | 90% | Compress Excess Silence | `Output_truncsil_ts_compress_90pct.wav` | 640320 | 0.0 | 0.03 | PASS |

## Disabled-parameter invariance

| Case | Compared to | Output | Max diff | Status |
|---|---|---|---|---|
| dis_ts_trunc_1_10 | ind_ts_action_1 | `Output_truncsil_disabled_ts_trunc_in_Compress_Excess_Silence_10ms.wav` | 0.0 | PASS |
| dis_ts_trunc_1_2000 | ind_ts_action_1 | `Output_truncsil_disabled_ts_trunc_in_Compress_Excess_Silence_2000ms.wav` | 0.0 | PASS |
| dis_ts_compress_0_5 | ind_ts_action_0 | `Output_truncsil_disabled_ts_compress_in_Truncate_Detected_Silence_5pct.wav` | 0.0 | PASS |
| dis_ts_compress_0_90 | ind_ts_action_0 | `Output_truncsil_disabled_ts_compress_in_Truncate_Detected_Silence_90pct.wav` | 0.0 | PASS |

## All combinations (54 active / 108 disabled)

| # | Action | Threshold | Min | Truncate | Compress | Status | Output |
|---|---|---|---|---|---|---|---|
| combo_0001 | Truncate Detected Silence | -80dB | 50ms | 10ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0002 | Truncate Detected Silence | -80dB | 50ms | 10ms | 35% | PASS | `Output_truncsil_combo_0002_Truncate_Detected_Silence_ts_thresh_-80dB_ts_min_50ms_ts_trunc_10ms.wav` |
| combo_0003 | Truncate Detected Silence | -80dB | 50ms | 10ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0004 | Truncate Detected Silence | -80dB | 50ms | 150ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0005 | Truncate Detected Silence | -80dB | 50ms | 150ms | 35% | PASS | `Output_truncsil_combo_0005_Truncate_Detected_Silence_ts_thresh_-80dB_ts_min_50ms_ts_trunc_150ms.wav` |
| combo_0006 | Truncate Detected Silence | -80dB | 50ms | 150ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0007 | Truncate Detected Silence | -80dB | 50ms | 2000ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0008 | Truncate Detected Silence | -80dB | 50ms | 2000ms | 35% | PASS | `Output_truncsil_combo_0008_Truncate_Detected_Silence_ts_thresh_-80dB_ts_min_50ms_ts_trunc_2000ms.wav` |
| combo_0009 | Truncate Detected Silence | -80dB | 50ms | 2000ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0010 | Compress Excess Silence | -80dB | 50ms | 10ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0011 | Compress Excess Silence | -80dB | 50ms | 10ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0012 | Compress Excess Silence | -80dB | 50ms | 10ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0013 | Compress Excess Silence | -80dB | 50ms | 150ms | 5% | PASS | `Output_truncsil_combo_0013_Compress_Excess_Silence_ts_thresh_-80dB_ts_min_50ms_ts_compress_5pct.wav` |
| combo_0014 | Compress Excess Silence | -80dB | 50ms | 150ms | 35% | PASS | `Output_truncsil_combo_0014_Compress_Excess_Silence_ts_thresh_-80dB_ts_min_50ms_ts_compress_35pct.wav` |
| combo_0015 | Compress Excess Silence | -80dB | 50ms | 150ms | 90% | PASS | `Output_truncsil_combo_0015_Compress_Excess_Silence_ts_thresh_-80dB_ts_min_50ms_ts_compress_90pct.wav` |
| combo_0016 | Compress Excess Silence | -80dB | 50ms | 2000ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0017 | Compress Excess Silence | -80dB | 50ms | 2000ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0018 | Compress Excess Silence | -80dB | 50ms | 2000ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0019 | Truncate Detected Silence | -80dB | 400ms | 10ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0020 | Truncate Detected Silence | -80dB | 400ms | 10ms | 35% | PASS | `Output_truncsil_combo_0020_Truncate_Detected_Silence_ts_thresh_-80dB_ts_min_400ms_ts_trunc_10ms.wav` |
| combo_0021 | Truncate Detected Silence | -80dB | 400ms | 10ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0022 | Truncate Detected Silence | -80dB | 400ms | 150ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0023 | Truncate Detected Silence | -80dB | 400ms | 150ms | 35% | PASS | `Output_truncsil_combo_0023_Truncate_Detected_Silence_ts_thresh_-80dB_ts_min_400ms_ts_trunc_150ms.wav` |
| combo_0024 | Truncate Detected Silence | -80dB | 400ms | 150ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0025 | Truncate Detected Silence | -80dB | 400ms | 2000ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0026 | Truncate Detected Silence | -80dB | 400ms | 2000ms | 35% | PASS | `Output_truncsil_combo_0026_Truncate_Detected_Silence_ts_thresh_-80dB_ts_min_400ms_ts_trunc_2000ms.wav` |
| combo_0027 | Truncate Detected Silence | -80dB | 400ms | 2000ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0028 | Compress Excess Silence | -80dB | 400ms | 10ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0029 | Compress Excess Silence | -80dB | 400ms | 10ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0030 | Compress Excess Silence | -80dB | 400ms | 10ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0031 | Compress Excess Silence | -80dB | 400ms | 150ms | 5% | PASS | `Output_truncsil_combo_0031_Compress_Excess_Silence_ts_thresh_-80dB_ts_min_400ms_ts_compress_5pct.wav` |
| combo_0032 | Compress Excess Silence | -80dB | 400ms | 150ms | 35% | PASS | `Output_truncsil_combo_0032_Compress_Excess_Silence_ts_thresh_-80dB_ts_min_400ms_ts_compress_35pct.wav` |
| combo_0033 | Compress Excess Silence | -80dB | 400ms | 150ms | 90% | PASS | `Output_truncsil_combo_0033_Compress_Excess_Silence_ts_thresh_-80dB_ts_min_400ms_ts_compress_90pct.wav` |
| combo_0034 | Compress Excess Silence | -80dB | 400ms | 2000ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0035 | Compress Excess Silence | -80dB | 400ms | 2000ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0036 | Compress Excess Silence | -80dB | 400ms | 2000ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0037 | Truncate Detected Silence | -80dB | 5000ms | 10ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0038 | Truncate Detected Silence | -80dB | 5000ms | 10ms | 35% | PASS | `Output_truncsil_combo_0038_Truncate_Detected_Silence_ts_thresh_-80dB_ts_min_5000ms_ts_trunc_10ms.wav` |
| combo_0039 | Truncate Detected Silence | -80dB | 5000ms | 10ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0040 | Truncate Detected Silence | -80dB | 5000ms | 150ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0041 | Truncate Detected Silence | -80dB | 5000ms | 150ms | 35% | PASS | `Output_truncsil_combo_0041_Truncate_Detected_Silence_ts_thresh_-80dB_ts_min_5000ms_ts_trunc_150ms.wav` |
| combo_0042 | Truncate Detected Silence | -80dB | 5000ms | 150ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0043 | Truncate Detected Silence | -80dB | 5000ms | 2000ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0044 | Truncate Detected Silence | -80dB | 5000ms | 2000ms | 35% | PASS | `Output_truncsil_combo_0044_Truncate_Detected_Silence_ts_thresh_-80dB_ts_min_5000ms_ts_trunc_2000ms.wav` |
| combo_0045 | Truncate Detected Silence | -80dB | 5000ms | 2000ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0046 | Compress Excess Silence | -80dB | 5000ms | 10ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0047 | Compress Excess Silence | -80dB | 5000ms | 10ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0048 | Compress Excess Silence | -80dB | 5000ms | 10ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0049 | Compress Excess Silence | -80dB | 5000ms | 150ms | 5% | PASS | `Output_truncsil_combo_0049_Compress_Excess_Silence_ts_thresh_-80dB_ts_min_5000ms_ts_compress_5pct.wav` |
| combo_0050 | Compress Excess Silence | -80dB | 5000ms | 150ms | 35% | PASS | `Output_truncsil_combo_0050_Compress_Excess_Silence_ts_thresh_-80dB_ts_min_5000ms_ts_compress_35pct.wav` |
| combo_0051 | Compress Excess Silence | -80dB | 5000ms | 150ms | 90% | PASS | `Output_truncsil_combo_0051_Compress_Excess_Silence_ts_thresh_-80dB_ts_min_5000ms_ts_compress_90pct.wav` |
| combo_0052 | Compress Excess Silence | -80dB | 5000ms | 2000ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0053 | Compress Excess Silence | -80dB | 5000ms | 2000ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0054 | Compress Excess Silence | -80dB | 5000ms | 2000ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0055 | Truncate Detected Silence | -40dB | 50ms | 10ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0056 | Truncate Detected Silence | -40dB | 50ms | 10ms | 35% | PASS | `Output_truncsil_combo_0056_Truncate_Detected_Silence_ts_thresh_-40dB_ts_min_50ms_ts_trunc_10ms.wav` |
| combo_0057 | Truncate Detected Silence | -40dB | 50ms | 10ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0058 | Truncate Detected Silence | -40dB | 50ms | 150ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0059 | Truncate Detected Silence | -40dB | 50ms | 150ms | 35% | PASS | `Output_truncsil_combo_0059_Truncate_Detected_Silence_ts_thresh_-40dB_ts_min_50ms_ts_trunc_150ms.wav` |
| combo_0060 | Truncate Detected Silence | -40dB | 50ms | 150ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0061 | Truncate Detected Silence | -40dB | 50ms | 2000ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0062 | Truncate Detected Silence | -40dB | 50ms | 2000ms | 35% | PASS | `Output_truncsil_combo_0062_Truncate_Detected_Silence_ts_thresh_-40dB_ts_min_50ms_ts_trunc_2000ms.wav` |
| combo_0063 | Truncate Detected Silence | -40dB | 50ms | 2000ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0064 | Compress Excess Silence | -40dB | 50ms | 10ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0065 | Compress Excess Silence | -40dB | 50ms | 10ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0066 | Compress Excess Silence | -40dB | 50ms | 10ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0067 | Compress Excess Silence | -40dB | 50ms | 150ms | 5% | PASS | `Output_truncsil_combo_0067_Compress_Excess_Silence_ts_thresh_-40dB_ts_min_50ms_ts_compress_5pct.wav` |
| combo_0068 | Compress Excess Silence | -40dB | 50ms | 150ms | 35% | PASS | `Output_truncsil_combo_0068_Compress_Excess_Silence_ts_thresh_-40dB_ts_min_50ms_ts_compress_35pct.wav` |
| combo_0069 | Compress Excess Silence | -40dB | 50ms | 150ms | 90% | PASS | `Output_truncsil_combo_0069_Compress_Excess_Silence_ts_thresh_-40dB_ts_min_50ms_ts_compress_90pct.wav` |
| combo_0070 | Compress Excess Silence | -40dB | 50ms | 2000ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0071 | Compress Excess Silence | -40dB | 50ms | 2000ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0072 | Compress Excess Silence | -40dB | 50ms | 2000ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0073 | Truncate Detected Silence | -40dB | 400ms | 10ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0074 | Truncate Detected Silence | -40dB | 400ms | 10ms | 35% | PASS | `Output_truncsil_combo_0074_Truncate_Detected_Silence_ts_thresh_-40dB_ts_min_400ms_ts_trunc_10ms.wav` |
| combo_0075 | Truncate Detected Silence | -40dB | 400ms | 10ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0076 | Truncate Detected Silence | -40dB | 400ms | 150ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0077 | Truncate Detected Silence | -40dB | 400ms | 150ms | 35% | PASS | `Output_truncsil_combo_0077_Truncate_Detected_Silence_ts_thresh_-40dB_ts_min_400ms_ts_trunc_150ms.wav` |
| combo_0078 | Truncate Detected Silence | -40dB | 400ms | 150ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0079 | Truncate Detected Silence | -40dB | 400ms | 2000ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0080 | Truncate Detected Silence | -40dB | 400ms | 2000ms | 35% | PASS | `Output_truncsil_combo_0080_Truncate_Detected_Silence_ts_thresh_-40dB_ts_min_400ms_ts_trunc_2000ms.wav` |
| combo_0081 | Truncate Detected Silence | -40dB | 400ms | 2000ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0082 | Compress Excess Silence | -40dB | 400ms | 10ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0083 | Compress Excess Silence | -40dB | 400ms | 10ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0084 | Compress Excess Silence | -40dB | 400ms | 10ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0085 | Compress Excess Silence | -40dB | 400ms | 150ms | 5% | PASS | `Output_truncsil_combo_0085_Compress_Excess_Silence_ts_thresh_-40dB_ts_min_400ms_ts_compress_5pct.wav` |
| combo_0086 | Compress Excess Silence | -40dB | 400ms | 150ms | 35% | PASS | `Output_truncsil_combo_0086_Compress_Excess_Silence_ts_thresh_-40dB_ts_min_400ms_ts_compress_35pct.wav` |
| combo_0087 | Compress Excess Silence | -40dB | 400ms | 150ms | 90% | PASS | `Output_truncsil_combo_0087_Compress_Excess_Silence_ts_thresh_-40dB_ts_min_400ms_ts_compress_90pct.wav` |
| combo_0088 | Compress Excess Silence | -40dB | 400ms | 2000ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0089 | Compress Excess Silence | -40dB | 400ms | 2000ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0090 | Compress Excess Silence | -40dB | 400ms | 2000ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0091 | Truncate Detected Silence | -40dB | 5000ms | 10ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0092 | Truncate Detected Silence | -40dB | 5000ms | 10ms | 35% | PASS | `Output_truncsil_combo_0092_Truncate_Detected_Silence_ts_thresh_-40dB_ts_min_5000ms_ts_trunc_10ms.wav` |
| combo_0093 | Truncate Detected Silence | -40dB | 5000ms | 10ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0094 | Truncate Detected Silence | -40dB | 5000ms | 150ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0095 | Truncate Detected Silence | -40dB | 5000ms | 150ms | 35% | PASS | `Output_truncsil_combo_0095_Truncate_Detected_Silence_ts_thresh_-40dB_ts_min_5000ms_ts_trunc_150ms.wav` |
| combo_0096 | Truncate Detected Silence | -40dB | 5000ms | 150ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0097 | Truncate Detected Silence | -40dB | 5000ms | 2000ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0098 | Truncate Detected Silence | -40dB | 5000ms | 2000ms | 35% | PASS | `Output_truncsil_combo_0098_Truncate_Detected_Silence_ts_thresh_-40dB_ts_min_5000ms_ts_trunc_2000ms.wav` |
| combo_0099 | Truncate Detected Silence | -40dB | 5000ms | 2000ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0100 | Compress Excess Silence | -40dB | 5000ms | 10ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0101 | Compress Excess Silence | -40dB | 5000ms | 10ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0102 | Compress Excess Silence | -40dB | 5000ms | 10ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0103 | Compress Excess Silence | -40dB | 5000ms | 150ms | 5% | PASS | `Output_truncsil_combo_0103_Compress_Excess_Silence_ts_thresh_-40dB_ts_min_5000ms_ts_compress_5pct.wav` |
| combo_0104 | Compress Excess Silence | -40dB | 5000ms | 150ms | 35% | PASS | `Output_truncsil_combo_0104_Compress_Excess_Silence_ts_thresh_-40dB_ts_min_5000ms_ts_compress_35pct.wav` |
| combo_0105 | Compress Excess Silence | -40dB | 5000ms | 150ms | 90% | PASS | `Output_truncsil_combo_0105_Compress_Excess_Silence_ts_thresh_-40dB_ts_min_5000ms_ts_compress_90pct.wav` |
| combo_0106 | Compress Excess Silence | -40dB | 5000ms | 2000ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0107 | Compress Excess Silence | -40dB | 5000ms | 2000ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0108 | Compress Excess Silence | -40dB | 5000ms | 2000ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0109 | Truncate Detected Silence | -20dB | 50ms | 10ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0110 | Truncate Detected Silence | -20dB | 50ms | 10ms | 35% | PASS | `Output_truncsil_combo_0110_Truncate_Detected_Silence_ts_thresh_-20dB_ts_min_50ms_ts_trunc_10ms.wav` |
| combo_0111 | Truncate Detected Silence | -20dB | 50ms | 10ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0112 | Truncate Detected Silence | -20dB | 50ms | 150ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0113 | Truncate Detected Silence | -20dB | 50ms | 150ms | 35% | PASS | `Output_truncsil_combo_0113_Truncate_Detected_Silence_ts_thresh_-20dB_ts_min_50ms_ts_trunc_150ms.wav` |
| combo_0114 | Truncate Detected Silence | -20dB | 50ms | 150ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0115 | Truncate Detected Silence | -20dB | 50ms | 2000ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0116 | Truncate Detected Silence | -20dB | 50ms | 2000ms | 35% | PASS | `Output_truncsil_combo_0116_Truncate_Detected_Silence_ts_thresh_-20dB_ts_min_50ms_ts_trunc_2000ms.wav` |
| combo_0117 | Truncate Detected Silence | -20dB | 50ms | 2000ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0118 | Compress Excess Silence | -20dB | 50ms | 10ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0119 | Compress Excess Silence | -20dB | 50ms | 10ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0120 | Compress Excess Silence | -20dB | 50ms | 10ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0121 | Compress Excess Silence | -20dB | 50ms | 150ms | 5% | PASS | `Output_truncsil_combo_0121_Compress_Excess_Silence_ts_thresh_-20dB_ts_min_50ms_ts_compress_5pct.wav` |
| combo_0122 | Compress Excess Silence | -20dB | 50ms | 150ms | 35% | PASS | `Output_truncsil_combo_0122_Compress_Excess_Silence_ts_thresh_-20dB_ts_min_50ms_ts_compress_35pct.wav` |
| combo_0123 | Compress Excess Silence | -20dB | 50ms | 150ms | 90% | PASS | `Output_truncsil_combo_0123_Compress_Excess_Silence_ts_thresh_-20dB_ts_min_50ms_ts_compress_90pct.wav` |
| combo_0124 | Compress Excess Silence | -20dB | 50ms | 2000ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0125 | Compress Excess Silence | -20dB | 50ms | 2000ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0126 | Compress Excess Silence | -20dB | 50ms | 2000ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0127 | Truncate Detected Silence | -20dB | 400ms | 10ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0128 | Truncate Detected Silence | -20dB | 400ms | 10ms | 35% | PASS | `Output_truncsil_combo_0128_Truncate_Detected_Silence_ts_thresh_-20dB_ts_min_400ms_ts_trunc_10ms.wav` |
| combo_0129 | Truncate Detected Silence | -20dB | 400ms | 10ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0130 | Truncate Detected Silence | -20dB | 400ms | 150ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0131 | Truncate Detected Silence | -20dB | 400ms | 150ms | 35% | PASS | `Output_truncsil_combo_0131_Truncate_Detected_Silence_ts_thresh_-20dB_ts_min_400ms_ts_trunc_150ms.wav` |
| combo_0132 | Truncate Detected Silence | -20dB | 400ms | 150ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0133 | Truncate Detected Silence | -20dB | 400ms | 2000ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0134 | Truncate Detected Silence | -20dB | 400ms | 2000ms | 35% | PASS | `Output_truncsil_combo_0134_Truncate_Detected_Silence_ts_thresh_-20dB_ts_min_400ms_ts_trunc_2000ms.wav` |
| combo_0135 | Truncate Detected Silence | -20dB | 400ms | 2000ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0136 | Compress Excess Silence | -20dB | 400ms | 10ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0137 | Compress Excess Silence | -20dB | 400ms | 10ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0138 | Compress Excess Silence | -20dB | 400ms | 10ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0139 | Compress Excess Silence | -20dB | 400ms | 150ms | 5% | PASS | `Output_truncsil_combo_0139_Compress_Excess_Silence_ts_thresh_-20dB_ts_min_400ms_ts_compress_5pct.wav` |
| combo_0140 | Compress Excess Silence | -20dB | 400ms | 150ms | 35% | PASS | `Output_truncsil_combo_0140_Compress_Excess_Silence_ts_thresh_-20dB_ts_min_400ms_ts_compress_35pct.wav` |
| combo_0141 | Compress Excess Silence | -20dB | 400ms | 150ms | 90% | PASS | `Output_truncsil_combo_0141_Compress_Excess_Silence_ts_thresh_-20dB_ts_min_400ms_ts_compress_90pct.wav` |
| combo_0142 | Compress Excess Silence | -20dB | 400ms | 2000ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0143 | Compress Excess Silence | -20dB | 400ms | 2000ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0144 | Compress Excess Silence | -20dB | 400ms | 2000ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0145 | Truncate Detected Silence | -20dB | 5000ms | 10ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0146 | Truncate Detected Silence | -20dB | 5000ms | 10ms | 35% | PASS | `Output_truncsil_combo_0146_Truncate_Detected_Silence_ts_thresh_-20dB_ts_min_5000ms_ts_trunc_10ms.wav` |
| combo_0147 | Truncate Detected Silence | -20dB | 5000ms | 10ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0148 | Truncate Detected Silence | -20dB | 5000ms | 150ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0149 | Truncate Detected Silence | -20dB | 5000ms | 150ms | 35% | PASS | `Output_truncsil_combo_0149_Truncate_Detected_Silence_ts_thresh_-20dB_ts_min_5000ms_ts_trunc_150ms.wav` |
| combo_0150 | Truncate Detected Silence | -20dB | 5000ms | 150ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0151 | Truncate Detected Silence | -20dB | 5000ms | 2000ms | 5% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 5%, default 35%) | `` |
| combo_0152 | Truncate Detected Silence | -20dB | 5000ms | 2000ms | 35% | PASS | `Output_truncsil_combo_0152_Truncate_Detected_Silence_ts_thresh_-20dB_ts_min_5000ms_ts_trunc_2000ms.wav` |
| combo_0153 | Truncate Detected Silence | -20dB | 5000ms | 2000ms | 90% | disabled: ts_compress is inactive when ts_action=Truncate Detected Silence (value 90%, default 35%) | `` |
| combo_0154 | Compress Excess Silence | -20dB | 5000ms | 10ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0155 | Compress Excess Silence | -20dB | 5000ms | 10ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0156 | Compress Excess Silence | -20dB | 5000ms | 10ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 10ms, default 150ms) | `` |
| combo_0157 | Compress Excess Silence | -20dB | 5000ms | 150ms | 5% | PASS | `Output_truncsil_combo_0157_Compress_Excess_Silence_ts_thresh_-20dB_ts_min_5000ms_ts_compress_5pct.wav` |
| combo_0158 | Compress Excess Silence | -20dB | 5000ms | 150ms | 35% | PASS | `Output_truncsil_combo_0158_Compress_Excess_Silence_ts_thresh_-20dB_ts_min_5000ms_ts_compress_35pct.wav` |
| combo_0159 | Compress Excess Silence | -20dB | 5000ms | 150ms | 90% | PASS | `Output_truncsil_combo_0159_Compress_Excess_Silence_ts_thresh_-20dB_ts_min_5000ms_ts_compress_90pct.wav` |
| combo_0160 | Compress Excess Silence | -20dB | 5000ms | 2000ms | 5% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0161 | Compress Excess Silence | -20dB | 5000ms | 2000ms | 35% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |
| combo_0162 | Compress Excess Silence | -20dB | 5000ms | 2000ms | 90% | disabled: ts_trunc is inactive when ts_action=Compress Excess Silence (value 2000ms, default 150ms) | `` |

## Notes

- The expected-length simulation mirrors the processor's 10 ms RMS scan; all active cases must match within 25 ms.
- At Threshold = −20 dB the detector may treat quiet speech as silence. That raises the RMS of the remaining speech; the positive `Speech Δ dB` values are expected and are not failures as long as speech is not lost.

## Failures

None.
