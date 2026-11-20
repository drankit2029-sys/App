# TakeOne — Device UI test & fix task (Nov 20, 2026)

## Goal
Rampant-user UI testing on the connected phone (`192.0.0.4:5555`,
1080×2340 / 450dpi), fix every bug found, verify end-to-end.

Device is connected to itself via adb. Screen stay-awake enabled via
`settings put global stay_on_while_plugged_in 7` + `svc power stayon true`.

## Environment
- Installed debug APK SHA-256 matches repo `app-debug.apk`
  (`0bb30be4…8b8103b8`) — current code through commit `1ba64d1`.
- Branch: `ut/fx-gpu-undo`, HEAD `277d079` (docs only after APK build).
- Prefer `uiautomator dump` for element bounds, tap exact centers of
  elements only. Confirm `mCurrentFocus` before interacting.

## Reported bugs to reproduce and fix
- [ ] **Waveform scroll speed spikes** after: background app → remove from
      recents → reopen from notification; speed returns to normal later.
      Suspect stale Choreographer timestamps / dt not reset on resume.
- [ ] **UI buttons too large; teleprompter buttons misplaced in studio.**
      Suspect regression from earlier 30dp→48dp touch-target change on
      `teleEditBtn` / `telePauseBtn` / `teleMirrorBtn`.

## Progress log
- [x] Connected device, verified installed APK == repo debug APK.
- [x] Dismissed "Unfinished take found" recovery dialog via uiautomator dump.
- [x] Created this task file; switched to uiautomator-driven taps.
- [ ] Open Studio; screenshot teleprompter bar; measure button bounds.
- [ ] Reproduce waveform speed bug on device with timestamps/scroll deltas.
- [ ] Locate and fix root causes in Java/XML.
- [ ] Exhaustive feature-by-feature UI sweep (see checklist below).
- [ ] Build via CI, install new debug APK, re-verify on device.
- [ ] Refresh final APKs/AAB and commit/push.

## Exhaustive UI checklist ("rampant user")
Library: search (empty/garbage/long), sort menu, all 10 filter chips, multi-select,
favorite/unfavorite, play/pause/seek to extremes, edit, delete + undo, share,
export formats, import picker, privacy dialog + link, ad card visibility.

Studio/record: start/pause/resume/stop, background/recents/notification paths,
long record, FGS notification actions, discard/save, naming modal, preset picker,
mic permission revoke, sample-rate changes, waveform zoom min/max, scrub outside
view, selection edge cases, undo/redo stacks, waveform load on large takes.

FX: open/close panel, every category, add/duplicate/reorder/delete effects,
preset apply, parameter min/max sweeps, inspector dialog, preview play, apply to
selection, bake on save, bypass enable/disable, coupled params.

Teleprompter: script import (txt/docx), editor Save/Cancel/Import, empty script,
long script, mirror, size/speed/WPL extremes, scroll drag, edge pause, countdown.

Conversion/export: save while converting, cancel mid-convert, format matrix
round-trips, FLAC fallback, file-name collisions, storage full (where testable).

System: rotate tablet-safe (phone portrait-locked), notification shade, back
stack, process death / "Don't keep activities", auto-backup exclusions, TalkBack
focus basics, multi-window on emulator later if needed.
