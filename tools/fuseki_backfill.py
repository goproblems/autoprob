#!/usr/bin/env python3
"""Collect problems and near misses from finished fuseki scan runs into the shared folders.

Runs made before the scanner tracked them across runs left accepted problems only in
<run>/accepted/ and near misses (calm positions whose strong net gap landed in
[near_gap_min, min_gap)) in <run>/rejected/. This copies them into
fuseki_out/problems/ and fuseki_out/near_misses/ (near misses relabeled NEAR MISS)
and appends index.md rows in the same format the scanner writes.

usage: tools/fuseki_backfill.py fuseki_out/<run> [fuseki_out/<run> ...]
"""
import csv
import os
import re
import sys

PROBLEMS_DIR = "fuseki_out/problems"
NEAR_DIR = "fuseki_out/near_misses"
# the bar those runs used (2.5, near misses from 2.0); the scanner's default bar is 1.8 since 2026-10-16
NEAR_MIN, MIN_GAP = 2.0, 2.5

COLUMNS = (
    "| SGF | Position | To play | Correct | Game move (mistake) | Gap strong / fast | Ranks B/W | Run |\n"
    "|---|---|---|---|---|---|---|---|\n"
)
PROBLEMS_HEADER = (
    "# Fuseki problems\n\n"
    "Accepted problems from every scan run: calm openings where the game move lost to a natural good move by at least that run's bar (fuseki.min_gap, strong net). Each SGF is a one-move-deep A/B problem; the root comment has the analysis. Rows are added as runs find them; the gap column shows each problem's margin.\n\n" + COLUMNS
)
NEAR_HEADER = (
    "# Fuseki near misses\n\n"
    "Positions that passed every check except the last: the strong net gap between the correct move and the game move came out just under that run's bar (between fuseki.near_gap_min and fuseki.min_gap). Each SGF is a one-move-deep A/B problem; the root comment has the analysis. Rows are added as runs find them.\n\n" + COLUMNS
)


def sgf_prop(sgf, name):
    m = re.search(r"(?<![A-Z])" + name + r"\[([^\]]*)\]", sgf)
    return m.group(1).strip() if m else "?"


def track(folder, header, run, r, sgf):
    os.makedirs(folder, exist_ok=True)
    index = os.path.join(folder, "index.md")
    if not os.path.exists(index):
        with open(index, "w") as w:
            w.write(header)
    name = os.path.basename(r["sgf"])
    dest = os.path.join(folder, name)
    if os.path.exists(dest):
        return False
    with open(dest, "w") as w:
        w.write(sgf)
    row = "| [%s](%s) | %s move %s | %s | %s = %s | %s = %s | %.2f / %.2f | %s / %s | %s |\n" % (
        name, name, r["game"], r["move"], "Black" if r["to_play"] == "B" else "White",
        r["answer_label"], r["answer"], r["mistake_label"], r["game_move"],
        float(r["strong_gap"]), float(r["gap_fast"]), sgf_prop(sgf, "BR"), sgf_prop(sgf, "WR"), os.path.basename(run))
    with open(index, "a") as w:
        w.write(row)
    print("added", dest, "gap %.2f" % float(r["strong_gap"]))
    return True


def main(runs):
    added = 0
    for run in runs:
        run = run.rstrip("/")
        for r in csv.DictReader(open(os.path.join(run, "positions.csv"))):
            if not r["strong_gap"] or not r["sgf"]:
                continue
            sgf = open(os.path.join(run, r["sgf"])).read()
            gap = float(r["strong_gap"])
            if r["status"] == "accepted":
                added += track(PROBLEMS_DIR, PROBLEMS_HEADER, run, r, sgf)
            elif NEAR_MIN <= gap < MIN_GAP:
                sgf = re.sub(r"C\[REJECTED \(rejected by strong net\): [^\n]*\n",
                             "C[NEAR MISS: strong net gap %.2f, the bar is %.1f.\n" % (gap, MIN_GAP), sgf, count=1)
                added += track(NEAR_DIR, NEAR_HEADER, run, r, sgf)
    print(added, "added")


if __name__ == "__main__":
    main(sys.argv[1:])
