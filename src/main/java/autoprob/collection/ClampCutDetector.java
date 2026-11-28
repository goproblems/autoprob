package autoprob.collection;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.MoveAction;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects cuts made with a clamp (GoProblems collection 445, Clamp Cut: "cutting by making two
 * points with a clamp"). A lone stone of the opponent's has one of the solver's stones on one
 * side; the solver plays on the opposite side, so that the stone is left with just the two points
 * beside it. The opponent can take only one of those, and the solver takes the other, which cuts
 * the stone from whatever lay that way.
 *
 * <p>The problem's own tree says which first moves are right, and nothing is read out. A right
 * first move counts when
 * <ul>
 * <li>it captures nothing and gives no atari;
 * <li>it touches a stone of the opponent's that stands alone, with a stone of the solver's
 * directly behind it and the two points beside it open (GoProblems 45743 is the plain case). One
 * looser form is taken too: nothing behind the stone, but the solver's stones on both diagonals
 * there, so that each stands beside one of the two points (55821);
 * <li>it is played alone, not joined to the solver's stones, off the first line, and cannot be
 * taken at once. Often it touches a second stone of the opponent's as well, wedged between two or
 * peeping where two meet at a corner, and can then be put in atari straight away;
 * <li>one of the two points at least is next to another stone of the opponent's, so the clamped
 * stone is a link and not a stray stone;
 * <li>the solution shows the two points: the opponent takes one and the solver the other. Before
 * that or after it the opponent may put the clamp stone in atari and the solver extend it, and a
 * line that ends on such an extension, the two points still open, counts as well. A line that
 * turns to anything else once the points are settled does not: there the clamp only starts
 * something longer, most often a kill.
 * </ul>
 * Every right first move has to be such a clamp. Only the first move is looked at. One of the two
 * points may be on the first line (46352): the solver then connects along the edge rather than
 * cuts, and the reason says "the edge point".
 */
public final class ClampCutDetector implements CollectionDetector {
    @Override
    public String name() {
        return "clamp-cut";
    }

    @Override
    public int siteCollection() {
        return 445;
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
            if (!verdict.clampCut) {
                return MatchResult.noMatch(verdict.reason);
            }
            found.add(verdict.reason);
        }
        return MatchResult.match(String.join("; ", found));
    }

    private Verdict judge(Stones position, Node moveNode) {
        MoveAction action = moveNode.getMoveAction();
        int solver = action.stone;
        int opponent = Stones.other(solver);
        int move = position.point(action.loc.x, action.loc.y);
        String label = Intersection.color2name(solver) + " " + position.name(move);
        String theirs = Intersection.color2name(opponent);

        Stones played = position.play(move, solver);
        if (played == null) {
            return Verdict.no(label + " is not a legal move");
        }
        if (played.captured().length > 0) {
            return Verdict.no(label + " captures");
        }

        // the stones it clamps, and of those it does not the one that came closest
        List<Clamp> clamps = new ArrayList<>();
        Clamp nearest = null;
        for (int stone : position.neighbors(move)) {
            if (position.at(stone) != opponent) continue;
            Clamp clamp = clamp(position, move, stone, solver);
            if (clamp.failure == null) {
                clamps.add(clamp);
            } else if (nearest == null || clamp.rank > nearest.rank) {
                nearest = clamp;
            }
        }
        if (clamps.isEmpty()) {
            return Verdict.no(label + " " + (nearest == null ? "touches no " + theirs + " stone" : nearest.failure));
        }

        Stones.Chain own = played.chain(move);
        if (own.stones.length > 1) {
            return Verdict.no(label + " clamps a stone, but joins stones of its own: a clamp is played alone");
        }
        if (onEdge(position, move)) {
            return Verdict.no(label + " clamps a stone, but from the first line: a placement under it, not a clamp");
        }
        if (own.liberties.length < 2) {
            return Verdict.no(label + " clamps a stone, but can be taken at once");
        }
        for (int beside : played.neighbors(move)) {
            if (played.at(beside) == opponent && played.chain(beside).liberties.length < 2) {
                return Verdict.no(label + " is an atari, not a clamp");
            }
        }

        List<MoveAction> rest = movesAfter(position, SolutionTree.rightLine(moveNode));
        String shortfall = null;
        for (Clamp clamp : clamps) {
            String what = label + " " + clamp.describe(position, theirs, Intersection.color2name(solver));
            if (!leadsOn(position, clamp, opponent)) {
                if (shortfall == null) {
                    shortfall = what + ", but neither point beside it is next to another " + theirs
                            + " stone: there is nothing to cut it from";
                }
                continue;
            }
            Shown shown = follow(played, rest, clamp, move, solver);
            if (!shown.points) {
                shortfall = what + ", but " + shown.text;
            } else if (shown.more > 0) {
                shortfall = what + ": " + shown.text + ", but the line goes on " + shown.more
                        + " moves after that: the clamp only starts something longer";
            } else {
                return Verdict.yes(what + ": " + shown.text);
            }
        }
        return Verdict.no(shortfall);
    }

    /** The stone as a clamped one, or why it is not: seen from the move, what is behind and beside it. */
    private Clamp clamp(Stones position, int move, int stone, int solver) {
        if (position.chain(stone).stones.length > 1) {
            return Clamp.no(1, "is no clamp: the stone it touches at " + position.name(stone) + " is not alone");
        }
        int dx = position.x(stone) - position.x(move);
        int dy = position.y(stone) - position.y(move);
        int behind = offset(position, stone, dx, dy);
        int first = offset(position, stone, dy, dx);
        int second = offset(position, stone, -dy, -dx);
        if (behind < 0 || first < 0 || second < 0) {
            return Clamp.no(1, "is no clamp: the stone it touches at " + position.name(stone) + " is on the edge");
        }

        int[] against;
        if (position.at(behind) == solver) {
            against = new int[] {behind};
        } else {
            // nothing behind it, but a stone of the solver's on each diagonal there
            int high = offset(position, behind, dy, dx);
            int low = offset(position, behind, -dy, -dx);
            boolean mouth = position.at(behind) == Stones.EMPTY && high >= 0 && low >= 0
                    && position.at(high) == solver && position.at(low) == solver;
            if (!mouth) {
                return Clamp.no(2, "is no clamp: nothing of its own colour stands behind the stone at "
                        + position.name(stone));
            }
            against = new int[] {high, low};
        }
        if (position.at(first) != Stones.EMPTY || position.at(second) != Stones.EMPTY) {
            return Clamp.no(3, "clamps the stone at " + position.name(stone)
                    + ", but the two points beside that are not both open");
        }
        return new Clamp(stone, against, first, second, 0, null);
    }

    /** The point that many steps from the given one, or -1 off the board. */
    private int offset(Stones position, int point, int dx, int dy) {
        int x = position.x(point) + dx;
        int y = position.y(point) + dy;
        return position.onBoard(x, y) ? position.point(x, y) : -1;
    }

    /** Whether one of the two points is next to a stone of the opponent's other than the clamped one. */
    private boolean leadsOn(Stones position, Clamp clamp, int opponent) {
        for (int point : new int[] {clamp.first, clamp.second}) {
            for (int beside : position.neighbors(point)) {
                if (beside != clamp.stone && position.at(beside) == opponent) return true;
            }
        }
        return false;
    }

    /** The moves of the line after its first, as far as they are moves on the board. */
    private List<MoveAction> movesAfter(Stones position, List<Node> line) {
        List<MoveAction> moves = new ArrayList<>();
        for (Node node : line.subList(1, line.size())) {
            MoveAction action = node.getMoveAction();
            if (action == null) continue;
            if (!position.onBoard(action.loc.x, action.loc.y)) break;
            moves.add(action);
        }
        return moves;
    }

    /**
     * What the solution makes of the two points: the opponent takes one and the solver the other.
     * Before that or after it the opponent may put the clamp stone in atari, as often as the solver
     * answers by extending it. A line that ends on such an extension, with both points still open,
     * shows them too; one that turns to anything else once they are settled goes on, by so many moves.
     */
    private Shown follow(Stones played, List<MoveAction> rest, Clamp clamp, int move, int solver) {
        int opponent = Stones.other(solver);
        String ours = Intersection.color2name(solver);
        String theirs = Intersection.color2name(opponent);
        Stones current = played;
        StringBuilder told = new StringBuilder();
        boolean settled = false;
        int exchanges = 0;
        int at = 0;
        while (at < rest.size()) {
            MoveAction answer = rest.get(at);
            int point = current.point(answer.loc.x, answer.loc.y);
            if (answer.stone != opponent || current.at(point) != Stones.EMPTY) {
                return settled ? Shown.yes(told.toString(), rest.size() - at)
                        : Shown.no("the line does not go on with a " + theirs + " move");
            }
            MoveAction reply = at + 1 < rest.size() ? rest.get(at + 1) : null;
            int next = reply == null ? -1 : current.point(reply.loc.x, reply.loc.y);

            if (!settled && (point == clamp.first || point == clamp.second)) {
                int other = point == clamp.first ? clamp.second : clamp.first;
                if (reply == null || reply.stone != solver || next != other) {
                    return Shown.no("when " + theirs + " takes " + current.name(point) + " the solution does not take "
                            + current.name(other));
                }
                Stones taken = current.play(point, opponent);
                Stones both = taken == null ? null : taken.play(other, solver);
                if (both == null) {
                    return Shown.no("the two points cannot both be played");
                }
                told.append(theirs).append(" takes ").append(current.name(point)).append(" and ").append(ours)
                        .append(" ").append(current.name(other));
                current = both;
                settled = true;
                at += 2;
                continue;
            }

            // not one of the two points: then an atari on the clamp stone, which the solver extends
            Stones pressed = current.chain(move).hasLiberty(point) ? current.play(point, opponent) : null;
            boolean atari = pressed != null && pressed.at(move) == solver && pressed.chain(move).liberties.length == 1;
            Stones extended = !atari || reply == null || reply.stone != solver || pressed.at(next) != Stones.EMPTY
                    ? null : pressed.play(next, solver);
            boolean saved = extended != null && extended.at(move) == solver && extended.chain(move).contains(next)
                    && extended.chain(move).liberties.length >= 2;
            if (!saved) {
                if (settled) {
                    return Shown.yes(told.toString(), rest.size() - at);
                }
                return Shown.no(atari
                        ? "when " + theirs + " puts the clamp stone in atari at " + current.name(point)
                                + " the solution does not extend it"
                        : theirs + " answers at " + current.name(point)
                                + ", which neither takes one of the two points nor puts the clamp stone in atari");
            }
            told.append(settled ? ", then " : "").append(theirs).append(" ataris at ").append(current.name(point))
                    .append(" and ").append(ours).append(" extends to ").append(current.name(next))
                    .append(settled ? "" : ", ");
            current = extended;
            exchanges++;
            at += 2;
        }

        if (settled) {
            return Shown.yes(told.toString(), 0);
        }
        if (exchanges == 0) {
            return Shown.no("the tree ends at the clamp");
        }
        if (current.at(clamp.stone) != opponent || current.at(clamp.first) == opponent
                || current.at(clamp.second) == opponent) {
            return Shown.no("the two points are gone by the end of the line");
        }
        return Shown.yes(told + "and the line ends with " + current.name(clamp.first) + " and "
                + current.name(clamp.second) + " not both to be had by " + theirs, 0);
    }

    /**
     * A clamped stone: what it is clamped against (the stone behind it, or the two on the
     * diagonals there) and the two points beside it. Or, with a failure, how near a stone came.
     */
    private record Clamp(int stone, int[] against, int first, int second, int rank, String failure) {
        static Clamp no(int rank, String failure) {
            return new Clamp(-1, null, -1, -1, rank, failure);
        }

        String describe(Stones position, String theirs, String ours) {
            String held = against.length == 1
                    ? position.name(against[0])
                    : position.name(against[0]) + " and " + position.name(against[1]);
            // the point on the edge, where there is one, named last and as that
            boolean firstOnEdge = onEdge(position, first);
            int inner = firstOnEdge ? second : first;
            int outer = firstOnEdge ? first : second;
            return "clamps " + theirs + " " + position.name(stone) + " against " + ours + " " + held
                    + ", leaving it " + position.name(inner) + " and "
                    + (onEdge(position, outer) ? "the edge point " : "") + position.name(outer);
        }
    }

    private static boolean onEdge(Stones position, int point) {
        int x = position.x(point);
        int y = position.y(point);
        return x == 0 || y == 0 || x == position.width - 1 || y == position.height - 1;
    }

    /**
     * Whether the line shows the two points, what it does with them (or why it does not), and how
     * many moves it goes on once they are settled.
     */
    private record Shown(boolean points, String text, int more) {
        static Shown yes(String text, int more) {
            return new Shown(true, text, more);
        }

        static Shown no(String text) {
            return new Shown(false, text, 0);
        }
    }

    private record Verdict(boolean clampCut, String reason) {
        static Verdict yes(String reason) {
            return new Verdict(true, reason);
        }

        static Verdict no(String reason) {
            return new Verdict(false, reason);
        }
    }
}
