package autoprob.joseki;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.MoveAction;
import java.awt.Point;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Matches Josekipedia's history-chebyshev-4-v1 placement classification. */
public final class TenukiMoves {
    public static final String VERSION = "history-chebyshev-4-v1";
    private TenukiMoves() {}

    public static boolean isTenuki(Node position, String move) {
        if ("pass".equalsIgnoreCase(move.trim())) return false;
        Point point = Intersection.gtp2point(move.trim().toUpperCase(Locale.ROOT));
        if (!onBoard(point)) throw new IllegalArgumentException("Invalid move: " + move);
        boolean hasHistory = false;
        for (Node prior = position; prior != null; prior = prior.mom) {
            MoveAction action = prior.getMoveAction();
            if (action == null || !onBoard(action.loc)) continue;
            hasHistory = true;
            if (Math.max(Math.abs(point.x-action.loc.x), Math.abs(point.y-action.loc.y)) <= 4) return false;
        }
        return hasHistory;
    }

    public static Set<String> withoutTenuki(Node position, Set<String> moves) {
        Set<String> local = new LinkedHashSet<>();
        if (moves != null) for (String move : moves) {
            if (!isTenuki(position, move)) local.add(move);
        }
        return local;
    }

    private static boolean onBoard(Point point) {
        return point != null && point.x >= 0 && point.x < 19 && point.y >= 0 && point.y < 19;
    }
}
