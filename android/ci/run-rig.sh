#!/usr/bin/env bash
# Rig entry point for the emulator-runner step (which executes the
# script input as a single sh -c line — a multi-line script arrives
# truncated and dies with a syntax error; run 41175859181's lesson,
# inherited from the Citadel rig).
#   $1 = suite (core|kfc008|ctl|all)  $2 = api level  $3 = expect (pass|fail)
#   ctl = the KFC-008 discrimination control: CR-3 x3 reps + CR-4 +
#   CR-5 (the same cells and rep counts as the pass side) against the
#   cure-neutered build on the SILENT wallet. The predicted failure is
#   the KFC-008 hang in every cell; the control verdict is graded by
#   android/ci/control-verdict.py, which accepts ONLY genuine
#   hang-shaped failures — a bare `cr3=FAIL` string, a setup failure,
#   or a control run on the stock mock certifies nothing (defect hunt
#   round 2: R2-M4, R2-M5, R2-M6, R2-E3).
# Runs from the repo root; evidence lands at the repo root so the
# upload step finds it at a stable path.
#
# Every run also writes rig-outcome.txt into the evidence dir:
#   outcome=pass|fail|no-verdict
# describing the verdict THIS run actually produced (for ctl: the
# control verdict — pass = discrimination proven). The workflow names
# its artifacts from this file, never from the caller's expectation
# (R2-M7).
set -u
SUITE="$1"; API="$2"; EXPECT="${3:-pass}"
OUT_DIR="wallet-rig-evidence-$SUITE-api$API"
DRIVER="android/ci/verify-wallet-rig.sh"

write_outcome() { # $1 = pass|fail|no-verdict
  printf 'outcome=%s\n' "$1" > "$OUT_DIR/rig-outcome.txt"
}

if [ "$EXPECT" = "fail" ]; then
  # Faulted-control run: the driver is expected to fail; the CONTROL
  # verdict is whether the faulted build failed every abandonment
  # cell in the genuine hang shape (control-verdict.py decides).
  RIG_OUT="$OUT_DIR" SUITE="$SUITE" bash "$DRIVER" || true
  if [ ! -f "$OUT_DIR/verdict-rig.txt" ]; then
    echo "CONTROL VERDICT: NOT PROVEN — the driver produced no verdict file"
    write_outcome "no-verdict"
    exit 1
  fi
  if python3 android/ci/control-verdict.py "$OUT_DIR/verdict-rig.txt"; then
    write_outcome "pass"
  else
    write_outcome "fail"
    exit 1
  fi
else
  RIG_OUT="$OUT_DIR" SUITE="$SUITE" bash "$DRIVER"
  rc=$?
  if [ ! -f "$OUT_DIR/verdict-rig.txt" ]; then
    write_outcome "no-verdict"
    exit "$rc"
  fi
  # A DISPATCHED cell that never ran is a rig failure, not a verdict
  # on the build: name it as the absence it is. (Cells outside the
  # suite legitimately read NOT-RUN and do not count.)
  case "$SUITE" in
    core)   cells="cr1 cr2" ;;
    kfc008) cells="cr3 cr4 cr5" ;;
    all)    cells="cr1 cr2 cr3 cr4 cr5" ;;
    *)      cells="" ;;
  esac
  not_run=0
  for c in $cells; do
    if grep -q "^$c=NOT-RUN" "$OUT_DIR/verdict-rig.txt"; then not_run=1; fi
  done
  if [ "$not_run" = "1" ]; then
    write_outcome "no-verdict"
  elif [ "$rc" = "0" ]; then
    write_outcome "pass"
  else
    write_outcome "fail"
  fi
  exit "$rc"
fi
