#!/usr/bin/env bash
# Rig entry point for the emulator-runner step (which executes the
# script input as a single sh -c line — a multi-line script arrives
# truncated and dies with a syntax error; run 41175859181's lesson,
# inherited from the Citadel rig).
#   $1 = suite (core|kfc008|all)  $2 = api level  $3 = expect (pass|fail)
# Runs from the repo root; evidence lands at the repo root so the
# upload step finds it at a stable path.
set -u
SUITE="$1"; API="$2"; EXPECT="${3:-pass}"
OUT_DIR="wallet-rig-evidence-$SUITE-api$API"
DRIVER="android/ci/verify-wallet-rig.sh"
if [ "$EXPECT" = "fail" ]; then
  # Faulted-control run: the driver is expected to fail; the CONTROL
  # verdict is whether CR-3 failed on the neutered build (the cell
  # detecting the fault class is the proof the cured build's pass
  # means anything).
  RIG_OUT="$OUT_DIR" SUITE="$SUITE" bash "$DRIVER" || true
  if grep -q '^cr3=FAIL' "$OUT_DIR/verdict-rig.txt"; then
    echo "CONTROL VERDICT: faulted build failed CR-3 as required"
  else
    echo "CONTROL VERDICT: FAULTED BUILD DID NOT FAIL CR-3 — the cell cannot detect the fault"
    exit 1
  fi
else
  RIG_OUT="$OUT_DIR" SUITE="$SUITE" bash "$DRIVER"
fi
