#!/usr/bin/env bash
# Ore Kingdom cold-run proof (PLAN_ARCHITECT Phase 5, variant (i) for tag
# builds: the signed candidate against its production API base, whose host
# may not be live yet at tag time).
#
# Runs inside reactivecircus/android-emulator-runner on a fresh AVD
# (first-run, no-wallet state by construction — no wallet app exists on
# the AVD). Installs the signed release APK, cold-launches it, and
# machine-checks the gate's behavioral standards:
#   - install succeeds; launch completes; no FATAL EXCEPTION in logcat;
#   - the WebView renders and the app reaches a GRACEFUL state: forum
#     content (host live), the wrapper's error state with Retry, or the
#     shell's native error UI — never a parked loader, never a blank page;
#   - the offline variant (radios off) reaches the same graceful class.
# Which graceful state was observed is recorded, not hidden:
# verdict line state=content|error-ui|unknown.
#
# Evidence lands in ./verify-evidence/ (uploaded by the workflow).
# The script exits non-zero if any line is FAIL: a red job blocks the seal.
set -u

PKG="com.raaru.orekingdom"
ACT="com.raaru.orekingdom/.MainActivity"
OUT="verify-evidence"
TMPD="$(mktemp -d)"

APK_PATH="${APK_PATH:-}"
if [ -z "$APK_PATH" ]; then
  if [ -f "release-apk/app-release.apk" ]; then
    APK_PATH="release-apk/app-release.apk"
  else
    APK_PATH="$(find . -name 'app-release.apk' -not -path './verify-evidence/*' | head -n 1)"
  fi
fi

mkdir -p "$OUT"

install_v="FAIL"; launch_ms_v="FAIL"; crash_v="FAIL"
state_v="unknown"; offline_v="FAIL"

dump_ui() { # $1 = destination file
  adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1
  adb pull /sdcard/window_dump.xml "$1" >/dev/null 2>&1
}

classify_state() { # $1 = hierarchy file, $2 = screenshot file -> echoes class
  local hier="$1" shot="$2"
  if grep -q "Ore Kingdom" "$hier" && grep -qiE "miners|forum|General|Mining|thread" "$hier"; then
    echo "content"; return
  fi
  if grep -qiE "could not load|Retry|Something went wrong|error" "$hier"; then
    echo "error-ui"; return
  fi
  # Fallback: a non-blank screenshot with a live WebView counts as rendered
  # content whose text the accessibility tree did not expose.
  local sz=0
  [ -f "$shot" ] && sz="$(stat -c %s "$shot" 2>/dev/null || echo 0)"
  if grep -q "android.webkit.WebView" "$hier" && [ "$sz" -gt 40000 ]; then
    echo "content"; return
  fi
  echo "unknown"
}

echo "== APK under test: $APK_PATH"
[ -n "$APK_PATH" ] && [ -f "$APK_PATH" ] && sha256sum "$APK_PATH" | tee "$OUT/apk-sha256.txt"
adb wait-for-device
for _ in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done

# --- Install on the fresh AVD (first-run state by construction) ---
if [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ] && adb install -r "$APK_PATH" > "$TMPD/install.log" 2>&1; then
  install_v="ok"
else
  cat "$TMPD/install.log" 2>/dev/null || true
fi
adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode" | head -2 > "$OUT/version.txt" || true

# --- Cold launch, timed ---
adb logcat -c >/dev/null 2>&1 || true
start_ms="$(date +%s%3N)"
am_out="$(adb shell am start -W -n "$ACT" 2>&1)"
end_ms="$(date +%s%3N)"
total_time="$(printf '%s' "$am_out" | sed -n 's/.*TotalTime: \([0-9]*\).*/\1/p' | head -n 1)"
if [ -n "$total_time" ]; then launch_ms_v="$total_time"; else launch_ms_v="$((end_ms - start_ms))"; fi
echo "== am start -W: $am_out"

# Settle past the wrapper watchdog (8s): content, or the error state.
sleep 16
adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
if [ "$install_v" = "ok" ] && ! grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  crash_v="0"
fi
dump_ui "$OUT/hierarchy-cold.xml"
adb exec-out screencap -p > "$OUT/cold.png" 2>/dev/null || true
state_v="$(classify_state "$OUT/hierarchy-cold.xml" "$OUT/cold.png")"
echo "== cold state: $state_v"

# --- Offline variant: radios off, relaunch ---
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb shell svc wifi disable >/dev/null 2>&1 || true
adb shell svc data disable >/dev/null 2>&1 || true
sleep 2
adb logcat -c >/dev/null 2>&1 || true
adb shell am start -W -n "$ACT" >/dev/null 2>&1
sleep 16
adb logcat -d > "$OUT/logcat-offline.txt" 2>&1 || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat-offline.txt"; then
  crash_v="FAIL"
fi
dump_ui "$OUT/hierarchy-offline.xml"
adb exec-out screencap -p > "$OUT/offline.png" 2>/dev/null || true
offline_state="$(classify_state "$OUT/hierarchy-offline.xml" "$OUT/offline.png")"
echo "== offline state: $offline_state"
# Offline, content can only be the bundled shell's graceful states; both
# content (local shell UI) and error-ui are graceful. Unknown is a park/blank.
if [ "$offline_state" = "content" ] || [ "$offline_state" = "error-ui" ]; then
  offline_v="ok"
fi

# --- Verdict ---
{
  echo "install=$install_v"
  echo "launch_ms=$launch_ms_v"
  echo "crash_lines=$crash_v"
  echo "state=$state_v"
  echo "offline=$offline_v"
  echo "offline_state=$offline_state"
} > "$OUT/verdict.txt"
cat "$OUT/verdict.txt"

if [ "$install_v" != "ok" ] || [ "$crash_v" != "0" ] || [ "$launch_ms_v" = "FAIL" ] \
   || [ "$state_v" = "unknown" ] || [ "$offline_v" != "ok" ]; then
  echo "verify-cold-run: FAIL"
  exit 1
fi
echo "verify-cold-run: PASS"
exit 0
