package autoprob.collection;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.MoveAction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects capturing races that are won by first adding a stone to be given up: the stone does not
 * fill a liberty of the group to be caught, it makes the opponent spend moves on capturing it
 * before the solver's own group can be approached, and that is the liberty the race is won by.
 *
 * <p>There are two collections of these, told apart by what is given up, and a detector for each:
 * {@link #loneStone()} for a stone that stands alone (GoProblems collection 485, Stone for
 * Liberty; 43924 is one), and {@link #addedStone()} for a stone added to stones of its own colour
 * that are cut off already, so that all of them are given up together (collection 486, Another
 * Stone for Liberty; 4500 is one). Each says of the other's problems which collection they are.
 *
 * <p>The problem's own tree says which first moves are right; nothing is read out beyond counting
 * liberties. A right first move counts when
 * <ul>
 * <li>it captures nothing and gives no atari;
 * <li>it has to be approached before it can be taken. A stone the opponent takes at once is a
 * throw-in: it gains its liberty too, by the connection it forces afterwards, but that is a wider
 * group than this collection (GoProblems 59677 is one) and is recognised only to be left out;
 * <li>the opponent then needs at least two moves more than before to capture one of the solver's
 * short chains, so the stone gains a move net of the one it cost. The opponent's moves are counted
 * as if the solver never answered, but none that leaves shorter of liberties a chain of its own
 * which the solution goes on to attack beside that chain: going round the stone by lengthening
 * the chain being raced fills a liberty of that chain too, and gains nothing in the race
 * (GoProblems 47577 is one, where that way round looked a move quicker than taking the stone);
 * <li>that chain is two stones or more. Where a single stone was all the move gained time for, it
 * was the vital point of an eye space both sides wanted, and no race was won (GoProblems 47748 is
 * one);
 * <li>the stone is not on a liberty of that chain nor beside one: it stands on the far side of
 * the stones that have to approach, where next to the chain it would make an eye or a ko;
 * <li>the quickest capture of that chain goes through capturing the stone first, which leaves out
 * a wall that stays;
 * <li>the stone really is given up: it cannot get away, or the solution leaves it in atari or
 * lets it be taken, and does not play there again as a snapback would; and the chain it gained
 * time for is still there at the end of the solution.
 * </ul>
 * Every right first move has to be such a stone, and of the detector's own kind, so a problem that
 * can also be solved without one does not match. Only the first move is looked at: the same
 * sacrifice later in a line came with snapbacks and kos far more often than with a plain capturing
 * race.
 */
public final class SacrificeForLibertyDetector implements CollectionDetector {
    private static final String LONE_NAME = "stone-for-liberty";
    private static final String ADDED_NAME = "another-stone-for-liberty";
    private static final int LONE_COLLECTION = 485;
    private static final int ADDED_COLLECTION = 486;

    /** A chain with more liberties than this at the start is not in a capturing race. */
    private static final int MAX_LIBERTIES = 3;
    /** A chain of fewer stones than this is not a group in a capturing race. */
    private static final int MIN_CHAIN_STONES = 2;
    /** How many moves in a row the opponent is given to capture a chain before it counts as safe. */
    private static final int MAX_MOVES = 6;
    /** The stone must cost the opponent this many moves more: one to pay for itself, one gained. */
    private static final int MIN_GAIN = 2;
    /** More than this many beyond the least, and the stone has made an eye or a ko, not a liberty. */
    private static final int MAX_EXTRA_GAIN = 2;
    /** With fewer liberties than this once played, the stone is a throw-in: taken at once. */
    private static final int MIN_STONE_LIBERTIES = 2;

    /** Whether this is the detector for a stone added to stones already cut off. */
    private final boolean added;

    private SacrificeForLibertyDetector(boolean added) {
        this.added = added;
    }

    /** The detector for a single stone given up: Stone for Liberty. */
    public static SacrificeForLibertyDetector loneStone() {
        return new SacrificeForLibertyDetector(false);
    }

    /** The detector for a stone given up together with the stones it joins: Another Stone for Liberty. */
    public static SacrificeForLibertyDetector addedStone() {
        return new SacrificeForLibertyDetector(true);
    }

    @Override
    public String name() {
        return added ? ADDED_NAME : LONE_NAME;
    }

    @Override
    public int siteCollection() {
        return added ? ADDED_COLLECTION : LONE_COLLECTION;
    }

    @Override
    public MatchResult detect(Node startingPosition) {
        Node start = SolutionTree.firstChoice(startingPosition);
        List<Node> right = SolutionTree.rightMoves(start);
        if (right.isEmpty()) {
            return MatchResult.noMatch("the tree marks no first move right");
        }

        Stones position = Stones.of(start.board);
        List<String> found = new ArrayList<>();
        for (Node move : right) {
            Verdict verdict = judge(position, move);
            if (!verdict.sacrifice) {
                return MatchResult.noMatch(verdict.reason);
            }
            if (verdict.added != added) {
                return MatchResult.noMatch(verdict.label + (verdict.added
                        ? " is given up with the stones it joins: that is " + ADDED_NAME
                        : " is given up alone: that is " + LONE_NAME) + ", not this collection");
            }
            found.add(verdict.reason);
        }
        return MatchResult.match(String.join("; ", found));
    }

    private Verdict judge(Stones position, Node moveNode) {
        MoveAction action = moveNode.getMoveAction();
        int solver = action.stone;
        int move = position.point(action.loc.x, action.loc.y);
        String label = Intersection.color2name(solver) + " " + position.name(move);

        Stones played = position.play(move, solver);
        if (played == null) {
            return Verdict.no(label + " is not a legal move");
        }
        if (played.captured().length > 0) {
            return Verdict.no(label + " captures");
        }
        if (!answeredByCapture(played, move)) {
            return Verdict.no(label + " is an atari that taking the stone does not answer");
        }

        Line line = playRightLine(position, moveNode);
        Gain best = null;
        String shortfall = label + " is near no " + Intersection.color2name(solver) + " chain short of liberties";
        int most = Integer.MIN_VALUE;
        Stones.Chain given = played.chain(move);
        for (Stones.Chain chain : position.chains(solver)) {
            // a move on the chain's own liberty lengthens the chain: that is not a sacrifice
            if (chain.liberties.length > MAX_LIBERTIES || chain.hasLiberty(move)) continue;
            Gain gain = gain(position, played, chain, move, against(position, chain, line.attacked));
            // nor is a stone beside one of the chain's liberties, whatever it gains: that makes
            // an eye or a ko there. The stone given up stands on the far side of the stones that
            // have to approach
            if (gain.after - gain.before >= MIN_GAIN && sharesLiberty(given, chain)) {
                gain = new Gain(chain, gain.before, gain.after,
                        "gains time for a chain, but beside its own liberty: an eye or a ko, not a stone given up");
            }
            if (gain.failure == null) {
                if (best == null || chain.stones.length > best.chain.stones.length) best = gain;
            } else if (gain.after - gain.before > most) {
                most = gain.after - gain.before;
                shortfall = label + " " + gain.failure;
            }
        }
        if (best == null) {
            return Verdict.no(shortfall);
        }

        Stones end = line.end;
        if (line.playedAgain) {
            return Verdict.no(label + " is played again once it is taken: a snapback or a squeeze, not a liberty");
        }
        boolean taken = end.at(move) != solver;
        if (!taken && end.chain(move).liberties.length > 1 && !played.capturable(move)) {
            return Verdict.no(label + " is not given up: it can get away and the solution keeps it");
        }
        for (int stone : best.chain.stones) {
            if (end.at(stone) != solver) {
                return Verdict.no(label + " gains time for a chain the solution loses");
            }
        }
        // time gained for a single stone is no race won: where that was the most the move did (the
        // chain kept is the largest it helps), it was the vital point of an eye space, which the
        // opponent wanted as much
        if (best.chain.stones.length < MIN_CHAIN_STONES) {
            return Verdict.no(label + " gains time only for a single stone, which is no group in a capturing race");
        }
        // last, so that a throw-in which fails for another reason says that reason: what is left
        // here is the throw-in that does gain the liberty, the wider group this collection leaves out
        if (given.liberties.length < MIN_STONE_LIBERTIES) {
            return Verdict.no(label + " is a throw-in: it gains the liberty, but is taken at once, not approached first");
        }

        // what is given up: the stone alone, or along with it the stones of its own colour it joins
        int joined = given.stones.length - 1;
        String with = "";
        for (int stone : given.stones) {
            if (stone != move) {
                with = " with the " + (joined == 1 ? "stone" : joined + " stones") + " it joins at "
                        + position.name(stone);
                break;
            }
        }
        String opponent = Intersection.color2name(Stones.other(solver));
        return Verdict.yes(label, joined > 0, label + " is given up" + with + ": " + opponent
                + " then needs " + best.after + " moves, not " + best.before + ", to capture the "
                + best.chain.stones.length + (best.chain.stones.length == 1 ? " stone" : " stones") + " at "
                + position.name(best.chain.stones[0]));
    }

    private boolean sharesLiberty(Stones.Chain stone, Stones.Chain chain) {
        for (int liberty : stone.liberties) {
            if (chain.hasLiberty(liberty)) return true;
        }
        return false;
    }

    /**
     * A stone that gives atari is given up only when it is thrown in: the opponent takes it at
     * once, safely, and is out of atari by that. Otherwise the move wins by the atari itself, by a
     * capture or a ko, and the stone is not what is given up. (A throw-in is left out in the end
     * all the same, but as a throw-in, not as an atari.)
     */
    private boolean answeredByCapture(Stones played, int move) {
        int solver = played.at(move);
        int opponent = Stones.other(solver);
        List<Integer> inAtari = new ArrayList<>();
        for (int beside : played.neighbors(move)) {
            if (played.at(beside) == opponent && played.chain(beside).liberties.length == 1) {
                inAtari.add(beside);
            }
        }
        if (inAtari.isEmpty()) return true;

        Stones.Chain stone = played.chain(move);
        if (stone.liberties.length != 1) return false;
        int capture = stone.liberties[0];
        Stones taken = played.play(capture, opponent);
        if (taken == null || taken.at(move) == solver || taken.chain(capture).liberties.length < 2) return false;
        for (int point : inAtari) {
            if (taken.at(point) != opponent || taken.chain(point).liberties.length < 2) return false;
        }
        return true;
    }

    /**
     * Those of the opponent's chains the solution goes on to attack that stand against the chain
     * itself: the ones it is in a race with. A stone of each.
     */
    private int[] against(Stones position, Stones.Chain chain, Set<Integer> attacked) {
        List<Integer> racing = new ArrayList<>();
        for (int stone : attacked) {
            if (touches(position, position.chain(stone), chain)) racing.add(stone);
        }
        int[] stones = new int[racing.size()];
        for (int i = 0; i < stones.length; i++) stones[i] = racing.get(i);
        return stones;
    }

    private boolean touches(Stones position, Stones.Chain one, Stones.Chain other) {
        for (int stone : one.stones) {
            for (int beside : position.neighbors(stone)) {
                if (other.contains(beside)) return true;
            }
        }
        return false;
    }

    /**
     * What the move gains for the chain, with the reason when it is not a sacrifice for a liberty.
     * The opponent's moves are counted with the chain's owner never answering, but none that would
     * shorten a chain the owner is racing: see {@link Stones#movesToCapture(int, int, int, int[])}.
     */
    private Gain gain(Stones position, Stones played, Stones.Chain chain, int move, int[] raced) {
        int target = chain.stones[0];
        int before = position.movesToCapture(target, MAX_MOVES, -1, raced);
        if (before == Stones.UNKNOWN || before > MAX_MOVES) {
            return new Gain(chain, 0, 0, "is near a chain that is in no danger");
        }

        int limit = before + MIN_GAIN + MAX_EXTRA_GAIN;
        int after = played.movesToCapture(target, limit, -1, raced);
        if (after == Stones.UNKNOWN) {
            return new Gain(chain, 0, 0, "leads to a count too long to finish");
        }
        if (after - before < MIN_GAIN) {
            return new Gain(chain, before, after, after > before
                    ? "makes a chain take one move longer to capture, which only pays for the stone"
                    : "does not make any chain short of liberties take longer to capture");
        }
        if (after > limit) {
            return new Gain(chain, before, after, "puts a chain out of reach rather than gaining a liberty");
        }

        // the quickest capture has to take the stone: one that can stay is a wall, not a sacrifice
        int keeping = played.movesToCapture(target, after, move, raced);
        if (keeping == Stones.UNKNOWN || keeping <= after) {
            return new Gain(chain, before, after, "gains time for a chain, but need not be captured for it");
        }

        // and take it first: a stone that only falls together with the chain is part of its eye
        int stone = played.movesToCapture(move, after - 1, -1, raced);
        if (stone == Stones.UNKNOWN || stone >= after) {
            return new Gain(chain, before, after, "gains time for a chain, but only falls together with it");
        }
        return new Gain(chain, before, after, null);
    }

    /**
     * The first line under the move that reaches a node marked right: the position at its end,
     * whether the solver plays on the move's own point a second time on the way, and which of the
     * opponent's chains the solver's later moves are played against (a stone of each, as the
     * chains stood before the move).
     */
    private Line playRightLine(Stones position, Node moveNode) {
        MoveAction first = moveNode.getMoveAction();
        int opponent = Stones.other(first.stone);
        boolean playedAgain = false;
        Set<Integer> attacked = new LinkedHashSet<>();
        Stones current = position;
        Node node = moveNode;
        while (node != null) {
            MoveAction action = node.getMoveAction();
            boolean later = node != moveNode && action != null && action.stone == first.stone;
            if (later && current.onBoard(action.loc.x, action.loc.y)) {
                for (int beside : current.neighbors(current.point(action.loc.x, action.loc.y))) {
                    if (current.at(beside) != opponent) continue;
                    for (int stone : current.chain(beside).stones) {
                        if (position.at(stone) == opponent) attacked.add(position.chain(stone).stones[0]);
                    }
                }
            }
            Stones next = play(current, node);
            if (next == null) break;
            current = next;
            if (later && action.loc.equals(first.loc)) {
                playedAgain = true;
            }
            Node following = null;
            for (Node child : node.babies) {
                if (!child.notThis && child.searchForTheTruth()) {
                    following = child;
                    break;
                }
            }
            node = following;
        }
        return new Line(current, playedAgain, attacked);
    }

    /** The position after the node's move; the same position when it has none, null when illegal. */
    private Stones play(Stones position, Node node) {
        MoveAction action = node.getMoveAction();
        if (action == null || !position.onBoard(action.loc.x, action.loc.y)) return position;
        return position.play(position.point(action.loc.x, action.loc.y), action.stone);
    }

    private record Gain(Stones.Chain chain, int before, int after, String failure) { }

    private record Line(Stones end, boolean playedAgain, Set<Integer> attacked) { }

    /** For a stone given up for a liberty: its label, and whether stones it joins are given up with it. */
    private record Verdict(boolean sacrifice, boolean added, String label, String reason) {
        static Verdict yes(String label, boolean added, String reason) {
            return new Verdict(true, added, label, reason);
        }

        static Verdict no(String reason) {
            return new Verdict(false, false, null, reason);
        }
    }
}
