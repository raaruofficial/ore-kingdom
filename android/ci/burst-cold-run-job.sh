#!/usr/bin/env bash
# Burst-box driver for the Ore Kingdom M5 seal cold-run leg.
#
# Runs the repo's own M4 battery (android/ci/emulator-cold-run.sh) in its
# three graded variants — online / nowallet / offline — against the
# SEALED release APK, on an API 35 AVD (the level the M4 gate graded;
# the offline variant's airplane-mode proof is API-35-shaped).
#
# The burst orchestrator pre-boots the kit's default API 36 AVD for
# EMULATE jobs; this driver retires that device and provisions its own
# API 35 AVD (pixel_6, matching the graded CI runs) via the kit's
# boot-avd.sh, because the AVD's system image is fixed at creation.
#
# Usage: burst-cold-run-job.sh <apk-path>
# Evidence: cold-run-evidence-<variant>/ at the repo root (the battery
# writes verdict.txt + dumps + logcat + screenshot into each dir).
# Exit code is the gate: 0 only if all three variants pass.

set -uo pipefail

APK="${1:?apk path required}"
[ -f "$APK" ] || { echo "APK not found: $APK"; exit 1; }

SDK="${ANDROID_HOME:-/opt/burst/android-sdk}"
export ANDROID_HOME="$SDK"
KIT=/opt/burst/kit

# --- retire the orchestrator-provisioned (API 36) device ---------------
OLD_SERIAL="${ANDROID_SERIAL:-}"
OLD_PORT="${OLD_SERIAL#emulator-}"
if [ -n "$OLD_SERIAL" ]; then
  adb -s "$OLD_SERIAL" emu kill >/dev/null 2>&1 || true
  sleep 3
fi
case "$OLD_PORT" in ''|*[!0-9]*) OLD_PORT=5554 ;; esac
MY_PORT=$(( OLD_PORT + 100 ))

# --- API 35 system image (bootstrap ships the API 36 image only) -------
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" \
  "system-images;android-35;google_apis;x86_64" >/dev/null 2>&1 \
  || { echo "API 35 system-image install FAILED"; exit 1; }

# --- provision + boot the API 35 AVD ------------------------------------
export AVD_NAME=ok-api35
export SYSTEM_IMAGE="system-images;android-35;google_apis;x86_64"
export DEVICE_DEF=pixel_6
bash "$KIT/boot-avd.sh" create || { echo "AVD create FAILED"; exit 1; }
OUT="$(bash "$KIT/boot-avd.sh" boot ok-cold-run "$MY_PORT")" \
  || { echo "$OUT"; echo "AVD boot FAILED"; exit 1; }
SERIAL="$(printf '%s\n' "$OUT" | sed -n 's/^AVD_SERIAL=//p' | head -1)"
[ -n "$SERIAL" ] || { echo "$OUT"; echo "no AVD_SERIAL from boot-avd.sh"; exit 1; }
export ANDROID_SERIAL="$SERIAL"
adb wait-for-device

# --- the three graded variants (all run even if one fails — the gate
#     wants all three verdicts, mirroring the CI workflow) ---------------
rc=0
for v in online nowallet offline; do
  bash android/ci/emulator-cold-run.sh "$v" "$APK" "cold-run-evidence-$v" || rc=1
done

bash "$KIT/boot-avd.sh" kill "$SERIAL" >/dev/null 2>&1 || true
exit $rc
