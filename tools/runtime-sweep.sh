#!/usr/bin/env bash
# Ore Kingdom runtime sweep (ESTATE ALERT 2026-10-29, FUN-001 class).
#
# HAUNT's FUN-001 rejection proved static analysis is not runtime
# evidence: its "crash" was an error-boundary takeover, and its shell
# carried three named native risks (render-process recreate loop,
# uncaught WebView-constructor throw, capture-black). This sweep
# runtime-verifies the Ore Kingdom shell on a real (emulated) device:
#   A. fresh install -> first launch -> rendered state, no system
#      "has stopped" dialog, no FATAL for our process;
#   B. EVERY bottom-nav destination exercised by tap: after each, the
#      process must be alive, our activity must be the resumed one,
#      and no system crash dialog may be present;
#   C. WebView provider DISABLED -> relaunch: the shell must degrade
#      (native surface or a clean exit), never die with an uncaught
#      FATAL EXCEPTION. Any stack is captured verbatim to stacks.txt.
# Legs (API span / low-RAM / slow network) are workflow matrix entries;
# this script is leg-agnostic. Evidence lands in ./sweep-evidence-$LEG/.
set -u

PKG="com.raaru.orekingdom"
ACT="com.raaru.orekingdom/.MainActivity"
LEG="${LEG:-api35}"
OUT="sweep-evidence-$LEG"
TMPD="$(mktemp -d)"
mkdir -p "$OUT"
FAILS=0

APK_PATH="${APK_PATH:-}"
if [ -z "$APK_PATH" ]; then
  if [ -f "release-apk/app-release.apk" ]; then APK_PATH="release-apk/app-release.apk";
  else APK_PATH="$(find . -name 'app-release.apk' -not -path './sweep-evidence*' | head -n 1)"; fi
fi

note() { echo "== $*" >&2; }
fail() { echo "FAIL: $*" | tee -a "$OUT/failures.txt"; FAILS=$((FAILS + 1)); }

dump_ui() {
  if adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 \
     && adb pull /sdcard/window_dump.xml "$1" >/dev/null 2>&1; then return 0; fi
  echo "uiautomator dump unavailable" > "$1.unavailable"; return 1
}

pixel_share() { # stdlib PNG non-black census, app area only (as verify v3+)
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
        if y % 8 == 0 and h * 0.04 < y < h * 0.92:
            for x in range(0, w, 8):
                i = x * bpp
                if line[i] > 24 or line[i+1] > 24 or line[i+2] > 24: nonblack += 1
                total += 1
        prev = line
    print(f"{100.0 * nonblack / max(total,1):.1f}")
except Exception:
    print("ERR")
PY
}

dismiss_anr() { # tap "Wait" if a system ANR dialog is up; record if it is a CRASH dialog
  local hier="$1" line bounds nums x y
  [ -f "$hier" ] || return 1
  if grep -q "has stopped" "$hier"; then
    grep -oE 'text="[^"]*has stopped[^"]*"' "$hier" | head -n 1 >> "$OUT/failures.txt"
    return 2
  fi
  grep -q "isn't responding" "$hier" || return 1
  line="$(grep -oE '<node[^>]*text="Wait"[^>]*>' "$hier" | head -n 1)"
  bounds="$(printf '%s' "$line" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -n 1)"
  [ -n "$bounds" ] || return 1
  nums="$(printf '%s' "$bounds" | tr -cs '0-9' ' ')"
  # shellcheck disable=SC2086
  set -- $nums
  x=$(( ($1 + $3) / 2 )); y=$(( ($2 + $4) / 2 ))
  note "ANR dialog present; tapping Wait at $x,$y"
  adb shell input tap "$x" "$y" >/dev/null 2>&1 || true
  sleep 3
  return 0
}

shot() { adb exec-out screencap -p > "$1" 2>/dev/null || true; }

fatal_since() { # $1 = logcat file; echoes count of our-process FATAL markers
  # v1 bug: `grep -c ... || echo 0` printed the count AND the fallback
  # (grep -c exits 1 on zero matches), producing "0\n0" != "0" and 13
  # phantom FATAL failures across the first run. The counts were 0.
  local c
  c="$(grep -c "Process: $PKG" "$1" 2>/dev/null || true)"
  echo "${c:-0}"
}

capture_stacks() { # $1 = logcat file, $2 = label
  if grep -q "Process: $PKG" "$1" 2>/dev/null; then
    { echo "### stacks ($2)"; grep -B2 -A25 "Process: $PKG" "$1" | head -n 120; } >> "$OUT/stacks.txt"
  fi
}

alive_and_fg() { # echoes ok|dead|background
  # v2 probe: mCurrentFocus exists across API 28-35; v1's
  # topResumedActivity token does not exist on API 28 (it is
  # ResumedActivity there), which read a healthy app as "background"
  # for an entire leg.
  local pid focus
  pid="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')"
  [ -z "$pid" ] && { echo "dead"; return; }
  focus="$(adb shell dumpsys window windows 2>/dev/null | grep -m1 "mCurrentFocus" || true)"
  if [ -z "$focus" ]; then
    focus="$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|ResumedActivity" || true)"
  fi
  case "$focus" in *"$PKG"*) echo "ok" ;; *) echo "background" ;; esac
}

app_window_bottom() { # echoes the app window's bottom edge in px, or screen height
  local hier="$1" b
  b="$(grep -oE 'bounds="\[0,0\]\[[0-9]+,[0-9]+\]"' "$hier" 2>/dev/null | head -n 1 | grep -oE '[0-9]+' | tail -n 1)"
  if [ -n "$b" ] && [ "$b" -gt 0 ] 2>/dev/null; then echo "$b"; else echo "$SH"; fi
}

echo "== leg: $LEG; APK: $APK_PATH"
[ -n "$APK_PATH" ] && [ -f "$APK_PATH" ] && sha256sum "$APK_PATH" | tee "$OUT/apk-sha256.txt"
adb wait-for-device
for _ in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
dump_ui "$TMPD/hier-boot.xml" && dismiss_anr "$TMPD/hier-boot.xml" || true
# Gesture navigation (API 29+) frees the bottom strip: with the
# 3-button bar, v1's tap row overlapped the system buttons and one
# tap hit BACK, ejecting to the launcher — the alert's symptom,
# produced by the harness. Harmless no-op where unsupported.
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural >/dev/null 2>&1 || true
sleep 2
adb shell cmd webviewupdate getCurrentWebViewPackage > "$OUT/webview-provider.txt" 2>&1 || true

# ---------------- Phase A: fresh install + first launch ----------------
if [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ] && adb install -r "$APK_PATH" > "$TMPD/install.log" 2>&1; then
  echo "install=ok" >> "$OUT/verdict.txt"
else
  fail "install failed"; cat "$TMPD/install.log" >&2 || true
fi
adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode" | head -2 > "$OUT/version.txt" || true
adb logcat -c >/dev/null 2>&1 || true
adb shell am start -W -n "$ACT" > "$TMPD/am.log" 2>&1 || true

# Await a rendered frame (share above the near-black floor). If the
# frame looks like the wrapper's sparse error state (share < 2.0) after
# a grace period, tap its Retry once — the exact recovery a reviewer
# would use — and keep waiting.
W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -n 1)"
SW="${W%x*}"; SH="${W#*x}"; [ -n "$SW" ] && [ -n "$SH" ] || { SW=1440; SH=3120; }
share="0"; waited=0; retried=0
while [ "$waited" -lt 150 ]; do
  sleep 10; waited=$((waited + 10))
  shot "$TMPD/a.png"; dump_ui "$TMPD/a.xml" || true
  if [ -f "$TMPD/a.xml" ]; then
    dismiss_anr "$TMPD/a.xml"; rc=$?
    [ "$rc" = "2" ] && fail "phase A: system 'has stopped' dialog present"
  fi
  share="$(pixel_share "$TMPD/a.png")"
  note "[A] ${waited}s share=$share alive=$(alive_and_fg)"
  if [ "$share" != "ERR" ] && python3 -c "exit(0 if float('$share') > 2.0 else 1)" 2>/dev/null; then break; fi
  if [ "$waited" -ge 40 ] && [ "$retried" = "0" ] && [ "$share" != "ERR" ] \
     && python3 -c "exit(0 if 0.3 < float('$share') < 2.0 else 1)" 2>/dev/null; then
    note "[A] sparse frame (probable wrapper error state); tapping Retry"
    adb shell input tap $((SW / 2)) $((SH * 575 / 1000)) >/dev/null 2>&1 || true
    retried=1; sleep 8
  fi
done
cp "$TMPD/a.png" "$OUT/phase-a.png" 2>/dev/null || true
[ -f "$TMPD/a.xml" ] && cp "$TMPD/a.xml" "$OUT/hierarchy-a.xml" || true
share="$(pixel_share "$OUT/phase-a.png")"  # final-frame share, from the saved file
adb logcat -d > "$OUT/logcat-a.txt" 2>&1 || true
capture_stacks "$OUT/logcat-a.txt" "phase A"
st="$(alive_and_fg)"
[ "$st" = "ok" ] || fail "phase A: app state after launch = $st"
[ "$(fatal_since "$OUT/logcat-a.txt")" = "0" ] || fail "phase A: FATAL for $PKG in logcat"
echo "phase_a_share=$share phase_a_state=$st retried=$retried" >> "$OUT/verdict.txt"

# ---------------- Phase B: exercise every nav destination -------------
# Bottom nav: 6 destinations; labels read from the live build (Forum,
# Motherlode, Search, Alerts, Safe, Profile). The tap row is anchored
# to the APP WINDOW's bottom edge (the window excludes the system bar
# on API 28 and includes it edge-to-edge on 35) minus a small inset.
WB="$(app_window_bottom "$OUT/hierarchy-a.xml")"
TY=$((WB - SH * 22 / 1000))
note "nav tap row: window_bottom=$WB ty=$TY"
for i in 0 1 2 3 4 5; do
  TX=$((SW * (2 * i + 1) / 12))
  adb logcat -c >/dev/null 2>&1 || true
  adb shell input tap "$TX" "$TY" >/dev/null 2>&1 || true
  sleep 4
  shot "$OUT/nav-$i.png"; dump_ui "$TMPD/nav.xml" || true
  if [ -f "$TMPD/nav.xml" ]; then
    dismiss_anr "$TMPD/nav.xml"; rc=$?
    [ "$rc" = "2" ] && fail "nav $i: system 'has stopped' dialog present"
  fi
  adb logcat -d > "$OUT/logcat-nav-$i.txt" 2>&1 || true
  capture_stacks "$OUT/logcat-nav-$i.txt" "nav $i"
  st="$(alive_and_fg)"
  nf="$(fatal_since "$OUT/logcat-nav-$i.txt")"
  echo "nav_${i}_state=$st nav_${i}_fatal=$nf nav_${i}_share=$(pixel_share "$OUT/nav-$i.png")" >> "$OUT/verdict.txt"
  [ "$st" = "ok" ] || fail "nav $i: app state = $st (ejected or dead)"
  [ "$nf" = "0" ] || fail "nav $i: FATAL for $PKG in logcat"
done

# ---------------- Phase C: WebView provider disabled ------------------
WVPKG="$(adb shell cmd webviewupdate getCurrentWebViewPackage 2>/dev/null | sed -n 's/.*(\(.*\),.*/\1/p' | head -n 1 | tr -d '\r ')"
[ -n "$WVPKG" ] || WVPKG="com.google.android.webview"
note "WebView provider: $WVPKG"
# v1 used `pm disable`, which did NOT remove the provider (the app
# rendered the full forum afterward) — phase C tested nothing. v2
# removes the provider for user 0 and VERIFIES no provider remains
# current; if one still does, the phase records INCONCLUSIVE.
adb shell pm uninstall --user 0 "$WVPKG" > "$TMPD/nowv-uninstall.log" 2>&1 || true
adb shell cmd webviewupdate getCurrentWebViewPackage > "$OUT/webview-provider-after.txt" 2>&1 || true
if grep -q "$WVPKG" "$OUT/webview-provider-after.txt"; then
  echo "nowv_phase=INCONCLUSIVE (provider still current after uninstall attempt)" >> "$OUT/verdict.txt"
  note "phase C inconclusive: provider still current"
fi
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true
adb shell am start -W -n "$ACT" > "$TMPD/am-nowv.log" 2>&1 || true
sleep 12
shot "$OUT/nowv.png"; dump_ui "$TMPD/nowv.xml" || true
sleep 8
shot "$OUT/nowv2.png"
if [ -f "$TMPD/nowv.xml" ]; then
  dismiss_anr "$TMPD/nowv.xml"; rc=$?
  [ "$rc" = "2" ] && fail "phase C: system 'has stopped' dialog with WebView disabled"
fi
adb logcat -d > "$OUT/logcat-nowv.txt" 2>&1 || true
capture_stacks "$OUT/logcat-nowv.txt" "phase C (WebView disabled)"
st="$(alive_and_fg)"
nf="$(fatal_since "$OUT/logcat-nowv.txt")"
echo "nowv_provider=$WVPKG nowv_state=$st nowv_fatal=$nf" >> "$OUT/verdict.txt"
if [ "$nf" != "0" ]; then
  fail "phase C: FATAL for $PKG with WebView provider disabled (stack in stacks.txt)"
elif [ "$st" = "dead" ]; then
  # A clean exit with no FATAL is a degradation, recorded, not a crash.
  echo "phase C note: process exited without FATAL (clean-exit degradation)" >> "$OUT/verdict.txt"
fi
adb shell cmd package install-existing --user 0 "$WVPKG" >/dev/null 2>&1 \
  || adb shell pm enable "$WVPKG" >/dev/null 2>&1 || true

# ---------------- Verdict ----------------------------------------------
echo "failures=$FAILS" >> "$OUT/verdict.txt"
cat "$OUT/verdict.txt"
[ -f "$OUT/stacks.txt" ] && { echo "--- stacks ---"; cat "$OUT/stacks.txt"; }
if [ "$FAILS" != "0" ]; then echo "runtime-sweep: FAIL ($LEG)"; exit 1; fi
echo "runtime-sweep: PASS ($LEG)"
exit 0
