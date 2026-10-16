# Problem redo (validation)

The `redo` command measures how close autoprob's automated solution path creation gets to
high quality human work. It takes an existing, well crafted problem, throws away the
author's solution, regenerates a solution line from scratch with KataGo plus the human
policy network, and scores the regenerated line against the original.

The question it answers is not just "does KataGo find the right first move" (it usually
does) but "does the generated line look like what a good author would write": which
resistance the opponent puts up, and especially **where the path ends**. Ending too early
leaves the solver unsure the problem is solved; ending too late pads the line with moves
that no longer matter.

The code lives in `src/main/java/autoprob/validate/`:

| Class | Role |
|---|---|
| `ProblemRedoer` | Entry point: loops over files, runs each stage, scores, writes output |
| `ProblemIsolator` | Strips the original paths, fills the board, balances komi, finds the stones at stake |
| `BalancedFortress` | The board fill used during isolation |
| `SolutionPathGenerator` | Generates the main line move by move and decides when it ends |
| `MovePath` | Move sequence helpers: extracting RIGHT paths, common prefix |

## Running it

```
java -cp autoprob.jar autoprob.GoTool config.properties cmd=redo path=sample_problems/gp-721.sgf
```

- `path` can be a single SGF or a directory; for a directory every `.sgf` in it is run in
  name order and a summary table is printed at the end.
- `csvout.path=results.csv` writes one row per problem, useful for comparing tuning runs.
- Any `config.properties` value can be overridden on the command line as `name=value`,
  which is the easiest way to point at a local KataGo, model and human model
  (`katago=`, `kata.model=`, `kata.human_model=`). Use forward slashes in Windows paths:
  backslashes are escape characters in properties files.

The human model (`kata.human_model`, e.g. `b18c384nbt-humanv0.bin.gz`) is required. The
default model is the b28 network: on gp-10330 it finds the Q3 throw-in at policy 0.96 and
reproduces the reference line exactly, where b15 does not see the tesuji at all. b15 is
much faster and fine for quick iteration on the plumbing.

`sample_problems/` holds 20 validated goproblems.com life and death problems for this
purpose.

### Input requirements

- A 19x19 problem SGF. The problem position is the stones on the root node, and the side
  to move is taken from the root's children.
- At least one solution path ending in a node whose comment contains `RIGHT` (the
  goproblems.com convention). A problem with no RIGHT path is reported as an error.
- Everything else in the tree (wrong branches, alternative resistance) is ignored for
  generation but used by the tree-walk metric.

## How it works

### 1. Extract the reference

Every path from the root to a RIGHT node becomes a reference path. The console prints them
along with the set of distinct first moves.

### 2. Isolate the problem

KataGo is a whole-board player, so a bare life and death position is not a well posed
question for it: it may tenuki, or value the empty board more than the corner. Isolation
turns the problem into a game whose result is decided by the local fight.

**Fresh root.** A new root is built with only the problem stones; the original paths are
gone.

**Fortress fill** (`BalancedFortress`). Moving outward from the bounding box of the problem
stones:

1. `redo.gap` empty lines of breathing room
2. a wall of the outward facing color (the color hit most often by rays shot in from the
   board edges)
3. a wall of the other color behind it
4. one empty line
5. checkerboard fill over the rest of the board, split into a black region and a white
   region by an empty seam line

The seam runs along whichever axis has more room and is placed so that the two colors'
areas balance, counting the empty problem zone as belonging to the wall color. The goal is
a score close enough to zero that komi can compensate (KataGo only accepts komi in
[-150, 150]); a warning is printed if the estimated imbalance exceeds 100 points. All
filled stones are tracked so they can be excluded from every later stone count.

**Komi balance.** The isolated root is analyzed (`redo.balance_visits`) and komi is set so
that with correct play the solver leads by `redo.komi_margin` points. Solving then decides
the game, and any slack move loses it. Komi is rounded to the nearest 0.5.

**Stakes.** The stones at stake are the non-filled stones whose fate depends on solving.
They are found by comparing two positions' ownership:

- the *solved state*: after KataGo's best first move. This is deliberately the position
  after the move, not the root, because a diffuse root search can miss a hard tesuji
  entirely and make the whole group look settled.
- the solver *passing* at the root.

A stone whose ownership differs by more than `redo.stake_threshold` (on the -1..1 scale,
so the maximum is 2) is at stake. If no stakes are found, or fewer than
`redo.threat_stones` while the score swing exceeds 10 points, the analysis is retried at
double visits. If the solved state's score is more than 5 points from the komi target (the
root analysis missed part of the solution's value), komi is rebalanced on the solved
state.

The isolated board, with the stakes marked by triangles, is saved as
`<name>_isolated.sgf` when `redo.output.isolated=true`. Load it in a GUI to see exactly
what KataGo is evaluating. Queries use Tromp-Taylor rules, so match that when analyzing it
by hand.

### 3. Generate the main line

`SolutionPathGenerator` alternates solver and opponent moves. All analysis is restricted
to moves within `redo.near_dist` of a real (non-filled) stone.

**Solver move.** The solver plays KataGo's top move (`redo.visits_root` visits for the first
move, `redo.visits` after). If KataGo's top move is a pass, the line ends. With
`redo.force_first_move=true` the first move is pinned to the first reference path's first
move, as a diagnostic for "is the rest of the line right if the start is right".

After each solver move the position is analyzed again and two sanity checks run:

- **stakes held:** a warning if more than a third of the stake stones have flipped
  ownership relative to the solved state.
- **score drift:** a warning (once per run) if the score is more than 15 points from the
  komi target. This means the isolation baseline missed part of the solution, and
  triggers recalibration (step 4).

**Opponent response.** This is the heart of the tool: a correct path continues only while
the opponent has a move the solver *must* answer. Lesser tries belong in refutation
branches, not the main line.

Candidates are gathered from:

- KataGo's top `redo.response_candidates` moves, skipping passes and moves with both a
  prior below `redo.min_response_prior` and fewer than 10% of `redo.visits` visits.
- if `redo.human_threat_extends=true`, moves the human policy network finds natural
  (policy at least `redo.min_human_policy`), blended over the ranks in `redo.human_ranks`
  by taking the maximum policy across ranks. These catch natural human tries that KataGo
  dismisses but an author would still answer in the main line.

Candidates must be on an empty, legal, local point; duplicates are dropped.

Each candidate's **threat** is measured with a pass test: play the candidate, then compare
the ownership of the position where the solver answers it against the position where the
solver ignores it (passes). The threat is the number of non-filled stones whose ownership
differs by more than `redo.threat_ownership_threshold`. A candidate is a real threat if
that count reaches `redo.threat_stones`. The same analysis also records how obvious the
solver's correct answer is: its KataGo prior, and (for the `testing` criterion) its human
policy.

Which threatening candidate extends the line depends on `redo.extend_mode` and
`redo.response_criterion`:

| `extend_mode` | `response_criterion` | Behavior |
|---|---|---|
| `primary` | (ignored) | Only the first candidate (KataGo's top move) may extend. If it is not a threat, the line ends. Paths end sooner. |
| `scan` | `order` | The first candidate, in KataGo order, that is a threat extends. |
| `scan` | `human` | Among *severe* threats, the one with the highest human policy. |
| `scan` | `testing` (default) | Among severe threats, the one whose correct answer is **hardest for a human to find** (lowest human policy on the answer, compared in bands of 0.2), with ties broken by human policy of the response. |

A threat is *severe* if it is at least `redo.severe_threat_ratio` times the largest threat
among the candidates. The `testing` criterion reflects how good problems are written:
the main line shows the resistance that tests the solver, while plain liberty fills with
automatic answers are left for side branches. (The code also accepts `local`, which prefers
the response closest to the stakes; it is not listed in `config.properties`.)

**Ending.** The line ends naturally when no candidate is a real threat ("no opponent
threat remains") or when the solver's best move is a pass. It is truncated, and reported
as not ended naturally, if it reaches `redo.max_moves` (counting both colors' moves) or an
analysis fails after a retry. The last solver move is marked RIGHT.

### 4. Recalibrate if the score drifted

If generation raised the score drift warning and `redo.recalibrate=true`, the stakes and
komi are rebuilt from the end of the generated line (its end position against the root
pass position) and the isolated SGF is rewritten. If that moved the baseline by more than 5
points, the tree is discarded and the line regenerated once with the corrected
calibration. If not (the drift was a transient dip, for example mid-capture), the first
generation is kept.

### 5. Score against the reference

The generated line is compared against every reference path, keeping the one with the
longest common prefix (ties go to the closer length).

| Metric | Meaning |
|---|---|
| `first` | The generated first move matches some reference path's first move |
| `prefix` | Number of leading moves shared with the best reference path |
| `gen` / `ref` | Lengths of the generated line and of the best reference path |
| `delta` | `gen - ref`: positive means the generated line runs long, negative short |
| `exact` | The generated line is identical to a reference path |
| `tree` | How many generated moves can be followed through the **whole** original tree, not just RIGHT paths. `R` is appended if that walk ends on a RIGHT node |
| natural / `(truncated)` | Whether the line ended by the threat rule rather than hitting `max_moves` or an error |

`tree` separates two kinds of mismatch. If the generated opponent move exists anywhere in
the author's tree, the author considered it, and the difference is a judgment call about
which resistance belongs in the main line. If it does not, the generator found something
the author never addressed.

`prefix` and `exact` compare moves position by position, so a transposition (the same
moves in a different order) is not credited.

## Output

- **Console:** the reference paths, the fortress and komi decisions, the stakes, the
  isolated board, every generated move with its candidates and threat numbers, a per-file
  report and the final summary. Notes and warnings from isolation and generation are
  printed with each report.
- **`redo_out/<name>_redo.sgf`** (`redo.write_file`): the original problem with the
  generated line grafted in as a variation. Its first move is commented `GENERATED` and
  its last node is marked RIGHT.
- **`redo_out/<name>_isolated.sgf`** (`redo.output.isolated`): the isolated board KataGo
  evaluates, with komi, side to move and triangle-marked stakes.
- **CSV** (`csvout.path`): columns `file, error, refpaths, firstmatch, prefix, genlen,
  reflen, delta, exact, natural, intree, endsright, generated`. A failed problem has only
  `file` and `error` filled in.
- **KataGo logs** in `analysis_logs/`, including every request and response (see below).

## Configuration

All `redo.*` settings live in `config.properties` with comments. Summary:

| Setting | Default | Purpose |
|---|---|---|
| `redo.visits` | 1000 | Visits per node during generation |
| `redo.visits_root` | 2000 | Visits for the first solver move, stake detection and recalibration |
| `redo.pass_visits` | 1000 | Visits for each half of a threat pass test |
| `redo.balance_visits` | 1500 | Visits for the initial komi balance |
| `redo.gap` | 4 | Empty lines between the problem stones and the fortress walls |
| `redo.komi_margin` | 2.5 | Solver's target lead after correct play |
| `redo.stake_threshold` | 1.3 | Ownership change (0 to 2) for a stone to count as at stake |
| `redo.threat_stones` | 4 | Stones that must change fate for a response to be a real threat |
| `redo.threat_ownership_threshold` | 1.1 | Ownership change (0 to 2) for a stone to count in a threat |
| `redo.threat_region_dist` | 2 | Intended to limit threats to stones near the stakes; see known gaps |
| `redo.extend_mode` | `scan` | `scan` or `primary`, see the table above |
| `redo.response_criterion` | `testing` | `testing`, `human`, `order` (or `local`) |
| `redo.severe_threat_ratio` | 0.6 | Fraction of the biggest threat that counts as severe |
| `redo.force_first_move` | false | Pin the first solver move to the reference |
| `redo.recalibrate` | true | Rebuild stakes and komi and regenerate on score drift |
| `redo.response_candidates` | 4 | KataGo moves considered as opponent responses |
| `redo.min_response_prior` | 0.02 | Minimum prior for a low-visit KataGo candidate |
| `redo.min_human_policy` | 0.05 | Minimum human policy for a human candidate |
| `redo.human_ranks` | `5k,1d` | Human SL ranks blended for human policy |
| `redo.human_threat_extends` | true | Include human-natural moves as candidates |
| `redo.near_dist` | 2.1 | Analysis restricted to moves this close to real stones |
| `redo.max_moves` | 20 | Truncate the line after this many moves |
| `redo.write_file` | true | Write `<name>_redo.sgf` |
| `redo.output.dir` | `redo_out` | Output directory |
| `redo.output.isolated` | true in `config.properties` (false if unset) | Write `<name>_isolated.sgf` |

Engine settings that matter for redo runs:

| Setting | Value | Purpose |
|---|---|---|
| `kata.config` | `config/redo_analysis.cfg` | Lean analysis config, see below |
| `kata.query.timeout.ms` | 600000 | Give up on a single query after 10 minutes |
| `kata.nudge.ms` | 90000 | Send a wake-up query if a result is this overdue |

## Engine robustness

Long b28 batches surfaced several engine problems, mostly diagnosed on a 16 GB M2 Mac.
The tool now defends against them:

- **Lean config.** `config/redo_analysis.cfg` uses one analysis thread and a small NN cache.
  Redo sends strictly sequential queries, so a second thread and a big cache only added
  memory pressure, and paging was stalling queries.
- **Request logging.** The same config logs every request and response to
  `analysis_logs/`, so a lost query can be told apart from a slow engine.
- **Nudge.** The logs showed the engine sometimes reads a request and then sits idle until
  the next one arrives, which releases it instantly. If a result is more than
  `kata.nudge.ms` overdue, a 1-visit query is sent to kick the scheduler.
- **Timeouts sized from measurements.** Fresh b28 plus human model queries measured 4 to 86
  seconds on an idle M2, so the timeout is generous and the nudge only fires on outliers.
  On a desktop GPU queries take a few seconds.
- **Retries.** A failed analysis is retried once (in `NodeAnalyzer`, so isolation queries
  are covered too).
- **Engine restarts.** If KataGo dies mid-batch, it is restarted (up to 3 times per batch)
  and the current problem retried once.
- **Sleep.** On a laptop, wrap long batches in `caffeinate -i` (macOS) or otherwise prevent
  idle sleep. A sleeping machine freezes KataGo while the wall-clock timeouts keep running,
  which looks exactly like an engine stall.

`tools/kata_latency_bench.py` measures raw engine latency without Java: it replays the
captured request in `tools/bench_request.json` with a random extra move per query, so the
NN cache never helps, and flags stalls over 60 seconds. Use it to check a new machine or
compare KataGo versions. The engine and model paths are constants at the top of the script.

```
python3 tools/kata_latency_bench.py tools/bench_request.json 36 mylabel
```

## Known gaps

- **Only the main line is generated.** The original's refutations, wrong branches and
  alternative RIGHT paths are not regenerated; the generated line is scored against the
  closest reference path.
- **`redo.threat_region_dist` is not applied.** `SolutionPathGenerator` computes the threat
  region around the stakes but `calcPassDelta` never consults it, so ownership changes
  anywhere on the board (outside the fortress fill) count toward a threat. The intent, per
  the commit that added it, was to stop the opponent grabbing neutral space between the
  problem and the walls from registering as threats.
- **19x19 only.** Board size is assumed throughout.
- **Komi clamp.** If the fortress fill is badly imbalanced, komi hits KataGo's ±150 limit
  and the game is no longer balanced; a warning is printed.
