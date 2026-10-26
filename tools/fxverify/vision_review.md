# Vision review pass

The device tier records the real UI while every parameter of every effect is
applied. The video is the primary evidence, but a model can only be handed
images, so the pass works like this:

1. `extract_frames.sh <run-dir>` writes, per effect:
   - `frames/step-NNN-<param>.png` — one frame from the video at the exact
     millisecond the test logged when it applied that parameter value
   - `frames/timeline-NNNNN.png` — the recording sampled at 1 fps, for anything
     that happens *between* parameter changes
   - `filmstrip.jpg` — the whole recording as a contact sheet
   - the `shot-NNN-<param>.jpg` screenshots themselves (pixel-exact, not a video
     frame)
2. `make_vision_prompts.py --run <run-dir>` writes `vision/<effect>.md`: one
   self-contained prompt per effect listing every image with its
   `parameter = value` label and the JSON schema to answer with.
3. One sub-agent per effect is started with that prompt, pinned to
   `z-ai/glm-5.3-flash` (fallback `minimax/minimax-m3.1-flash`, then
   `deepseek/deepseek-v4.1-flash` if the pinned model cannot read images — the
   smoke run checks exactly this). Agents run in waves so each returns its JSON
   while its images are still its only context.

Automated checks still run over 100% of the pixels: `aggregate_report.py` reads
the app's own dry/wet peak arrays to confirm the waveform actually changed, and
the device test records every exception, missing control and logcat error line.

## Verdict schema (what each agent must return)

```json
{
  "effect": "reverb",
  "imagesReviewed": 21,
  "uiErrors": [
    {"image": "shot-004-rv_size.jpg", "kind": "toast",
     "evidence": "red toast reading 'Preview failed' across the panel",
     "confidence": "high"}
  ],
  "waveformFindings": [
    {"image": "shot-004-rv_size.jpg",
     "observation": "waveform bars grow after the impulse positions as Size rises",
     "verdict": "expected"}
  ],
  "artifacts": [],
  "unknownControls": [],
  "verdict": "clean",
  "summary": "..."
}
```

## Honest limitations

- A model reads the frames it is given: motion between frames is inferred from
  the ordering, not from true video understanding.
- Frame timestamps come from the test's own clock; the recording starts ~1 s
  later than the baseline, so video frames are context, and the screenshots are
  the exact evidence.
- The waveform claim is verified numerically first (peak arrays), then read
  visually as a cross-check — a disagreement between the two is itself a
  finding.
