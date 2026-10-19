#!/usr/bin/env python3
"""Collect problems and near misses from finished fuseki scan runs into the shared folders.

Runs made before the scanner tracked them across runs left accepted problems only in
<run>/accepted/ and near misses (calm positions whose strong net gap landed in
[near_gap_min, min_gap)) in <run>/rejected/. This copies them into
fuseki_out/problems/ and fuseki_out/near_misses/ (near misses relabeled NEAR MISS)
and appends index.md rows in the same format the scanner writes.

With --json it instead writes the candidate json the scanner now puts next to each SGF
(schema in feedback/fuseki/README.md) for shared-folder SGFs made before that, and fills
fuseki_out/review_sample/ from older runs' rejected candidates with the scanner's sampling rule.

usage: tools/fuseki_backfill.py fuseki_out/<run> [fuseki_out/<run> ...]
       tools/fuseki_backfill.py --json
"""
import csv
import glob
import json
import os
import re
import sys
import zlib

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


SAMPLE_DIR = "fuseki_out/review_sample"
SAMPLE_RATE = 0.1
COLS = "ABCDEFGHJKLMNOPQRST"


def clean(s):
    """Printable ascii and newlines only, as FusekiScanner.cleanSgf."""
    return "".join(" " if c == "\t" else c for c in s if c == "\n" or c == "\t" or " " <= c <= "~")


def portable(path):
    """As FusekiScanner.portablePath: relative to the archive, never a home directory."""
    i = path.find("/NNGS/")
    if i >= 0:
        return path[i + 1:]
    home = os.path.expanduser("~")
    return "~" + path[len(home):] if path.startswith(home) else path


def in_sample(cid):
    """As FusekiScanner.inReviewSample: crc32 of the id, so both pick the same candidates."""
    return zlib.crc32(cid.encode()) % 1000 < SAMPLE_RATE * 1000


def num(v):
    return round(float(v), 3) if v not in (None, "") else None


def candidate_json(sgf_path, kind, rows, run_hint=None):
    sgf = open(sgf_path, encoding="utf-8", errors="replace").read()
    cid = os.path.basename(sgf_path)[:-4]
    src = re.search(r"Source: (.*?), move (\d+) \(the game move was (\w+)\)", sgf)
    if not src:
        print("no source line, skipped:", sgf_path)
        return None
    source_path, move, game_move = src.group(1), int(src.group(2)), src.group(3)
    sgf = clean(sgf.replace("Source: " + source_path, "Source: " + portable(source_path))).strip()
    game = re.sub(r"_move\d+$", "", cid)
    r = rows.get((game, str(move)))
    if r is None:
        print("no csv row, skipped:", sgf_path)
        return None
    source_text = open(os.path.expanduser(source_path), "rb").read().decode("utf-8", "replace")

    def prop(name):
        m = re.search(r"(?<![A-Z])" + name + r"\[([^\]]*)\]", source_text[:3000])
        return clean(m.group(1)).strip() if m else None

    def pt(m):
        return COLS.index(m[0]), 19 - int(m[1:])

    (ax, ay), (bx, by) = pt(r["answer"]), pt(r["game_move"])
    curve = {}
    for part in (r["curve"] or "").split(", "):
        if " " in part:
            k, v = part.split(" ")
            if v != "?":
                curve[k] = float(v)
    metrics = {"status": "near miss" if kind == "near_miss" else r["status"], "reasons": r["reasons"],
               "score_lead": num(r["score_lead"]), "screen_loss": num(r["screen_loss"]), "gap_fast": num(r["gap_fast"])}
    if r["strong_gap"]:
        best, ans = float(r["strong_best_score"]), float(r["strong_answer"])
        metrics.update({"gap_strong": num(r["strong_gap"]), "strong_best": r["strong_best"],
                        "strong_answer_loss": round(max(best, ans) - ans, 3)})
    metrics.update({"best_fast": r["best"], "good_moves": r["good_moves"], "good_ideas": int(r["good_ideas"]),
                    "choice_distance": abs(ax - bx) + abs(ay - by), "stone_stdev_mean": num(r["stdev_mean"]),
                    "stone_stdev_max": num(r["stdev_max"]), "lines": r["lines"], "mover_rank": r["mover_rank"],
                    "mistake_human_policy": num(r["mistake_human_policy"]),
                    "mistake_human_order": int(r["mistake_human_order"]),
                    "answer_natural_policy": num(r["answer_natural_policy"]), "curve": curve, "crossover": r["crossover"]})
    rec = {
        "schema": 1, "id": cid, "kind": kind, "created": "backfilled",
        "source": {"path": portable(source_path), "game": game, "move": move, "to_play": r["to_play"],
                   "game_move": game_move, "black": prop("PB"), "black_rank": prop("BR"), "white": prop("PW"),
                   "white_rank": prop("WR"), "date": prop("DT"), "sgf": clean(source_text)},
        "problem": {"sgf": sgf, "correct": r["answer"], "correct_label": r["answer_label"],
                    "mistake": r["game_move"], "mistake_label": r["mistake_label"]},
        "metrics": {k: v for k, v in metrics.items() if v is not None},
        "generator": {"commit": "unknown (before candidate json)", "run": run_hint or r["_run"]},
    }
    rec["source"] = {k: v for k, v in rec["source"].items() if v is not None}
    return rec, sgf


def write_json(folder, cid, rec, sgf):
    with open(os.path.join(folder, cid + ".json"), "w") as w:
        w.write(json.dumps(rec, indent=2) + "\n")
    with open(os.path.join(folder, cid + ".sgf"), "w") as w:
        w.write(sgf + "\n")


def backfill_json():
    rows = {}
    for f in sorted(glob.glob("fuseki_out/2*/positions.csv")):  # later runs win
        for r in csv.DictReader(open(f)):
            r["_run"] = os.path.basename(os.path.dirname(f))
            if r["answer"]:
                rows[(r["game"], r["move"])] = r
    made = 0
    for folder, kind in ((PROBLEMS_DIR, "problem"), (NEAR_DIR, "near_miss")):
        for sgf_path in sorted(glob.glob(folder + "/*.sgf")):
            if os.path.exists(sgf_path[:-4] + ".json"):
                continue
            out = candidate_json(sgf_path, kind, rows)
            if out:
                write_json(folder, os.path.basename(sgf_path)[:-4], *out)
                made += 1
    os.makedirs(SAMPLE_DIR, exist_ok=True)
    sampled = 0
    taken = {os.path.basename(p)[:-4] for p in glob.glob(PROBLEMS_DIR + "/*.sgf") + glob.glob(NEAR_DIR + "/*.sgf")}
    for sgf_path in sorted(glob.glob("fuseki_out/2*/rejected/*.sgf")):
        cid = os.path.basename(sgf_path)[:-4]
        if cid in taken or not in_sample(cid) or os.path.exists(os.path.join(SAMPLE_DIR, cid + ".json")):
            continue
        run = os.path.basename(os.path.dirname(os.path.dirname(sgf_path)))
        out = candidate_json(sgf_path, "rejected", rows, run)
        if out:
            write_json(SAMPLE_DIR, cid, *out)
            sampled += 1
    print(made, "candidate json files written,", sampled, "rejected candidates sampled into", SAMPLE_DIR)


if __name__ == "__main__":
    if sys.argv[1:] == ["--json"]:
        backfill_json()
    else:
        main(sys.argv[1:])
