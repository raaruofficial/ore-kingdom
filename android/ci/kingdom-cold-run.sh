#!/usr/bin/env bash
# Ore Kingdom — Discord client (KingdomActivity) cold-run against the
# LOCAL contract backend (server/kingdom) running on the CI host.
# The emulator reaches the host at 10.0.2.2:8799, which is the debug
# build's compiled-in KINGDOM_API_URL default.
#
# Asserts: (1) am start -W reports Status: ok for KingdomActivity and
# records TotalTime (the cold-start number for this surface);
# (2) the rendered UI hierarchy contains a channel name that exists
# ONLY in the backend seed (ch_announcements -> "announcements"),
# proving the client read its rail from the local backend end to end;
# (3) the app process stays alive with no AndroidRuntime crash.
#
# Surface honesty: CI emulator (API 35, x86_64, SwiftShader), debug
# APK. This is EMULATION evidence, never a device number.
set -u
APK="$1"
EVIDENCE="$2"
PKG="com.raaru.orekingdom.debug"
ACTIVITY="$PKG/com.raaru.orekingdom.kingdom.KingdomActivity"
mkdir -p "$EVIDENCE"
FAIL=0
note() { echo "NOTE: $1" | tee -a "$EVIDENCE/checks.txt"; }
fail() { echo "FAIL: $1" | tee -a "$EVIDENCE/checks.txt"; FAIL=1; }

adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 2; done' || true
adb install -r "$APK" | tee "$EVIDENCE/install.txt" || fail "APK install failed"

# The backend must be reachable from the emulator before launch.
for i in $(seq 1 15); do
  if adb shell 'curl -s http://10.0.2.2:8799/api/spotlight || wget -qO- http://10.0.2.2:8799/api/spotlight' > "$EVIDENCE/backend-probe.txt" 2>/dev/null && grep -q floor_usd "$EVIDENCE/backend-probe.txt"; then
    note "backend reachable from emulator at 10.0.2.2:8799"
    break
  fi
  sleep 2
  [ "$i" = 15 ] && fail "backend not reachable from emulator"
done

adb shell am force-stop "$PKG" || true
adb shell am start -W -n "$ACTIVITY" | tee "$EVIDENCE/launch.txt" || true
grep -q "Status: ok" "$EVIDENCE/launch.txt" || fail "am start -W did not report Status: ok"
grep "TotalTime" "$EVIDENCE/launch.txt" > "$EVIDENCE/cold-start.txt" || true

# Poll the UI hierarchy for the backend-seeded channel name.
FOUND=0
for i in $(seq 1 20); do
  sleep 3
  adb shell uiautomator dump /sdcard/window_dump.xml > /dev/null 2>&1 || true
  adb pull /sdcard/window_dump.xml "$EVIDENCE/window_dump.xml" > /dev/null 2>&1 || true
  if grep -q "announcements" "$EVIDENCE/window_dump.xml" 2>/dev/null; then
    FOUND=1
    note "UI contains the backend-seeded channel 'announcements' (poll $i)"
    break
  fi
done
[ "$FOUND" = 1 ] || fail "UI never showed the backend-seeded channel 'announcements'"

adb shell screencap -p /sdcard/screen.png > /dev/null 2>&1 || true
adb pull /sdcard/screen.png "$EVIDENCE/screen.png" > /dev/null 2>&1 || true
adb logcat -d > "$EVIDENCE/logcat.txt" 2>/dev/null || true

PID="$(adb shell pidof "$PKG" || true)"
[ -n "$PID" ] || fail "app process not alive after cold start"
if grep -q "AndroidRuntime: Process: $PKG" "$EVIDENCE/logcat.txt"; then
  fail "AndroidRuntime crash record for $PKG in logcat"
fi

if [ "$FAIL" = 0 ]; then
  echo "PASS: KingdomActivity cold start ok; rail rendered from the local backend; process alive; no crash" | tee "$EVIDENCE/verdict.txt"
else
  echo "FAIL: see checks.txt" | tee "$EVIDENCE/verdict.txt"
  exit 1
fi
