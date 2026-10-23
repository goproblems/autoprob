# Case library (redo feedback)

Whether a generated solution line is good is often a taste question. The case library
collects those judgments so they can be used two ways:

1. **Regression tests.** Re-run the generator on the same scenarios after changing code or
   settings, and see whether it now does the right thing.
2. **A tuning corpus.** Each case keeps the numbers the generator saw (threats, policies,
   human answer difficulty) next to the verdict, so good and bad examples can be analyzed
   and settings tuned offline.

It builds on the [redo command](redo.md).

## The pieces

```
redo  ──►  redo_runs/<id>/         every decision of every problem, with its candidates
             │
             ├──► cases-seed   ──►  cases/<problem>.json   (author cases, free)
             └──► review       ──►  cases/<problem>.json   (your judgments)
                                       │
                 cases  ◄──────────────┤  replay against current code ──► cases_results/<id>.json
                 cases-export ◄────────┤  csv for analysis
                 cases-tune   ◄────────┘  re-choose offline under other settings
```

`cases/` is meant to be committed; `redo_runs/` and `cases_results/` are local output.

## Decisions

The generator (`SolutionPathGenerator`) builds the correct tree out of decisions. At each
position it puts one or more moves in the tree or ends the line, in two steps:

1. `DecisionFeatureExtractor` asks KataGo and the human policy network for the candidates
   and their numbers (the expensive part).
2. `ChoiceRule` picks the moves from those numbers and the `redo.*` settings. It never
   touches the engine.

A decision is recorded as a `DecisionRecord`: the moves leading to the position (`at`),
whose turn it is, the role (`solver` or `opponent`), the candidates, the main choice
(`chose`: a move, or `END`) and any `alternatives` that also branch the tree there. At a
solver turn the alternatives are the other moves that work; each solver candidate keeps
its `afterLead`, the solver's lead after the move, which decides whether it works. For
opponent candidates the record keeps, per real stone, the ownership change between
answering and ignoring the move, so threat counts can be redone under any threshold.

Because the choice step is a pure function of the stored numbers, the same `ChoiceRule`
drives live generation, case replay, and offline tuning.

## Run records

Every `redo` run writes `redo_runs/<yyyyMMdd-HHmmss>/`:

- `manifest.json`: the git commit (with `-dirty` if tracked files were modified), KataGo
  binary, model, human model, all `redo.*` settings, and summary counts.
- `<problem>.json`: the problem's difficulty (its `DI` property, when it has one), the
  frozen isolation (board rows, komi, stakes), every decision with its candidates, the
  generated tree's lines (`generatedLines`) and main line (`generated`), the author's
  RIGHT lines (`refs`), and the scores including the tree comparison (`tree`).

Problem files are written as each problem finishes. Turn recording off with
`redo.record=false`; move it with `redo.runs.dir`.

## Cases

A case file holds every case for one problem:

```json
{
  "problem": "gp-721",
  "problemFile": "sample_problems/gp-721.sgf",
  "problemSha": "0afc…",
  "isolation": { "solver": "B", "komi": 12.5, "targetLead": 2.5, "stakes": ["G2", "…"], "rows": ["…"] },
  "acceptedLines": [],
  "decisions": [
    {
      "at": ["B L1"],
      "toMove": "W",
      "role": "opponent",
      "expect": { "choose": ["W F1"], "avoid": ["W N1"] },
      "source": "review",
      "tags": [],
      "moves": { "W N1": { "tags": ["wrong-resistance"], "note": "F1 is the real test here" } },
      "by": "adam",
      "created": "2026-10-21",
      "observed": { "run": "20261021-170828", "commit": "a07cf72", "model": "…", "decision": { "…": "…" } }
    }
  ]
}
```

Files are written compactly: lists and objects of plain values stay on one line (except
the board rows, one per line), numbers are rounded to 3 decimals, and ownership changes of
0 are left out of the candidates' `deltas`. Files in the older format, with an isolation in
every case, still load and are rewritten in the new format the next time they are saved.

**Decision cases** are the main unit: a position and what should happen there.

- `expect.choose`: moves that are right here. At a **solver** turn every one of them must
  be in the tree, since each one works and a working move left out marks a correct answer
  wrong. At an **opponent** turn they are acceptable resistances, and the tree must play
  one of them. `END` alone means the line should end here; `END` with other moves means
  either way is fine: the line may end here, or go on with one of those moves (at a solver
  turn, with all of them). Empty means "anything not in avoid".
- `expect.avoid`: moves that are wrong here: the tree must not play any of them. `END` in
  avoid means the line must continue.
- `source`: `author` for cases seeded from the problem's RIGHT paths, `review` for cases a
  person judged.
- `moves`: a reviewer's tags and note on single moves from this position, keyed by move
  (`tags` and `note` on the case itself are about the position as a whole).
- `isolation`: the isolated board, komi and stakes, frozen. Replays rebuild exactly this
  position, so a failure points at the decision logic rather than an isolation change. It
  is stored once for the file; a case recorded on a run that isolated the problem
  differently keeps its own.
- `observed`: the decision the generator made when the case was recorded, with all its
  candidate numbers.

There is one case per position (the `at` list is the key).

**Tree cases** are implicit: a problem passes as a whole when a fresh generation has
every working solver move the author has (no missing solver moves in the tree comparison)
and every generated line is acceptable: one of the author's RIGHT paths or one of
`acceptedLines`.

### Tags

The review page offers a starting set, and any other tag can be typed in:

| Tag | Meaning |
|---|---|
| `ended-early` | The line stopped while the opponent still had a real try |
| `ended-late` | The line kept going after the problem was settled |
| `wrong-resistance` | The opponent's response is not the one that belongs in the main line |
| `missing-move` | A move that works (or a resistance that matters) is missing from the tree |
| `unnatural-move` | A move no human would consider |
| `missed-tesuji` | The solver missed the key move |
| `wrong-solver-move` | The solver's move is wrong or inferior |
| `isolation-broken` | The isolated position misrepresents the problem (stakes, komi, fortress) |

## Workflow

The commands below use `gotool.sh`, which compiles and runs `GoTool` with
`redo_local.properties` from WSL. Elsewhere, run
`java -cp … autoprob.GoTool config.properties cmd=…` with the same arguments.

### 1. Run redo

```
bash redo.sh path=sample_problems
```

### 2. Seed author cases (optional, no engine)

```
bash gotool.sh cmd=cases-seed run=latest
```

Every position on an author's RIGHT path becomes a case expecting the author's move, and
the end of each RIGHT path a case expecting `END`. Where several RIGHT paths branch, all
the author's moves at that position count as right: at a solver turn the tree must contain
all of them, at an opponent turn one of them. Positions that already have a case
are left alone. These give a starting set of good examples before any review, but they are
weaker evidence than reviewed cases: the author picked one good line, not the only one.

### 3. Review

```
bash gotool.sh cmd=review run=latest
```

Open http://localhost:8765/. For each problem the page shows a board, the **correct tree**
under it, and a panel for the selected move. The panel also lists where the generated tree
and the author's differ (missing or extra working moves and resistances, and where one
ends before the other).

The problem list on the left gives each problem a status: ○ not reviewed, ◐ partly
reviewed, ✓ every choice reviewed (or the tree accepted), ! needs a look. Next to the name
is the problem's difficulty on goproblems.com (the SGF's `DI` property; for older runs it
is read from the problem file). On the right is how many of the generated tree's choices
you have reviewed. Below the name: the number of lines in the generated tree and the
author's (or "same as author"), and in red the author's working moves the tree is missing
and the choices where the run disagrees with your judgments.

The correct tree is drawn like the goproblems.com tree: depth runs right, branches stack
downward, and a branch drops straight down from its parent before slanting in. It merges
both trees:

| Mark | Meaning |
|---|---|
| green line | the move is in both trees |
| light blue line | only the generated tree has it |
| dashed light green line, faded stone | only the author has it: the generator missed it |
| halo (same colors) | a line ends here |
| green / red dot | you marked this move right / wrong |
| blue square | the selected move |
| thick lines | the current line: from the start to the selected move, then on through the chosen branches |

**A judgment is about the selected move**: whether it belongs in the tree at the position
it was played from. Select a stone in the tree and mark it right (`g`) or wrong (`b`). What
right means depends on whose move it is:

- a **solver** move marked right works, so it must be in the tree (a working move left
  out marks a correct answer wrong on the site); marked wrong, it must not be.
- an **opponent** move marked right is a good resistance for the tree to play; the tree
  passes if it plays one of the resistances you marked right and none you marked wrong.

Tags and a note can be added to the selected move. **After this move** says whether the
line ends there, in the generated tree and in the author's, and three buttons judge it:
the line should end here (`e`, which also marks wrong any move the tree plays next), should
continue (`c`), or either way is fine (`w`: ending here and going on as the tree does are
both right). Sometimes how long a line runs is a matter of taste; marking such places
either way keeps the tuner from counting an earlier or later ending as a mistake. Cases
below a position where the line must end (accepted before the ending was marked, say) are
kept but not counted anywhere: the tree should never get there. A line
usually ends on a solver move, when the opponent has no real try left, but it can end on
an opponent move too, as when a ko is the result. The generator
only ends a line after an opponent move when KataGo's best answer is a pass, so these
judgments mostly record what it does not do yet.

The table shows the other choices at the position the selected move was played from (at
the start position, the first moves): the candidates the generator weighed, with your
verdicts, the author's moves starred and the selected move highlighted. It is for
reference: verdicts are set only with the buttons or their keys, on moves selected in the
tree.

The board shows what can come next from the position on it. Its circles are the
generator's candidates there, numbered in its order, and the next moves of both trees,
colored like the tree: green when both trees play the move, solid blue when only the
generated tree does, dotted light green when only the author does (with a star instead of
a number when it is not one of the candidates). A candidate no tree plays is brown, dotted
when KataGo barely considered it (a prior below 0.02). A green or red dot is your verdict
on that move, and the selected move's stone is ringed in the color of your verdict on it.
Past the start position these candidates are not the table's: the table is about the
selected move, the board about the next one.

On a solver turn the table shows each candidate's lead after the
move and whether the tree plays it (main or alternative); on an opponent turn the threat
numbers.

Every button has a tooltip.

| Key | Action |
|---|---|
| click on the board | play the next move there, if either tree has it (hovering a next move shows its stone) |
| `←` `→` | back / forward along the current line |
| `↑` `↓` | choose which branch `→` follows from the selected move |
| `Home` | start position |
| `p` `n` | previous / next problem |
| `g` `b` | mark the selected move right / wrong (press again to clear) |
| `x` | clear your verdict on the selected move |
| `e` `c` `w` | after the selected move, the line should end / continue / either way is fine |
| `A` | accept the whole generated tree |
| `s` `f` | stakes, full board |

Each change is written to `cases/<problem>.json` immediately, as the case for the position
the move was played from, with the run's isolation and the generator's decision there.
Moves only the author's tree reaches can be judged too; their case has no recorded
decision, and replay makes it fresh. Judging a position that has an author case turns it
into a review case and keeps the author's expected moves. Accepting the tree marks every
choice in it that has no case yet as right as generated and adds its lines to
`acceptedLines`.

The page works on a run that is still going or was stopped: it shows the problems that
have finished, and while the run is not finished it checks for new ones every 30 seconds.
Start it with the run's id (printed as `recording run to redo_runs/<id>` when the run
starts) to review while a long run goes on.

Settings: `review.port` (default 8765), `review.by` (the name stored on judgments,
default the login name), `review.host` (default loopback, or all interfaces under WSL so
Windows can reach it).

### 4. Replay

```
bash gotool.sh cmd=cases
bash gotool.sh cmd=cases cases.lines=true problem=gp-721,gp-5844
```

Each decision case rebuilds its frozen position and asks the current generator for that
one decision, then checks it as described under Cases: no move in `avoid`, and at a solver
turn every move in `choose` in the tree, at an opponent turn one of them.

KataGo is not deterministic between runs, so a choice near a threshold can flip. When a
decision is a close call (see `ChoiceRule.isFragile`: an opponent choice that changes if
threats move by one stone, a human answer by 0.05 or a human policy by 0.03; a solver
candidate within half a point of the works / does not work line), it is re-run
`cases.repeat_close` times (default 3) and reported `unstable` if the results disagree.

With `cases.lines=true` each problem's tree is also regenerated from scratch (fresh
isolation too) and checked as a tree case. A failing tree lists the author's working
moves it lacks, and each generated line that is not acceptable with where it left the
closest acceptable line and what the decision case there expects.

The report counts pass, fail, unstable and error by source and by tag, and lists what
changed since the previous replay. Results, including fresh candidate numbers for every
case, go to `cases_results/<id>.json`.

Filters: `problem=a,b` and `source=author|review`.

### 5. Analyze and tune

```
bash gotool.sh cmd=cases-export out=cases.csv
bash gotool.sh cmd=cases-tune tune.threat_stones=3,4,5 tune.severe_threat_ratio=0.5,0.6,0.8
```

Both use the numbers from the latest replay where a case has them (`results=<id>` for a
specific one, `results=none` for only the recorded observations), otherwise the numbers
observed when the case was recorded. Author cases have no numbers until they are replayed
or the generator reaches their position in a reviewed run.

**Export** writes one row per candidate per case: the case's expectation, the candidate's
label (`choose`, `avoid` or blank), whether the tree plays it (`algo_chose`), and its
numbers, including `after_lead` for solver candidates.
Opponent decisions also get an `END` row whose threat column is the largest threat among
the candidates, since "should the line end here" mostly comes down to that number. Solver
decisions get one (with no threat) when the case judges the ending or the tree ended
there: a line that should end after an opponent move, as in a ko.

**Tune** re-chooses every decision case under each combination of the given
`tune.<setting>` values and reports pass counts, with the cases each setting fixes and
breaks relative to the current config. Tunable settings:

| Setting | Notes |
|---|---|
| `alt_score_tolerance`, `max_solver_moves` | which solver moves count as working, and how many are kept |
| `alt_region_dist` | how close to the stakes an alternative must be; can only narrow, since distant moves are not evaluated |
| `opponent_branches` | how many resistances each opponent turn keeps |
| `max_answers` | how many working answers a threat may have and still count as a real try |
| `threat_stones`, `threat_ownership_threshold` | threats are recounted from the stored ownership changes |
| `threat_region_dist` | only count stones within this distance of the stakes; `-1` counts everywhere (the live default, see the known gap in redo.md) |
| `extend_mode`, `response_criterion`, `severe_threat_ratio`, `testing_band` | the choice among threatening candidates |
| `response_candidates`, `min_response_prior`, `min_human_policy`, `human_threat_extends` | can only narrow: candidates that were never analyzed cannot be added |

Settings that change what the engine computes (visits, `near_dist`, human ranks) cannot be
tuned offline. Each case's numbers come from one engine run, so confirm a promising setting
with a replay (`cmd=cases` with the setting on the command line) before adopting it.

## Files

| Path | Committed | Contents |
|---|---|---|
| `cases/<problem>.json` | yes | the case library |
| `redo_runs/<id>/` | no | run records from `redo` |
| `cases_results/<id>.json` | no | replay results |
| `src/main/java/autoprob/validate/cases/` | | seeding, replay, export, tune |
| `src/main/java/autoprob/validate/review/`, `src/main/resources/review/` | | the review page |
