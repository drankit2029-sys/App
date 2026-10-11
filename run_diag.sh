#!/data/data/com.termux/files/usr/bin/bash
# Exercises the real MediaCodec/MediaMuxer path ON THIS PHONE and reports
# which save format kills the app. No network, no ssh required.
#
#   bash run_diag.sh <IP:PORT> <PAIR_IP:PAIR_PORT>
# Both values are shown in Settings > Developer options > Wireless debugging.
set -u

# Locate this script's folder even if invoked from elsewhere.
DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
if [ ! -f "$DIR/app-debug.apk" ]; then
  DIR="$(find /storage /mnt/sdcard /sdcard "$HOME" -maxdepth 6 -name 'app-debug-androidTest.apk' 2>/dev/null | head -1 | xargs -r dirname)"
fi
[ -z "${DIR:-}" ] && { echo "ERROR: can't locate the APKs"; exit 1; }
cd "$DIR" || exit 1
echo "== working in $DIR"

IPPORT="${1:-}"; PAIR="${2:-}"
if [ -z "$IPPORT" ] || [ -z "$PAIR" ]; then
  echo "usage: bash run_diag.sh <IP:PORT> <PAIR_IP:PAIR_PORT>"
  echo "(both shown under Settings > Developer options > Wireless debugging)"
  exit 1
fi

if ! command -v adb >/dev/null; then
  echo "== installing android-tools"
  pkg install -y android-tools || pkg install -y adb || { echo "ERROR: adb unavailable"; exit 1; }
fi
adb version | head -1

echo "== pairing $PAIR"
adb pair "$PAIR" 2>&1 | tail -2
echo "== connecting $IPPORT"
adb connect "$IPPORT" 2>&1 | tail -2
adb wait-for-device || { echo "ERROR: adb never connected"; exit 1; }
echo "== device: $(adb shell getprop ro.product.model | tr -d '\r') / Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"

echo "== installing APKs"
adb install -r -t app-debug.apk 2>&1 | tail -1
adb install -r -t app-debug-androidTest.apk 2>&1 | tail -1

echo "== running every encoder (this is the test)"
adb logcat -c
timeout 900 adb shell am instrument -w -e class com.example.hello.EncodeFormatsTest \
  com.example.hello.test/androidx.test.runner.AndroidJUnitRunner 2>&1

echo "== FORMAT RESULTS"
adb logcat -d -s ENCFMT:* 2>&1 | grep -E "PASS|Exception|Error" | tail -40
echo "== CRASH BUFFER"
adb logcat -d -b crash 2>&1 | tail -60
echo "== DONE"
