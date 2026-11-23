# The crawl

The crawl works slowly through goproblems.com's problems, newest first, and checks every
life and death problem marked standard for correct moves it lacks, at the start or deeper in
its tree (the [missing-variations finder](missing-variations.md)). For each problem it finds
some in, it writes a suggested edit. Nothing goes to the site until a person has approved it.

There are three steps, each a command:

```
bash gotool.sh cmd=crawl crawl.hours=8          # judge problems, write suggestions
bash gotool.sh cmd=crawl-redraft                # write the suggestions again, no engine
bash gotool.sh cmd=crawl-review                 # approve or reject them on a local page
bash gotool.sh cmd=crawl-submit                 # dry run: what would be sent
bash gotool.sh cmd=crawl-submit crawl.post=true # send the approved ones
bash gotool.sh cmd=crawl-sync                   # what became of the ones sent
```

All of it happens in one folder, `crawl.dir` (`crawl/`, not committed), which keeps the
crawl's state between runs.

## Crawling

`cmd=crawl` can be stopped at any time and started again. What it made of each problem is in
`problems.jsonl`, one line per problem, so it never judges a problem twice. Each run starts
from the newest problem on the site and goes down past what is done, so problems added since
the last run come first. A run ends with `crawl.max` (problems judged) or `crawl.hours`, or with
ctrl-c. A problem it was judging when stopped is judged again next time.

Each problem comes to one of:

| Status | Meaning |
|---|---|
| `found` | correct moves missing: `suggestions/gp-<id>.json` holds the suggestion |
| `unsuggested` | correct moves missing, but none could go into a suggestion (a line ending on the problem's own wrong line, with none through its refutation); the reason says why |
| `clean` | nothing missing (accepted moves that fall short are noted, not suggested) |
| `unfinished` | the problem's time ran out with nothing found (below): some positions were never judged, so it is not clean. Left alone by later runs unless asked for |
| `skipped` | no such problem, another genre, not standard and no longer new, deleted, in the sandbox (`crawl.include_sandbox=true` takes those), a board the finder cannot play, nothing at stake in the isolation, or a start the isolation reads differently from the author (the problem's own first move falls short of another by its reading) |
| `deferred` | the site is not ready with it: a new problem not marked standard yet, or one with a suggestion open. Read again at the start of every later run (below) |
| `error` | the finder failed: tried again, `crawl.attempts` (2) times in all |
| `stale` | its sgf changed on the site after its suggestion was made: judged again first |

A new problem is not standard until the site's editors mark it, which can take weeks, so a
skip there loses it. On 27 October the crawl skipped 34 problems between gp-62866 and
gp-62915 for good as not standard; four weeks later the site had marked 21 of them, and they
had never been judged. Such a problem is deferred instead, and every run starts by reading
the deferred problems from the site again, newest first, a read each and no engine:

```
crawling goproblems.com from problem 62969 down, in crawl: 3328 looked at before, 111 of them deferred (not marked standard yet, or a suggestion open) and read again first
deferred problems read again: 55 can be judged now, 56 still deferred
```

One that can be judged now is judged like a new problem when the run comes to it. One still
deferred stays as it is: nothing is written for it, and it gets no line of its own. A problem
still not standard `crawl.wait_days` (180) days after it was made is taken to stay so and is
skipped for good ("not marked standard in its first 180 days"); `crawl.only` still judges
one. A line from before the crawl waited (`skipped`, "not marked standard") is read as
deferred.

Above each problem it judges, the console says how many problems the run has looked at so
far, in how long, and what they came to, the finds first:

```
crawled so far this run: 37 in 2 h 14 min (1 found, 4 clean, 2 deferred, 30 skipped)
=== gp-62657 (8 kyu) ===
```

The finder takes about five minutes a problem, so about 290 a day. About half the site's
50,000 problems are life and death.

A problem gets an hour (`missing.max_minutes=60`; 0 for no limit) and no longer. Most take
minutes, but of the 1765 problems judged by 23 November, 63 took more than an hour, and those
took 196 of the 426 hours of judging: gp-62959 took 13 hours over the 37 positions of a ko and
had nothing missing. When a problem's time is up:

- the positions not judged yet are left, and one half judged is dropped;
- the lines being generated end where they are, each on its last solver move. They go into
  the suggestion as they are and the review page lists them as cut short ("out of time"), like
  a line any other limit stops. The turn being decided is finished first, and what the lines
  came to is still checked, for ten minutes more at most;
- a move found there with no line yet is still checked and goes in alone, listed as cut
  short too. One there is no time to check either is not suggested.

What was found by then stands, and the problem's line in `problems.jsonl` says what was left:
"the time limit of 60 min stopped it: 12 of 37 positions judged, 3 lines ended where they
were". With nothing found it is `unfinished`, not `clean`. 34 of those 63 problems had a find,
of the 157 with one, so an hour stops about one find in five short of its end; two hours
would stop one in sixteen. `crawl.recheck=unfinished missing.max_minutes=180` judges the
unfinished ones again with more time, and `crawl.only` takes any problem again the same way.

What a run prints is kept as well, in `logs/<start>.log`, each line with the time it was
printed (`crawl.log=false` for none). From a run with `missing.max_minutes=10`:

```
11-23 12:21:55  crawled so far this run: 1 in 48 s (1 clean)
11-23 12:21:55  === gp-62951 (29 kyu) ===
11-23 12:24:39    at the start: nothing missing (best clean; the problem accepts J11)
11-23 12:26:02    after B J11 W J10: nothing missing (best clean; the problem accepts J12)
11-23 12:31:56    after B J11 W K10: out of time before it was judged to the end, so nothing of it is kept
11-23 12:31:56  gp-62951: unfinished (the time limit of 10 min stopped it: 2 of 12 positions judged) in 600 s
```

The console alone is gone with its terminal: on 22 November a run of 26 hours was stopped five
hours into gp-62951, and what it had made of that problem could be read nowhere.

`MissingVariationFinder.JUDGING_VERSION` goes up with every change to how moves are judged.
Each judged problem keeps the version that judged it, and `crawl.recheck=found` (or
`found,clean`) judges again those an older version gave (`unfinished` ones are judged again
whatever the version, since they want more time). `crawl.only=62299` (or a list,
`62299,62311`) judges just those problems again, whatever they came to before, and ends the
run; its earlier suggestion and any review of it are replaced, as with any judging again. A
problem whose suggestion went to the site is still left alone.

Before a change to the judging goes to the crawl, `cmd=crawl-replay` judges the problems
review has been through again and holds the result against the reviews, in a folder of its
own (see [missing-variations.md](missing-variations.md#measuring-against-review)).

A judged problem's findings stay in `findings/` with its sgf in `sgf/`. When only the drafting
changes, `cmd=crawl-redraft` makes every suggestion again from them, with no engine, in
seconds. A problem whose suggestion went to the site is left alone, and a review of a draft
that changed is kept aside (`approvals/gp-<id>.old.json`).

A correct move the problem has on a wrong line goes in by carrying that line on: past the
author's refutation, to the solver's answer, marked right. The comment says the problem had
it as wrong.

The comment lists the lines added. The first is in full, and each one after it starts from
where it leaves an earlier one: "Lines added: B M13, W M11, B M10, W L9, B M9, W N11, B M8;
after W L9: B N11, W N10, B M12."

## Reviewing

`cmd=crawl-review` serves a page (`review.port`, 8770) with the suggestions, the surest first.
The page opens on the first one waiting for review without putting its name in the address,
and the page's title goes back there from any problem. For each one it shows:

- in the headline, what the checker makes of the problem's correct lines: live, kill, ko or
  seki, the best result at the start (or at the first position it judged, when the start was
  passed over). The crawl keeps it with the problem in `problems.jsonl`. A problem judged before
  it did shows the best result at its shallowest finding instead, dashed, and there live and
  kill are one result ("live or kill");
- the problem's tree as the suggestion leaves it, laid out like the redo review's (depth runs
  right, branches stack downward): green lines lead to a correct end and red ones only to
  wrong ends, blue is what the suggestion adds, and dashed blue is the problem's own move on a
  wrong line that the suggestion carries on to a correct end (a move the problem had as
  wrong). A halo marks the end of a correct line. Next to the tree is the board at the
  selected node, with the next moves circled in the same colors, and under it the node's
  comment. Click a node or a circle to go there; ←/→ walk the thick line, which follows what
  the suggestion adds unless ↑/↓ pick another branch. It starts at the first position the
  suggestion adds lines at, and the problem's wrong lines can be hidden. **Copy Path**, under
  the tree, copies the moves from the start to the selected node as the page writes them
  ("B M13, W M11, B M10");
- each position on the problem's own board: the missing moves (blue squares) and the moves
  the problem accepts there (green), and a button to select it in the tree. A line the
  finder's generator stopped at a limit (`redo.max_tree_decisions`, `redo.max_moves`, or the
  problem's time, `missing.max_minutes`) or a
  failed analysis is listed above the boards as cut short, with why: it went in as it is,
  ending on its last solver move, and can be taken on by hand (the note goes once it is).
  The list marks such a suggestion "cut short", and the crawl's status counts the lines. A
  problem the finder ran out of time on says under its headline what that left undone ("Not
  judged to the end: the time limit of 60 min stopped it: 2 of 5 positions judged"), and the
  list marks it "out of time";
- each line it adds, to step through move by move with the arrows under it. Stepping a line
  selects it, and then the arrow keys step it too (until the tree is clicked);
- the confidence and the reasons for and against it;
- the comment that goes with it, which can be edited;
- buttons under the tree to edit its lines by hand (below).

Approving or rejecting one (`a`, `r`; `j` and `k` move on) writes `approvals/gp-<id>.json`, with
the comment as it stands and a note kept only here. A rejected suggestion is never sent.

### Editing the tree

A suggestion waiting for review can be edited by hand under the tree:

- **Mark right** puts a right mark on the selected move, or takes it off. **Cut branch** cuts
  it and everything after it. Both work on the moves the suggestion adds, not the problem's
  own. A cut only stops a line sooner: when the move before the cut is one the suggestion adds
  and the cut leaves nothing after it, it ends the line now and is marked right.
- **Add moves** lets a click on an empty point of the board play a move at the position shown,
  for the side to play there. The move goes into the suggestion and is selected, so the next
  can follow it. Esc stops adding.

Edits not saved yet are light blue. A cut branch stays as a light blue ghost until it is saved,
and **Restore branch** brings it back, taking off the right mark the cut put before it. Moves
the edits turn back into the problem's own wrong line go light blue too. The selected node is
marked in amber, so it never looks like an edit.

**Save** writes `edits/gp-<id>.json`; **Discard** drops what is not saved, and **Undo all
edits** goes back to the lines as the checker made them. Saved edits are what the page shows
and what `cmd=crawl-submit` sends. The positions, lines and comment are worked out again from
the edited tree. A line added where the suggestion had none makes a position of its own.
Comment text typed before saving is kept.

A suggestion with a decision cannot be edited: take it back to pending first, so that a
decision is always on what it decided. With edits not saved, approving or rejecting waits
until they are saved or discarded, and leaving them asks first. A judging or redraft that
changes the draft sets its edits aside with its review
(`edits/gp-<id>.old.json`). Once edits leave nothing to add, the suggestion is not sent.

## Sending

`cmd=crawl-submit` sends each approved suggestion not sent yet, after checking it against the
site as it is now:

- the problem's sgf is still the one the suggestion was made from. If not, it is marked
  `stale`, its review is kept aside (`approvals/gp-<id>.stale.json`), and the next crawl
  judges it again;
- no suggestion is open on the problem, since approving either of two would undo the other.
  It waits until there is none;
- no suggestion made before, approved or rejected, has its lines already.

It is a dry run unless `crawl.post=true`; `crawl.only=gp-1,gp-2` limits it to those. What was
sent goes in `submitted/gp-<id>.json`, and the crawl never judges that problem again.
`cmd=crawl-sync` reads back each one's status (open, approved, rejected) and the reviewer's
comment. Run it after the site has been through a round: an approval here is not one there,
and the site's verdicts are what a change to the judging is measured against (see
[missing-variations.md](missing-variations.md#measuring-against-review)).

The site checks nothing a suggestion carries (see
[missing-variations.md](missing-variations.md#suggesting-a-finding-to-the-site)), so these
checks are the only ones.

## The folder

| Path | Contents |
|---|---|
| `problems.jsonl` | what each problem came to, a line each, a later line replacing an earlier; for a judged one, what its correct lines come to (live, kill, ko or seki) |
| `state.json` | counts and where the last run was, for a look |
| `lock` | held by a running crawl: one crawl per folder, since KataGo needs the memory |
| `logs/<start>.log` | what each run printed, each line with its time |
| `sgf/gp-<id>.sgf` | the problem as the site had it |
| `findings/gp-<id>.json` | the finder's findings at each position |
| `suggestions/gp-<id>.json` | the suggestion: both sgfs, the comment, the positions and lines |
| `approvals/gp-<id>.json` | the review's decision; `.old.json` and `.stale.json` are earlier ones |
| `submitted/gp-<id>.json` | the suggestion's id on the site and its status as last synced |
