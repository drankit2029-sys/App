#!/usr/bin/env bash
# Runs the Play Store screenshot tour on one CI emulator, then pulls the
# screenshot tree. Variant of run_device.sh with the store test class and a
# store-screens output root. Called by .github/workflows/store-screenshots.yml.
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
UI_CLASS="${UI_CLASS:-com.takeone.studio.PlayStoreScreenshotTest}"
UI_ARG="${UI_ARG:-storeScreensDevice}"
OUT_ROOT="${OUT_ROOT:-store-screens}"
OUT="$OUT_ROOT/$DEVICE_ID"
REMOTE="/sdcard/Android/data/$APP_PACKAGE/files/store-screens/$DEVICE_ID"

mkdir -p "$OUT" "$OUT_ROOT"
adb wait-for-device

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
if [ "$UI_WIDTH" = "0" ] || [ "$UI_HEIGHT" = "0" ]; then
  [ "$UI_ORIENTATION" = "portrait" ] && ROTATION=1
else
  [ "$UI_ORIENTATION" = "landscape" ] && ROTATION=1
fi
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
  -e class "$UI_CLASS" \
  -e "$UI_ARG" "$DEVICE_ID" \
  "$TEST_RUNNER" > "$OUT/instrumentation.txt" 2>&1
INSTR_STATUS=$?
set -e
cat "$OUT/instrumentation.txt"
if [ "$INSTR_STATUS" -ne 0 ] \
   || grep -q -E "FAILURES!!!|INSTRUMENTATION_FAILED" \
        "$OUT/instrumentation.txt"; then
  INSTR_FAILED=1
fi

adb root >>"$OUT/pull.txt" 2>&1 || true
adb wait-for-device
adb pull "$REMOTE/." "$OUT/" >>"$OUT/pull.txt" 2>&1 || true
if [ ! -f "$OUT/report.json" ]; then
  adb exec-out run-as "$APP_PACKAGE" sh -c \
    "cd /sdcard/Android/data/$APP_PACKAGE/files && tar cf - store-screens/$DEVICE_ID" \
    > "$OUT/screens.tar" 2>>"$OUT/pull.txt" || true
  if [ -s "$OUT/screens.tar" ]; then
    tar xf "$OUT/screens.tar" -C "$OUT" --strip-components=2 >>"$OUT/pull.txt" 2>&1 || true
  fi
fi
adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>&1 || true

if [ "$INSTR_FAILED" -ne 0 ]; then
  echo "instrumentation reported failure"
  exit 1
fi
if [ ! -f "$OUT/report.json" ]; then
  echo "report.json missing; the test may not have finished"
  exit 1
fi
