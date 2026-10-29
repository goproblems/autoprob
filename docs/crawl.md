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
`problems.jsonl`, one line per problem, so it never looks at a problem twice. Each run starts
from the newest problem on the site and goes down past what is done, so problems added since
the last run come first. A run ends with `crawl.max` (problems judged) or `crawl.hours`, or with
ctrl-c. A problem it was judging when stopped is judged again next time.

Each problem comes to one of:

| Status | Meaning |
|---|---|
| `found` | correct moves missing: `suggestions/gp-<id>.json` holds the suggestion |
| `unsuggested` | correct moves missing, but none could go into a suggestion (a line ending on the problem's own wrong line, with none through its refutation); the reason says why |
| `clean` | nothing missing (accepted moves that fall short are noted, not suggested) |
| `skipped` | no such problem, another genre, not standard, deleted, in the sandbox (`crawl.include_sandbox=true` takes those), a board the finder cannot play, or nothing at stake in the isolation |
| `deferred` | a suggestion is open on it on the site: looked at again on later runs |
| `error` | the finder failed: tried again, `crawl.attempts` (2) times in all |
| `stale` | its sgf changed on the site after its suggestion was made: judged again first |

Above each problem it judges, the console says how many problems the run has looked at so
far, in how long, and what they came to, the finds first:

```
crawled so far this run: 37 in 2 h 14 min (1 found, 4 clean, 2 deferred, 30 skipped)
=== gp-62657 (8 kyu) ===
```

The finder takes about five minutes a problem, so about 290 a day. About half the site's
50,000 problems are life and death.

`MissingVariationFinder.JUDGING_VERSION` goes up with every change to how moves are judged.
Each judged problem keeps the version that judged it, and `crawl.recheck=found` (or
`found,clean`) judges again those an older version gave.

A judged problem's findings stay in `findings/` with its sgf in `sgf/`. When only the drafting
changes, `cmd=crawl-redraft` makes every suggestion again from them, with no engine, in
seconds. A problem whose suggestion went to the site is left alone, and a review of a draft
that changed is kept aside (`approvals/gp-<id>.old.json`).

A correct move the problem has on a wrong line goes in by carrying that line on: past the
author's refutation, to the solver's answer, marked right. The comment says the problem had
it as wrong.

## Reviewing

`cmd=crawl-review` serves a page (`review.port`, 8770) with the suggestions, the surest first.
For each one it shows:

- the problem's tree as the suggestion leaves it, laid out like the redo review's (depth runs
  right, branches stack downward): green lines lead to a correct end and red ones only to
  wrong ends, blue is what the suggestion adds, and dashed blue is the problem's own move on a
  wrong line that the suggestion carries on to a correct end (a move the problem had as
  wrong). A halo marks the end of a correct line. Next to the tree is the board at the
  selected node, with the next moves circled in the same colors, and under it the node's
  comment. Click a node or a circle to go there; ←/→ walk the thick line, which follows what
  the suggestion adds unless ↑/↓ pick another branch. It starts at the first position the
  suggestion adds lines at, and the problem's wrong lines can be hidden;
- each position on the problem's own board: the missing moves (blue squares) and the moves
  the problem accepts there (green), and a button to select it in the tree;
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
  own.
- **Add moves** lets a click on an empty point of the board play a move at the position shown,
  for the side to play there. The move goes into the suggestion and is selected, so the next
  can follow it. Esc stops adding.

Edits not saved yet are light blue. A cut branch stays as a light blue ghost until it is saved,
and **Restore branch** brings it back. Moves the edits turn back into the problem's own wrong
line go light blue too. The selected node is marked in amber, so it never looks like an edit.

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
comment.

The site checks nothing a suggestion carries (see
[missing-variations.md](missing-variations.md#suggesting-a-finding-to-the-site)), so these
checks are the only ones.

## The folder

| Path | Contents |
|---|---|
| `problems.jsonl` | what each problem came to, a line each, a later line replacing an earlier |
| `state.json` | counts and where the last run was, for a look |
| `lock` | held by a running crawl: one crawl per folder, since KataGo needs the memory |
| `sgf/gp-<id>.sgf` | the problem as the site had it |
| `findings/gp-<id>.json` | the finder's findings at each position |
| `suggestions/gp-<id>.json` | the suggestion: both sgfs, the comment, the positions and lines |
| `approvals/gp-<id>.json` | the review's decision; `.old.json` and `.stale.json` are earlier ones |
| `submitted/gp-<id>.json` | the suggestion's id on the site and its status as last synced |
