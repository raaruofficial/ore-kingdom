#!/usr/bin/env bash
# Ore Kingdom native client — M4 gate, runtime leg.
#
# Cold-run proof for ONE variant against the debug APK, run INSIDE a booted
# CI emulator (invoked by .github/workflows/android-emulator.yml via
# reactivecircus/android-emulator-runner). Variants:
#
#   online    fresh install, network up: cold start must reach the River
#             (the forum's first screen) rendered from the live host —
#             board filter chips + threads, NOT the unreachable card.
#   nowallet  fresh install on a stock image (no third-party wallet can
#             exist): cold start must reach the same readable River with
#             our activity on top — no wallet prompt may block reading.
#   offline   fresh install, airplane mode ON (proven in evidence):
#             cold start must render the host-unreachable card with its
#             Retry action — never a blank screen, never a crash.
#
# Every variant also asserts: our process alive at the end, resumed
# activity is ours, and no FATAL EXCEPTION for our process in logcat.
# Evidence (hierarchy dump, logcat, screenshot, checks) lands in the
# evidence dir; verdict.txt carries the one-line verdict. Exit code is
# the gate: 0 = PASS, 1 = FAIL.
#
# Usage: emulator-cold-run.sh <variant> <apk-path> <evidence-dir>

set -uo pipefail

VARIANT="${1:?variant required: online|nowallet|offline}"
APK="${2:?apk path required}"
EVIDENCE="${3:?evidence dir required}"
mkdir -p "$EVIDENCE"

fail() { echo "FAIL: $*" | tee "$EVIDENCE/verdict.txt"; exit 1; }
note() { echo "[cold-run:$VARIANT] $*"; }

# --- Package of record: derive from the APK itself, never assume. ----------
PKG=""
if [ -n "${ANDROID_HOME:-}" ]; then
  AAPT="$(ls "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
  [ -z "$AAPT" ] && AAPT="$(ls "$ANDROID_HOME"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1)"
  if [ -n "$AAPT" ]; then
    PKG="$("$AAPT" dump badging "$APK" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
  fi
fi
[ -z "$PKG" ] && PKG="com.raaru.orekingdom.debug"
ACTIVITY="$PKG/com.raaru.orekingdom.MainActivity"
note "package=$PKG activity=$ACTIVITY variant=$VARIANT"
echo "$PKG" > "$EVIDENCE/package.txt"

adb wait-for-device
adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 2; done' || true

# --- Variant preconditions ---------------------------------------------------
if [ "$VARIANT" = "nowallet" ]; then
  # A stock google_apis image ships no third-party apps; the only -3 package
  # allowed at verdict time is ours (installed below). Recorded BEFORE
  # install too, so the evidence shows the pristine set.
  adb shell pm list packages -3 > "$EVIDENCE/packages-thirdparty-before.txt" || true
fi

if [ "$VARIANT" = "offline" ]; then
  # Airplane mode, proven — not merely attempted. google_apis images allow
  # adb root, which the airplane broadcast needs on API 35.
  adb root >/dev/null 2>&1 || true
  adb wait-for-device
  adb shell settings put global airplane_mode_on 1 || true
  adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1 || true
  adb shell svc wifi disable >/dev/null 2>&1 || true
  adb shell svc data disable >/dev/null 2>&1 || true
  sleep 3
  {
    echo "airplane_mode_on=$(adb shell settings get global airplane_mode_on 2>/dev/null)"
    echo "--- ping 8.8.8.8 (must fail offline) ---"
    adb shell ping -c 1 -W 2 8.8.8.8 2>&1 || echo "ping failed as expected"
    echo "--- dumpsys connectivity (head) ---"
    adb shell dumpsys connectivity 2>/dev/null | head -40
  } > "$EVIDENCE/offline-precondition.txt"
  grep -q "airplane_mode_on=1" "$EVIDENCE/offline-precondition.txt" \
    || fail "offline precondition unproven: airplane_mode_on != 1"
fi

# --- Fresh install -----------------------------------------------------------
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -g "$APK" | tee "$EVIDENCE/install.txt"
grep -q "Success" "$EVIDENCE/install.txt" || fail "APK install did not succeed"

if [ "$VARIANT" = "nowallet" ]; then
  adb shell pm list packages -3 > "$EVIDENCE/packages-thirdparty-after.txt" || true
  # Every third-party package other than ours is a potential wallet — the
  # stock image must show none. Ours is the single allowed line.
  OTHERS="$(grep -v "package:$PKG" "$EVIDENCE/packages-thirdparty-after.txt" | grep -c . || true)"
  [ "$OTHERS" = "0" ] || fail "no-wallet precondition false: $OTHERS other third-party packages present"
fi

# --- Cold launch ---------------------------------------------------------------
adb logcat -c || true
adb shell am force-stop "$PKG" || true
adb shell am start -W -n "$ACTIVITY" | tee "$EVIDENCE/launch.txt" || true
grep -q "Status: ok" "$EVIDENCE/launch.txt" || note "am start -W did not report Status: ok (see launch.txt)"

# --- Settle: poll the UI hierarchy until the variant's marker appears --------
# uiautomator dump refuses while the UI never idles (the loading spinner),
# so a failed/empty dump during Loading is expected and retried.
dump_ui() {
  adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || return 1
  adb exec-out cat /sdcard/window_dump.xml 2>/dev/null || return 1
}
marker_present() {
  local xml="$1"
  case "$VARIANT" in
    online|nowallet)
      grep -q 'text="Ore Kingdom"' <<<"$xml" \
        && grep -q 'text="All"' <<<"$xml" \
        && ! grep -q 'could not load' <<<"$xml" ;;
    offline)
      grep -q 'could not load' <<<"$xml" && grep -q 'text="Retry"' <<<"$xml" ;;
  esac
}

XML=""
for i in $(seq 1 10); do
  sleep 6
  XML="$(dump_ui)" || XML=""
  if [ -n "$XML" ] && marker_present "$XML"; then
    note "variant marker found on poll $i"
    break
  fi
  XML=""
done
[ -n "$XML" ] || { adb logcat -d > "$EVIDENCE/logcat.txt" 2>/dev/null || true; fail "expected UI state for variant '$VARIANT' never rendered (see logcat.txt)"; }
printf '%s' "$XML" > "$EVIDENCE/window_dump.xml"

# Text-node census: a blank activity has ~zero text nodes.
TEXT_NODES="$(grep -o 'text="[^"]\+"' "$EVIDENCE/window_dump.xml" | grep -vc 'text=""' || true)"
echo "text_nodes=$TEXT_NODES" > "$EVIDENCE/checks.txt"
[ "${TEXT_NODES:-0}" -ge 5 ] || fail "hierarchy nearly blank: only $TEXT_NODES text nodes"

# --- Common assertions ---------------------------------------------------------
PID="$(adb shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')"
echo "pid=$PID" >> "$EVIDENCE/checks.txt"
[ -n "$PID" ] || fail "app process not alive after cold start"

adb shell dumpsys activity activities > "$EVIDENCE/activities.txt" 2>/dev/null || true
grep -m1 "topResumedActivity" "$EVIDENCE/activities.txt" >> "$EVIDENCE/checks.txt" || true
grep -m1 "topResumedActivity" "$EVIDENCE/activities.txt" | grep -q "$PKG" \
  || fail "resumed activity is not ours (another surface is on top)"

adb logcat -d > "$EVIDENCE/logcat.txt" 2>/dev/null || true
if grep -A3 "FATAL EXCEPTION" "$EVIDENCE/logcat.txt" | grep -q "Process: $PKG"; then
  grep -B2 -A12 "FATAL EXCEPTION" "$EVIDENCE/logcat.txt" > "$EVIDENCE/crash.txt" || true
  fail "FATAL EXCEPTION for $PKG in logcat (see crash.txt)"
fi
# Belt-and-braces: any AndroidRuntime crash block naming our process.
if grep -q "AndroidRuntime: Process: $PKG" "$EVIDENCE/logcat.txt"; then
  fail "AndroidRuntime crash record for $PKG in logcat"
fi
echo "fatal_exceptions_for_pkg=0" >> "$EVIDENCE/checks.txt"

adb exec-out screencap -p > "$EVIDENCE/screen.png" 2>/dev/null || true

case "$VARIANT" in
  online)   MSG="cold start rendered the River from the live host (chips + content, no unreachable card); process alive; no crash" ;;
  nowallet) MSG="no third-party wallet on device; cold start reached the readable River with our activity on top; no blocking prompt; no crash" ;;
  offline)  MSG="airplane mode proven on; cold start rendered the host-unreachable card with Retry; process alive; no crash; never blank" ;;
esac
echo "VARIANT $VARIANT: PASS — $MSG" | tee "$EVIDENCE/verdict.txt"
exit 0
