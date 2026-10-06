package autoprob.fuseki;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import autoprob.go.Board;
import autoprob.go.Intersection;

// replays the start of a principal variation and records ataris and captures.
// a calm fuseki choice should not lead straight into a fight.
public class PvTactics {
    public int ataris;
    public int captures;
    public int movesReplayed;
    public String firstEvent; // e.g. "atari by W at line move 3 (C5)", null when quiet

    public boolean isTactical() {
        return ataris + captures > 0;
    }

    public String describe() {
        if (!isTactical()) {
            return "none in first " + movesReplayed + " moves";
        }
        return firstEvent + (ataris + captures > 1 ? " (" + ataris + " ataris, " + captures + " stones captured)" : "");
    }

    public static PvTactics replay(Board board, int firstColor, List<String> pv, int maxMoves) {
        PvTactics t = new PvTactics();
        int[][] g = new int[19][19];
        for (int x = 0; x < 19; x++) {
            for (int y = 0; y < 19; y++) {
                g[x][y] = board.board[x][y].stone;
            }
        }
        int color = firstColor;
        int n = Math.min(maxMoves, pv == null ? 0 : pv.size());
        for (int i = 0; i < n; i++) {
            String mv = pv.get(i);
            if (mv.equalsIgnoreCase("pass")) {
                break;
            }
            Point p = Intersection.gtp2point(mv);
            if (g[p.x][p.y] != Intersection.EMPTY) {
                break; // stale pv, nothing more to learn
            }
            g[p.x][p.y] = color;
            int enemy = 3 - color;
            String who = color == Intersection.BLACK ? "B" : "W";

            int captured = 0;
            for (Point nb : neighbors(p)) {
                if (g[nb.x][nb.y] != enemy) continue;
                List<Point> grp = group(g, nb);
                if (liberties(g, grp) == 0) {
                    for (Point s : grp) g[s.x][s.y] = Intersection.EMPTY;
                    captured += grp.size();
                }
            }
            List<Point> own = group(g, p);
            int ownLibs = liberties(g, own);
            if (ownLibs == 0) {
                break; // suicide, katago never plays it
            }
            t.movesReplayed++;
            if (captured > 0) {
                t.captures += captured;
                t.note("capture by " + who + " at line move " + (i + 1) + " (" + mv + ")");
            }
            boolean atari = ownLibs == 1;
            for (Point nb : neighbors(p)) {
                if (g[nb.x][nb.y] == enemy && liberties(g, group(g, nb)) == 1) {
                    atari = true;
                }
            }
            if (atari) {
                t.ataris++;
                t.note("atari " + (ownLibs == 1 ? "(self) " : "") + "by " + who + " at line move " + (i + 1) + " (" + mv + ")");
            }
            color = enemy;
        }
        return t;
    }

    private void note(String event) {
        if (firstEvent == null) {
            firstEvent = event;
        }
    }

    private static List<Point> neighbors(Point p) {
        List<Point> l = new ArrayList<>(4);
        if (p.x > 0) l.add(new Point(p.x - 1, p.y));
        if (p.x < 18) l.add(new Point(p.x + 1, p.y));
        if (p.y > 0) l.add(new Point(p.x, p.y - 1));
        if (p.y < 18) l.add(new Point(p.x, p.y + 1));
        return l;
    }

    private static List<Point> group(int[][] g, Point start) {
        int color = g[start.x][start.y];
        boolean[][] seen = new boolean[19][19];
        List<Point> grp = new ArrayList<>();
        List<Point> stack = new ArrayList<>();
        stack.add(start);
        seen[start.x][start.y] = true;
        while (!stack.isEmpty()) {
            Point p = stack.remove(stack.size() - 1);
            grp.add(p);
            for (Point nb : neighbors(p)) {
                if (!seen[nb.x][nb.y] && g[nb.x][nb.y] == color) {
                    seen[nb.x][nb.y] = true;
                    stack.add(nb);
                }
            }
        }
        return grp;
    }

    private static int liberties(int[][] g, List<Point> grp) {
        boolean[][] seen = new boolean[19][19];
        int libs = 0;
        for (Point p : grp) {
            for (Point nb : neighbors(p)) {
                if (g[nb.x][nb.y] == Intersection.EMPTY && !seen[nb.x][nb.y]) {
                    seen[nb.x][nb.y] = true;
                    libs++;
                }
            }
        }
        return libs;
    }
}
