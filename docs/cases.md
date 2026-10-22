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
- `<problem>.json`: the frozen isolation (board rows, komi, stakes), every decision with
  its candidates, the generated tree's lines (`generatedLines`) and main line
  (`generated`), the author's RIGHT lines (`refs`), and the scores including the tree
  comparison (`tree`).

Problem files are written as each problem finishes. Turn recording off with
`redo.record=false`; move it with `redo.runs.dir`.

## Cases

A case file holds every case for one problem:

```json
{
  "problem": "gp-721",
  "problemFile": "sample_problems/gp-721.sgf",
  "problemSha": "6584…",
  "acceptedLines": [],
  "decisions": [
    {
      "at": ["B L1"],
      "toMove": "W",
      "role": "opponent",
      "expect": { "choose": ["W F1"], "avoid": ["W N1"] },
      "source": "review",
      "tags": ["wrong-resistance"],
      "note": "F1 is the real test here",
      "by": "adam",
      "created": "2026-10-21",
      "isolation": { "solver": "B", "komi": 12.5, "targetLead": 2.5, "stakes": ["G2", "…"], "rows": ["…"] },
      "observed": { "run": "20261021-170828", "commit": "a07cf72", "model": "…", "decision": { "…": "…" } }
    }
  ]
}
```

**Decision cases** are the main unit: a position and what should happen there.

- `expect.choose`: moves that are right here. At a **solver** turn every one of them must
  be in the tree, since each one works and a working move left out marks a correct answer
  wrong. At an **opponent** turn they are acceptable resistances, and the tree must play
  one of them. `END` means the line should end here. Empty means "anything not in avoid".
- `expect.avoid`: moves that are wrong here: the tree must not play any of them. `END` in
  avoid means the line must continue.
- `source`: `author` for cases seeded from the problem's RIGHT paths, `review` for cases a
  person judged.
- `isolation`: the isolated board, komi and stakes, frozen. Replays rebuild exactly this
  position, so a failure points at the decision logic rather than an isolation change.
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
under it, and for the selected position the decision made there: the candidates the
generator weighed, numbered on the board, with the author's moves starred. The panel also
lists where the generated tree and the author's differ (missing or extra working moves and
resistances, and where one ends before the other).

The correct tree is drawn like the goproblems.com tree: depth runs right, branches stack
downward, and a branch drops straight down from its parent before slanting in. It merges
both trees:

| Mark | Meaning |
|---|---|
| green line | the move is in both trees |
| light blue line | only the generated tree has it |
| dashed amber line, faded stone | only the author has it: the generator missed it |
| halo (same colors) | a line ends here |
| green / red dot | a case on this position passes / fails in this run |
| blue square | the selected position |
| thick lines | the current line: from the start to the selected position, then on through the chosen branches |

Clicking a node selects that position. On a solver turn the table shows each candidate's
lead after the move and whether the tree plays it (main or alternative); on an opponent
turn the threat numbers.

| Key | Action |
|---|---|
| `←` `→` | back / forward along the current line |
| `↑` `↓` | choose which branch `→` follows from the selected position |
| `Home` | start position |
| `p` `n` | previous / next problem |
| `g` | what the tree plays here is right (every working move it kept, or its resistance) |
| `b` | the main move is wrong (at an end: the line should continue) |
| `e` | the line should end here instead of this response |
| `1`–`9`, click | cycle a candidate (or any board point): right, wrong, unjudged; `0` the end |
| `x` | clear the verdict on what the tree plays here |
| `d` | delete this decision's case |
| `A` | accept the whole generated tree |
| `s` `f` | stakes, full board |

At a solver turn, marking a move right means "this move works and must be in the tree": if
the generator left out a working move, mark it right and the case fails until generation
includes it.

Each change is written to `cases/<problem>.json` immediately, with the run's isolation
and the decision's numbers. Judging a position that has an author case turns it into a
review case and keeps the author's expected moves. Accepting the tree marks every decision
in it that has no case yet as right as generated and adds its lines to `acceptedLines`.

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
the candidates, since "should the line end here" mostly comes down to that number.

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
