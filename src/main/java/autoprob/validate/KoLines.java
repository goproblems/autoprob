package autoprob.validate;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;

// kos on lines of play from a position. lines are moves with color ("B T18"), played from the
// position given and taken back after. a ko is a capture of one stone that leaves a ko
// (Node.getKo): the shape, whether or not the result hangs on it
public class KoLines {
    // a ko taken again this soon after the last is the same fight
    private static final int FIGHT_MOVES = 4;

    private KoLines() {
    }

    // the move that takes a ko on any of the lines, or null
    public static String koIn(Node from, List<List<String>> lines) throws Exception {
        for (List<String> line : lines) {
            Node cur = from;
            Node first = null;
            try {
                for (String move : line) {
                    Point p = Intersection.gtp2point(move.substring(2));
                    if (!cur.legalMove(p)) break;
                    cur = cur.addBasicMove(p.x, p.y);
                    if (first == null) first = cur;
                    if (cur.getKo() != null) return move;
                }
            } finally {
                if (first != null) from.removeChildNode(first);
            }
        }
        return null;
    }

    // the move that takes a ko a line then fights over, or null: the reply to it is a ko
    // threat (a move on the board's threat pattern), or a ko is taken again within
    // FIGHT_MOVES moves. a ko no one fights over (taken for points, or one the result does
    // not hang on) does not count, and neither does a move on the threat pattern played later
    // as endgame
    public static String koFight(Node from, List<List<String>> lines, Set<String> threatPoints) throws Exception {
        for (List<String> line : lines) {
            Node cur = from;
            Node first = null;
            String ko = null;
            int koAt = -1;
            try {
                for (int i = 0; i < line.size(); i++) {
                    String move = line.get(i);
                    Point p = Intersection.gtp2point(move.substring(2));
                    if (!cur.legalMove(p)) break;
                    if (ko != null && i == koAt + 1 && threatPoints.contains(move.substring(2))) return ko;
                    cur = cur.addBasicMove(p.x, p.y);
                    if (first == null) first = cur;
                    if (cur.getKo() != null) {
                        if (ko != null && i - koAt <= FIGHT_MOVES) return ko;
                        ko = move;
                        koAt = i;
                    }
                }
            } finally {
                if (first != null) from.removeChildNode(first);
            }
        }
        return null;
    }

    // katago's own lines after the moves: the moves, then the principal variation of its best
    // answer and of every other answer with at least a tenth of the best one's visits. an
    // answer that makes a ko the opponent cannot win on a board without threats scores
    // poorly, but it is still the opponent's try in a game
    public static List<List<String>> katagoLines(List<String> moves, KataAnalysisResult kar) {
        List<List<String>> lines = new ArrayList<>();
        if (kar.moveInfos == null || kar.moveInfos.isEmpty() || kar.moveInfos.get(0).visits == null) return lines;
        int top = kar.moveInfos.get(0).visits;
        String toMove = moves.get(moves.size() - 1).startsWith("B") ? "W" : "B";
        for (MoveInfo mi : kar.moveInfos) {
            if (mi.pv == null || mi.visits == null || mi.visits < 0.1 * top) continue;
            List<String> line = new ArrayList<>(moves);
            String color = toMove;
            for (String move : mi.pv) {
                if (move.equalsIgnoreCase("pass")) break;
                line.add(color + " " + move);
                color = color.equals("B") ? "W" : "B";
            }
            lines.add(line);
        }
        return lines;
    }
}
