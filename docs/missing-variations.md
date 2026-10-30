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
   about the stones the ko decides (see [Stakes](#stakes)). A problem with nothing at stake
   is skipped: no move could fail in it, and a line would have nothing to settle.
2. Every position on the author's correct lines (the lines ending RIGHT) where the solver
   is to play is checked, the start first, then deeper.
3. At each, the candidate moves come up as in `redo` ([redo.md](redo.md)): KataGo's top
   candidates, natural human moves, and every other point within `redo.scan_dist` of a
   stone at stake. The author's moves from elsewhere on its correct lines are candidates
   too, since a different move order is a common way to miss an answer (gp-62070's R19, the
   author's first move, works again later). The ones that do not fail outright on the plain
   board are judged, along with the author's own moves whatever they did. Each gets a
   [level](#levels).
4. The best level any move reaches is what the position allows, worked out without
   trusting the author. A move at that level the problem does not accept is a missing
   variation. A move it accepts that reaches a lower level is an error in the problem.
   Each is judged again with a deeper search (`missing.verify_visits`, twice
   `redo.visits`) before it counts. When no move reaches anything, even winning every
   ko, the isolation misreads the position and it is skipped.

   A position the solver could leave and still reach the best level is settled: every
   move that does not throw the result away reaches it there, so no move is missing.
   Playing elsewhere is a move in the solver's own fill. In gp-62860, after B E2, W E3,
   B F2, W F1, E1 and D2 are miai: Black can play elsewhere once and keep the seki, and
   the scan found twenty points around White's group that keep it at a quick look.

   A found move that only puts an exchange in before a move that works anyway is not
   another answer either. Its line goes on, after the opponent's answer, with a move that
   reaches the best level at the position as it was. Either the answer captures the move
   at once, a stone lost for nothing, or neither stone touches a stone at stake or an
   enclosed eye space, so the exchange does not affect the problem. Review rejected both
   kinds (in the site's coordinates): in gp-62839 Black's E1 is captured by F1 before the
   author's E3, and in gp-62700 Black's peep at M5, answered at M4, came before the
   author's moves at nine positions. A move inside the fight before one of the author's is
   another order of play, and counts: gp-62668's Q17 before O18 and gp-62856's D4 before
   G1 were approved.

   Where the best is a ko, a found move whose ko is worse than the author's is not missing.
   The site splits ko results by who takes the ko first (see [Levels](#levels)), and on
   the board without threats a ko goes to whoever takes it first, so a move that comes to
   less there than the author's best ko answer has the worse ko. In gp-62851 the author's
   R18 takes the ko first; after R19, W S18, Black can only take back the ko White takes at
   T19, or settle for the seki of B T19.

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
   generated as well. The line only illustrates the move, whose level already holds
   against every answer (see [Answers](#answers)), so the opponent resists the way
   `redo`'s lines do (`redo.response_criterion`): by default with the try a human is most
   likely to get wrong, not KataGo's strongest one, which may be a move nobody plays.

   Each line has to show the level its move was judged to reach: the position where it
   ends is walked out under both regimes, and a move with a line that falls short is not
   suggested, since a suggestion has to show what it claims. The generator plays without
   threats, so it can settle for less than a ko the move reaches only by winning it:
   gp-62851's R19 line ended in the seki of B T19.

   A move with a line that runs out of the problem is not suggested either: a move more than
   a line (`OUTSIDE`) past the box around the problem's stones and every move of the
   author's tree, right and wrong. A path that leaks out to the side is never something to
   add to a problem, and out there the [fill](#caveats) around the isolation decides the
   fight. In gp-62395, after B D1, W F2, Black's E1 came out a clean kill: White crawled
   along the second line from G2 and Black blocked under it on the first, and Black's
   first-line stones reached the black wall at N1 and lived. On the board White just
   extends at K2, and the crawl goes nowhere; the line went to W L1, three lines past the
   problem. Review rejected gp-62448's E1, crawling the same way to L1, and gp-62509's J4
   line, with its forcing moves on the outside out to M4. Every line approved so far stays
   within a line of the problem.

   A found move the raw board takes for clearly worse than the author's is not suggested
   either. This is the check adam makes by hand. KataGo looks at the problem as it is, with
   no fill and a short search (`missing.raw_visits`, 1000), after the found move and after
   the author's best move at the position. Each move is scored by the solver's share of the
   stakes: ownership averaged over the stones at stake, 1 when all go the solver's way.

   With both sides free to play elsewhere on the raw board, the numbers run lower than in the
   isolation, so only the two moves are compared. The found move may score up to
   `missing.raw_gap` (0.3) less where the best is clean. Where the best is a ko or seki,
   `missing.raw_gap_ko` (0.6) applies, since the stakes come out about half and half after any
   move. gp-62416's Q19 and Q16 left White's stones 0.40 and 0.27 Black's, against 0.79 after
   the author's O17.

   On the crawl's 70 found moves, the check drops gp-62395's and gp-62448's E1 and most of
   what review rejected, and nothing review approved. The most an approved move gave away was
   0.17 where the best is clean (gp-62554's C1) and 0.37 at a ko (gp-62668's Q18).

   The board has to be the problem's own, 13x13 for a 13x13 problem. On 19x19, the open lines
   past its far edges change the fight: there gp-62468's L13, the author's, looked better than
   the H11 review approved, while on 13x13 H11 scores 0.77 against 0.31.

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
Here every result that needs the solver to win a ko is one level, except that a found move
whose ko is worse than the author's is not offered (step 4 above).

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
- **A seki inside the opponent's area**: in a killing problem, the opponent's stones at
  stake live, but only because the solver's stones inside their area do too, sharing
  liberties with them. That is a seki, not a failure: the solver's stones alive on points
  the opponent owned with the solver passing, enclosed with a point neither side owns (a
  shared liberty), reaching no fill stone. Stones the solver plays inside that the
  opponent kills are the opponent's, and the solver's wall around the area was the
  solver's when it passed, so neither makes a seki. In gp-62860, after B E2, W E3, Black's
  F2 leaves White alive only in seki, and E1 or D2 leaves White alive outright.

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
forbidden there, unless the other side has taken another ko from there too (a double ko,
below).

The exchanges need eyes in each side's fill, so the fortress keeps eight for each color
(`BalancedFortress.EYES`), splitting its fill the other way when that balances better. It
once balanced the areas alone. In gp-62416 the empty zone counted to Black's wall, the whole
fill went to White, and Black had no eye to answer a threat with. After Q19, W P19, Black's
P18 took Q18 and White could never be given the ko back, so Q19 came out a clean kill. On the
raw board KataGo leaves White's stones about half alive after Q19 and Q16, against the
author's O17, which kills. With eyes for both sides, both come out a ko. Should the eyes still
run out, a ko the solver took that the opponent could not be given back counts for nothing.

KataGo can also give up a ko the winning side has taken back. Sure of losing it for want of
threats, it plays that side elsewhere, and its view of the position counts the ko lost. So
when that side's first move on KataGo's line comes after the other side's and is away from
the problem, while the other side has a ko to take, the walk plays to there and searches
again from that side's turn. In gp-62613, after B B4, W B5, Black's C6 was judged clean.
After W C5, B B1, W A4, B A3, W A6, Black takes the ko at A5, and once White had taken it
back at A4, KataGo's line went on B C2 with White elsewhere and Black killing. Searched from
White's turn, White answers at A2 or C4, Black has to take the ko again, and C6 is only a
ko, as the author has it.

A ko the side winning every ko can take back but never end is no ko. In gp-62169, after
B5, W B4, B A6, White's group has one eye, at D4. White can retake at B6 whenever Black
takes at B5, but filling at B5 leaves the group a single liberty. So the group is dead as
it stands, not dead in ko: Black never has to take. Taking gains Black nothing either,
since White takes back.

Such a dead ko is found on the board: after the take back, filling the point would leave
the stones there one liberty, and none of the taker's stones next to the ko can be captured
at once. Without threats, KataGo is sure of winning the ko, and plays elsewhere while White
pushes at C7 and B8 against A6 and A7. With White winning every ko, those stones would be
lost. So while a dead ko is on the board, the taker may not take it anywhere in KataGo's
search, not just as the first move. KataGo answers the pushes instead, and its evaluation
still sees the shape as dead. When a dead ko comes up partway along KataGo's line, the walk
plays to there and searches again with the take barred.

KataGo can only bar a point. So a dead ko is barred only where taking it can never capture
more than the one stone: the other stones next to it have no liberty the taker could fill.
In gp-32711, after B T16, Black can take T17 at T18. But once Black fills S19, T18 captures
T19 and S18 too, and barring the point would bar that capture. Barring a ko the taker needs
does no harm, since with the other side winning every ko the taker could not keep it
anyway: gp-62080's D1 is barred, and B1 still comes out a ko.

Two kos can make a double ko. In gp-62623 (on the site six rows lower: E12, D10, F9, B13),
after B E18, W D16, B F15, W B19, Black takes at D19 and White takes back at E19. Black's
A19 then puts B19 and C19 in atari, and White has to take it at A18. Now Black takes at D19
again without a threat, since White's last capture was at A18, and when White takes back
at E19, Black takes A18 at A19. Black can take one ko whenever White takes back the other,
so White never gets the move to fill either. Like a ko it can take back but never fill, the
double ko goes against White: F15 kills cleanly, as B19 and D19 do. The walk sees a double
ko when the other side has taken two different kos from the same position, each taken back
to it, and ends the line with the other side's take, as KataGo sees the position there,
with the winning side to play and no threat.

Right after White's retake at E19, D19 is banned to Black for that one move. So the dead-ko
test counts a point banned only by the ko just taken as one the taker could fill. Otherwise
A19 would look like a dead ko there, since White can never fill it, and barring it would
take Black's second ko away.

Leaving dead kos to the taker, with no exchange for the other side, was tried and dropped.
It hands the taker kos it needs to win, as in gp-32711's S16: White can never fill T19, but
Black needs the point as an eye.

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

When the answer is a seki, no stone swings: the opponent's stones live either way, and
only their eye space changes hands. In gp-62860 nothing came out at stake, so no move
could fail, every move came out a seki, and the generated lines wandered off to the outside
(B E6). So when no stone swings, the empty points the solver gains in the solved state are
followed through what the opponent owns with the solver passing, and the opponent's stones
reached are at stake: there, both of White's strings around the eye space.

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
  that makes a ko, say), which KataGo passes over, and while a ko is open with KataGo's next
  moves too: KataGo, expecting that ko lost for want of threats, gives up a fight the regime
  lets it win. In gp-62080 White retakes the ko at C1 and KataGo then plays elsewhere for
  it, though G1 and E2 live, so Black's B1 there is only a ko;
- the opponent, with KataGo's next moves, at its turns before a ko it took and lost or
  before the solver found a way out. Its moves there were KataGo's guesses on the way to a
  line KataGo misjudged.

A move KataGo plays away from the problem on its line counts as a turn too, before a ko on
the walk's line as after where the walk settles. Only the walker's own exchanges are no
one's turn: right after a ko take, each side fills an eye of its own fill. Before a ko,
KataGo may play elsewhere because it expects to win the ko, which the regime then takes
away. In gp-36582 White's S19, offering Black the ko, comes where KataGo plays elsewhere.
Single deviations
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
Every suggestion, missing move or accepted move that falls short, carries a
[confidence](#confidence).

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

### Confidence

Suggestions are meant to go to the site as edit suggestions for people to review, so each
one says how far to trust it, as a band and the reasons for and against: "probably right
(65-85%). For it: ... Against it: ...". It is in the console report, the SGF comment, the
review notes and the JSON (`confidence`: `low`, `high`, `label`, `reasons`).

It is a heuristic, not a measured rate. A missing move starts at 75, an accepted move that
falls short at 70, since telling the author a move is wrong is the bolder claim. The band
then moves:

| Evidence | Change |
|---|---|
| the line that shows the result goes through a ko, settled by giving one side every threat | -15 |
| against some of the opponent's answers, the result came from the search's deviations, not KataGo's own line | -10 (-15 for three or more) |
| the stones at stake end the solver's way only narrowly (ownership of the deciding one under 0.7) | -10 |
| the move takes a ko, or comes right after a ko capture | -5 |
| missing move: it is more than 2.5 lines from every stone (more like a tenuki), or else out in the open, with lines to the edge on three sides | -10, or -5 |
| missing move: the problem has it on a wrong line, so the author looked at it and rejected it | -15 |
| move that falls short: the problem answers it this way too and still accepts it | -10 |
| the isolation had to be adjusted (stakes on a retry, komi rebalanced, stakes settled under kos, only the opponent's area at stake) | -5 each, up to -15 |
| the move's line reaches a position on the author's correct lines | +15 |
| the author plays the same move on a correct line elsewhere in the problem | +5 |
| it is KataGo's own first choice there | +5 |
| the author's accepted moves reach the same result, so the isolation reads the problem as the author does | +5 |

The band is the score plus or minus 5, and 5 more for each kind of evidence against it, up
to 25. Its middle names it: likely right from 80, probably right from 60, uncertain from 40,
doubtful below. The first two reviewed findings sit where they should: gp-62070's R19, which
review confirmed, comes out likely right; gp-62080's B1, which review rejected (the author
had it as wrong, and its line went through a ko), would have come out uncertain. The weights
are a starting point to fit to reviews once there are enough of them in the case library.

## Suggesting a finding to the site

```
bash gotool.sh cmd=suggest finding=missing_variations/easy/gp-62070_missing.json
bash gotool.sh cmd=suggest finding=missing_variations/easy/gp-62070_missing.json suggest.post=true
```

This offers a finding's missing moves to goproblems.com as a suggested edit, which the
author or an editor approves or rejects. Without `suggest.post=true` it is a dry run that
prints the comment and both SGFs. The site's API (`POST /api/v2/suggested_problem_edits`,
with `problemId`, `comment`, `originalSgf` and `suggestedSgf`) checks none of them. Approving
a suggestion replaces the problem's SGF with the suggested one, and creating one notifies
the author and goes out on the site's public feed. So the command checks first:

- the problem on the site is still the one the finder read (`suggest.source`, by default
  `sample_problems/<problem>.sgf`, whose `DI` is set aside);
- no suggestion is open on the problem, since approving either of two would undo the other
  (`suggest.force=true` goes on anyway);
- no suggestion made before, approved or rejected, has the lines already.

The suggested SGF is the site's own with the finding's lines added at their position, moved
back to the problem's board, each ending `C[RIGHT]`. Nothing else changes, since the site
shows reviewers every node that differs. The finder's own SGF is on 19x19 and does not do
for this. The comment names the position, the move, the line and the confidence;
`suggest.comment=...` replaces it. gp-62070's R19 went in as suggestion #851.

[The crawl](crawl.md) does all this for the whole site: it judges problems newest first,
writes their suggestions, and sends only the ones a person approved.

## Checking the levels

`cases/levels.txt` holds positions with the levels their moves reach, as reviewed. Each line
gives the problem, the moves from the start (or `start`), and each move with its level:

```
gp-62169 | B B5,W B4 | B A6 CLEAN
```

The level is `CLEAN`, `SEKI`, `KO`, `FAIL`, `!CLEAN` (anything but clean) or `?` (just
report), and a `#` line above says why. To judge every move with the finder's own
classifier and count how many come out as expected:

```
bash gotool.sh cmd=levels
bash gotool.sh cmd=levels problem=gp-62169,gp-62080
```

The problems come from `sample_problems/`. A full run takes about 25 minutes. Run it after
any change to how moves are judged. gp-10330's Q3 and gp-62623's D19 miss in some runs (see
[Caveats](#caveats)).

## Caveats

The verdict is KataGo's, on the isolated position, so look at each finding before adding
it to a problem. Moves that reach the result in a slower or less efficient way are reported
on purpose, since the site counts them correct, but not an exchange put in before a move
that works anyway (step 4 above); positions the isolation misreads are skipped rather than
reported, so some missing variations go unnoticed.

- Past the opponent's first answer, its resistance is KataGo's own, apart from the ko
  offers and lost kos above. A deeper try KataGo overlooks can make a move look better
  than it is.
- The deviations cover the first six moves of a line and three tries at each turn. A ko
  the solver walks into further on, which it could have avoided, is missed: gp-10330's Q3
  line reaches a ko ten moves on, and Q3 comes out a ko or worse in some runs.
- KataGo, sure of winning a ko for want of threats, can play elsewhere while the other
  side fills its liberties, and the deviations do not always find the answer. gp-62623's
  D19 came out a ko in one run of five, through W G19, though Black's E19 and D17 both
  kill against it.
- Ko levels are not split by steps. Who takes first only counts against a found move whose
  ko is worse than the author's.
- The exchange rule reads "touches the fight" off the board: next to a stone at stake, or
  in an enclosed eye space. A key move out in the open, such as a net a step away from the
  stones, followed by the author's move after an answer that also stays clear of them,
  would be taken for an exchange.
- A goal stated in the root comment ("make a ko", say) is not read: every problem is judged
  by the default goals, as the site does when there is none.
- Positions the isolation misreads are skipped: when the problem relies on outside stones
  being strong (gp-62114's black stones below the corner), the fortress fill can leave
  them weak, and then every move fails.
- The raw-board check only ever drops a move. The raw board can be wrong the other way: in
  gp-62564 it rated the found moves above the author's A2, though review found they let White
  live where the answer is a ko.
- The fill lives, so stones that reach it live too. Its wall stands `redo.gap` (4) lines
  past the problem's stones, and a fight that runs out that far is judged on the fill, not
  the open board: gp-62395's E1 came out a clean kill only because Black's first-line crawl
  joined the wall at N1. With the wall at 8 lines, E1 failed and the author's G2 still
  killed, but at 10 the fill could not be balanced (komi past ±150), so a wider gap is no
  general answer. A found move with a line that runs out of the problem is not suggested
  (step 5), but a level can still rest on the fill, an accepted move's included.
