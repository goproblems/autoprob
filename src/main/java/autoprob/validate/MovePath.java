package autoprob.validate;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.MoveAction;

// a sequence of moves from a problem root down to the end of a path
public class MovePath {
    public static class Move {
        public final int color;
        public final Point loc;

        public Move(int color, Point loc) {
            this.color = color;
            this.loc = loc;
        }

        public String gtp() {
            return Intersection.toGTPloc(loc.x, loc.y, 19);
        }

        public boolean same(Move other) {
            return color == other.color && loc.equals(other.loc);
        }

        @Override
        public String toString() {
            return (color == Intersection.BLACK ? "B " : "W ") + gtp();
        }
    }

    public final List<Move> moves = new ArrayList<>();

    // walk from an end node up to the root, collecting moves (skips no-move nodes)
    public static MovePath fromEndNode(Node end) {
        MovePath path = new MovePath();
        List<Move> reversed = new ArrayList<>();
        Node n = end;
        while (n != null) {
            MoveAction ma = n.getMoveAction();
            if (ma != null) {
                reversed.add(new Move(ma.stone, ma.loc));
            }
            n = n.mom;
        }
        for (int i = reversed.size() - 1; i >= 0; i--) {
            path.moves.add(reversed.get(i));
        }
        return path;
    }

    // collect all paths ending in a RIGHT node from a problem tree
    public static List<MovePath> extractRightPaths(Node root) {
        List<MovePath> paths = new ArrayList<>();
        collectRightPaths(root, paths);
        return paths;
    }

    private static void collectRightPaths(Node node, List<MovePath> paths) {
        if (node.result == Intersection.RIGHT) {
            paths.add(fromEndNode(node));
        }
        for (Node baby : node.babies) {
            collectRightPaths(baby, paths);
        }
    }

    public int length() {
        return moves.size();
    }

    // number of leading moves shared with another path
    public int commonPrefix(MovePath other) {
        int n = Math.min(length(), other.length());
        for (int i = 0; i < n; i++) {
            if (!moves.get(i).same(other.moves.get(i))) {
                return i;
            }
        }
        return n;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Move m : moves) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(m);
        }
        return sb.toString();
    }
}
