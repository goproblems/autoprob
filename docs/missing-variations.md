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

1. The problem is isolated the way `redo` does it ([redo.md](redo.md)), balanced komi and
   the stones at stake, on a board with ko threats for one side (see [Kos](#kos)).
2. Every position on the author's correct lines (the lines ending RIGHT) where the solver
   is to play is checked, the start first, then deeper.
3. At each, every candidate move is measured as `redo` measures solver moves
   ([redo.md](redo.md)): KataGo's top candidates, natural human moves, and every other
   point within `redo.scan_dist` (2) of a stone at stake, each with the life status and the
   solver's lead after it. A move works when it reaches the life status of KataGo's best
   move, whatever it costs in points, as goproblems.com grades a standard problem: a stone
   or two may change fate (`redo.status_stones`), and the lead may fall up to
   `redo.status_score_margin` (10) behind. Unless it is KataGo's top move, it must also be
   within `redo.alt_region_dist` of a stone at stake (a move away from the fight is a
   tenuki, not an answer).
4. The author's own moves there are measured too. If none of them works by this measure,
   KataGo thinks the author's answer fails: the isolation misreads the position and no
   comparison is safe, so the position is skipped.
5. A working move the problem does not accept is measured again against the author's move
   with a deeper search (`missing.verify_visits`, 4 times `redo.visits`). One that reaches
   a different life status, or a lead more than the status margin behind, is dropped.
6. If the problem has the move on a wrong line, the author thinks it is refuted: each of
   the problem's own answers to it is played and measured with the deeper search too, and
   if any of them leaves the solver short in the same way, the author is right and the
   move is dropped.
7. For a move that holds, its line is generated with the `redo` generator from that
   position, with the move forced first, so the line answers and ends the way `redo`'s
   would; for each of the problem's own answers to it, the solver's way through is
   generated as well. The generator does not scan every point near the stakes at each of
   its turns, which would multiply the time a line takes. Otherwise the command stops there.

### Kos

On a board without ko threats, KataGo counts every ko as lost by whoever needs a threat.
That misjudges kos both ways, so the board gets KaTrain's tsumego frame with lizgoban's ko
threat pattern (`redo.ko_threats`, see [redo.md](redo.md)), with the threats for one side:

- **A problem whose own line goes through a ko: threats for the solver.** A ko the solver
  starts then counts as won, as the author's ko answer intends. Without them the answer
  looks no better than a move that simply fails, and failing moves get reported: in
  gp-36582 the author's S17 ends in a ko for the whole corner, while Q18 lets Black live
  outright, and on a board without threats both came out as Black living. With them the
  corner dies after S17 and lives after Q18, 25 points apart, and Q18 is not reported.
- **Any other problem: threats for the opponent** (`missing.opponent_ko_threats`, on by
  default). A move that needs to win a ko then fails, so a clean answer comes out ahead of
  a ko, as on the site. Without them a ko the opponent cannot win looks like clean life: in
  gp-32711 after B Q19, W O18, B T16, W T18, B T19, W S19, B S18, W T14, B R19, W T15,
  Black's S16 only lives through a ko, yet came out best (+2.96, against +1.39 and +1.42
  for T17 and T19, which live outright). With threats for White, S16 lets the whole corner
  die, while T17 and T19 still live.

A move on the threat pattern is never reported, and generated lines stop where they would
go on into the threats, so they end at the ko the way the problem's do.

With threats for the opponent the board itself decides kos, so the lines are not checked
for one: a ko taken on them is one the move does without, such as a retake for points (the
report notes it). Without them (`missing.opponent_ko_threats=false`), a move that only
works through a ko is not counted when the author's lines from that position take none
(`missing.allow_ko=true` keeps it, flagged). KataGo's own lines are checked for a ko
before any are generated (the principal variation of its best answer, and of every other
answer with at least a tenth of the best one's visits, from the deeper searches), then the
generated lines. That catches kos by their shape, not by whether the result depends on
them, so it also drops moves whose ko is optional.

`missing.skip_ko=true` instead passes over every position whose own line goes through a ko
("ko, not judged"), which avoids relying on the solver's threats at all; useful when
scanning the site for findings to trust without a second look.

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
it to a problem. Moves that reach the result in a slower or less efficient way are
reported on purpose, since the site counts them correct; positions the isolation misreads
are skipped rather than reported, so some missing variations go unnoticed. Seki can show
the same life status as clean life when KataGo's ownership does not tell them apart. The
ko threats are modest: a ko worth more than they are can still be counted as lost by the
side holding them, and a solver with threats of its own nearby (against a weak outside
wall, say) can still win a ko the opponent's threats should have cost it.
