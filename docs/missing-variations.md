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

   The best level has to stand on a move the problem accepts or on one the deeper search
   confirms. A move the problem lacks that only looked best at the quick look is taken down
   to what the deeper search makes of it, and the best is worked out again, so the problem's
   own moves are never held to a level nothing reaches.

   Where every move the problem accepts falls short of a move it lacks, the reading and the
   author disagree about what the position comes to, and nothing is suggested there. A
   missing move is one that does as well as the author's; one that does better by this
   reading has nearly always been a misreading. In gp-60421 the author's M13 is a ko for the
   whole corner. The stones at stake had come out as the two that L12 captures, so L12, which
   lets the corner live, was a clean kill and M13 a failure ("this is ko in correct
   solution"). In gp-61415 one stone was at stake ("real solution is ko, this is nothing"),
   and gp-61344's F2 "doesn't work". Review rejected 19 of the 21 moves found that way. At
   the start the whole problem is passed over (`skipped`, "the isolation reads the problem
   differently from its author"), since every level in it would be read the same way. The
   two it approved were moves that work beside an answer the reading had undervalued
   (gp-61100's K11, gp-60688's T17); those are no longer found.

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

   A found move played outside is not another answer either, when the solver's own stones
   are at stake, so that the problem is to live (or to win a race). Played outside means
   the opponent keeps the move's point once the best move is played: KataGo's ownership
   there, from the solver's view, is at most minus `missing.outside_owner` (0.7). Nothing
   the problem decides is decided there. The move keeps the result, since the group lives
   anyway, and that is all it does: a peep or an atari against the wall around the group,
   answered or ignored, its stone lost. Review calls these outside peeps, and rejected more
   suggestions for them than for anything else (in the site's coordinates). In gp-61551,
   after B3, A3, A2, A1, Black's A4 is an atari from outside that White ignores for C1 and
   captures later at A5. In gp-61593 White's A4 captures Black's B4 at once. In gp-61668
   Black's N10 and N7 come before the author's M13.

   On 14 suggestions review rejected for these, the peeps' points came out at -0.91 to
   -1.00 in 12 (every move each one found). The moves a line played outside, beside its
   own, came out at -0.85 to -1.00, in approved suggestions too: after D1 and White's C5 in
   gp-62261, Black's A5, A4 and B5 around White's B4. The approved found moves of a group
   living inside came out at -0.47 (gp-62194's G5, next to Black's stones at stake) to
   +1.00, in 6 suggestions, and the moves inside on the lines at -0.24 or more. A move
   inside that ends up meaning nothing still counts, since it loses nothing. When the
   solver kills, its stones outside are safe, and nothing it plays is held to this
   (gp-61650's A4, an atari from outside on White's stones, was approved). The other two
   rejections were pushes from the group outward, whose stone lives: gp-62219's C5 came
   out at +0.60 to +0.82, Black's own point, and gp-62141's F11 at -0.10 to -0.33. This
   does not catch those.

   Where the best is a ko, a found move whose ko is worse than the author's is not missing.
   The site splits ko results by who takes the ko first (see [Levels](#levels)), and on
   the board without threats a ko goes to whoever takes it first, so a move that comes to
   less there than the author's best ko answer has the worse ko. In gp-62851 the author's
   R18 takes the ko first; after R19, W S18, Black can only take back the ko White takes at
   T19, or settle for the seki of B T19.

   Where the best is a ko and every answer of the problem's that reaches it takes the ko, a
   found move that plays elsewhere first is not missing either: it only spends a ko threat.
   In gp-61191, after the author's line to B M10, White's N11 and N10 came before the
   author's K12, which takes the ko ("wastes ko threats"). With the solver winning every ko,
   nearly any move that loses nothing there reaches the ko too: gp-60002 had six at one
   position. Two kinds of move are not that, and review approved one of each:

   - where the problem also accepts an answer that takes no ko, the found move may be
     another order of play before that one. In gp-62278, after B D1, W E1, the author takes
     the ko at E2 or plays G1, and the found H1 goes on W C1, B G1;
   - a move the problem itself has, as wrong, is judged by its line: the problem says it
     fails, and the line through the problem's own answer shows whether the ko is still
     there to take. In gp-62098, after B D13 and the problem's W H12, the author's F13 takes
     the ko as before.

   Review approved a third kind that the rule still leaves out: gp-62668's Q18 and Q17,
   placements inside the eye space before the author's O18 takes the ko.

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
   would. The line only illustrates the move, whose level already holds against every
   answer (see [Answers](#answers)), so the opponent resists the way `redo`'s lines do
   (`redo.response_criterion`): by default with the try a human is most likely to get
   wrong, not KataGo's strongest one, which may be a move nobody plays. The opponent plays
   one move at each turn (`redo.opponent_branches`).

   A line ends as soon as the opponent has nothing left that tests the solver. It is added
   to show that its move works, not to play the position out, and review cut the generator's
   lines back in every one of the 42 suggestions it edited by hand, from 965 moves to 310.
   The lines use `redo`'s rule for ending ([redo.md](redo.md)) with these settings:

   - a single last try with an obvious answer ends the line too (`missing.routine_min_tries`,
     1), where `redo` still shows one;
   - katago's certainty of the answer counts every answer that holds, as the human's does
     (`missing.routine_summed`). Two answers that both hold split katago's prior, and a try
     either of them answers looked like a hard one: after gp-59959's B K9, White's L11 has
     two answers, 0.56 on the better and 1.00 on both;
   - a try that only the human policy offers has to be one people play
     (`missing.min_human_try`, 0.1). After gp-60235's B S16, W T15, B T18, White's S15, at
     0.06 and with no katago prior, kept the line going;
   - the line still goes on where the solver's answer to the opponent's first try is another
     move that worked when its last move was chosen (`missing.prepared_goes_on`). That last
     move only prepared the answer, and a line ending on it would mark it right before the
     solver had shown the answer. In gp-60321, after B G2, W G1, B F1, W E1, every white try
     after the found H3 has D1 for an obvious answer, and D1 is the author's move there:
     review kept B H3, W J2, B D1.

   Where a line reaches a position of the author's correct lines by another order of moves,
   it follows the author from there: it ends where the author's line ends and goes on with
   the author's answers where it goes on. gp-59989's B G12, W H10, B H11 ends on the position
   the author's H11, H10, G12 ends on; the generator had gone on W J12, B J11, W H12, B H13.

   A line also runs no deeper than two moves past the problem's own longest correct line
   from the position, and seven moves at least. One stopped there goes in as it is and is
   listed as cut short, like a line any other limit stops. Of the crawl's 27 suggestions
   with a line of ten moves or more that review did not edit, it approved one.

   And a line ends where the result is plain: at a solver's turn where more moves work than
   a real try leaves (`redo.max_answers`, three; `missing.many_work_ends`). What ends there
   depends on the move before:

   - after a try the generator chose, the try was no real one. The count of answers it was
     chosen on comes from the visits of one search after it, which follows its best answer
     and gives the others too few to count. The try comes out, and the opponent's turn is
     decided again without it: another try, or the end of the line. In gp-59689, after
     B C16, W D19, B B15, White's E17 went in with one answer counted, and C15, A16, A17 and
     F17 all work; each was followed out. Review took such a try out by hand in gp-62299
     (13x13): after B M13, W M11, B M10 the draft went on W L9, which four moves answer, and
     review ended the line on M10;
   - after an answer the line was given (the problem's own, or the author's at a position
     reached by another order of moves), the opponent had no choice of it, so it stays, and
     each move that works goes in and ends its line there. In gp-61269 (13x13), after the
     found K10 and the problem's W N11, four moves work. The draft followed them out to 15
     added moves, and review cut it to those four. It did the same in gp-62261, and kept
     lines going past such a turn in one tree, gp-62045.

   `missing.max_answers` sets the number for these lines alone, where `redo.max_answers`
   would change `redo`'s trees too. Three is the lenient end of what review did: in the 40
   suggestions it edited by hand and approved, counting the tries whose move before them
   it kept, it kept a try the draft gave one answer 71 times of 97, one with two answers 9
   times of 21, one with three 3 times of 9, and one with four or more once in 4. After
   one of the problem's own answers it kept lines going where the draft added two moves
   (7 of 9) and ended all four where it added four or more.

   In a line for a move that reaches a clean result, the solver takes no ko. A clean result
   is one the solver reaches with the opponent winning every ko, so it needs none taken, and
   the opponent would take it back: a tree where the solver takes a ko reads as a ko. Where
   another move works, a solver's move that itself takes a ko is left out
   (`redo.solver_avoids_ko`). A line that still has one after its first move takes its
   branch with it, or the found move when it is the move's own line. Of the crawl's
   suggestions at a clean level, review approved one of the eight whose lines had the solver
   take a ko (gp-61354: "ko shape"), and cut the ko out of three more by hand before
   approving them. The found move itself may take a ko; its confidence says so.

   The rule covers a move the problem itself has, too, and there it leaves out a line
   review approved: in gp-60579, after B O18, W O19, the problem has Black's N19, which
   takes a ko, and review kept W L19, B P19 after it. Of the three reviewed suggestions
   whose lines ran on past such a move of the problem's, it approved that one.

   `cmd=line-ends` measures the ending rule against review (see
   [Measuring against review](#measuring-against-review)). On the 40 positions measured so
   far from suggestions edited by hand, the rule as it was ended 6 of the 19 lines where
   review did and the rule as it is now 14, both going on at all but one or two of the 21
   positions review kept inside a line.

   Where the line runs on the problem's own tree, as it does from a move the problem has as
   wrong, the opponent plays the problem's own answers instead, each of them and no other.
   On the site the computer answers with those, so a correct line through an answer of the
   line's own, beside the problem's, is one it never takes, and shows nothing. In gp-62134,
   after B B3, W B2, B D1, the problem's wrong line, the line took White's C4, the try a
   human gets wrong most, beside the problem's C3, KataGo's strongest by far (prior 0.77
   against 0.06). What the problem lacks there is Black's answer to C3. The solver's answer
   to one of the problem's moves is made whatever the limits (below): without it the line
   would end on the problem's wrong line, where it cannot go in.

   For the same reason a line does not end on a solver's move the problem itself has. Where
   the ending rule would stop there, the line goes on with the try that is left, however
   routine its answer, and ends on the answer. Review approved gp-60480's B S18, W Q18,
   B R18, W R19, B S19 that way: S18 and R18 are the problem's, and R19 has an answer
   everyone finds. Ended on R18, the line could not go in, and the suggestion was lost. Only
   where nothing threatens at all is there no try to show, and the line is left out with a
   note.

   A line on the problem's own tree does not go on through a solver's move the problem
   answers itself (`missing.through_refuted`, off). The author read that move out and has
   it fail. Where the generator reads it as working, the two disagree, and a line through
   the author's own refutation cannot settle that. So after the problem's answer to the
   found move, the moves that get a line are the ones the problem lacks there, or has with
   nothing after them. One it answers is left as the problem has it: the finding lists it
   under `left`, and the console says so. Where no other move works, the found move is not
   suggested, since its only way runs through the author's refutation. Of the crawl's
   suggestions with a line that went on through such a move and the problem's answer to
   it, review approved none of nine (by its notes a ko four times, an escape once), and no
   tree it approved or edited has such a line.

   The solver is never cut down to one move: at each of its turns on the line, every move
   that works goes in, each followed out the same way as the main one, as in `redo`'s
   trees (where so many work that the result is plain, above, each still goes in, and ends
   its line). On the site a correct move the tree lacks is marked wrong. In gp-62299 the line
   kept one move of four: after B M13, W M11, B M10, W L9 (on its 13x13 board), Black's
   N11, M12 and N10 work as well as the line's M9, and they were left out. The same
   happened twice more further down the line. Every plausible solver move is played out,
   too: the finder lifts `redo.solver_candidates`, whose cap left the human moves, tried
   after KataGo's, untried. The one exception is a move played outside (see step 4): for a
   group living inside, the finder sets `redo.outside_owner` from `missing.outside_owner`,
   and no such move goes in at a turn where a move inside works. In gp-61668 the lines of
   N7 went through N10 again at five turns.

   A generated tree is still bounded by `redo.max_tree_decisions` (gp-62299's came to 37
   decisions) and each line by `redo.max_moves`. A limit only keeps a branch from going
   deeper: every move that works at a turn already decided stays in the tree, and a branch
   the limit reaches ends on its last solver move. Such a line goes in as it is, never
   held back, and the finding lists it under `cut` with why (as does a line an analysis
   failed on); the review page says so beside the position, and the crawl's status counts
   them. A move is never left out for a limit, and a limit never stops the solver's answer
   to one of the problem's own moves.

   The solver's moves on a line are the ones KataGo and the human policy offer, without the
   scan of every point near the stakes that the positions checked get. So a working move
   both rate low can still be missed. N10 there had a human policy of 0.043 to 0.055 from
   run to run, so with `redo.min_human_policy` at 0.05 it came and went; the cutoff is now
   0.03. A move rated lower still is only found by the scan, and with the scan on in a
   line, the tree ran into its limit. A line can go on past the point where its result is
   settled, and there every move that keeps the result works: nine moves after gp-62299's
   W N12 in one branch, where Black had won already.

   Each line has to show the level its move was judged to reach: the position where it
   ends is walked out under both regimes, and a move with a line that falls short is not
   suggested, since a suggestion has to show what it claims. The generator plays without
   threats, so it can settle for less than a ko the move reaches only by winning it:
   gp-62851's R19 line ended in the seki of B T19. The solver's other moves along the way
   are held to the same: one with a line that falls short or runs out of the problem (see
   below) is left out with all that follows it, and the found move stays with its other
   lines. A line a limit stopped is not held to its level, since it ends where the tree ran
   out rather than where the result is settled: it stays, and its note says what its end
   walks out to. With the limit at 20 decisions, gp-62299's line through Black's L13 after
   W M12 was cut right after L13, and walked out from there it came to ko; followed on, as
   in the full tree, it ends clean.

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

KataGo also gives up the moves that only work once a ko is won. A ko the favored side has
to win twice, to fill the liberties of the stones it decides, is one it never starts on.
In gp-60421 (13x13), after B M13, W L12, B N12, White takes the ko at N13 and Black, given
a threat, takes it back. Black's N11 then puts White in atari, White takes the ko again,
Black takes it back again and captures at N10. KataGo's Black never played N11, since it
expected White's second take to stand, so the walk settled with White alive and the
author's answer came out a failure, or a ko in the runs where the deviations happened on
N11.

So where KataGo's line settles against the favored side while the other side still has a
ko to take, KataGo is asked for the favored side's move again with those takes barred to
the other side in its whole search. For a ko on its own that is what the regime comes to:
each take is taken back and leaves the position as it was. When the favored side does
better for it, the walk goes on with that move, and from there without the bar: the other
side may take the ko again, and the walk gives it back as ever. The bar only finds the
favored side's move. Nothing is judged under it, since it would not hold where the other
side has a second ko to take in turn (a double ko, below) or could capture more than the
ko's stone at that point; and when the other side is to play first, its move is the one
KataGo's own line has, chosen with nothing barred. Pressing on only ever helps the favored
side: where the walk runs out of steps before it settles again, or settles no better for
that side than where KataGo's line first did, the result is the one from before it pressed.
With it gp-60421's M13 came out a ko, the stakes the whole corner, and L12 a failure.

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
like a clean kill of two stones. So the author's own first moves, each on its own with
KataGo playing on from it, and the author's lines as they stand are also played out with
the solver winning every ko (KataGo's top first moves stand in where the problem gives no
line). The one that gains the solver the most of the problem area is the solved state. When it gains the solver more than KataGo's own, the
stones of the group it decides (the bulk of the stones it swings, by color) join the ones
KataGo found, and it is the solved state from then on. The
other side's stones it swings are only the means: in gp-62121 the author's line captures
White's stones inside Black's eye space as Black lives, but another line may live without
capturing them. The author's lines are only hints at what may be at
stake; they never decide what a move comes to. In gp-36582 the author's line puts the whole
corner at stake, and the answer comes out as a ko. This also steadies the isolation, since
KataGo's search at the start does not always find the answer: some runs of gp-10330 had
only Black's stones inside White's group at stake, and not the group Black's answer kills.

A first move the author does not play adds nothing to the stakes. What a problem is about
is what its answer decides, and a move that gains the solver more than the answer does so
in a fight the problem never asks for. In gp-61269 (13x13) the author's L10 kills six
stones with no ko, as KataGo's own solved state has it, while M10 played first wins a ko
four times over and takes six stones more. KataGo's first moves used to be played out
whoever's they were, and once kos were pressed on (see [Kos](#kos)) those twelve stones
came out at stake: the author's answer read as a failure, and K10, which review had
approved, with it.

The state a start comes to is the solved state too where it puts no more stones at stake
but turns the ones there the solver's way. In gp-60164 KataGo's first move at the start did
not kill, so nothing swung, and White's six stones came out at stake through their area
(below). The author's lines kill them, 27 points better than KataGo's own solved state.
Left on KataGo's, komi had the solver far behind its target, and a first-line crawl came
out as good as the author's answer.

The console shows what each start came to ("with the solver winning every ko, B S19 W R18
B T18 comes to 89.90 of the problem area (2 kos won on the way)") beside KataGo's own
solved state, so a problem whose stakes look too few can be checked there.

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
| missing move, the best clean: on the raw board the author's own best answer leaves the solver under 0.7 of the stakes, so the result may be a ko or the stakes misjudged | -20 |
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
The raw-board weight is the first taken from the crawl's reviews: of the suggestions at a
clean level judged since the peep rule, review approved 9 of the 26 where the author's
answer came under 0.7 on the raw board, and 37 of the 43 above.

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

## Measuring against review

The crawl's reviews ([crawl.md](crawl.md)) are the other record of what is right: which
suggestions were approved, which rejected and why, and where review cut a line short by
hand. Two commands hold a change against them before the crawl runs on it. Neither writes
to the crawl's folder or asks the site for anything.

```
bash gotool.sh cmd=crawl-replay replay.dir=crawl-replay replay.only=60421,59938
bash gotool.sh cmd=crawl-replay replay.dir=crawl-replay replay.set=rejected replay.shard=0/2
bash gotool.sh cmd=crawl-replay replay.dir=crawl-replay replay.report=true
```

`cmd=crawl-replay` judges reviewed problems again, from the sgf the crawl kept, and writes
the findings and suggestions to `replay.dir`. For a suggestion review approved, the new
tree is held against the one that went to the site, edits by hand included, node for node:
how many of review's nodes it has, and how many more. For one review rejected, nothing
should be suggested any more. `replay.only` takes a list of problems, `replay.set` the
`approved`, `rejected` or `edited` reviews, and `replay.shard=0/2` every second problem, to
run two engines side by side. A problem `replay.dir` already has is not judged again, so a
run can be stopped and taken up, and `replay.report=true` prints the table from what is
there:

```
gp-60421: review rejected "this is ko in correct solution"; now clean; 0 nodes suggested (the crawl's draft had 2)
gp-59938: review approved (edited); now found; 1 of review's 1 nodes kept, 2 more (the crawl's draft had 74)
```

```
bash gotool.sh cmd=line-ends study.out=line_ends.jsonl study.edited=true
```

`cmd=line-ends` measures where lines end. For every suggestion review approved, each
position after a solver's move on its lines gets the opponent's decision as the generator
measures it, with a label from the tree that went to the site: `END` where the line ends
there (as the checker had it, or cut back to there by hand), `CONT` where it goes on. The
decisions go to `study.out` as json lines with every candidate's numbers, so a rule for
ending lines can be tried against them with no engine (`ChoiceRule` takes none).
`study.edited=true` takes only the suggestions edited by hand, whose ends were chosen;
`study.only`, `study.shard` and `study.max_nodes` (14 positions a problem, the ends first)
limit it. The labels are a guide, not a ruling: review cuts where the checker's tree
allows, so a position it kept inside a line could often have ended one.

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
- The favored side presses on with a ko only where the ko is on the board when KataGo's line
  settles. A ko the favored side would first have to set up, with a move that offers the
  other side the first take, is still only found by the deviations, in the first six moves.
- A problem whose own answer the reading takes for worse than another move is passed over,
  so an author's real mistake of that kind is not reported as a missing move. It still
  shows in the crawl's `problems.jsonl`, as skipped with the moves and levels in the reason.
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
