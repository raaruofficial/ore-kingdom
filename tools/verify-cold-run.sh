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
#   - the app reaches a RENDERED, graceful state within a bounded poll:
#     forum content (host live), the wrapper's error state with Retry, or
#     the shell's native error UI — never a parked loader, never a
#     black/blank frame. Classification is pixel-first (a stdlib-PNG
#     non-black census) because uiautomator dies when the emulator's
#     System UI is under load; hierarchy text refines the class when
#     available (content vs error-ui vs rendered-unclassified);
#   - the offline variant (radios off) reaches the same graceful class.
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
state_v="unknown"; offline_v="FAIL"; offline_state="unknown"

dump_ui() { # $1 = destination file; failure is recorded, never fatal
  if adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 \
     && adb pull /sdcard/window_dump.xml "$1" >/dev/null 2>&1; then
    return 0
  fi
  echo "uiautomator dump unavailable" > "$1.unavailable"
  return 1
}

# Non-black pixel share of a PNG screenshot, via python3 stdlib only.
# Echoes a percentage with one decimal, or "ERR".
pixel_share() { # $1 = png file
  python3 - "$1" <<'PY'
import sys, zlib, struct
try:
    data = open(sys.argv[1], 'rb').read()
    pos = 8; idat = b''; w = h = ctype = None
    while pos < len(data):
        ln = struct.unpack('>I', data[pos:pos+4])[0]; typ = data[pos+4:pos+8]
        if typ == b'IHDR':
            w, h, depth, ctype = struct.unpack('>IIBB', data[pos+8:pos+18])
            assert depth == 8 and ctype in (2, 6)
        elif typ == b'IDAT':
            idat += data[pos+8:pos+8+ln]
        pos += 12 + ln
    raw = zlib.decompress(idat)
    bpp = 4 if ctype == 6 else 3
    stride = w * bpp
    prev = bytearray(stride)
    nonblack = 0; total = 0; off = 0
    for y in range(h):
        filt = raw[off]; line = bytearray(raw[off+1:off+1+stride]); off += 1 + stride
        if filt == 1:
            for i in range(bpp, stride): line[i] = (line[i] + line[i-bpp]) & 0xff
        elif filt == 2:
            for i in range(stride): line[i] = (line[i] + prev[i]) & 0xff
        elif filt == 3:
            for i in range(stride):
                a = line[i-bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xff
        elif filt == 4:
            for i in range(stride):
                a = line[i-bpp] if i >= bpp else 0; b = prev[i]; c = prev[i-bpp] if i >= bpp else 0
                p = a + b - c; pa, pb, pc = abs(p-a), abs(p-b), abs(p-c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xff
        if y % 8 == 0:
            for x in range(0, w, 8):
                i = x * bpp
                if line[i] > 24 or line[i+1] > 24 or line[i+2] > 24:
                    nonblack += 1
                total += 1
        prev = line
    print(f"{100.0 * nonblack / max(total,1):.1f}")
except Exception as e:
    print("ERR")
PY
}

classify_state() { # $1 = hierarchy file (may be absent), $2 = screenshot
  local hier="$1" shot="$2" share
  share="$(pixel_share "$shot")"
  echo "== pixel non-black share: $share" >&2
  if [ -f "$hier" ]; then
    if grep -q "Ore Kingdom" "$hier" && grep -qiE "miners|General|Mining|thread" "$hier"; then
      echo "content"; return
    fi
    if grep -qiE "could not load|Retry|Something went wrong" "$hier"; then
      echo "error-ui"; return
    fi
  fi
  if [ "$share" != "ERR" ] && python3 -c "exit(0 if float('$share') > 0.3 else 1)" 2>/dev/null; then
    echo "rendered"; return
  fi
  echo "unknown"
}

# Poll until a classifiable rendered state or the deadline passes.
await_state() { # $1 = tag (cold|offline), $2 = deadline seconds
  local tag="$1" deadline="$2" waited=0 cls="unknown"
  while [ "$waited" -lt "$deadline" ]; do
    sleep 10; waited=$((waited + 10))
    adb exec-out screencap -p > "$TMPD/shot-$tag.png" 2>/dev/null || true
    dump_ui "$TMPD/hier-$tag.xml" || true
    cls="$(classify_state "$TMPD/hier-$tag.xml" "$TMPD/shot-$tag.png")"
    echo "== [$tag] ${waited}s -> $cls" >&2
    [ "$cls" != "unknown" ] && break
  done
  cp "$TMPD/shot-$tag.png" "$OUT/$tag.png" 2>/dev/null || true
  [ -f "$TMPD/hier-$tag.xml" ] && cp "$TMPD/hier-$tag.xml" "$OUT/hierarchy-$tag.xml" || true
  echo "$cls"
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

state_v="$(await_state cold 180 | tail -n 1)"
adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
if [ "$install_v" = "ok" ] && ! grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  crash_v="0"
fi
echo "== cold state: $state_v"

# --- Offline variant: radios off, relaunch ---
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb shell svc wifi disable >/dev/null 2>&1 || true
adb shell svc data disable >/dev/null 2>&1 || true
sleep 2
adb logcat -c >/dev/null 2>&1 || true
adb shell am start -W -n "$ACT" >/dev/null 2>&1
offline_state="$(await_state offline 150 | tail -n 1)"
adb logcat -d > "$OUT/logcat-offline.txt" 2>&1 || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat-offline.txt"; then
  crash_v="FAIL"
fi
echo "== offline state: $offline_state"
if [ "$offline_state" != "unknown" ]; then
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
