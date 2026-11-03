#!/usr/bin/env bash
# ORE KINGDOM — wallet-present runtime rig driver (estate section 2.2
# pattern; harness lineage HAUNT -> TITHE -> Citadel, cells rewritten
# for Ore Kingdom).
#
# Runs INSIDE one emulator step on the CI-KVM lane against the debug
# build of record, the official Mock MWA Wallet (pinned commit
# d444aff0, imported FRESH THROWAWAY key — the Ore Kingdom rig
# identity), and the REAL v0.1.4 staging host (Bun, on the runner)
# whose chain reads land on the rig stub chain
# (android/ci/rig-stub.ts): every transaction the host verifies was
# really built by the client and really signed by the wallet; the
# mock's broadcast endpoint is rig-patched to the stub (the mock is
# test equipment). The host copy carries ONE disclosed fixture shim
# (android/ci/rig-host-shim.patch): authenticateSiws is answered from
# the rig-seeded member row after the wrapper's own SIWS proof
# verification, because the bundle's .skr name resolution reads a
# compiled-in mainnet URL and the rig key owns no mainnet .skr name.
# Everything downstream of sign-in — sessions, payment verification,
# posting — runs the real host code, and the whole client is the real
# build.
#
# Suites (env SUITE):
#   core   = CR-1 SIWS sign-in (connect approve + sign approve) with
#            host-side member proof; CR-2 sign DECLINE -> clean error
#            surface, no session, in-process retry signs in.
#   kfc008 = CR-3 connect-path abandonment x3 reps (estate skill bar:
#            in-process resolution AND one-tap retry to signed-in,
#            same process, every rep); CR-4 sign-path abandonment x1;
#            CR-5 PAYMENT-path abandonment x1 (thread fee paid in
#            SOL): abandon at the wallet send sheet -> the paying
#            state resets, NOTHING parks, the host session stays
#            unconsumed; the in-process retry posts the thread and
#            consumes its session exactly once.
#   all    = core + kfc008.
#
# RESOLUTION BAR (registry KFC-008 + VR-12 precedent): an abandoned
# request must resolve to session truth within a 30 s poll window
# counted from the return to the app. The cured build resolves in
# ~5 s (reconciler grace); a build with the cure inert hangs past
# the window — that gap is what makes the cell discriminate, and the
# rig-control lane proves it against the neutered build.
#
# Grades ride on the app's own UI state strings, the rig host's DB
# rows, and the WalletManager's OreKingdomWallet log markers. Any
# AndroidRuntime FATAL in either package fails the whole verdict. A
# dispatched cell ending NOT-RUN is a rig failure.
set -u

PKG="com.raaru.orekingdom.debug"
ACT="com.raaru.orekingdom.debug/com.raaru.orekingdom.MainActivity"
MOCK="com.solana.mwallet"
MOCK_ACT="com.solana.mwallet/.MainActivity"
OUT="${RIG_OUT:-wallet-rig-evidence}"
SUITE="${SUITE:-all}"
APP_APK="${OK_APK:-}"
MOCK_APK="${MOCK_APK:-}"
EXPECTED_PUBKEY="${EXPECTED_PUBKEY:-}"
EXPECTED_TRUNC="${EXPECTED_TRUNC:-}"
RIG_SKR_NAME="${RIG_SKR_NAME:-rigwallet.skr}"
RIG_DB="${RIG_DB_PATH:-}"
DEVICE_PIN="${DEVICE_PIN:-1234}"
RELAY="BiuPWZ4kiwJjyptxS5Uetr89wTb19TMdWnA4JyZEr1fK"
OPS="GZzwVPjZ3AhnQznVpZoz14LM6YEBgtjZx7g9XYCXLBgQ"
TMPD="$(mktemp -d)"
LANE_LOG="$OUT/wallet-rig-lane.log"
AUTH_EPOCH_FILE="$OUT/auth-epoch.txt"

mkdir -p "$OUT"
: > "$LANE_LOG"
log() { echo "$*" | tee -a "$LANE_LOG"; }

# --- verdict state -------------------------------------------------------------
cr1="NOT-RUN"; cr2="NOT-RUN"; cr3="NOT-RUN"; cr4="NOT-RUN"; cr5="NOT-RUN"
fatal_app_total="0"; fatal_mock_total="0"; ise_total="0"; stopped_dialogs="0"
lock_pin="unknown"; lock_fp="unknown"; auth_note="not attempted"; seed_note="not attempted"

# --- hierarchy helpers (HAUNT driver lineage, JUDGE-hardened) ------------------
dump_is_substantive() { # rejects hollow mid-transition dumps
  python3 - "$1" <<'PY'
import re, sys
data = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
nodes = re.findall(r"<node[^>]*>", data)
if len(nodes) >= 3:
    sys.exit(0)
for n in nodes:
    if re.search(r'text="[^"]+"', n) or re.search(r'content-desc="[^"]+"', n) or "WebView" in n:
        sys.exit(0)
sys.exit(1)
PY
}

dump_ui() { # $1 = destination file
  local dst="$1" attempt
  for attempt in 1 2 3 4 5 6 7 8; do
    adb shell rm -f /sdcard/window_dump.xml >/dev/null 2>&1 || true
    timeout 40 adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
    adb exec-out cat /sdcard/window_dump.xml > "$dst.tmp" 2>/dev/null || true
    if grep -q "</hierarchy>" "$dst.tmp" 2>/dev/null && dump_is_substantive "$dst.tmp"; then
      mv "$dst.tmp" "$dst"
      return 0
    fi
    sleep 3
  done
  log "dump_ui: FAILED after 8 attempts -> $dst"
  return 1
}

_tap_match() { # $1 = file, $2 = attr, $3 = needle, $4 = exact(0/1), $5 = first|last
  python3 - "$1" "$2" "$3" "$4" "$5" <<'PY'
import re, sys
data = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
attr, needle, exact = sys.argv[2], sys.argv[3], sys.argv[4] == "1"
want_last = len(sys.argv) > 5 and sys.argv[5] == "last"
found = None
for m in re.finditer(r"<node[^>]*>", data):
    tag = m.group(0)
    a = re.search(attr + r'="([^"]*)"', tag)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if a and b and ((a.group(1) == needle) if exact else (needle in a.group(1))):
        x = (int(b.group(1)) + int(b.group(3))) // 2
        y = (int(b.group(2)) + int(b.group(4))) // 2
        found = f"{x} {y}"
        if not want_last:
            break
if found:
    print(found)
PY
}

tap_text() { # $1 = file, $2 = substring of text (first match)
  local coords; coords="$(_tap_match "$1" text "$2" 0 first)"
  if [ -n "$coords" ]; then adb shell input tap $coords; return 0; fi
  return 1
}

tap_exact() { # $1 = file, $2 = exact text
  local coords; coords="$(_tap_match "$1" text "$2" 1 first)"
  if [ -n "$coords" ]; then adb shell input tap $coords; return 0; fi
  return 1
}

tap_desc() { # $1 = file, $2 = substring of content-desc
  local coords; coords="$(_tap_match "$1" content-desc "$2" 0 first)"
  if [ -n "$coords" ]; then adb shell input tap $coords; return 0; fi
  return 1
}

tap_frac() {
  local wh w h x y
  wh="$(adb shell wm size 2>/dev/null | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | head -n 1)"
  w="$(printf '%s' "$wh" | awk '{print $1}')"; h="$(printf '%s' "$wh" | awk '{print $2}')"
  [ -n "$w" ] && [ -n "$h" ] || { w=1080; h=2400; }
  x="$(awk -v w="$w" -v f="$1" 'BEGIN{printf "%d", w*f}')"
  y="$(awk -v h="$h" -v f="$2" 'BEGIN{printf "%d", h*f}')"
  adb shell input tap "$x" "$y"
}

pid_alive() { [ -n "$(adb shell pidof "$1" 2>/dev/null | tr -d '\r')" ]; }

fg_pkg() {
  local f
  f="$(adb shell dumpsys window windows 2>/dev/null | sed -n 's/.*mCurrentFocus=Window{[^ ]* [^ ]* \([^/]*\)\/.*/\1/p' | head -n 1 | tr -d '\r')"
  if [ -z "$f" ]; then
    f="$(adb shell dumpsys activity activities 2>/dev/null | sed -n 's/.*topResumedActivity=ActivityRecord{[^ ]* [^ ]* \([^/]*\)\/.*/\1/p' | head -n 1 | tr -d '\r')"
  fi
  printf '%s' "$f"
}

snap() { adb exec-out screencap -p > "$OUT/$1" 2>/dev/null || true; }

dismiss_host_anr() { # host (launcher/System UI) ANR dialog: tap Wait
  local f="$TMPD/anr-check.xml"
  if dump_ui "$f" && grep -q "isn.t responding" "$f"; then
    if grep -q "Pixel Launcher isn.t responding\|System UI isn.t responding" "$f"; then
      log "anr: host dialog present; tapping Wait"
      tap_text "$f" "Wait" || tap_frac 0.66 0.54
      sleep 2
    fi
  fi
}

scan_logcat() { # $1 = file; updates global fatal/ise counters
  local f="$1" a b c raw
  a="$(grep -A3 "FATAL EXCEPTION" "$f" 2>/dev/null | grep -c "Process: $PKG" || true)"
  b="$(grep -A3 "FATAL EXCEPTION" "$f" 2>/dev/null | grep -c "Process: $MOCK" || true)"
  c="$(python3 - "$f" <<'PY' || echo 0
import re, sys
lines = open(sys.argv[1], encoding="utf-8", errors="ignore").read().splitlines()
hits = 0
for i, ln in enumerate(lines):
    if "IllegalStateException" in ln:
        ctx = "\n".join(lines[max(0, i-3): i+12])
        if "ActivityResultSender" in ctx or "ActivityResultRegistry" in ctx:
            hits += 1
print(hits)
PY
)"
  raw="$(grep -c "IllegalStateException" "$f" 2>/dev/null || true)"
  fatal_app_total=$((fatal_app_total + ${a:-0}))
  fatal_mock_total=$((fatal_mock_total + ${b:-0}))
  ise_total=$((ise_total + ${c:-0}))
  log "scan: $f app_fatal=${a:-0} mock_fatal=${b:-0} sender_signature_ise=${c:-0} (raw_system_ise=${raw:-0}, informational)"
}

capture_window() { # $1 = tag; dumps logcat since last clear into evidence + scans
  adb logcat -d > "$OUT/logcat-$1.txt" 2>&1 || true
  scan_logcat "$OUT/logcat-$1.txt"
  grep "OreKingdomWallet" "$OUT/logcat-$1.txt" > "$OUT/markers-$1.txt" 2>/dev/null || true
  adb logcat -c >/dev/null 2>&1 || true
}

# --- device record ---------------------------------------------------------------
adb wait-for-device
for _ in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
{
  echo "sdk=$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  echo "release=$(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
  echo "fingerprint=$(adb shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r')"
  echo "model=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  echo "suite=$SUITE"
  echo "build_sha=${BUILD_SHA:-unknown}"
  echo "expected_pubkey=$EXPECTED_PUBKEY"
  echo "expected_trunc=$EXPECTED_TRUNC"
} > "$OUT/device-info.txt"
cat "$OUT/device-info.txt" | tee -a "$LANE_LOG"

# --- PIN lock + anti-lock mitigation (binding recipe) ---------------------------
log "security: setting device PIN via locksettings"
adb shell locksettings set-pin "$DEVICE_PIN" >> "$LANE_LOG" 2>&1 || true
if adb shell locksettings verify --old "$DEVICE_PIN" >/dev/null 2>&1; then
  lock_pin="set+verified (PIN $DEVICE_PIN)"
else
  lock_pin="set (locksettings set-pin issued, PIN $DEVICE_PIN)"
fi
log "security: lock_pin=$lock_pin"
adb shell settings put system screen_off_timeout 2147483647 >/dev/null 2>&1 || true
adb shell settings put secure lock_screen_lock_after_timeout 2147483647 >/dev/null 2>&1 || true
adb shell svc power stayon usb >/dev/null 2>&1 || true
log "security: lock mitigation applied (screen_off_timeout=max, lock_after_timeout=max, stayon usb)"

looks_locked() { # $1 = dump file; structural keyguard detection
  python3 - "$1" <<'PY'
import re, sys
try:
    data = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
except OSError:
    sys.exit(1)
pkgs = set(re.findall(r'package="([^"]+)"', data))
texts = " ".join(re.findall(r'text="([^"]*)"', data))
if "Unlock for all features and data" in texts: sys.exit(0)
if "Swipe up to open" in texts or "Swipe up to unlock" in texts: sys.exit(0)
if pkgs and pkgs <= {"com.android.systemui"}: sys.exit(0)
sys.exit(1)
PY
}

ensure_unlocked() {
  local f="$TMPD/lock.xml" try
  dump_ui "$f" || return 0
  if ! looks_locked "$f" && [ "$(fg_pkg)" != "com.android.systemui" ]; then return 0; fi
  for try in 1 2 3; do
    log "lock: keyguard appears engaged (try $try; fg=$(fg_pkg)); wake + swipe + PIN unlock"
    adb shell input keyevent 224 >/dev/null 2>&1 || true
    sleep 1
    adb shell input keyevent 82 >/dev/null 2>&1 || true
    sleep 1
    adb shell input swipe 540 2200 540 700 300 >/dev/null 2>&1 || true
    sleep 1
    if dump_ui "$f" && looks_locked "$f"; then
      adb shell input text "$DEVICE_PIN" >/dev/null 2>&1 || true
      adb shell input keyevent 66 >/dev/null 2>&1 || true
      sleep 2
    fi
    dump_ui "$f" || return 0
    if ! looks_locked "$f"; then log "lock: unlocked on try $try"; return 0; fi
  done
  log "lock: STILL LOCKED after 3 unlock attempts"
}

enroll_fingerprint() { # best-effort Settings enrollment on the virtual sensor
  log "security: attempting fingerprint enrollment via Settings"
  adb shell am start -a android.settings.SECURITY_SETTINGS >/dev/null 2>&1 || true
  sleep 3
  local f="$TMPD/fp.xml" i
  for i in 1 2 3 4 5 6; do
    dump_ui "$f" || return 1
    if grep -q "Fingerprint" "$f"; then
      tap_text "$f" "Fingerprint" || return 1
      sleep 2
      break
    fi
    adb shell input swipe 540 1800 540 900 300 >/dev/null 2>&1 || true
    sleep 1
  done
  dump_ui "$f" || return 1
  if grep -q "PIN\|password" "$f" && ! grep -q "Fingerprint" "$f"; then
    adb shell input text "$DEVICE_PIN" >/dev/null 2>&1 || true
    adb shell input keyevent 66 >/dev/null 2>&1 || true
    sleep 2
    dump_ui "$f" || return 1
  fi
  if grep -q "Add a fingerprint\|Fingerprint Unlock\|fingerprint" "$f"; then
    tap_text "$f" "Add a fingerprint" || tap_text "$f" "Fingerprint" || true
    sleep 2
  fi
  local t
  for t in 1 2 3 4 5 6 7 8 9 10 11 12; do
    adb emu finger touch 1 >/dev/null 2>&1 || true
    sleep 1
    if dump_ui "$f" && grep -q "Fingerprint added\|Done\|fingerprint.*added" "$f"; then
      tap_text "$f" "Done" || true
      return 0
    fi
  done
  dump_ui "$f" || true
  grep -q "Fingerprint added" "$f" 2>/dev/null
}
if enroll_fingerprint; then
  lock_fp="enrolled (emulator virtual sensor, id 1)"
else
  lock_fp="ENROLLMENT FAILED (see lane log + fp dumps; PIN-secured path still exercised)"
fi
log "security: lock_fp=$lock_fp"
adb shell input keyevent 3 >/dev/null 2>&1 || true
sleep 1

# --- rig DB seed (the battery's prep pattern, driver-side) -----------------------
seed_rig_db() {
  if [ -z "$RIG_DB" ] || [ ! -f "$RIG_DB" ]; then
    seed_note="NO RIG DB at '$RIG_DB' — host-side proofs unavailable"
    log "seed: $seed_note"
    return 1
  fi
  python3 - "$RIG_DB" "$EXPECTED_PUBKEY" "$RIG_SKR_NAME" "$RELAY" "$OPS" <<'PY'
import sqlite3, sys, time
db_path, wallet, skr, relay, ops = sys.argv[1:6]
now = int(time.time() * 1000)
con = sqlite3.connect(db_path, timeout=30)
con.execute("PRAGMA busy_timeout=30000")
con.execute("UPDATE forum_settings SET treasury_wallet=?, admin_wallet=? WHERE id=1", (relay, ops))
con.execute(
  """INSERT INTO members (wallet, display_name, bio, reputation, strikes, is_banned,
       created_at, referral_code, founder_credit_days, is_founding_member, skr_name, skr_verified_at)
     VALUES (?, ?, '', 0, 0, 0, ?, 'RIG-0001', 0, 0, ?, ?)
     ON CONFLICT(wallet) DO UPDATE SET display_name=excluded.display_name, skr_name=excluded.skr_name""",
  (wallet, skr, now, skr, now),
)
mid = con.execute("SELECT id FROM members WHERE wallet=?", (wallet,)).fetchone()[0]
existing = con.execute(
  "SELECT id FROM memberships WHERE member_id=? AND state='active'", (mid,)).fetchone()
if not existing:
    con.execute(
      """INSERT INTO memberships (member_id, expires_at, payment_asset, simulated,
           checkout_id, created_at, tier, state, cancel_at_period_end, updated_at)
         VALUES (?, ?, 'SOL', 0, 'rig-seed', ?, 'standard', 'active', 0, ?)""",
      (mid, now + 30 * 24 * 3600 * 1000, now, now),
    )
con.commit()
row = con.execute("SELECT COUNT(*) FROM post_sessions").fetchone()[0]
print(f"seeded member id={mid} wallet={wallet} skr={skr}; post_sessions rows at seed={row}")
PY
  seed_note="seeded (member $EXPECTED_PUBKEY as $RIG_SKR_NAME; treasury set to the Relay)"
  log "seed: $seed_note"
}

db_scalar() { # $1 = SQL; echoes the first column of the first row (or empty)
  python3 - "$RIG_DB" "$1" <<'PY'
import sqlite3, sys
try:
    con = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True, timeout=15)
    row = con.execute(sys.argv[2]).fetchone()
    print("" if row is None or row[0] is None else row[0])
except Exception as e:
    print(f"DBERR:{e}")
PY
}

# --- installs + mock Authenticate -------------------------------------------------
log "install: mock wallet ($MOCK_APK)"
if ! adb install "$MOCK_APK" > "$TMPD/install-mock.log" 2>&1; then
  cat "$TMPD/install-mock.log" | tee -a "$LANE_LOG"
  log "install: MOCK INSTALL FAILED — no wallet legs can run"
  echo "wallet_install=FAIL" > "$OUT/verdict-rig.txt"
  exit 1
fi

satisfy_auth_prompt() { # works the biometric/PIN prompt until it clears
  local f="$TMPD/auth.xml" i
  for i in 1 2 3 4 5 6; do
    sleep 2
    dump_ui "$f" || return 1
    if grep -q "Log in to" "$f"; then
      adb emu finger touch 1 >/dev/null 2>&1 || true
      sleep 2
      dump_ui "$f" || return 1
      if grep -q "Log in to" "$f"; then
        tap_text "$f" "Use PIN" || tap_text "$f" "PIN" || true
        sleep 1
        adb shell input text "$DEVICE_PIN" >/dev/null 2>&1 || true
        adb shell input keyevent 66 >/dev/null 2>&1 || true
        sleep 2
      fi
    else
      return 0
    fi
  done
  dump_ui "$f" || true
  ! grep -q "Log in to" "$f"
}

authenticate_mock() {
  log "auth: opening mock wallet to Authenticate (15-min window)"
  ensure_unlocked
  dismiss_host_anr
  adb shell am start -W -n "$MOCK_ACT" >/dev/null 2>&1 || true
  sleep 3
  local f="$TMPD/mock-main.xml"
  dump_ui "$f" || return 1
  if grep -q "isn.t responding" "$f"; then
    dismiss_host_anr
    sleep 2
    dump_ui "$f" || return 1
  fi
  if ! grep -q "AUTHENTICATE\|Authenticate" "$f" && looks_locked "$f"; then
    log "auth: mock launch landed behind the keyguard — unlocking + relaunching"
    ensure_unlocked
    adb shell am start -W -n "$MOCK_ACT" >/dev/null 2>&1 || true
    sleep 3
    dump_ui "$f" || return 1
  fi
  grep -oE 'text="[^"]+"' "$f" | sort -u | head -12 | tee -a "$LANE_LOG" || true
  if grep -q "AUTHENTICATE\|Authenticate" "$f"; then
    tap_exact "$f" "AUTHENTICATE" || tap_exact "$f" "Authenticate" || tap_text "$f" "UTHENTICATE" || return 1
    if satisfy_auth_prompt; then
      date +%s > "$AUTH_EPOCH_FILE"
      auth_note="authenticated (window opened $(date -u +%H:%M:%S)Z)"
      log "auth: $auth_note"
      sleep 1
      dump_ui "$OUT/mock-after-auth.xml" || true
      snap "mock-after-auth.png"
      return 0
    fi
    auth_note="AUTHENTICATE PROMPT NOT SATISFIED"
    log "auth: $auth_note"
    return 1
  fi
  auth_note="Authenticate button not found on mock main screen"
  log "auth: $auth_note"
  dump_ui "$OUT/mock-main-fail.xml" || true
  snap "mock-main-fail.png"
  return 1
}

launch_app() {
  ensure_unlocked
  dismiss_host_anr
  local out i
  out="$(adb shell am start -W -n "$ACT" 2>&1 || true)"
  sleep 3
  for i in 1 2 3 4; do
    [ "$(fg_pkg)" = "$PKG" ] && return 0
    sleep 2
  done
  log "launch_app: $PKG not foreground after am start (fg=$(fg_pkg)); am said: $(printf '%s' "$out" | head -n 2 | tr '\n' ' ')"
}

auth_fresh_enough() { # re-authenticate if the window is >8 min old
  local now last
  now="$(date +%s)"
  last="$(cat "$AUTH_EPOCH_FILE" 2>/dev/null || echo 0)"
  if [ $((now - last)) -gt 480 ]; then
    log "auth: window age $((now - last))s > 480s — re-authenticating"
    authenticate_mock || true
    launch_app
  fi
}

authenticate_mock || true
seed_rig_db || true
log "install: Ore Kingdom debug build ($APP_APK)"
adb uninstall "$PKG" >/dev/null 2>&1 || true
if ! adb install "$APP_APK" > "$TMPD/install-app.log" 2>&1; then
  cat "$TMPD/install-app.log" | tee -a "$LANE_LOG"
  log "install: APP INSTALL FAILED"
  echo "app_install=FAIL" > "$OUT/verdict-rig.txt"
  exit 1
fi
adb logcat -c >/dev/null 2>&1 || true

# Rig IME (env RIG_IME_APK): selected AFTER the PIN setup and mock auth
# (proven paths on the stock keyboard) and before any cell types into a
# field. Later auth prompts type the PIN as injected keyevents, which
# pass through this IME untouched.
if [ -n "${RIG_IME_APK:-}" ] && [ -f "${RIG_IME_APK:-}" ]; then
  adb install -r "$RIG_IME_APK" >/dev/null 2>&1 || log "rig IME install FAILED (typing falls back to key injection)"
  adb shell ime enable com.tithe.rigime/.RigIme >/dev/null 2>&1 || true
  adb shell ime set com.tithe.rigime/.RigIme >/dev/null 2>&1 || true
  sleep 1
  log "ime: default now $(adb shell settings get secure default_input_method 2>/dev/null)"
else
  log "ime: no RIG_IME_APK provided — typing uses key injection (known-corrupt on this image)"
fi

type_text() { # $1 = value; the target field must be focused
  adb shell am broadcast -a com.tithe.rigime.INPUT_TEXT --es text "$1" >/dev/null 2>&1 || true
  sleep 1
}

# --- Ore Kingdom navigation / state helpers --------------------------------------
signin_screen_state() { # $1 = dump file; echoes the SignIn screen's state
  if dump_ui "$1"; then
    if grep -q "You're signed in as" "$1"; then echo "signedin"
    elif grep -q "Waiting for wallet" "$1"; then echo "working"
    elif grep -q "Wallet connected:" "$1"; then echo "connected"
    elif grep -q "Connect wallet & sign in" "$1" || grep -q "Read this first" "$1"; then echo "ready"
    else echo "unknown"; fi
  else echo "nodump"; fi
}

signed_in_visible() { # $1 = dump file
  grep -q "You're signed in as" "$1" 2>/dev/null
}

dismiss_catchup() { # $1 = dump file (refreshed in place); the River's
  # Catch-Up auto-presents once per process for a signed-in member.
  if grep -q "To the River" "$1" 2>/dev/null; then
    log "nav: Catch-Up screen is up — tapping To the River"
    tap_text "$1" "To the River" || true
    sleep 2
    dump_ui "$1" || true
  fi
}

goto_signin() { # lands on the SignIn screen from anywhere in the app
  launch_app
  local f="$TMPD/gs.xml"
  dump_ui "$f" || { log "goto_signin: FAILED — dump_ui produced no hierarchy (fg=$(fg_pkg))"; return 1; }
  if grep -q "Read this first" "$f"; then return 0; fi
  dismiss_catchup "$f"
  # The River's member chip always routes to the sign-in screen: its
  # text is "Sign in" signed-out and the member's .skr name signed-in.
  if grep -q 'text="Sign in"' "$f"; then
    tap_exact "$f" "Sign in" || return 1
  elif grep -q "$RIG_SKR_NAME" "$f"; then
    tap_text "$f" "$RIG_SKR_NAME" || return 1
  else
    log "goto_signin: no member chip on this screen (fg=$(fg_pkg))"
    return 1
  fi
  sleep 2
  dump_ui "$f" && grep -q "Read this first" "$f"
}

sign_out() { # 0 if signed out at the end (incl. never signed in)
  goto_signin || return 1
  local f="$TMPD/so.xml"
  dump_ui "$f" || return 1
  if grep -q "You're signed in as" "$f"; then
    tap_text "$f" "Sign out" || { log "sign_out: no Sign out control on the signed-in screen"; return 1; }
    sleep 2
    dump_ui "$f" || return 1
    if grep -q "You're signed in as" "$f"; then
      log "sign_out: still signed in after tapping Sign out"
      return 1
    fi
    # sign-out pops back to the River; walk back in for a clean state
    goto_signin || return 1
  fi
  local st
  st="$(signin_screen_state "$TMPD/so-state.xml")"
  [ "$st" = "ready" ] || [ "$st" = "connected" ]
}

sheet_present() { # $1 = dump file; mock sheet = mock fg + sheet controls
  local fg
  fg="$(fg_pkg)"
  [ "$fg" = "$MOCK" ] && grep -qE 'text="(Connect|Approve|Cancel)"' "$1" 2>/dev/null
}

wait_sheet() { # $1 = dump file, $2 = timeout; 0 if the mock sheet appeared
  local dst="$1" timeout_s="$2" elapsed=0
  while [ "$elapsed" -lt "$timeout_s" ]; do
    if dump_ui "$dst" && sheet_present "$dst"; then return 0; fi
    dismiss_host_anr
    sleep 3; elapsed=$((elapsed + 3))
  done
  dump_ui "$dst" || true
  sheet_present "$dst"
}

approve_sheet() { # $1 = evidence tag, $2 = button (Connect|Approve)
  local f="$OUT/hier-$1-sheet.xml" btn="$2"
  if wait_sheet "$f" 45; then
    snap "shot-$1-sheet.png"
    log "$1: mock sheet present (fg=$(fg_pkg)); tapping $btn"
    tap_exact "$f" "$btn" || tap_text "$f" "$btn" || return 1
    sleep 4
    satisfy_auth_prompt || true
    return 0
  fi
  log "$1: NO mock sheet appeared within 45s (fg=$(fg_pkg))"
  snap "shot-$1-nosheet.png"
  return 1
}

# Approval loop for the two-sheet sign-in flow (connect, then sign):
# approves whichever sheet is up until the app reports signed-in or
# the budget ends. Echoes the number of approvals.
approval_loop() { # $1 = tag, $2 = budget secs
  local tag="$1" budget="${2:-150}" approvals=0 elapsed=0
  while [ "$elapsed" -lt "$budget" ]; do
    local pf="$TMPD/$tag-probe.xml"
    if dump_ui "$pf" && signed_in_visible "$pf"; then break; fi
    local cf="$TMPD/$tag-cur.xml"
    if dump_ui "$cf" && sheet_present "$cf"; then
      cp "$cf" "$OUT/hier-$tag-sheet-$approvals.xml" || true
      if tap_exact "$cf" "Connect" || tap_text "$cf" "Connect" || tap_exact "$cf" "Approve" || tap_text "$cf" "Approve"; then
        approvals=$((approvals + 1))
        # NOT log(): this function's stdout is captured by callers as
        # the approvals count — lane notes go to the log file + stderr.
        echo "$tag: approved sheet #$approvals" >> "$LANE_LOG"
        echo "$tag: approved sheet #$approvals" >&2
        sleep 4
        satisfy_auth_prompt || true
      fi
    fi
    sleep 3; elapsed=$((elapsed + 3))
  done
  echo "$approvals"
}

app_pid() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

host_member_skr() { # echoes the seeded member's skr_name from the rig DB
  db_scalar "SELECT skr_name FROM members WHERE wallet = '$EXPECTED_PUBKEY'"
}

fresh_process() { # $1 = tag; cell independence by construction
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep 1
  launch_app
  log "$1: fresh process for this cell (pid $(app_pid))"
}

# The abandon gesture + return path, shared by CR-3/CR-4/CR-5: HOME
# (no approval), then bring the wallet task forward once via recents
# (the Citadel-proven path that produces the true zombie-scenario
# shape), then return to the app.
abandon_and_return() { # $1 = tag
  local tag="$1"
  adb shell input keyevent 3 >/dev/null 2>&1 || true  # HOME — abandon
  sleep 3
  adb shell input keyevent 187 >/dev/null 2>&1 || true  # recents
  sleep 3
  dump_ui "$OUT/hier-$tag-recents.xml" || true
  if tap_desc "$OUT/hier-$tag-recents.xml" "Mock MWA Wallet" || tap_text "$OUT/hier-$tag-recents.xml" "Mock"; then
    log "$tag: returned via the wallet's recents card"
  else
    adb shell am start -W -n "$MOCK_ACT" >/dev/null 2>&1 || true
    log "$tag: brought the wallet task forward via am start"
  fi
  sleep 3
  launch_app
}

# --- CR-1: sign-in happy path -----------------------------------------------------
cell_cr1() {
  log "=== CR-1 SIWS sign-in (connect + sign approvals) ==="
  fresh_process "cr1"
  sign_out || { cr1="NOT-RUN(could not reach a signed-out start)"; return; }
  goto_signin || { cr1="FAIL(sign-in screen unreachable)"; return; }
  auth_fresh_enough
  adb logcat -c >/dev/null 2>&1 || true
  local pid0; pid0="$(app_pid)"
  local f="$TMPD/cr1.xml"
  dump_ui "$f" || { cr1="FAIL(no dump)"; return; }
  tap_text "$f" "Connect wallet" || { cr1="FAIL(no connect control)"; return; }
  local approvals; approvals="$(approval_loop "cr1" 150)"
  capture_window "cr1"
  dump_ui "$OUT/hier-cr1-final.xml" || true
  if signed_in_visible "$OUT/hier-cr1-final.xml" && [ "$(host_member_skr)" = "$RIG_SKR_NAME" ] && [ "$(app_pid)" = "$pid0" ]; then
    cr1="PASS(connect+sign approved ($approvals sheets) -> signed in as $RIG_SKR_NAME; host member row present; same process $pid0)"
    sign_out || true
  else
    cr1="FAIL(sign-in did not complete: approvals=$approvals host_skr='$(host_member_skr)' pid $(app_pid) vs $pid0)"
  fi
  log "cr1: $cr1"
}

# --- CR-2: sign DECLINE -> clean error, retry signs in ------------------------------
cell_cr2() {
  log "=== CR-2 sign decline -> clean error -> in-process retry ==="
  fresh_process "cr2"
  sign_out || { cr2="NOT-RUN(could not reach a signed-out start)"; return; }
  goto_signin || { cr2="FAIL(sign-in screen unreachable)"; return; }
  auth_fresh_enough
  adb logcat -c >/dev/null 2>&1 || true
  local pid0; pid0="$(app_pid)"
  local f="$TMPD/cr2.xml"
  dump_ui "$f" || { cr2="FAIL(no dump)"; return; }
  tap_text "$f" "Connect wallet" || { cr2="FAIL(no connect control)"; return; }
  approve_sheet "cr2-connect" "Connect" || { cr2="FAIL(connect sheet)"; return; }
  local sf="$OUT/hier-cr2-sign-sheet.xml"
  if ! wait_sheet "$sf" 45; then cr2="FAIL(no sign sheet to decline)"; capture_window "cr2"; return; fi
  log "cr2: sign sheet up; DECLINING (Cancel)"
  tap_exact "$sf" "Cancel" || tap_text "$sf" "Cancel" || { cr2="FAIL(no Cancel control on sign sheet)"; return; }
  sleep 3
  launch_app
  # A decline is an ANSWER, not an abandonment: the app must leave the
  # working state promptly and show a clean surface — never a session.
  local elapsed=0 st="working"
  while [ "$elapsed" -lt 30 ]; do
    st="$(signin_screen_state "$TMPD/cr2-poll.xml")"
    if [ "$st" = "ready" ] || [ "$st" = "connected" ]; then break; fi
    if [ "$st" = "signedin" ]; then break; fi
    sleep 5; elapsed=$((elapsed + 5))
  done
  log "cr2: post-decline poll: state=$st after ${elapsed}s; pid now $(app_pid) (was $pid0)"
  capture_window "cr2-decline"
  if [ "$st" = "signedin" ]; then
    cr2="FAIL(a DECLINED sign produced a session)"
    return
  fi
  if [ "$st" != "ready" ] && [ "$st" != "connected" ]; then
    cr2="FAIL(no clean resolution after decline within 30s — state stuck at '$st')"
    return
  fi
  if [ "$(app_pid)" != "$pid0" ]; then
    cr2="FAIL(process changed across the decline ($pid0 -> $(app_pid)))"
    return
  fi
  # Retry in-process: one tap, approval loop, signed in.
  dump_ui "$f" || { cr2="FAIL(no dump for retry)"; return; }
  if grep -q "Sign the sign-in message" "$f"; then
    tap_text "$f" "Sign the sign-in message" || { cr2="FAIL(retry tap)"; return; }
  else
    tap_text "$f" "Connect wallet" || { cr2="FAIL(retry tap)"; return; }
  fi
  local approvals; approvals="$(approval_loop "cr2-retry" 150)"
  capture_window "cr2-retry"
  if profile_signed_in_ok && [ "$(app_pid)" = "$pid0" ]; then
    sign_out || true
    cr2="PASS(decline resolved cleanly in ~${elapsed}s (state '$st', no session); ONE retry tap + $approvals approval(s) -> signed in, SAME process $pid0)"
  else
    cr2="FAIL(retry after decline did not reach signed-in in-process (approvals=$approvals, pid $(app_pid) vs $pid0))"
  fi
  log "cr2: $cr2"
}

profile_signed_in_ok() { # 0 if the sign-in screen shows the rig member
  goto_signin || return 1
  local f="$TMPD/psi.xml"
  dump_ui "$f" || return 1
  grep -q "You're signed in as $RIG_SKR_NAME" "$f" && grep -q "Wallet $EXPECTED_TRUNC" "$f"
}

# --- CR-3: KFC-008 abandonment, connect path, x3 reps -------------------------------
abandon_connect_rep() { # $1 = rep; echoes "PASS|FAIL <detail>" on stdout's last line
  local rep="$1"
  local tag="cr3r$rep"
  log "=== CR-3 rep $rep: abandon connect ==="
  fresh_process "$tag"
  sign_out || { echo "FAIL(rep $rep: could not reach a signed-out start)"; return; }
  goto_signin || { echo "FAIL(rep $rep: sign-in screen unreachable)"; return; }
  auth_fresh_enough
  adb logcat -c >/dev/null 2>&1 || true
  local pid0; pid0="$(app_pid)"
  log "$tag: process pid at rep start: $pid0"
  local f="$TMPD/$tag.xml"
  dump_ui "$f" || { echo "FAIL(rep $rep: dump failed)"; return; }
  tap_text "$f" "Connect wallet" || { echo "FAIL(rep $rep: no connect control)"; return; }
  local sf="$OUT/hier-$tag-sheet.xml"
  if ! wait_sheet "$sf" 45; then echo "FAIL(rep $rep: no sheet to abandon)"; return; fi
  log "$tag: connect sheet up; abandoning via HOME (no approval)"
  snap "shot-$tag-sheet.png"
  abandon_and_return "$tag"
  # Poll for in-process resolution within the 30 s bar.
  local elapsed=0 st="working"
  while [ "$elapsed" -lt 30 ]; do
    st="$(signin_screen_state "$TMPD/$tag-poll.xml")"
    if [ "$st" = "ready" ] || [ "$st" = "connected" ]; then break; fi
    if [ "$st" = "signedin" ]; then break; fi
    sleep 5; elapsed=$((elapsed + 5))
  done
  log "$tag: post-abandon poll: state=$st after ${elapsed}s; pid now $(app_pid) (was $pid0); fg=$(fg_pkg)"
  capture_window "$tag-resolution"
  if [ "$st" = "signedin" ]; then
    echo "FAIL(rep $rep: abandoned connect completed to a session with no approval — zombie result delivered as success)"
    return
  fi
  if [ "$st" != "ready" ] && [ "$st" != "connected" ]; then
    echo "FAIL(rep $rep: no in-process resolution within the 30s backstop window — state stuck at '$st' (the KFC-008 hang shape))"
    return
  fi
  if [ "$(app_pid)" != "$pid0" ]; then
    echo "FAIL(rep $rep: process changed across the abandonment ($pid0 -> $(app_pid)) — resolution was a restart, not in-process)"
    return
  fi
  log "$tag: RESOLVED in-process to state '$st' after ~${elapsed}s of polling"
  # RETRY LEG: one tap on the recovery control, then the approval loop.
  dump_ui "$f" || { echo "FAIL(rep $rep: no dump for the retry)"; return; }
  if grep -q "Connect wallet" "$f"; then
    tap_text "$f" "Connect wallet" || { echo "FAIL(rep $rep: retry tap failed)"; return; }
  else
    tap_text "$f" "Sign the sign-in message" || { echo "FAIL(rep $rep: no recovery control on the resolved screen)"; return; }
  fi
  log "$tag: retry tapped ONCE; approval loop running"
  local approvals; approvals="$(approval_loop "$tag-retry" 150)"
  capture_window "$tag-retry"
  if profile_signed_in_ok && [ "$(app_pid)" = "$pid0" ]; then
    sign_out || true
    echo "PASS(rep $rep: resolution to state '$st' in ~${elapsed}s; ONE retry tap + $approvals sheet approval(s) -> signed in, SAME process $pid0)"
  else
    echo "FAIL(rep $rep: retry did not reach signed-in in-process within 150s (approvals=$approvals, pid $(app_pid) vs $pid0))"
  fi
}

cell_cr3() {
  log "=== CR-3 KFC-008 abandonment (connect) x3 reps ==="
  local results="" rep verdict
  for rep in 1 2 3; do
    verdict="$(abandon_connect_rep "$rep" | tail -n 1)"
    log "cr3 rep $rep verdict: $verdict"
    results="$results | rep$rep: $verdict"
  done
  case "$results" in
    *FAIL*) cr3="FAIL($results )" ;;
    *)      cr3="PASS(all 3 reps: resolution + retry-to-signed-in in-process$results )" ;;
  esac
  log "cr3: $cr3"
}

# --- CR-4: KFC-008 abandonment, sign path, x1 ---------------------------------------
cell_cr4() {
  log "=== CR-4 KFC-008 abandonment (sign path) x1 ==="
  fresh_process "cr4"
  sign_out || { cr4="NOT-RUN(could not reach a signed-out start)"; return; }
  goto_signin || { cr4="FAIL(sign-in screen unreachable)"; return; }
  auth_fresh_enough
  adb logcat -c >/dev/null 2>&1 || true
  local pid0; pid0="$(app_pid)"
  log "cr4: process pid at start: $pid0"
  local f="$TMPD/cr4.xml"
  dump_ui "$f" || { cr4="FAIL(no dump)"; return; }
  tap_text "$f" "Connect wallet" || { cr4="FAIL(no connect control)"; return; }
  approve_sheet "cr4-connect" "Connect" || { cr4="FAIL(connect sheet)"; return; }
  local sf="$OUT/hier-cr4-sign-sheet.xml"
  if ! wait_sheet "$sf" 45; then cr4="FAIL(no sign sheet to abandon)"; capture_window "cr4"; return; fi
  log "cr4: sign sheet up (connect already approved); abandoning via HOME"
  snap "shot-cr4-sheet.png"
  abandon_and_return "cr4"
  local elapsed=0 st="working"
  while [ "$elapsed" -lt 30 ]; do
    st="$(signin_screen_state "$TMPD/cr4-poll.xml")"
    if [ "$st" = "ready" ] || [ "$st" = "connected" ]; then break; fi
    if [ "$st" = "signedin" ]; then break; fi
    sleep 5; elapsed=$((elapsed + 5))
  done
  log "cr4: post-abandon poll: state=$st after ${elapsed}s; pid now $(app_pid) (was $pid0); fg=$(fg_pkg)"
  capture_window "cr4-resolution"
  if [ "$st" = "signedin" ]; then
    cr4="FAIL(abandoned sign completed to a session with no approval — zombie result)"
    return
  fi
  if [ "$st" != "ready" ] && [ "$st" != "connected" ]; then
    cr4="FAIL(no in-process resolution within the 30s backstop window — state stuck at '$st' (the KFC-008 hang shape, sign path))"
    return
  fi
  if [ "$(app_pid)" != "$pid0" ]; then
    cr4="FAIL(process changed across the abandonment ($pid0 -> $(app_pid)))"
    return
  fi
  dump_ui "$f" || { cr4="FAIL(no dump for retry)"; return; }
  if grep -q "Sign the sign-in message" "$f"; then
    tap_text "$f" "Sign the sign-in message" || { cr4="FAIL(retry tap)"; return; }
  else
    tap_text "$f" "Connect wallet" || { cr4="FAIL(retry tap)"; return; }
  fi
  local approvals; approvals="$(approval_loop "cr4-retry" 150)"
  capture_window "cr4-retry"
  if profile_signed_in_ok && [ "$(app_pid)" = "$pid0" ]; then
    sign_out || true
    cr4="PASS(sign approval abandoned after connect approved -> interrupted resolution in ~${elapsed}s (state '$st'), session truth intact; ONE retry tap + $approvals approval(s) -> signed in, SAME process $pid0)"
  else
    cr4="FAIL(retry after sign abandonment did not reach signed-in in-process (approvals=$approvals, pid $(app_pid) vs $pid0))"
  fi
  log "cr4: $cr4"
}

# --- CR-5: KFC-008 abandonment, PAYMENT path (thread fee in SOL), x1 -----------------
CR5_TITLE_ABANDON="Rig pay abandon"
CR5_TITLE_RETRY="Rig pay retry"
CR5_BODY="Thread fee abandonment cell body."

open_composer() { # 0 if the composer is up with Title/Body fields
  launch_app
  local f="$TMPD/comp.xml"
  dump_ui "$f" || return 1
  if grep -q 'text="Title"' "$f" && grep -q 'text="Body"' "$f"; then return 0; fi
  if grep -q "Read this first" "$f"; then
    # Starting on the sign-in screen (the CR-5 setup ends there):
    # BACK pops to the River.
    adb shell input keyevent 4 >/dev/null 2>&1 || true
    sleep 2
    dump_ui "$f" || return 1
  fi
  dismiss_catchup "$f"
  tap_text "$f" "New thread" || { log "open_composer: no New thread control"; return 1; }
  sleep 2
  dump_ui "$f" || return 1
  grep -q 'text="Title"' "$f" && grep -q 'text="Body"' "$f"
}

fill_composer() { # $1 = title
  local f="$TMPD/comp-fill.xml"
  dump_ui "$f" || return 1
  tap_text "$f" "Title" || return 1
  sleep 1
  type_text "$1"
  dump_ui "$f" || return 1
  tap_text "$f" "Body" || return 1
  sleep 1
  type_text "$CR5_BODY"
  # Pay in SOL (the stub synthesis is exactest on the lamport leg)
  dump_ui "$f" || return 1
  if grep -q 'text="SOL"' "$f"; then tap_exact "$f" "SOL" || tap_text "$f" "SOL" || true; fi
  sleep 1
  return 0
}

composer_to_review() { # taps Continue, lands on the review with "Post thread"
  local f="$TMPD/comp-rev.xml"
  dump_ui "$f" || return 1
  tap_text "$f" "Continue" || { log "composer_to_review: no Continue control"; return 1; }
  local elapsed=0
  while [ "$elapsed" -lt 45 ]; do
    if dump_ui "$f" && grep -q 'text="Post thread"' "$f"; then return 0; fi
    sleep 3; elapsed=$((elapsed + 3))
  done
  log "composer_to_review: review with Post thread never appeared"
  dump_ui "$OUT/hier-cr5-noreview.xml" || true
  return 1
}

db_post_sessions() { # echoes "status:count ..." for the rig wallet
  db_scalar "SELECT group_concat(status || ':' || n) FROM (SELECT status, COUNT(*) AS n FROM post_sessions WHERE wallet = '$EXPECTED_PUBKEY' GROUP BY status)"
}
db_thread_count() { # $1 = title
  db_scalar "SELECT COUNT(*) FROM threads t JOIN members m ON m.id = t.author_id WHERE m.wallet = '$EXPECTED_PUBKEY' AND t.title = '$1'"
}

cell_cr5() {
  log "=== CR-5 KFC-008 abandonment (payment send, thread fee in SOL) x1 ==="
  fresh_process "cr5"
  # Signed-in member start (the composer is member-gated).
  goto_signin || { cr5="NOT-RUN(sign-in screen unreachable)"; return; }
  local f="$TMPD/cr5.xml"
  if ! dump_ui "$f" || ! grep -q "You're signed in as" "$f"; then
    tap_text "$f" "Connect wallet" || { cr5="NOT-RUN(no connect control for the setup sign-in)"; return; }
    local setup_approvals; setup_approvals="$(approval_loop "cr5-setup" 150)"
    log "cr5: setup sign-in approvals=$setup_approvals"
    if ! profile_signed_in_ok; then cr5="NOT-RUN(setup sign-in did not complete)"; return; fi
  fi
  auth_fresh_enough
  adb logcat -c >/dev/null 2>&1 || true
  local pid0; pid0="$(app_pid)"
  log "cr5: process pid at start: $pid0; sessions before: $(db_post_sessions)"
  open_composer || { cr5="FAIL(composer unreachable)"; return; }
  fill_composer "$CR5_TITLE_ABANDON" || { cr5="FAIL(composer fill)"; return; }
  composer_to_review || { cr5="FAIL(no review/Post thread after Continue)"; capture_window "cr5"; return; }
  local sess_before; sess_before="$(db_post_sessions)"
  log "cr5: review up; sessions at review: $sess_before"
  dump_ui "$f" || { cr5="FAIL(no dump at review)"; return; }
  tap_text "$f" "Post thread" || { cr5="FAIL(no Post thread tap)"; return; }
  local sf="$OUT/hier-cr5-send-sheet.xml"
  if ! wait_sheet "$sf" 60; then cr5="FAIL(no wallet send sheet to abandon)"; capture_window "cr5"; return; fi
  log "cr5: wallet send sheet up; abandoning via HOME (no approval, nothing signed)"
  snap "shot-cr5-sheet.png"
  abandon_and_return "cr5"
  # Resolution: back in the app, the paying state must clear — the
  # composer (or the River) must be live again, never stuck on
  # "Posting…". Window: 30 s, the registry bar.
  local elapsed=0 resolved="no" rf="$TMPD/cr5-poll.xml"
  while [ "$elapsed" -lt 30 ]; do
    if dump_ui "$rf" && ! grep -q "Posting…" "$rf" && ! grep -q "Asking the host…" "$rf"; then
      if grep -q 'text="Post thread"' "$rf" || grep -q 'text="New thread"' "$rf" || grep -q 'text="Title"' "$rf"; then
        resolved="yes"; break
      fi
    fi
    sleep 5; elapsed=$((elapsed + 5))
  done
  log "cr5: post-abandon poll: resolved=$resolved after ${elapsed}s; pid now $(app_pid) (was $pid0); fg=$(fg_pkg)"
  capture_window "cr5-resolution"
  if [ "$resolved" != "yes" ]; then
    cr5="FAIL(no in-process resolution within 30s after the abandoned send — paying state stuck (the KFC-008 hang shape, payment path))"
    return
  fi
  if [ "$(app_pid)" != "$pid0" ]; then
    cr5="FAIL(process changed across the abandoned send ($pid0 -> $(app_pid)))"
    return
  fi
  # Nothing may park: the River must carry no "Payments to finish".
  launch_app
  dump_ui "$OUT/hier-cr5-river-after-abandon.xml" || true
  if grep -q "Payments to finish" "$OUT/hier-cr5-river-after-abandon.xml"; then
    cr5="FAIL(the abandoned send PARKED a flow (Payments to finish is showing) — abandonment must park nothing)"
    return
  fi
  # The host session must be unconsumed and no thread may exist.
  local sess_after; sess_after="$(db_post_sessions)"
  log "cr5: sessions after abandon: $sess_after (at review: $sess_before)"
  case "$sess_after" in
    *verified*|*dispatched*|*consumed*)
      cr5="FAIL(host session moved past pending after an abandoned send: $sess_after)"
      return ;;
  esac
  if [ "$(db_thread_count "$CR5_TITLE_ABANDON")" != "0" ]; then
    cr5="FAIL(a thread exists for the abandoned payment — the host consumed a send that never happened)"
    return
  fi
  log "cr5: ABANDON LEG held — paying state reset in ~${elapsed}s, nothing parked, session unconsumed, no thread"
  # RETRY, in-process: a fresh composer run that completes end-to-end.
  open_composer || { cr5="FAIL(retry: composer unreachable after the abandon)"; return; }
  fill_composer "$CR5_TITLE_RETRY" || { cr5="FAIL(retry: composer fill)"; return; }
  composer_to_review || { cr5="FAIL(retry: no review)"; return; }
  dump_ui "$f" || { cr5="FAIL(retry: no dump at review)"; return; }
  tap_text "$f" "Post thread" || { cr5="FAIL(retry: no Post thread tap)"; return; }
  approve_sheet "cr5-retry-send" "Approve" || { cr5="FAIL(retry: send sheet never approved)"; capture_window "cr5-retry"; return; }
  # The send auto-broadcasts to the stub; the client then completes
  # against the host. Poll the host DB + the UI for the posted thread.
  local waited=0 done_flag="no"
  while [ "$waited" -lt 120 ]; do
    if [ "$(db_thread_count "$CR5_TITLE_RETRY")" = "1" ]; then done_flag="yes"; break; fi
    sleep 5; waited=$((waited + 5))
  done
  capture_window "cr5-retry"
  dump_ui "$OUT/hier-cr5-final.xml" || true
  local sess_final; sess_final="$(db_post_sessions)"
  log "cr5: retry outcome: thread_posted=$done_flag after ~${waited}s; sessions final: $sess_final; pid $(app_pid) vs $pid0"
  if [ "$done_flag" != "yes" ]; then
    cr5="FAIL(in-process retry did not post the thread within 120s (sessions: $sess_final))"
    return
  fi
  case "$sess_final" in
    *consumed*) : ;;
    *) cr5="FAIL(retry posted a thread but no session was consumed ($sess_final)"; return ;;
  esac
  if [ "$(app_pid)" != "$pid0" ]; then
    cr5="FAIL(process changed across the retry ($pid0 -> $(app_pid)))"
    return
  fi
  if grep -q "Payments to finish" "$OUT/hier-cr5-final.xml"; then
    log "cr5: note — Payments to finish still showing after completion (informational; the consumed-session + thread proofs govern)"
  fi
  cr5="PASS(send abandoned at the wallet sheet -> paying state reset in ~${elapsed}s, nothing parked, host session left pending and unconsumed, no thread; in-process retry approved -> tx signed + sent to the stub chain, host verified + consumed the session, thread '$CR5_TITLE_RETRY' posted; SAME process $pid0 throughout)"
  log "cr5: $cr5"
}

# --- dispatch + verdict -------------------------------------------------------------------
case "$SUITE" in
  core)   cell_cr1; cell_cr2 ;;
  kfc008) cell_cr3; cell_cr4; cell_cr5 ;;
  all)    cell_cr1; cell_cr2; cell_cr3; cell_cr4; cell_cr5 ;;
  *) log "unknown SUITE=$SUITE"; exit 2 ;;
esac

adb logcat -d > "$OUT/logcat-final-full.txt" 2>&1 || true
scan_logcat "$OUT/logcat-final-full.txt"
final_fatal="$(grep -c 'AndroidRuntime: FATAL EXCEPTION' "$OUT/logcat-final-full.txt" 2>/dev/null || echo 0)"
if dump_ui "$OUT/hier-final.xml" && grep -q "has stopped" "$OUT/hier-final.xml"; then stopped_dialogs=$((stopped_dialogs + 1)); fi
log "totals: app_fatal=$fatal_app_total mock_fatal=$fatal_mock_total illegal_state=$ise_total stopped_dialogs=$stopped_dialogs"

{
  echo "suite=$SUITE"
  echo "lock_pin=$lock_pin"
  echo "lock_fingerprint=$lock_fp"
  echo "authenticate=$auth_note"
  echo "seed=$seed_note"
  echo "cr1=$cr1"
  echo "cr2=$cr2"
  echo "cr3=$cr3"
  echo "cr4=$cr4"
  echo "cr5=$cr5"
  echo "fatal_app_total=$fatal_app_total"
  echo "fatal_mock_total=$fatal_mock_total"
  echo "illegal_state_total=$ise_total"
  echo "stopped_dialogs=$stopped_dialogs"
  if [ "$fatal_app_total" -gt 0 ] || [ "$fatal_mock_total" -gt 0 ]; then
    echo "whole_verdict=FAIL(binding rule: any AndroidRuntime FATAL in either package fails the verdict)"
  fi
} > "$OUT/verdict-rig.txt"
cat "$OUT/verdict-rig.txt" | tee -a "$LANE_LOG"

fail="0"
for v in "$cr1" "$cr2" "$cr3" "$cr4" "$cr5"; do
  case "$v" in FAIL*) fail="1" ;; esac
done
case "$SUITE" in
  core)   required="$cr1 $cr2" ;;
  kfc008) required="$cr3 $cr4 $cr5" ;;
  all)    required="$cr1 $cr2 $cr3 $cr4 $cr5" ;;
  *)      required="" ;;
esac
for v in $required; do
  case "$v" in NOT-RUN*) fail="1" ;; esac
done
if [ "$fatal_app_total" -gt 0 ] || [ "$fatal_mock_total" -gt 0 ]; then fail="1"; fi
[ "$fail" = "0" ]
