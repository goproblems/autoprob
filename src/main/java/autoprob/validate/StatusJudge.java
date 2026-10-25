package autoprob.validate;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;

// what a position comes to for the solver, read from katago's ownership: the way goproblems
// grades a standard problem, by the life status of the stones at stake, never by points
// (article/bestpractices). every stone at stake has to end the way the solver wants, its own
// alive and the opponent's dead, which is the same thing as owned by the solver. losing a
// stone or two is not wrong, so a few may be missed. and the opponent may not come alive
// enclosed inside the problem area: such a pocket is a seki, or the opponent living inside.
public class StatusJudge {
    public enum Status { FAIL, SEKI, SUCCESS }

    // a point belongs to a side when katago's ownership for it is past this
    static final double OWNED = 0.5;

    private final ProblemIsolator.IsolatedProblem iso;
    private final int allowedMisses;
    private final int sign; // ownership is black's: this turns it into the solver's
    // the points the solver owned in the solved position (the isolator's, after the best first
    // move). an opponent stone alive on one has come alive inside the solver's area; one alive
    // anywhere else is a group of the opponent's own, which lives whatever the problem comes to,
    // like a corner that lives beside the stones being killed
    private final boolean[][] solverArea = new boolean[19][19];

    public StatusJudge(ProblemIsolator.IsolatedProblem iso, int statusStones) {
        this.iso = iso;
        int n = iso.stakes.size();
        // a stone or two, but never half the stones at stake
        this.allowedMisses = n == 0 ? 0 : Math.min(statusStones, (n - 1) / 2);
        this.sign = iso.solverColor == Intersection.BLACK ? 1 : -1;
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                solverArea[x][y] = iso.solvedKar == null || iso.solvedKar.ownership == null
                        || sign * iso.solvedKar.ownership.get(x + y * 19) >= OWNED;
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
        if (missed(kar).size() > allowedMisses) return Status.FAIL;
        return pocket(node, kar).isEmpty() ? Status.SUCCESS : Status.SEKI;
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
                if (reachesFill(x, y, kar, seen, component)) continue;
                boolean cameAlive = false;
                for (Point p : component) {
                    if (node.board.board[p.x][p.y].stone == opponent && solverArea[p.x][p.y]
                            && sign * kar.ownership.get(p.x + p.y * 19) < -OWNED) cameAlive = true;
                }
                if (cameAlive) for (Point p : component) out.add(Intersection.toGTPloc(p.x, p.y, 19));
            }
        return out;
    }

    private boolean reachesFill(int x0, int y0, KataAnalysisResult kar, boolean[][] seen, List<Point> component) {
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
                if (seen[x][y] || sign * kar.ownership.get(x + y * 19) >= OWNED) continue;
                seen[x][y] = true;
                component.add(new Point(x, y));
                todo.add(new Point(x, y));
            }
        }
        return reaches;
    }

    // the stones at stake the position leaves the solver short of, for reports
    public List<String> missed(KataAnalysisResult kar) {
        return iso.stakes.stream()
                .filter(s -> sign * kar.ownership.get(s.x + s.y * 19) <= OWNED)
                .map(s -> Intersection.toGTPloc(s.x, s.y, 19))
                .toList();
    }
}
