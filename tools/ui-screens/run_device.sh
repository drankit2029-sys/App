#!/usr/bin/env bash
# Runs the app on one CI emulator, executes the screenshot tour, and pulls a
# device-specific screenshot tree. Called by .github/workflows/ui-screenshots.yml.
set -u

DEVICE_ID="${DEVICE_ID:-emulator}"
UI_WIDTH="${UI_WIDTH:-0}"
UI_HEIGHT="${UI_HEIGHT:-0}"
UI_DENSITY="${UI_DENSITY:-0}"
UI_ORIENTATION="${UI_ORIENTATION:-portrait}"
APK_PATH="${APK_PATH:-app-debug.apk}"
TEST_APK_PATH="${TEST_APK_PATH:-app-debug-androidTest.apk}"
APP_PACKAGE="${APP_PACKAGE:-com.takeone.studio.debug}"
TEST_RUNNER="${TEST_RUNNER:-com.takeone.studio.debug.test/androidx.test.runner.AndroidJUnitRunner}"
OUT_ROOT="${OUT_ROOT:-ui-screens}"
OUT="$OUT_ROOT/$DEVICE_ID"
REMOTE="/sdcard/Android/data/$APP_PACKAGE/files/ui-screens/$DEVICE_ID"

mkdir -p "$OUT" "$OUT_ROOT"
adb wait-for-device

# Wait until the framework is fully up even when the emulator runner returns.
for i in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    break
  fi
  sleep 2
done

adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb shell settings put global stay_on_while_plugged_in 3 || true
adb shell svc power stayon true || true
adb shell wm dismiss-keyguard || true
adb shell input keyevent 82 || true

if [ "$UI_WIDTH" != "0" ] && [ "$UI_HEIGHT" != "0" ]; then
  adb shell wm size "${UI_WIDTH}x${UI_HEIGHT}" || true
fi
if [ "$UI_DENSITY" != "0" ]; then
  adb shell wm density "$UI_DENSITY" || true
fi
adb shell settings put system accelerometer_rotation 0 || true
ROTATION=0
# Native-landscape tablet profiles (no wm size override) stay at rotation 0.
if [ "$UI_ORIENTATION" = "landscape" ] && [ "$UI_WIDTH" != "0" ] && [ "$UI_HEIGHT" != "0" ]; then
  ROTATION=1
fi
adb shell settings put system user_rotation "$ROTATION" || true
adb shell cmd window user-rotation lock "$ROTATION" >/dev/null 2>&1 \
  || adb shell wm user-rotation lock "$ROTATION" >/dev/null 2>&1 || true
sleep 1

# Best-effort display-cutout and navigation-bar emulation. AOSP images vary;
# the test records the real WindowInsets/cutout even when no overlay exists.
for pkg in \
  com.android.internal.display.cutout.emulation.corner \
  com.android.internal.display.cutout.emulation.double \
  com.android.internal.display.cutout.emulation.hole; do
  if adb shell cmd overlay enable-exclusive --category "$pkg" >/dev/null 2>&1; then
    echo "enabled cutout overlay: $pkg"
    break
  fi
done
adb shell cmd overlay enable-exclusive --category \
  com.android.internal.systemui.navbar.threebutton >/dev/null 2>&1 || true

adb install -r -g "$APK_PATH"
adb install -r -g "$TEST_APK_PATH"
adb shell pm grant "$APP_PACKAGE" android.permission.RECORD_AUDIO || true
adb shell pm grant "$APP_PACKAGE" android.permission.POST_NOTIFICATIONS || true
adb shell pm grant "$APP_PACKAGE" android.permission.WRITE_EXTERNAL_STORAGE || true
adb shell settings put system user_rotation "$ROTATION" || true
adb shell cmd window user-rotation lock "$ROTATION" >/dev/null 2>&1 \
  || adb shell wm user-rotation lock "$ROTATION" >/dev/null 2>&1 || true
sleep 1

SDK="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
{
  echo "device=$DEVICE_ID"
  echo "sdk=$SDK"
  echo "requested_size=${UI_WIDTH}x${UI_HEIGHT}"
  echo "requested_density=$UI_DENSITY"
  echo "requested_orientation=$UI_ORIENTATION"
  adb shell wm size
  adb shell wm density
  adb shell getprop ro.product.model
  adb shell getprop ro.build.version.release
} | tee "$OUT/device-info.txt"

INSTR_FAILED=0
set +e
timeout 900 adb shell am instrument -w -r \
  -e class com.takeone.studio.UiScreenshotTourTest \
  -e uiScreensDevice "$DEVICE_ID" \
  "$TEST_RUNNER" > "$OUT/instrumentation.txt" 2>&1
INSTR_STATUS=$?
set -e
cat "$OUT/instrumentation.txt"
if [ "$INSTR_STATUS" -ne 0 ] \
   || grep -q -E "FAILURES!!!|INSTRUMENTATION_FAILED" \
        "$OUT/instrumentation.txt"; then
  INSTR_FAILED=1
fi

# The app writes report.json and its screenshots into external files.
# API 30+ often blocks adb from /sdcard/Android/data directly; userdebug
# emulator images allow root, and run-as is the fallback for test builds.
adb root >>"$OUT/pull.txt" 2>&1 || true
adb wait-for-device
adb pull "$REMOTE/." "$OUT/" >>"$OUT/pull.txt" 2>&1 || true
if [ ! -f "$OUT/report.json" ]; then
  adb exec-out run-as "$APP_PACKAGE" sh -c     "cd /sdcard/Android/data/$APP_PACKAGE/files && tar cf - ui-screens/$DEVICE_ID"     > "$OUT/screens.tar" 2>>"$OUT/pull.txt" || true
  if [ -s "$OUT/screens.tar" ]; then
    tar xf "$OUT/screens.tar" -C "$OUT" --strip-components=2 >>"$OUT/pull.txt" 2>&1 || true
  fi
fi
adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>&1 || true
adb shell dumpsys activity activities > "$OUT/activities.txt" 2>&1 || true

# Drive raw adb-level system surfaces too (notification shade, home, recents).
python3 tools/ui-screens/ui_driver.py \
  --out "$OUT" \
  --scenario tools/ui-screens/scenarios/system.json \
  >>"$OUT/driver.txt" 2>&1 || DRIVER_FAILED=1

SHOT_COUNT="$(find "$OUT" -type f -name '*.png' | wc -l | tr -d ' ')"
EMPTY_COUNT=0
for f in $(find "$OUT" -type f -name '*.png'); do
  if [ "$(wc -c < "$f")" -lt 2048 ]; then
    echo "suspiciously small screenshot: $f"
    EMPTY_COUNT=$((EMPTY_COUNT + 1))
  fi
done
echo "screenshots=$SHOT_COUNT empty_or_small=$EMPTY_COUNT" | tee -a "$OUT/device-info.txt"

REPORT_FAILED=0
if [ ! -f "$OUT/report.json" ]; then
  echo "missing app report.json for $DEVICE_ID" >&2
  REPORT_FAILED=1
else
  python3 - "$OUT/report.json" <<'PY' || REPORT_FAILED=1
import json, sys
r = json.load(open(sys.argv[1]))
issues = r.get("issues", [])
shots = len(r.get("screenshots", []))
print("report screenshots=%d issues=%s" % (shots, issues))
if issues or shots < 16:
    sys.exit(1)
PY
fi

if [ "$INSTR_FAILED" -ne 0 ] || [ "$SHOT_COUNT" -lt 16 ] \
   || [ "${DRIVER_FAILED:-0}" -ne 0 ] || [ "$REPORT_FAILED" -ne 0 ]; then
  echo "UI screenshot run failed for $DEVICE_ID (instrumentation=$INSTR_FAILED, shots=$SHOT_COUNT, report=$REPORT_FAILED)" >&2
  exit 1
fi
