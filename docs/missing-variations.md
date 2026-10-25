# Missing variations

`cmd=missing-variations` checks a problem's correct moves against what the position
allows. It finds correct lines the problem is missing: moves that solve it as well as the
author's but that the tree does not accept, so on goproblems.com a solver who plays one is
told it is wrong. It also finds moves the problem accepts that fall short, such as a ko
answer where clean life is possible. It stops at the first position where it finds either
and shows it.

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
| `path=<dir>` | every `.sgf` in the directory, in name order, until one has a finding |
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

### Board size

Everything here plays on 19x19, so a problem on a smaller board (the site has many on
13x13) is moved into the matching corner of a full board: each axis keeps the edge its
stones and moves are nearer, and the far edge goes. A problem that comes within two lines
of both edges of an axis, such as a group along the whole side of a 13x13 board, cannot
move without changing, so it is skipped. Everything the finder writes uses the 19x19
coordinates; the console says how far the problem moved.

## How it works

1. The problem is isolated the way `redo` does it ([redo.md](redo.md)): its stones on a
   board walled off by a balanced fortress fill, with komi balanced and the stones at stake
   found from KataGo's ownership after its best first move and after a pass. The board has
   no ko threats: kos are settled by playing them out (see [Kos](#kos)). Then the stakes
   are settled with the solver winning every ko, since a problem whose answer is a ko is
   about the stones the ko decides (see [Stakes](#stakes)).
2. Every position on the author's correct lines (the lines ending RIGHT) where the solver
   is to play is checked, the start first, then deeper.
3. At each, the candidate moves come up as in `redo` ([redo.md](redo.md)): KataGo's top
   candidates, natural human moves, and every other point within `redo.scan_dist` of a
   stone at stake. The ones that do not fail outright on the plain board are judged, along
   with the author's own moves whatever they did. Each gets a [level](#levels).
4. The best level any move reaches is what the position allows, worked out without
   trusting the author. A move at that level the problem does not accept is a missing
   variation. A move it accepts that reaches a lower level is an error in the problem.
   Each is judged again with a deeper search (`missing.verify_visits`, twice
   `redo.visits`) before it counts. When no move reaches anything, even winning every
   ko, the isolation misreads the position and it is skipped.

   The author's moves are judged first. Once some move reaches a level, a move the
   problem does not accept only matters if it reaches that level too, so one that falls
   short of a clean result in the regime where the opponent wins every ko is not played
   out further ("short of the best" in the position's summary). The author's own moves
   are always judged in full.

   The site's controls are respected: a move tagged `NOTTHIS` is outlawed at its
   position, so it is not judged there, and at a position tagged `FORCE` the solver can
   only play the moves the file has, so only those are judged. In gp-62070 the author
   outlaws R19 after B S16, W S15, though R19 kills there too, and it is not reported.
5. For a missing move, its line is generated with the `redo` generator from that
   position, with the move forced first, so the line answers and ends the way `redo`'s
   would. For each of the problem's own answers to it, the solver's way through is
   generated as well. The opponent answers with its strongest resistance, the first real
   threat in KataGo's order, rather than redo's most testing one for a human: a found move
   has to hold against the best defense.

## Levels

goproblems ranks the results of a standard problem by life status, not points
(https://goproblems.com/article/bestpractices). Points do not matter within a level, and
losing a stone or two is not wrong:

| Living | Killing |
|---|---|
| clean life | clean death |
| seki | ko |
| ko | seki |
| failure | failure |

The site splits the ko results further, by how many steps a ko has and who takes it first.
Here every result that needs the solver to win a ko is one level.

A problem is killing when most of the stones at stake are the opponent's. A capturing race
goes by whichever side has more at stake.

### Status

What a position comes to is read from KataGo's ownership (`StatusJudge`):

- **The stones at stake** all end the solver's way: its own alive and the opponent's dead,
  which is the same thing as owned by the solver. Up to `redo.status_stones` (2) may be
  missed, but never half of them.
- **A pocket**: the opponent comes alive enclosed in the problem area, on points the
  solver owned in the solved position. That is a seki, or the opponent living inside.
  A group of the opponent's own that lived in the solved position too is no pocket. In
  gp-8142, Black's corner lives beside the white stones White is saving whatever White
  plays.

### Kos

A move's level depends on who wins the kos. Clean means the solver succeeds even with the
opponent winning every ko; ko means it succeeds only by winning every ko itself. The
isolated board has no threats, so to KataGo whoever takes a ko first wins it, and the
regime is applied to the line itself (`RegimeWalker`). When the side that is to lose the
kos takes one on KataGo's line, the other side gets a free exchange in the fill (each side
fills an eye of its own, worth nothing under area scoring) and takes the ko back. KataGo is
not left to decide the retake: it would weigh it as if that exchange were the last threat,
and pass it up whenever the other side has threats near the problem. The other side may
then use its own threats and take the ko again; a ko taken again from the same position is
forbidden there.

### Stakes

The stones at stake come from KataGo's solved state, after its best first move. On a board
without threats, when the opponent takes the ko first in a problem whose answer is a ko,
that state leaves the stones the ko decides with the opponent, and they are not at stake.
In gp-36582 only the two stones the answer cuts off came out at stake, so the answer looked
like a clean kill of two stones. So KataGo's top first moves and the author's lines are
also played out with the solver winning every ko. The one that gains the solver the most of
the problem area is the solved state. When it gains the solver more than KataGo's own, the
stones of the group it decides (the bulk of the stones it swings, by color) join the ones
KataGo found, and it is the solved state from then on. The
other side's stones it swings are only the means: in gp-62121 the author's line captures
White's stones inside Black's eye space as Black lives, but another line may live without
capturing them. The author's lines are only hints at what may be at
stake; they never decide what a move comes to. In gp-36582 the author's line puts the whole
corner at stake, and the answer comes out as a ko. This also steadies the isolation, since
KataGo's search at the start does not always find the answer: some runs of gp-10330 had
only Black's stones inside White's group at stake, and not the group Black's answer kills.

### Komi

KataGo plays for the game, not for life status, so komi decides what it fights for. The
isolation balances it so that the solver is just ahead in the solved position (and the
stakes settled with the solver winning its kos rebalance it). A wider margin, halfway
between the solved and the failed position, was tried so that points would sway KataGo
less, but then it takes a line through a ko it expects to win as readily as a clean one,
and the regime takes the ko away: gp-32711's T19 and gp-36582's S17 came out a level lower.
Just ahead, keeping the status is what keeps KataGo winning. The cost is that a problem can
come out differently between runs when KataGo's first estimate of the score moves komi by a
few points: gp-10330's Q3 has come out killing in some runs and failing in others.

### Answers

KataGo's own search is not trusted to find the opponent's best answer to a move. On a board
without threats it rarely looks at one that starts a ko. So every legal answer within
`missing.reply_dist` (2) of a stone at stake or of the move is played and walked out
under the regime, quickly at first (`missing.reply_visits`, 300). An answer that brings the
result down is walked out again with a deeper search before it counts. The worst answer
decides.

### Deviations

After the answer, KataGo plays both sides as if on a board without threats, and for points
rather than life status, so its line is not always either side's best under the regime. In
the first six moves of the line each side may deviate:

- the solver, when the line falls short, with KataGo's next moves there;
- the side that wins every ko, with moves offering the other side a ko to take (a throw-in
  that makes a ko, say), which KataGo passes over;
- the opponent, with KataGo's next moves, at its turns before a ko it took and lost or
  before the solver found a way out. Its moves there were KataGo's guesses on the way to a
  line KataGo misjudged.

A move KataGo plays away from the problem on its line counts as a turn too: in gp-36582
White's S19, offering Black the ko, comes where KataGo plays elsewhere. Single deviations
are tried at every turn before deviations within them, the earliest turns first, and when a
deviation changes the result the line is gone over again, since the other side may now do
better earlier. All of it is held to a fixed number of fresh KataGo analyses (16 for a
move's own line and a confirmed answer, 8 for a quick answer), and a deviation is only tried
while enough is left for the other side to answer it, so that none decides the result just
by coming last. In gp-32711 after B Q19, W O19, KataGo's line goes on with B T15, a ko that
White wins when it has every threat; B O18 lives without one.

`missing.skip_ko=true` passes over every position whose own line goes through a ko ("ko,
not judged"); useful when scanning the site for findings to trust without a second look.

## What it shows

The console report names the problem and its difficulty, the position, the best level
there and the moves the problem accepts. For each missing move it shows how the move came
up (KataGo prior and visits, human policy, distance to the stones at stake), whether the
problem has the move on a wrong line, how it plays out and the generated lines. For each
accepted move that falls short, it shows the level the move reaches and the answer that
holds it there. Each position checked also gets a line with every judged move by level.

`missing_variations/<problem>_missing.sgf` is the problem with the lines added at their
position. The new move is commented `MISSING VARIATION (autoprob)` and the lines end
RIGHT. If the problem already has the move on a wrong line, that node is reused and is no
longer marked wrong, and the solver's way through each of the problem's answers to it is
added below that answer, next to the old wrong continuation. An accepted move that falls
short gets an `AUTOPROB:` comment saying what it reaches and what is possible.
`<problem>_missing.json` has the same finding as data. `missing.dir` moves all of this.

Each finding is also added to a run the review tool ([cases.md](cases.md)) reads,
`missing_variations/review`:

```
bash gotool.sh cmd=review run=missing_variations/review
```

It lists every problem with a finding, latest last. The author's tree shows as shared and
the found lines as generated only, so the new moves stand out. Clicking the position shows
its decision: every candidate measured there and the moves the rule counts as working. The
generated lines have their decisions too. Judgments made there are saved to the case
library like any other review (add `review.port=8766` to run it next to a redo review).

## Caveats

The verdict is KataGo's, on the isolated position, so look at each finding before adding
it to a problem. Moves that reach the result in a slower or less efficient way are reported
on purpose, since the site counts them correct; positions the isolation misreads are
skipped rather than reported, so some missing variations go unnoticed.

- Past the opponent's first answer, its resistance is KataGo's own, apart from the ko
  offers and lost kos above. A deeper try KataGo overlooks can make a move look better
  than it is.
- The deviations cover the first six moves of a line and three tries at each turn. A ko
  the solver walks into further on, which it could have avoided, is missed: gp-10330's Q3
  line reaches a ko ten moves on, and Q3 comes out a ko or worse in some runs.
- When the opponent's stones at stake live in seki in a killing problem, that comes out as
  failure, not as the seki level.
- Ko levels are not split by steps or by who takes first.
- A goal stated in the root comment ("make a ko", say) is not read: every problem is judged
  by the default goals, as the site does when there is none.
- Positions the isolation misreads are skipped: when the problem relies on outside stones
  being strong (gp-62114's black stones below the corner), the fortress fill can leave
  them weak, and then every move fails.
