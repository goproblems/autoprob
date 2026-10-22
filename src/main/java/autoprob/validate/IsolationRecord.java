package autoprob.validate;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import autoprob.go.Intersection;
import autoprob.go.Node;

// a frozen copy of an isolated problem: everything needed to rebuild the exact board,
// komi and stakes the engine evaluated, so a decision can be replayed later without
// re-running isolation. stored in run records and test cases.
//
// rows holds the board top to bottom (row 0 is gtp row 19): X/O are problem stones,
// x/o are fortress fill, . is empty.
public class IsolationRecord {
    public String solver; // B or W
    public double komi;
    public double targetLead;
    public List<String> stakes = new ArrayList<>();
    public List<String> rows = new ArrayList<>();

    public static IsolationRecord from(ProblemIsolator.IsolatedProblem iso) {
        IsolationRecord rec = new IsolationRecord();
        rec.solver = colorLetter(iso.solverColor);
        rec.komi = iso.komi;
        rec.targetLead = iso.targetLead;
        for (Point p : iso.stakes) {
            rec.stakes.add(Intersection.toGTPloc(p.x, p.y, 19));
        }
        for (int y = 0; y < 19; y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < 19; x++) {
                int stone = iso.problem.board.board[x][y].stone;
                char c = stone == Intersection.BLACK ? 'X' : stone == Intersection.WHITE ? 'O' : '.';
                if (iso.isFilled(x, y)) c = Character.toLowerCase(c);
                sb.append(c);
            }
            rec.rows.add(sb.toString());
        }
        return rec;
    }

    // rebuild a fresh isolated problem root from the frozen copy
    public ProblemIsolator.IsolatedProblem toIsolated() {
        ProblemIsolator.IsolatedProblem iso = new ProblemIsolator.IsolatedProblem();
        iso.solverColor = letterColor(solver);
        iso.komi = komi;
        iso.targetLead = targetLead;
        Node problem = new Node(null);
        for (int y = 0; y < 19; y++) {
            String row = rows.get(y);
            for (int x = 0; x < 19; x++) {
                char c = row.charAt(x);
                int stone = switch (Character.toUpperCase(c)) {
                    case 'X' -> Intersection.BLACK;
                    case 'O' -> Intersection.WHITE;
                    default -> Intersection.EMPTY;
                };
                problem.board.board[x][y].stone = stone;
                if (Character.isLowerCase(c)) {
                    iso.filledStones.board[x][y].stone = stone;
                }
            }
        }
        problem.defaultToMoveColor = iso.solverColor;
        problem.addXtraTag("PL", solver);
        problem.addXtraTag("KM", String.valueOf(komi));
        iso.problem = problem;
        for (String s : stakes) {
            iso.stakes.add(Intersection.gtp2point(s));
        }
        return iso;
    }

    public static String colorLetter(int color) {
        return color == Intersection.BLACK ? "B" : "W";
    }

    public static int letterColor(String letter) {
        return letter.equals("B") ? Intersection.BLACK : Intersection.WHITE;
    }
}
