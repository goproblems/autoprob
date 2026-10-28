package autoprob.validate;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.DoublePredicate;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;

// what a position comes to for the solver, read from katago's ownership: the way goproblems
// grades a standard problem, by the life status of the stones at stake, never by points
// (article/bestpractices). every stone at stake has to end the way the solver wants, its own
// alive and the opponent's dead, which is the same thing as owned by the solver. losing a
// stone or two is not wrong, so a few may be missed. and the opponent may not come alive
// enclosed inside the problem area: such a pocket is a seki, or the opponent living inside.
// in a killing problem the opponent's stones at stake alive are a failure, unless they live
// only in seki with the solver's stones inside their area.
public class StatusJudge {
    public enum Status { FAIL, SEKI, SUCCESS }

    // a point belongs to a side when katago's ownership for it is past this
    static final double OWNED = 0.5;

    private final ProblemIsolator.IsolatedProblem iso;
    private final int allowedMisses;
    private final int sign; // ownership is black's: this turns it into the solver's
    private final boolean kills;
    // the points the solver owned in the solved position (the isolator's, after the best first
    // move). an opponent stone alive on one has come alive inside the solver's area; one alive
    // anywhere else is a group of the opponent's own, which lives whatever the problem comes to,
    // like a corner that lives beside the stones being killed
    private final boolean[][] solverArea = new boolean[19][19];
    // the points the opponent owned with the solver passing: its area, where the solver's
    // stones can live only in seki. null when there is no such analysis (an isolation rebuilt
    // from a record), and then there is no seki of that kind
    private final boolean[][] opponentArea;

    public StatusJudge(ProblemIsolator.IsolatedProblem iso, int statusStones) {
        this.iso = iso;
        int n = iso.stakes.size();
        // a stone or two, but never half the stones at stake
        this.allowedMisses = n == 0 ? 0 : Math.min(statusStones, (n - 1) / 2);
        this.sign = iso.solverColor == Intersection.BLACK ? 1 : -1;
        this.kills = kills(iso);
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                solverArea[x][y] = iso.solvedKar == null || iso.solvedKar.ownership == null
                        || sign * iso.solvedKar.ownership.get(x + y * 19) >= OWNED;
            }
        boolean known = iso.passKar != null && !iso.passKar.isError() && iso.passKar.ownership != null;
        this.opponentArea = known ? new boolean[19][19] : null;
        if (known) {
            for (int x = 0; x < 19; x++)
                for (int y = 0; y < 19; y++) opponentArea[x][y] = sign * iso.passKar.ownership.get(x + y * 19) <= -OWNED;
        }
    }

    // whether the problem is to kill: most of the stones at stake are the opponent's. a
    // capturing race goes by whichever side has more at stake
    public static boolean kills(ProblemIsolator.IsolatedProblem iso) {
        int own = 0, theirs = 0;
        for (Point s : iso.stakes) {
            int stone = iso.problem.board.board[s.x][s.y].stone;
            if (stone == iso.solverColor) own++;
            else if (stone != Intersection.EMPTY) theirs++;
        }
        return theirs > own;
    }

    public Status status(Node node, KataAnalysisResult kar) {
        if (kar == null || kar.isError() || kar.ownership == null) return Status.FAIL;
        if (missed(kar).size() > allowedMisses) return livesInSeki(node, kar) ? Status.SEKI : Status.FAIL;
        return pocket(node, kar).isEmpty() ? Status.SUCCESS : Status.SEKI;
    }

    // with more stones at stake missed than may be, in a killing problem: whether the
    // opponent's ones live only in seki. the solver's own may not be lost past the stone or two.
    // in a living problem the solver's group living in seki is a pocket
    private boolean livesInSeki(Node node, KataAnalysisResult kar) {
        if (!kills) return false;
        int own = 0;
        for (Point s : iso.stakes) {
            if (iso.problem.board.board[s.x][s.y].stone == iso.solverColor && sign * kar.ownership.get(s.x + s.y * 19) <= OWNED) own++;
        }
        return own <= allowedMisses && !seki(node, kar).isEmpty();
    }

    // the points of every enclosed pocket the opponent has come alive in: points not owned by
    // the solver, connected to one the opponent owns, that reach no fill stone, with an
    // opponent stone alive where the solver's area was. the opponent's own surroundings reach
    // the fill through the open area around the problem, so they never count
    public List<String> pocket(Node node, KataAnalysisResult kar) {
        int opponent = iso.solverColor == Intersection.BLACK ? Intersection.WHITE : Intersection.BLACK;
        boolean[][] seen = new boolean[19][19];
        List<String> out = new ArrayList<>();
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (seen[x][y] || iso.isFilled(x, y)) continue;
                if (sign * kar.ownership.get(x + y * 19) >= -OWNED) continue; // not the opponent's
                List<Point> component = new ArrayList<>();
                if (reachesFill(x, y, kar, seen, component, o -> o < OWNED)) continue;
                boolean cameAlive = false;
                for (Point p : component) {
                    if (node.board.board[p.x][p.y].stone == opponent && solverArea[p.x][p.y]
                            && sign * kar.ownership.get(p.x + p.y * 19) < -OWNED) cameAlive = true;
                }
                if (cameAlive) for (Point p : component) out.add(Intersection.toGTPloc(p.x, p.y, 19));
            }
        return out;
    }

    // the points of every seki the solver keeps inside the opponent's area, the other way
    // round from a pocket: the solver's stones alive where the opponent's area was, enclosed
    // with points neither side owns, the liberties they share with the opponent's stones,
    // reaching no fill stone. the opponent's stones around live then only because these do
    // (gp-62860, where black's stones inside white's eye space leave white alive in seki).
    // stones the solver plays inside that the opponent kills are the opponent's, and the
    // solver's wall around the opponent's area was the solver's when it passed, so neither
    // is a seki
    public List<String> seki(Node node, KataAnalysisResult kar) {
        List<String> out = new ArrayList<>();
        if (opponentArea == null) return out;
        boolean[][] seen = new boolean[19][19];
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (seen[x][y] || node.board.board[x][y].stone != iso.solverColor) continue;
                if (sign * kar.ownership.get(x + y * 19) < OWNED) continue; // not alive
                List<Point> component = new ArrayList<>();
                if (reachesFill(x, y, kar, seen, component, o -> o > -OWNED)) continue;
                boolean inside = true, shared = false;
                for (Point p : component) {
                    int stone = node.board.board[p.x][p.y].stone;
                    if (stone == iso.solverColor && !opponentArea[p.x][p.y]) inside = false;
                    if (stone == Intersection.EMPTY && Math.abs(kar.ownership.get(p.x + p.y * 19)) < OWNED) shared = true;
                }
                if (inside && shared) for (Point p : component) out.add(Intersection.toGTPloc(p.x, p.y, 19));
            }
        return out;
    }

    // the points connected to the start through points whose ownership for the solver passes
    // through, into component; whether they reach a fill stone
    private boolean reachesFill(int x0, int y0, KataAnalysisResult kar, boolean[][] seen, List<Point> component,
                                DoublePredicate through) {
        Deque<Point> todo = new ArrayDeque<>();
        todo.add(new Point(x0, y0));
        seen[x0][y0] = true;
        component.add(new Point(x0, y0));
        boolean reaches = false;
        while (!todo.isEmpty()) {
            Point p = todo.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = p.x + d[0], y = p.y + d[1];
                if (x < 0 || y < 0 || x >= 19 || y >= 19) continue;
                if (iso.isFilled(x, y)) {
                    reaches = true;
                    continue;
                }
                if (seen[x][y] || !through.test(sign * kar.ownership.get(x + y * 19))) continue;
                seen[x][y] = true;
                component.add(new Point(x, y));
                todo.add(new Point(x, y));
            }
        }
        return reaches;
    }

    // how clearly the stones at stake end the solver's way: its ownership of the deciding one,
    // the weakest once the stone or two that may be lost are set aside. when the opponent's
    // live in seki, how clearly the solver's stones in the seki live. past 0.5 is the
    // solver's, 1 is certain
    public double margin(Node node, KataAnalysisResult kar) {
        if (kar == null || kar.isError() || kar.ownership == null || iso.stakes.isEmpty()) return 0;
        if (missed(kar).size() > allowedMisses && livesInSeki(node, kar)) {
            double weakest = 1;
            for (String s : seki(node, kar)) {
                Point p = Intersection.gtp2point(s);
                if (node.board.board[p.x][p.y].stone == iso.solverColor) weakest = Math.min(weakest, sign * kar.ownership.get(p.x + p.y * 19));
            }
            return weakest;
        }
        double[] owned = iso.stakes.stream().mapToDouble(s -> sign * kar.ownership.get(s.x + s.y * 19)).sorted().toArray();
        return owned[Math.min(allowedMisses, owned.length - 1)];
    }

    // the stones at stake the position leaves the solver short of, for reports
    public List<String> missed(KataAnalysisResult kar) {
        return iso.stakes.stream()
                .filter(s -> sign * kar.ownership.get(s.x + s.y * 19) <= OWNED)
                .map(s -> Intersection.toGTPloc(s.x, s.y, 19))
                .toList();
    }
}
