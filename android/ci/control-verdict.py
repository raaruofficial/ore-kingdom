#!/usr/bin/env python3
"""Ore Kingdom wallet rig — KFC-008 discrimination-control gate.

Replaces the pre-repair gate in run-rig.sh, which was a bare
`grep -q '^cr3=FAIL'` (defect hunt round 2, R2-M5): ANY cr3=FAIL string
satisfied it, including pure rig/setup failures such as
"could not reach a signed-out start" — the gate that exists to prove
the rig can see would certify blindness as sight.

This gate accepts a control run ONLY when every abandonment cell
failed in the genuine KFC-008 hang shape, on the silent-wallet mock
variant, at the full rep count:

  * mock_variant=silent must be recorded in the verdict file. A control
    run against the stock mock proves nothing (R2-M4): its session
    teardown hands the dapp a definitive answer, so even a cure-neutered
    build "resolves".
  * CR-3 (connect), CR-4 (sign), CR-5 (payment) must ALL be present and
    FAIL — the control covers all three abandonment cells (R2-M6).
  * Each cell's failure must be the hang shape the cells themselves
    emit for a genuine in-process non-resolution:
      - "no in-process resolution within" (CR-3/CR-4) or
        "no in-process resolution within 30s after the abandoned send"
        (CR-5), AND
      - the literal marker "(the KFC-008 hang shape" in the same cell.
  * CR-3 runs 3 reps, the same count as the pass side (R2-E3: the
    behaviour is stochastic, so a single lucky hang certifies
    nothing). Every rep must fail, and every rep's failure must be
    hang-shaped — the hang-shaped rep count must equal the total
    rep-failure count, so a setup failure hiding among hangs fails
    the gate.
  * Any setup/rig-failure marker anywhere in a required cell
    (signed-out start unreachable, no sheet to abandon, dump failure,
    NOT-RUN, ...) fails the gate, even alongside a hang.

Usage: control-verdict.py <verdict-rig.txt>
Exit 0 = discrimination proven. Exit 1 = not proven (reason printed).
"""
import re
import sys

HANG_RESOLUTION = "no in-process resolution within"
HANG_MARKER = "(the KFC-008 hang shape"

SETUP_MARKERS = [
    "could not reach a signed-out start",
    "sign-in screen unreachable",
    "no sheet to abandon",
    "no sign sheet to abandon",
    "no wallet send sheet to abandon",
    "no connect control",
    "no recovery control",
    "dump failed",
    "no dump",
    "NOT-RUN",
    "composer unreachable",
    "setup sign-in did not complete",
]

REQUIRED = {  # cell -> minimum hang-shaped failures (rep count)
    "cr3": 3,
    "cr4": 1,
    "cr5": 1,
}


def parse_verdict(text):
    fields = {}
    for line in text.splitlines():
        if "=" in line:
            key, _, value = line.partition("=")
            fields[key.strip()] = value.strip()
    return fields


def cell_verdict(name, value, min_hangs):
    """Return a list of problems with this cell (empty = hang-shaped)."""
    problems = []
    if value is None:
        return [f"{name}: missing from verdict file"]
    if not value.startswith("FAIL("):
        problems.append(f"{name}: not a FAIL verdict: {value[:80]!r}")
        return problems
    for marker in SETUP_MARKERS:
        if marker in value:
            problems.append(f"{name}: contains setup/rig-failure marker {marker!r}")
    hangs = value.count(HANG_MARKER)
    resolutions = value.count(HANG_RESOLUTION)
    if hangs < min_hangs:
        problems.append(
            f"{name}: only {hangs} hang-shaped failure(s), need {min_hangs} "
            "(full rep count, every rep hang-shaped)")
    if resolutions < hangs:
        problems.append(
            f"{name}: hang marker without the non-resolution wording "
            f"({resolutions} resolution phrase(s) vs {hangs} hang marker(s))")
    # Every rep failure in the cell must be hang-shaped: count the
    # per-rep FAIL segments (CR-3 aggregate) / the cell FAIL itself.
    rep_failures = len(re.findall(r"FAIL\(rep \d+:", value))
    if rep_failures and rep_failures != hangs:
        problems.append(
            f"{name}: {rep_failures} rep failure(s) but only {hangs} "
            "hang-shaped — a non-hang failure is hiding in the cell")
    return problems


def main(argv):
    if len(argv) != 2:
        print("usage: control-verdict.py <verdict-rig.txt>")
        return 1
    try:
        text = open(argv[1], encoding="utf-8", errors="ignore").read()
    except OSError as e:
        print(f"CONTROL GATE: cannot read verdict file: {e}")
        return 1
    fields = parse_verdict(text)
    problems = []
    variant = fields.get("mock_variant")
    if variant != "silent":
        problems.append(
            f"mock_variant={variant!r}, need 'silent' — a control run on "
            "the stock mock cannot discriminate the KFC-008 hang (R2-M4)")
    for cell, min_hangs in REQUIRED.items():
        problems.extend(cell_verdict(cell, fields.get(cell), min_hangs))
    if problems:
        print("CONTROL VERDICT: NOT PROVEN — the faulted build did not "
              "fail every abandonment cell in the genuine hang shape:")
        for p in problems:
            print(f"  - {p}")
        return 1
    print("CONTROL VERDICT: faulted build failed CR-3 (x3 reps), CR-4 "
          "and CR-5 in the genuine KFC-008 hang shape on the silent "
          "wallet — the rig discriminates the fault")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
