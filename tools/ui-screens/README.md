# Emulator UI screenshot matrix

This folder contains the reusable UI driver and the per-device runner used by
`.github/workflows/ui-screenshots.yml`. Everything runs on GitHub-hosted
emulators; nothing here needs a locally attached device.

## Generic driver

`ui_driver.py` runs declarative adb actions and takes screenshots a fixed time
after each action:

```bash
python3 tools/ui-screens/ui_driver.py \
  --out artifacts/nexus5 \
  --scenario tools/ui-screens/scenarios/system.json
```

A scenario is JSON with a `steps` array. Any step can use:

```json
{"action":"tap","by":"text","value":"FX","delay_ms":750,
 "folder":"studio","screenshot":"fx_opened"}
```

Supported actions: `launch`, `wait`, `tap`, `swipe`, `press_back`,
`press_home`, `press_recents`, `press_key`, `open_notifications`,
`collapse_notifications`, `screenshot`, `assert_visible`, `shell`, `rotate`.
Selectors are `text`, `id`, `desc`, `contains`, or `xy`. `optional: true`
records a failure but continues. The driver writes `driver-report.json`.

## Full app tour

`UiScreenshotTourTest` seeds a real take, opens the studio, and captures the
reachable app surfaces that need session state: library, sort/search, studio,
recording settings, teleprompter editor, finish-take confirmation, FX rack,
presets, Tools, Add Effect, and the effect inspector. It also captures the
notification shade and records WindowInsets/display-cutout information.

`run_device.sh` installs the debug and androidTest APKs, applies the requested
resolution/density/orientation, runs the tour, pulls the screenshots into
`ui-screens/<device-id>/`, then runs the generic system scenario for
home/recents/notification overlays.

## Device matrix

The workflow covers every supported API level (24–35) with all phones locked
to portrait, plus tablet profiles allowed to rotate landscape. Phone jobs vary
common widths/densities from 320x480 through 1440x3120; tablet jobs cover
800x1280, 1200x1920, 1600x2560 and 1200x2000. Screenshots are uploaded as one
artifact per device, with subfolders for `library`, `studio`, `fx`, and
`system`.

The landscape resource overrides have been removed: large screens now use the
same single-column, full-width layout with elements expanding horizontally
instead of a two-pane split.
