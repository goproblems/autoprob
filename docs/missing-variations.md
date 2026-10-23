# Missing variations

`cmd=missing-variations` looks for correct lines a problem is missing: moves that also
solve it but that the problem's tree does not accept, so on goproblems.com a solver who
plays one is told it is wrong. It stops at the first one it finds and shows it.

```
bash gotool.sh cmd=missing-variations path=sample_problems/gp-36582.sgf
bash gotool.sh cmd=missing-variations path=sample_problems
bash gotool.sh cmd=missing-variations id=721,1311
bash gotool.sh cmd=missing-variations
```

(`gotool.sh` runs `autoprob.GoTool` with `redo_local.properties`; any properties file with
the KataGo and `redo.*` settings works.)

## Sources

| Arguments | What is checked |
|---|---|
| `path=<file>` | one SGF |
| `path=<dir>` | every `.sgf` in the directory, in name order, until one has a missing variation |
| `id=<ids>` | those goproblems.com problems (comma separated), in order |
| neither | a scan of goproblems.com by problem id, continuing from where the last scan stopped |

Problems from the site are fetched with `api.problem.details` (`api/v2/problems/{id}`
under `baseurl`), which needs no API key; `apikey=` is sent when given. Only problems whose
genre is life and death and that are marked standard are checked; the rest are skipped, as
are ids with no problem.

### Scanning

A scan remembers where it is in `missing_variations/state.json` (next id, problems checked
and skipped, the last problem with a finding), saved after every problem, so a stopped or
finished scan picks up at the next id and never checks a problem twice. `start=<id>`
starts somewhere else. It stops at the first finding, after `scan.max` checked problems
(0, the default, for no limit), or after `scan.max_misses` ids in a row with no problem
(500, which is taken as the end of the database). `api.delay_ms` (100) spaces the requests.
Every problem's outcome is appended to `missing_variations/log.jsonl`.

## How it works

1. The problem is isolated the way `redo` does it ([redo.md](redo.md)): fortress fill,
   balanced komi, and the stones at stake.
2. Every position on the author's correct lines (the lines ending RIGHT) where the solver
   is to play is checked, the start first, then deeper.
3. At each, every plausible move is played and measured as `redo` measures solver moves:
   KataGo's top candidates and natural human moves, each with the solver's lead after it.
   A move works when that lead is within `redo.alt_score_tolerance` (1.5) of the best
   reliable move's and, unless it is KataGo's top move, it is within `redo.alt_region_dist`
   of a stone at stake (a move away from the fight is a tenuki, not an answer).
4. The author's own moves there are measured too. If the best of them is more than
   `missing.misread_margin` (5) points behind the best reliable move, KataGo thinks the
   author's answer fails: the isolation misreads the position and no comparison is safe, so
   the position is skipped. A smaller gap is a slower way to the same result, and a
   faster move is just what is looked for.
5. A working move the problem does not accept, and whose lead is within the tolerance of
   the author's move's, is measured again against the author's move with a deeper search
   (`missing.verify_visits`, 4 times `redo.visits`). A move that falls short is dropped.
6. If the problem has the move on a wrong line, the author thinks it is refuted: each of
   the problem's own answers to it is played and measured with the deeper search too, and
   if any of them leaves the solver short, the author is right and the move is dropped.
7. For a move that holds, its line is generated with the `redo` generator from that
   position, with the move forced first, so the line answers and ends the way `redo`'s
   would; for each of the problem's own answers to it, the solver's way through is
   generated as well.
8. A move whose generated lines take a ko when the author's lines from that position do
   not is not counted: the solver would be trading an unconditional result for a ko
   (`missing.allow_ko=true` keeps it, flagged). Otherwise the command stops there.

### Kos

The usual isolated board has no ko threats, so KataGo counts every ko as lost by whoever
needs a threat. When the problem's own answer is a ko, that makes the answer look no
better than a move that simply fails, and failing moves get reported. In gp-36582 the
author's S17 ends in a ko for the whole corner, while Q18 lets Black live outright; on the
plain board both came out as Black living, within a point of each other.

So a problem whose own line goes through a ko is checked on a board with ko threats for the
solver: KaTrain's tsumego frame with lizgoban's ko threat pattern (`redo.ko_threats`, see
[redo.md](redo.md)). A ko the solver starts then counts as won by the solver, as the
author's ko answer intends: in gp-36582 the corner dies after S17 and lives after Q18, 25
points apart, and Q18 is no longer reported. A move on the threat pattern is never
reported, and generated lines stop where they would go on into the threats, so they end at
the ko the way the problem's do.

`missing.skip_ko=true` instead passes over every position whose own line goes through a ko
("ko, not judged"), which avoids relying on the threats at all; useful when scanning the
site for findings to trust without a second look.

## What it shows

The console report names the problem and its difficulty, the position, the moves the
problem accepts there, and for each move found: its lead and the author's move's, both
again from the deeper search, how it came up (KataGo prior and visits, human policy,
distance to the stones at stake), whether the problem has the move on a wrong line, and the
generated line.

`missing_variations/<problem>_missing.sgf` is the problem with the lines added at their
position. The new move is commented `MISSING VARIATION (autoprob)` with the numbers, and
the lines end RIGHT. If the problem already has the move on a wrong line, that node is
reused and is no longer marked wrong, and the solver's way through each of the problem's
answers to it is added below that answer, next to the old wrong continuation. `<problem>_missing.json` has the same finding as
data. `missing.dir` moves all of this.

## Caveats

The verdict is KataGo's, on the isolated position, so look at each finding before adding
it to a problem. Moves that keep the result but are clearly inferior (a slower or less
efficient way to the same result) can pass the tolerance; positions the isolation misreads
are skipped rather than reported, so some missing variations go unnoticed. The ko threats
are modest, so a ko worth more than they are can still be counted as lost.
