# Problem redo (validation)

The `redo` command measures how close autoprob's automated solution path creation gets to
high quality human work. It takes an existing, well crafted problem, throws away the
author's solution, regenerates the correct part of the solution tree from scratch with
KataGo plus the human policy network, and scores it against the original.

The question it answers is not just "does KataGo find the right first move" (it usually
does) but "does the generated tree look like what a good author would write": which moves
belong in it, which resistance the opponent puts up, and especially **where each line
ends**. A working move missing from the tree marks a correct answer wrong. Ending too early
leaves the solver unsure the problem is solved; ending too late pads the line with moves
that no longer matter.

The code lives in `src/main/java/autoprob/validate/`:

| Class | Role |
|---|---|
| `ProblemRedoer` | Entry point: loops over files, runs each stage, scores, writes output |
| `ProblemIsolator` | Strips the original paths, fills the board, balances komi, finds the stones at stake |
| `BalancedFortress` | The board fill used during isolation |
| `SolutionPathGenerator` | Generates the correct tree turn by turn: which moves go in and where each line ends |
| `DecisionFeatureExtractor` | The engine half of each decision: candidates and their numbers |
| `ChoiceRule`, `ChoiceParams` | The choice half: picks a move or END from those numbers, no engine calls |
| `DecisionRecord`, `CandidateFeatures` | One recorded decision and its candidates |
| `IsolationRecord` | A frozen copy of the isolated position (board, komi, stakes) |
| `RunRecorder`, `ProblemRecord` | Writes each run to `redo_runs/` |
| `TreeComparison` | Compares the generated tree with the author's correct tree |
| `MovePath` | Move sequence helpers: extracting RIGHT paths, common prefix |

Reviewing generated lines and building a library of feedback from them is covered in
[cases.md](cases.md).

## Running it

```
java -cp autoprob.jar autoprob.GoTool config.properties cmd=redo path=sample_problems/gp-721.sgf
```

- `path` can be a single SGF or a directory; for a directory every `.sgf` in it is run in
  name order and a summary table is printed at the end.
- `csvout.path=results.csv` writes one row per problem, useful for comparing tuning runs.
- A long run can be stopped with Ctrl-C, pressed twice: the first press stops KataGo,
  which the run would otherwise restart, the second stops the run. Every problem that
  finished is already in the run record and can be reviewed; only the one in progress is
  lost.
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
- Optionally, the problem's difficulty as a `DI` root property, worded like the
  goproblems.com rank: `DI[3 dan]`, `DI[12 kyu]`. SGF has no standard difficulty property,
  so this is a custom one. It does not affect generation; it is recorded in the run and
  shown in the review page. The sample problems all have it.

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

### 3. Generate the correct tree

`SolutionPathGenerator` builds the correct part of the solution tree, alternating solver
and opponent turns. All analysis is restricted to moves within `redo.near_dist` of a real
(non-filled) stone.

Each turn is a decision made in two halves: `DecisionFeatureExtractor` asks the engine for
the candidates and the numbers below, then `ChoiceRule` picks the moves using only those
numbers and the settings. Every decision is recorded with its candidates (see Output),
which is what lets a decision be reviewed, replayed and re-chosen offline.

A decision can put more than one move in the tree, and the tree branches there. The
**main line** follows the first choice at every turn; it is what the line metrics in step 5
score, and what recalibration (step 4) looks at.

**Solver turns: every move that works.** On the site, a move that solves the problem but
is missing from the tree gets a correct answer marked wrong, so the tree must contain every
working move. Candidates are KataGo's top moves (`redo.visits_root` visits for the first
move, `redo.visits` after) plus the moves a human would naturally try (human policy at least
`redo.min_human_policy`, blended over `redo.human_ranks`). Up to `redo.solver_candidates`
plausible ones (KataGo's top move, human-natural moves, and KataGo moves with a prior of at
least `redo.min_response_prior` or 10% of the visits) are each played and the position
after them analyzed on its own at `redo.visits`, giving the solver's **lead after** the
move. A move works if its lead after is within `redo.alt_score_tolerance` points of the
best lead among the *reliable* candidates: KataGo's top move and moves its search gave at
least a tenth of the top move's visits. A move the search barely looked at can come back
with an inflated lead, because the search after it can wander into a line that flatters it,
so it may join the tree but never sets the bar. Because komi is set so that correct play
wins by only `redo.komi_margin`, a move that gives up the result loses far more than that.

Moves other than KataGo's top move must also be within `redo.alt_region_dist` of a stake
stone: a move away from the fight is a tenuki, not an answer to the problem, and is not
evaluated.

KataGo's top move stays the main choice when it works; the other working moves become
alternatives, up to `redo.max_solver_moves` in all. If KataGo's top move is a pass, the
line ends. `redo.solver_alternatives=false` turns this off and the solver plays only
KataGo's top move, as before trees. With `redo.force_first_move=true` the first move is
pinned to the first reference path's first move, as a diagnostic for "is the rest of the
line right if the start is right".

After each main-line solver move the position is analyzed again and two sanity checks run:

- **stakes held:** a warning if more than a third of the stake stones have flipped
  ownership relative to the solved state.
- **score drift:** a warning (once per run) if the score is more than 15 points from the
  komi target. This means the isolation baseline missed part of the solution, and
  triggers recalibration (step 4).

**Opponent turns: the most testing resistance.** This is the heart of the tool: a correct
line continues only while the opponent has a move the solver *must* answer. Lesser tries
belong in refutation branches, not the correct tree.

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
differs by more than `redo.threat_ownership_threshold`. The per-stone differences are
stored, so the count can be redone under another threshold. Every candidate is measured
in every mode, even when the choice rule only needs the first one, so the record is
complete. A candidate is a real threat if
that count reaches `redo.threat_stones`. The same analysis also records how obvious the
solver's correct answer is: its KataGo prior and human policy, and (for the `testing`
criterion) the human policy summed over every answer that holds (`humanAnswers`): how
likely a human is to find some correct answer. A response several moves answer is easier
than its best answer's policy alone suggests. Records from before it was added fall back to
the best answer's policy.

A threat must also be a **real try**: the same analysis counts the solver's **answers**,
the moves KataGo's search took seriously (a tenth of the top move's visits) that score
within `redo.alt_score_tolerance` of the best. If more than `redo.max_answers` (default 3)
moves answer it, the threat tests nothing: the solver is safe whatever they play, and
since every working answer belongs in the tree, keeping it would fan the tree out into
lines no author would write. Authors' trees never have more than 3 working answers after a
resistance in the sample problems. A threat with 0 answers (the solver's best answer is to
pass) is not a real try either. When no real try remains the line ends ("no testing threat
remains").

Which threatening candidate extends the line depends on `redo.extend_mode` and
`redo.response_criterion`:

| `extend_mode` | `response_criterion` | Behavior |
|---|---|---|
| `primary` | (ignored) | Only the first candidate (KataGo's top move) may extend. If it is not a threat, the line ends. Paths end sooner. |
| `scan` | `order` | The first candidate, in KataGo order, that is a threat extends. |
| `scan` | `human` | Among *severe* threats, the one with the highest human policy. |
| `scan` | `testing` (default) | Among severe threats, the one a human is **most likely to get wrong, among those a human would play**: the chance a human finds an answer that holds, less `redo.naturalness_weight` (default 1) times the response's own human policy. Lowest wins, compared in bands of `redo.testing_band` (default 0.2), with ties broken by human policy of the response. |

A threat is *severe* if it is at least `redo.severe_threat_ratio` times the largest threat
among the candidates. The `testing` criterion reflects how good problems are written:
the main line shows the resistance that tests the solver, while plain liberty fills with
automatic answers are left for side branches. The naturalness credit keeps it to
resistances a human would actually play: without it, in gp-8142 after W P18 the main line
took B M18 (human policy 0.00) over the author's P17 (0.83), because M18's best answer
looked obscure, though four answers hold after it. On the sample problems' recorded
decisions a weight of 1 picks the author's resistance at 26 of 35 positions, against 23
with no weight. (The code also accepts `local`, which prefers
the response closest to the stakes; it is not listed in `config.properties`.)

Authors often answer more than one resistance in the correct tree (13 positions in the 20
sample problems). `redo.opponent_branches` (default 1) keeps more: with 2, the next most
testing severe threat by the same criterion also branches the tree.

**Ending.** A line ends naturally when no candidate is a real threat ("no opponent threat
remains"), when the solver's best move is a pass, or (with `redo.end_on_obvious`, the
default) when every real try left is routine: KataGo's prior on the answer is at least
`redo.routine_answer_prior` (0.9) and a human finds an answer that holds at least
`redo.routine_human_answer` (0.8) of the time ("only routine tries remain"). Authors stop
once the result is clear rather than play out replies everyone finds. On the reviewed
cases this ends 3 of the 6 lines a reviewer said ran too long and none they accepted going
on, apart from gp-5922's deep side branches, where they find either way fine. Across the
20 sample problems it ends 39 lines: 36 off the author's tree, 2 where the author ends too,
and one where the author goes on: gp-5849 after B T2, where the reviewer ended it too. A try two answers handle does not count as routine:
that would end gp-721 right after B G1, before the author's W J1 B H2 W N1 B M1, and
gp-28281 after its first move. It is truncated if it reaches
`redo.max_moves` (counting both colors' moves), if the tree reaches
`redo.max_tree_decisions` decisions, or if an analysis fails after a retry; a truncated
main line is reported as not ended naturally. The last solver move of every line is marked
RIGHT.

**Order.** The main line is expanded first, then the other branches shallowest first. When
the tree hits `redo.max_tree_decisions`, the cut falls on the deep ends of side branches,
never on an alternative first move.

**Transpositions.** When two move orders reach the same position, the decision made the
first time is reused, so the engine work is not repeated and both branches continue the
same way.

### 4. Recalibrate if the score drifted

If generation raised the score drift warning and `redo.recalibrate=true`, the stakes and
komi are rebuilt from the end of the main line (its end position against the root pass
position) and the isolated SGF is rewritten. If that moved the baseline by more than 5
points, the tree is discarded and regenerated once with the corrected calibration. If not (the drift was a transient dip, for example mid-capture), the first
generation is kept.

### 5. Score against the reference

**The main line** is compared against every reference path, keeping the one with the
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

**The whole tree** is compared with the author's correct tree (`TreeComparison`), at every
position both trees reach:

| Metric | Meaning |
|---|---|
| `lines` | Generated lines / author RIGHT lines |
| `repro` | Author lines the generated tree contains exactly |
| `solver-missing` | Working solver moves in the author's tree that the generated tree lacks. The most serious gap: on the site these answers would be marked wrong |
| `extra` | Working solver moves the generated tree has and the author does not: an author omission, or a move that does not really work |
| missing / extra responses | Opponent resistances only one tree has |
| ends early / late | Positions where the generated tree ends and the author continues, or the reverse |

Each problem's report lists these positions, and the summary counts the problems where
every author solver move was generated.

## Output

- **Console:** the reference paths, the fortress and komi decisions, the stakes, the
  isolated board, every generated move with its candidates and threat numbers (solver
  alternatives and branches are labeled), the generated tree's lines, a per-file report
  with the tree comparison, and the final summary. Notes and warnings from isolation and
  generation are printed with each report.
- **`redo_out/<name>_redo.sgf`** (`redo.write_file`): the original problem with the
  generated tree grafted in. Each generated first move is a new variation commented
  `GENERATED`, and the last node of every generated line is marked RIGHT.
- **`redo_out/<name>_isolated.sgf`** (`redo.output.isolated`): the isolated board KataGo
  evaluates, with komi, side to move and triangle-marked stakes.
- **CSV** (`csvout.path`): columns `file, error, refpaths, firstmatch, prefix, genlen,
  reflen, delta, exact, natural, intree, endsright, genlines, reproduced, solvermissing,
  solverextra, respmissing, respextra, endsearly, endslate, generated` (the last is the
  main line). A failed problem has only `file` and `error` filled in.
- **Run record** in `redo_runs/<id>/` (`redo.record`): a `manifest.json` with the git
  commit, engine, models and all `redo.*` settings, and per problem a `<problem>.json`
  with the frozen isolation, every decision with its candidates, the generated tree's lines
  and main line, the author lines, and the scores including the tree comparison. Problem files are written as each problem finishes. The review
  page and case seeding read these; see [cases.md](cases.md).
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
| `redo.testing_band` | 0.2 | Width of the human answer bands the `testing` criterion compares |
| `redo.naturalness_weight` | 1 | How much the `testing` criterion favors natural resistances: their human policy times this comes off how easy they are to answer. 0 leaves naturalness a tie-break |
| `redo.end_on_obvious` | true | End the line when every real try left has an obvious answer |
| `redo.routine_answer_prior` | 0.9 | An answer is obvious when KataGo's prior on it is at least this... |
| `redo.routine_human_answer` | 0.8 | ...and a human finds an answer that holds at least this often |
| `redo.force_first_move` | false | Pin the first solver move to the reference |
| `redo.recalibrate` | true | Rebuild stakes and komi and regenerate on score drift |
| `redo.response_candidates` | 4 | KataGo moves considered as opponent responses |
| `redo.min_response_prior` | 0.02 | Minimum prior for a low-visit KataGo candidate |
| `redo.min_human_policy` | 0.05 | Minimum human policy for a human candidate |
| `redo.human_ranks` | `5k,1d` | Human SL ranks blended for human policy |
| `redo.human_threat_extends` | true | Include human-natural moves as candidates |
| `redo.near_dist` | 2.1 | Analysis restricted to moves this close to real stones |
| `redo.max_moves` | 20 | Truncate a line after this many moves |
| `redo.solver_alternatives` | true | Evaluate every plausible solver move and keep each one that works |
| `redo.solver_candidates` | 6 | Solver candidates evaluated per position |
| `redo.alt_score_tolerance` | 1.5 | A solver move works within this many points of the best reliable move's lead |
| `redo.alt_region_dist` | 3 | Alternatives must be this close to a stake stone (-1: anywhere) |
| `redo.max_solver_moves` | 4 | Most solver moves kept at one position |
| `redo.opponent_branches` | 1 | Most opponent resistances kept at one position |
| `redo.max_answers` | 3 | A threat more solver answers handle than this is no real try (0: off) |
| `redo.max_tree_decisions` | 60 | Stop expanding the tree after this many decisions |
| `redo.write_file` | true | Write `<name>_redo.sgf` |
| `redo.output.dir` | `redo_out` | Output directory |
| `redo.output.isolated` | true in `config.properties` (false if unset) | Write `<name>_isolated.sgf` |
| `redo.record` | true | Write the run record to `redo_runs/<id>/` |
| `redo.runs.dir` | `redo_runs` | Where run records go |

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

- **Only the correct tree is generated.** Wrong branches and refutations are not.
- **One resistance per opponent turn by default.** Authors often answer two or three
  (`redo.opponent_branches` adds more), so expect "missing responses" in the comparison.
- **A costly resistance can be chosen.** The threat test asks what happens if the solver
  ignores a move, not what answering it costs the opponent, and the `testing` criterion
  prefers responses whose answer is hard to find. In gp-5973, after B C2 the chosen A2 is a
  real threat with a hard answer, but once it is answered the solver leads by about 30
  points; the author's C1, also KataGo's top response, leads to the seki the problem is
  about.
- **`redo.threat_region_dist` is not applied.** Ownership changes anywhere on the board
  (outside the fortress fill) count toward a threat. The intent, per the commit that added
  the setting, was to stop the opponent grabbing neutral space between the problem and the
  walls from registering as threats. `ChoiceParams` can apply it, but live generation
  leaves it off so behavior is unchanged; `cases-tune tune.threat_region_dist=-1,2` measures
  what turning it on would do to the case library first.
- **19x19 only.** Board size is assumed throughout.
- **Komi clamp.** If the fortress fill is badly imbalanced, komi hits KataGo's ±150 limit
  and the game is no longer balanced; a warning is printed.
