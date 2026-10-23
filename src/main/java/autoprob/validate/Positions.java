package autoprob.validate;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import autoprob.go.Intersection;
import autoprob.go.Node;

// helpers for positions given as move lists from a problem root, e.g. ["B L1", "W N1"]
public class Positions {

    // play the moves on the isolated problem, checking turn order and legality.
    // returns the node after the last move.
    public static Node play(ProblemIsolator.IsolatedProblem iso, List<String> at) throws Exception {
        Node node = iso.problem;
        for (String move : at) {
            String color = move.substring(0, 1);
            if (!color.equals(IsolationRecord.colorLetter(node.getToMove()))) {
                throw new IllegalArgumentException("out of turn move " + move + " in " + at);
            }
            Point p = Intersection.gtp2point(move.substring(2));
            if (!node.legalMove(p)) {
                throw new IllegalArgumentException("illegal move " + move + " in " + at);
            }
            node = node.addBasicMove(p.x, p.y);
        }
        return node;
    }

    // the board after each prefix of the moves: element 0 is the start position.
    // rows use the IsolationRecord format (lowercase for fortress fill).
    public static List<List<String>> boards(ProblemIsolator.IsolatedProblem iso, List<String> moves) throws Exception {
        List<List<String>> out = new ArrayList<>();
        Node node = iso.problem;
        out.add(rows(node, iso));
        for (String move : moves) {
            Point p = Intersection.gtp2point(move.substring(2));
            if (!node.legalMove(p)) break;
            node = node.addBasicMove(p.x, p.y);
            out.add(rows(node, iso));
        }
        return out;
    }

    static List<String> rows(Node node, ProblemIsolator.IsolatedProblem iso) {
        List<String> rows = new ArrayList<>();
        for (int y = 0; y < 19; y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < 19; x++) {
                int stone = node.board.board[x][y].stone;
                char c = stone == Intersection.BLACK ? 'X' : stone == Intersection.WHITE ? 'O' : '.';
                if (iso.isFilled(x, y) && c != '.') c = Character.toLowerCase(c);
                sb.append(c);
            }
            rows.add(sb.toString());
        }
        return rows;
    }
}
