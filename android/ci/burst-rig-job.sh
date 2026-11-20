#!/usr/bin/env bash
# Burst-box port of .github/actions/wallet-rig (the Ore Kingdom wallet
# rig) for the M5 seal campaign. One suite per invocation:
#
#   burst-rig-job.sh all pass   the owed lane=rig dispatch — the full
#                               CR suite incl. the CR-5 host-consumption
#                               re-attempt (host leg unproven at M4,
#                               blocked twice by mock equipment faults)
#   burst-rig-job.sh ctl fail   the owed lane=rig-control dispatch —
#                               the caller applies
#                               android/ci/control-neuter.patch first;
#                               run-rig.sh's control verdict (job passes
#                               only if CR-3 FAILS on the neutered build
#                               in the predicted hang shape) is the proof
#                               the cured build's CR-3 pass means anything
#
# Faithful to the composite action step-for-step, with four burst
# adaptations, all deliberate:
#   1. Gradle comes from the repo's own 8.11 wrapper (no setup-gradle
#      on the box): invoked by path from each subproject directory.
#   2. Bun is installed from the official oven-sh/bun v1.4.4 release,
#      sha256-verified against the release's SHASUMS256.txt before use
#      (the estate toolchain version; bootstrap does not ship bun).
#   3. The mock wallet's throwaway key is generated fresh on-box per
#      run. The CI value is a write-only GitHub repo secret and is not
#      held in estate custody; the key is a rig fixture that controls
#      nothing outside the rig (it only sets the mock's identity and
#      the driver's EXPECTED_PUBKEY/EXPECTED_TRUNC, both env-supplied),
#      and the generated key dies with the box.
#   4. The API 35 AVD is self-provisioned via the kit's boot-avd.sh
#      (the orchestrator pre-boots an API 36 device for EMULATE jobs;
#      it is retired first). CI's disk-reclaim step is skipped — the
#      box carries a 200 GB volume.
#
# Evidence: wallet-rig-evidence-<suite>-api35/ at the repo root (the
# driver writes it), with the rig host + stub logs copied in.

set -uo pipefail

SUITE="${1:?suite required: all|ctl}"
EXPECT="${2:-pass}"
REPO_ROOT="$(pwd)"
WORK="$REPO_ROOT/.burst-rig"
mkdir -p "$WORK"
SDK="${ANDROID_HOME:-/opt/burst/android-sdk}"
export ANDROID_HOME="$SDK"
KIT=/opt/burst/kit
MOCK_COMMIT=d444aff0c72dadd0f5c442ea2bc3559b62916e01

chmod +x gradlew 2>/dev/null || true

# --- emulator runtime libraries (CI parity; best-effort) ----------------
apt-get update -qq >/dev/null 2>&1 || true
apt-get install -y -qq libpulse0 libgl1 libegl1 libnss3 libxcb-cursor0 \
  unzip patch curl >/dev/null 2>&1 || true

# --- bun, hash-gated ------------------------------------------------------
BUN_VER=1.4.4
BUN_SHA=bf8ccbfb2b0b80159e0f9a6d15c55317add8077534dbc6130a3edb52fccb395c
if ! command -v bun >/dev/null 2>&1; then
  curl -fsSL "https://github.com/oven-sh/bun/releases/download/bun-v${BUN_VER}/bun-linux-x64.zip" \
    -o "$WORK/bun.zip" || { echo "bun download FAILED"; exit 1; }
  echo "$BUN_SHA  $WORK/bun.zip" | sha256sum -c - >/dev/null \
    || { echo "bun sha256 MISMATCH — refusing unverified binary"; exit 1; }
  (cd "$WORK" && unzip -oq bun.zip && cp bun-linux-x64/bun "$WORK/bun" \
    && chmod +x "$WORK/bun") || { echo "bun unpack FAILED"; exit 1; }
  export PATH="$WORK:$PATH"
fi
bun --version >/dev/null || { echo "bun not runnable"; exit 1; }

# --- fresh throwaway rig key (adaptation 3) -------------------------------
python3 - "$WORK" <<'PY' || { echo "rig key generation FAILED"; exit 1; }
import re, subprocess, sys
work = sys.argv[1]
subprocess.run(["openssl", "genpkey", "-algorithm", "ed25519",
                "-out", work + "/rig-key.pem"], check=True,
               capture_output=True)
txt = subprocess.run(["openssl", "pkey", "-in", work + "/rig-key.pem",
                      "-text", "-noout"], check=True,
                     capture_output=True, text=True).stdout
def block(name):
    m = re.search(name + r":\s*\n((?:\s*[0-9a-f:]+)+)", txt)
    return bytes(int(h, 16) for h in m.group(1).replace(":", " ").split())
seed, pub = block("priv"), block("pub")
assert len(seed) == 32 and len(pub) == 32, (len(seed), len(pub))
ALPH = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
def b58(b):
    n = int.from_bytes(b, "big")
    s = ""
    while n:
        n, r = divmod(n, 58)
        s = ALPH[r] + s
    return "1" * (len(b) - len(b.lstrip(b"\0"))) + s
pk, pub58 = b58(seed), b58(pub)
with open(work + "/rig-key.env", "w") as f:
    f.write("MOCK_PK=%s\n" % pk)
    f.write("EXPECTED_PUBKEY=%s\n" % pub58)
    f.write("EXPECTED_TRUNC=%s\n" % (pub58[:4] + "…" + pub58[-4:]))
print("rig key generated: pubkey", pub58)
PY
# shellcheck disable=SC1091
. "$WORK/rig-key.env"
export EXPECTED_PUBKEY EXPECTED_TRUNC

# --- builds ----------------------------------------------------------------
(cd android && ../gradlew :app:assembleDebug \
  -PHOST_URL=http://10.0.2.2:8377 --console=plain --no-daemon) \
  || { echo "OK debug build FAILED"; exit 1; }
cp android/app/build/outputs/apk/debug/app-debug.apk "$WORK/ok-debug.apk" \
  || exit 1
(cd android/ci-tools/rig-ime && ../../../gradlew :app:assembleDebug \
  --console=plain --no-daemon) || { echo "rig IME build FAILED"; exit 1; }
cp android/ci-tools/rig-ime/app/build/outputs/apk/debug/app-debug.apk \
  "$WORK/rig-ime.apk" || exit 1

# --- mock wallet: pinned clone + rig endpoint patch + build --------------
git clone -q https://github.com/solana-mobile/mock-mwa-wallet \
  "$WORK/mock-src" || { echo "mock clone FAILED"; exit 1; }
(cd "$WORK/mock-src" && git checkout -q "$MOCK_COMMIT" \
  && git apply "$REPO_ROOT/android/ci/rig-mock-rpc.patch") \
  || { echo "mock patch FAILED"; exit 1; }
{
  printf 'privateKey=%s\n' "$MOCK_PK"
  printf 'walletName=%s\n' "OreKingdomTestWallet"
  printf 'sdk.dir=%s\n' "$SDK"
} > "$WORK/mock-src/local.properties"
(cd "$WORK/mock-src" && chmod +x gradlew \
  && ./gradlew :app:assembleDebug --console=plain --no-daemon) \
  || { echo "mock build FAILED"; exit 1; }
cp "$WORK/mock-src/app/build/outputs/apk/debug/app-debug.apk" \
  "$WORK/mock-debug.apk" || exit 1

# --- rig host: staging tree copy + disclosed shim + custody DB copy ------
mkdir -p "$WORK/righost" "$WORK/empty-client"
cp host/staging-v014/server.ts host/staging-v014/actions.bundle.js \
  host/staging-v014/package.json "$WORK/righost/" || exit 1
(cd "$WORK/righost" && patch -p1 < "$REPO_ROOT/android/ci/rig-host-shim.patch" \
  && bun install) || { echo "rig host prep FAILED"; exit 1; }
cp host/staging-v014/custody-app.db "$WORK/forum-rig.db" || exit 1

nohup bun "$REPO_ROOT/android/ci/rig-stub.ts" > "$WORK/stub.log" 2>&1 &
STUB_PID=$!
nohup env OK_PORT=8377 OK_DB_PATH="$WORK/forum-rig.db" \
  OK_RPC_URL=http://127.0.0.1:18741 OK_PRICE_URL=http://127.0.0.1:18742 \
  OK_RIG_SKR=1 OK_CLIENT_DIR="$WORK/empty-client" \
  bun run "$WORK/righost/server.ts" > "$WORK/host.log" 2>&1 &
HOST_PID=$!
up=0
for _ in $(seq 1 40); do
  if curl -sf http://127.0.0.1:18741/healthz >/dev/null 2>&1 \
     && curl -sf http://127.0.0.1:8377/healthz >/dev/null 2>&1; then
    up=1; break
  fi
  sleep 2
done
if [ "$up" != 1 ]; then
  echo "rig host/stub FAILED to start"
  tail -n 20 "$WORK/stub.log" "$WORK/host.log" || true
  kill "$STUB_PID" "$HOST_PID" 2>/dev/null || true
  exit 1
fi
echo "stub chain + rig host up"

# --- API 35 AVD (adaptation 4) ----------------------------------------------
OLD_SERIAL="${ANDROID_SERIAL:-}"
OLD_PORT="${OLD_SERIAL#emulator-}"
if [ -n "$OLD_SERIAL" ]; then
  adb -s "$OLD_SERIAL" emu kill >/dev/null 2>&1 || true
  sleep 3
fi
case "$OLD_PORT" in ''|*[!0-9]*) OLD_PORT=5554 ;; esac
MY_PORT=$(( OLD_PORT + 100 ))
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" \
  "system-images;android-35;google_apis;x86_64" >/dev/null 2>&1 \
  || { echo "API 35 system-image install FAILED"; exit 1; }
export AVD_NAME=ok-api35
export SYSTEM_IMAGE="system-images;android-35;google_apis;x86_64"
export DEVICE_DEF=pixel_6
bash "$KIT/boot-avd.sh" create || { echo "AVD create FAILED"; exit 1; }
OUT="$(bash "$KIT/boot-avd.sh" boot "ok-rig-$SUITE" "$MY_PORT")" \
  || { echo "$OUT"; echo "AVD boot FAILED"; exit 1; }
SERIAL="$(printf '%s\n' "$OUT" | sed -n 's/^AVD_SERIAL=//p' | head -1)"
[ -n "$SERIAL" ] || { echo "$OUT"; echo "no AVD_SERIAL from boot-avd.sh"; exit 1; }
export ANDROID_SERIAL="$SERIAL"
adb wait-for-device

# --- the driver ---------------------------------------------------------------
export BUILD_SHA="$(git rev-parse HEAD)"
export OK_APK="$WORK/ok-debug.apk"
export MOCK_APK="$WORK/mock-debug.apk"
export RIG_IME_APK="$WORK/rig-ime.apk"
export RIG_SKR_NAME="rigwallet.skr"
export DEVICE_PIN="1234"
export RIG_DB_PATH="$WORK/forum-rig.db"
bash android/ci/run-rig.sh "$SUITE" 35 "$EXPECT"
rc=$?

EV="$REPO_ROOT/wallet-rig-evidence-$SUITE-api35"
[ -d "$EV" ] && cp "$WORK/host.log" "$WORK/stub.log" "$EV/" 2>/dev/null || true
bash "$KIT/boot-avd.sh" kill "$SERIAL" >/dev/null 2>&1 || true
kill "$STUB_PID" "$HOST_PID" 2>/dev/null || true
exit $rc
