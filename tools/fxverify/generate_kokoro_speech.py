#!/usr/bin/env python3
"""Generate the speech fixture used by the FX speech probe matrix.

Runs Kokoro TTS (hexgrad/Kokoro-82M) for a set of phonetically varied lines,
concatenates them with short pauses, normalises the result and writes the
48 kHz mono 32-bit float WAV the harness expects:

    tools/fxverify/fixtures/kokoro_speech_48k_f32.wav

The lines deliberately contain plosives ("Peter Piper", "big black bug"),
sibilants ("seashells", "slick snakes"), a full-range pangram and ordinary
studio speech, so every effect in the rack sees realistic material.

Kokoro outputs 24 kHz; the final resample is done by ffmpeg (pcm_f32le).
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import numpy as np

PHRASES = [
    ("af_heart", "Peter Piper picked a peck of pickled peppers."),
    ("am_michael", "She sells seashells by the seashore."),
    ("af_heart", "The quick brown fox jumps over the lazy dog."),
    ("am_michael", "Recording check, one, two, three. Please speak clearly "
                   "into the microphone."),
    ("af_heart", "A big black bug bit a big black bear."),
    ("am_michael", "Six slick snakes slid slowly across the sand."),
    ("af_heart", "We will need a little more headroom before the final take."),
    ("am_michael", "This is the last line of the test recording."),
]

SR_OUT = 48000
GAP_S = 0.35
TARGET_MIN_S = 22.0
TARGET_MAX_S = 28.0


def synth_phrase(pipeline, text: str, voice: str):
    """Returns the concatenated 24 kHz float audio for one phrase."""
    chunks = []
    for _gs, _ps, audio in pipeline(text, voice=voice, speed=1.0):
        a = audio.detach().cpu().numpy() if hasattr(audio, "detach") \
            else np.asarray(audio)
        chunks.append(np.asarray(a, dtype=np.float32).reshape(-1))
    if not chunks:
        raise RuntimeError(f"Kokoro returned no audio for: {text!r}")
    return np.concatenate(chunks)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="tools/fxverify/fixtures/"
                                    "kokoro_speech_48k_f32.wav")
    ap.add_argument("--lang-code", default="a", help="Kokoro language code")
    args = ap.parse_args()

    try:
        from kokoro import KPipeline
    except Exception as exc:  # noqa: BLE001
        print(f"kokoro import failed: {exc}", file=sys.stderr)
        return 2

    if shutil.which("ffmpeg") is None:
        print("ffmpeg is required for the 48 kHz resample", file=sys.stderr)
        return 2

    # The model is fetched from Hugging Face on first use; retry transient
    # network failures without regenerating the whole phrase list.
    pipeline = None
    for attempt in range(1, 4):
        try:
            pipeline = KPipeline(lang_code=args.lang_code)
            break
        except Exception as exc:  # noqa: BLE001
            print(f"Kokoro init attempt {attempt} failed: {exc}", file=sys.stderr)
            time.sleep(10 * attempt)
    if pipeline is None:
        return 2

    pieces = []
    total = 0.0
    # Repeat the phrase list if the first pass is shorter than the target.
    passes = 0
    while total < TARGET_MIN_S and passes < 3:
        for voice, text in PHRASES:
            for attempt in range(1, 4):
                try:
                    audio = synth_phrase(pipeline, text, voice)
                    break
                except Exception as exc:  # noqa: BLE001
                    print(f"synth attempt {attempt} failed for {text!r}: {exc}",
                          file=sys.stderr)
                    audio = None
                    time.sleep(5 * attempt)
            if audio is None:
                return 2
            pieces.append(audio)
            total += len(audio) / 24000.0
            if total >= TARGET_MAX_S:
                break
            pieces.append(np.zeros(int(GAP_S * 24000), dtype=np.float32))
            total += GAP_S
        passes += 1
        if total >= TARGET_MAX_S:
            break

    audio = np.concatenate(pieces).astype(np.float32)
    if len(audio) == 0:
        print("no audio generated", file=sys.stderr)
        return 2

    # Short fades and a -6 dBFS peak target.
    fade = int(0.02 * 24000)
    if len(audio) > 2 * fade:
        audio[:fade] *= np.linspace(0.0, 1.0, fade, dtype=np.float32)
        audio[-fade:] *= np.linspace(1.0, 0.0, fade, dtype=np.float32)
    peak = float(np.max(np.abs(audio)))
    if peak > 0:
        audio *= 0.5 / peak

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        tmp24 = Path(tmp) / "kokoro_24k.wav"
        import soundfile as sf
        sf.write(tmp24, audio, 24000, subtype="FLOAT")
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", str(tmp24),
             "-ar", str(SR_OUT), "-ac", "1", "-c:a", "pcm_f32le",
             str(out)],
            check=True,
        )

    import soundfile as sf
    info = sf.info(str(out))
    if info.samplerate != SR_OUT or info.channels != 1:
        print(f"unexpected output format: {info}", file=sys.stderr)
        return 2
    print(f"wrote {out} ({info.duration:.2f} s, {info.samplerate} Hz, "
          f"{info.channels} ch, {info.subtype})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
