# Fuseki candidate feedback

Human judgments of generated fuseki problem candidates, kept so the generator's rules and filters can be checked and improved against them. One file per candidate: `<candidate id>.json`.

## Workflow

1. Run the scanner (`cmd=fuseki`, see the main README). Every candidate SGF it writes gets a `.json` next to it. Accepted problems and near misses collect in `fuseki_out/problems/` and `fuseki_out/near_misses/`; a deterministic 10% sample of rejected candidates (`fuseki.review_sample_rate`) goes to `fuseki_out/review_sample/`, so reviews also show when a filter throws away good problems.
2. Review: `python3 tools/fuseki_review.py` opens a local page (port 8765) that walks the unreviewed candidates. For each one: optionally pick A or B before seeing the answer, then read the analysis, choose a verdict, tag it, and write why.
3. Finishing the session (the Finish & commit button, or Ctrl-C) commits this folder: `Fuseki feedback: N reviews (...)`.

Candidates made before the scanner wrote json were backfilled with `tools/fuseki_backfill.py --json` (their `generator.commit` says so).

## Record

The record is the candidate json exactly as the scanner wrote it at generation time, plus `reviews`. Later rules will add fields; readers ignore fields they don't know and treat missing ones as unknown. `schema` changes only if an existing field changes meaning.

| Field | Meaning |
|---|---|
| `schema` | record format version (1) |
| `id` | `<game file name>_move<N>`, also the file name |
| `kind` | `problem` (accepted), `near_miss` (strong net gap just under the bar), `rejected` (sampled from rejected candidates) |
| `created` | when the scanner wrote it (`backfilled` for older ones) |
| `source.path` | game file relative to its archive, e.g. `NNGS/1997/11/takashi-chpr-9711222143` |
| `source.game`, `source.move`, `source.to_play`, `source.game_move` | the game, the move number being chosen (1-based), the side to play, and the move actually played |
| `source.black`, `source.black_rank`, `source.white`, `source.white_rank`, `source.date` | game info from the SGF header |
| `source.sgf` | the whole source game |
| `problem.sgf` | the generated problem: full board setup, labels A and B, one move deep, analysis in the root comment |
| `problem.correct`, `problem.correct_label`, `problem.mistake`, `problem.mistake_label` | the two choices; labels go in board order, so the correct move can be either |
| `metrics` | the scanner's numbers for this candidate, below |
| `generator.commit`, `generator.run`, `generator.settings` | scanner commit (`-dirty` if uncommitted changes), run directory, and every `fuseki.*` setting it ran with |
| `reviews[]` | one entry per reviewer, below |

Both SGFs are cleaned to printable ASCII and newlines (old archives carry CRLF line ends, latin-1 names and stray control bytes). Scores are points from the side to play's view.

`metrics` (version 1):

| Metric | Meaning |
|---|---|
| `status`, `reasons` | the scanner's verdict and every rule that rejected it |
| `score_lead` | score before the move |
| `screen_loss` | what the game move lost in the fast batch screen |
| `gap_fast`, `gap_strong` | correct move minus game move, fast net (equal visits) and strong net |
| `strong_best`, `strong_answer_loss` | the strong net's best move and how far the correct move trails it |
| `best_fast`, `good_moves`, `good_ideas` | fast net best move, every move within `fuseki.good_margin` of it, and how many distinct ideas they form |
| `choice_distance` | city-block distance between the two choices |
| `stone_stdev_mean`, `stone_stdev_max` | ownership spread over stones in the search; high means something is unsettled |
| `lines` | ataris or captures in the opening moves of the best, correct and game-move lines |
| `mover_rank`, `mistake_human_policy`, `mistake_human_order` | the player's rank, how often humans of that rank play the game move, and its rank among their choices |
| `answer_natural_policy` | how often the `fuseki.natural_rank` profile plays the correct move |
| `curve`, `crossover` | chance of picking the correct move over the game move, by rank, and where it crosses 50% |

`reviews[]`:

| Field | Meaning |
|---|---|
| `reviewer`, `date` | who and when (git user.name by default) |
| `verdict` | `good` (publish as is), `fixable` (good idea, needs a change such as another distractor), `bad` (reject), `unsure` |
| `tags` | from the list below, plus any free-form ones |
| `why` | the explanation in the reviewer's words |
| `blind_pick`, `blind_correct` | the label picked before the answer was shown, and whether it was the correct one (`null` when skipped) |

## Tags

Why a candidate is bad:

- `implausible-mistake`: nobody at that level would play the game move
- `tactical`: really a fight or life and death, not a fuseki choice
- `not-fuseki`: too late; middle game or endgame
- `local-shape`: a local shape question rather than a whole-board one
- `both-playable`: the "mistake" is fine for humans; the gap is engine nitpicking
- `answer-unnatural`: the correct move is an engine move a human can't learn from
- `ambiguous`: several good answers, and the A/B framing misleads
- `too-easy`: obvious even to beginners
- `duplicate-lesson`: the same lesson as another problem
- `engine-wrong`: the reviewer thinks the engine misjudges the position

Why a candidate is good:

- `instructive`: teaches a real fuseki idea
- `nice-trap`: the mistake is genuinely tempting
- `classic-lesson`: a textbook principle (urgent before big, direction of play, ...)

## Using it

- Stats: compare verdicts and tags with the stored metrics to find thresholds, e.g. whether every `implausible-mistake` had a low `mistake_human_policy`.
- Regression: re-run the current filters over every reviewed position (`source.sgf` and `source.move` are enough to rebuild it) and count how many `bad` ones they now reject and how many `good` ones still pass.
- Explanations: the `why` texts are examples for writing problem comments later.
