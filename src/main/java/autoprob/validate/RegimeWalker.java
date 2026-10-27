package autoprob.validate;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.AllowMove;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;

// plays a position out under a ko regime: one side, the favored one, wins every ko, as if it
// had more ko threats than the other could ever answer. on goproblems a clean result is one
// the solver reaches with the opponent winning every ko, a ko result one it reaches only by
// winning the ko itself, so these two regimes are what tell the levels apart.
//
// katago decides kos by the threats it finds on the board, and the isolated board has none
// far away while the problem area can have a few for either side. so the regime is applied to
// the line itself: when the other side takes a ko on katago's line, the favored side gets a
// free exchange (a move filling an eye of its own fill, answered by one in the other side's,
// worth nothing under area scoring) and takes the ko back. katago is not left to decide the
// retake: it would weigh it as if that one exchange were the favored side's last threat, and
// pass it up whenever the other side has threats near the problem (gp-36582, where white let
// black keep the corner's ko). the other side may then use its own threats and take the ko
// again; when it takes the same ko again from the same position, that capture is forbidden
// there and it has to play something else.
//
// a ko the favored side could take back but never end is no ko (deadKos): the stones it
// decides are dead as they stand, and taking it gains the other side nothing either, since
// the favored side takes it back. katago, sure of winning it on a board without threats,
// leaves the stones around it to be captured while it plays elsewhere, so while one is on the
// board the other side may not take it anywhere in katago's search. a dead ko is barred only
// where taking it can never be anything but the ko: katago can only bar a point, and where
// the other side could capture more stones there later, barring it would bar that too.
public class RegimeWalker {
    private static final int MAX_STEPS = 12;
    // how deep in katago's search a dead ko may not be taken: all of it
    private static final int WHOLE_SEARCH = 1000;

    // the end of a walk: the status there, the moves played to reach it (exchanges included),
    // katago's line on from there, the last analysis, how many exchanges the favored side was
    // given, and how clearly the stones at stake end the solver's way (StatusJudge.margin)
    public record Walk(StatusJudge.Status status, List<String> line, List<String> pv, KataAnalysisResult kar, int exchanges,
                       double margin) {
    }

    private final ProblemIsolator.IsolatedProblem iso;
    private final DecisionFeatureExtractor extractor;
    private final StatusJudge judge;
    private final boolean[][] zone; // the problem area: inside the fill, where the problem is played
    private final List<Point> neutralBlack;
    private final List<Point> neutralWhite;
    // katago's analyses by line, visits and forbidden points: the clean and the ko walks of a
    // move start from the same positions, and a deviation is tried from a position its walk
    // already looked at
    private final Map<String, KataAnalysisResult> analyses = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, KataAnalysisResult> eldest) {
            return size() > 4000;
        }
    };
    private int fresh;

    public RegimeWalker(ProblemIsolator.IsolatedProblem iso, DecisionFeatureExtractor extractor, StatusJudge judge) {
        this.iso = iso;
        this.extractor = extractor;
        this.judge = judge;
        this.zone = zone(iso);
        this.neutralBlack = neutralPoints(iso, zone, Intersection.BLACK);
        this.neutralWhite = neutralPoints(iso, zone, Intersection.WHITE);
    }

    // plays out the position after the moves (from the isolated root) with favoredColor
    // winning every ko, and says what it comes to
    public Walk walk(List<String> moves, int favoredColor, int visits) throws Exception {
        String favored = IsolationRecord.colorLetter(favoredColor);
        List<String> line = new ArrayList<>(moves);
        Map<String, Set<String>> forbidden = new HashMap<>(); // position -> ko captures not allowed there
        Set<String> taken = new HashSet<>();                  // kos the other side took, and from where
        int exchanges = 0;
        // the last move may itself take a ko the favored side can take back
        if (!line.isEmpty() && !line.get(line.size() - 1).startsWith(favored) && takesKo(line)) {
            List<String> ex = exchange(line, favoredColor);
            if (ex != null) {
                String back = koPoint(line);
                line.addAll(ex);
                exchanges++;
                retake(line, favored, back);
            }
        }
        KataAnalysisResult kar = null;
        List<String> pv = List.of();
        StatusJudge.Status status = StatusJudge.Status.FAIL;
        for (int step = 0; step < MAX_STEPS; step++) {
            Node first = null;
            try {
                Node node = iso.problem;
                for (String m : line) {
                    Point p = Intersection.gtp2point(m.substring(2));
                    node = node.addBasicMove(p.x, p.y);
                    if (first == null) first = node;
                }
                String key = zoneKey(node);
                Set<String> dead = deadKos(node, favoredColor);
                kar = analyzeAt(node, line, visits, forbidden.getOrDefault(key, Set.of()), dead, favoredColor);
                if (kar.isError()) return new Walk(StatusJudge.Status.FAIL, line, List.of(), kar, exchanges, 0);
                status = judge.status(node, kar);

                // the first ko the other side takes on katago's line, or the first place on it
                // where a dead ko comes up, which katago's search here was free to take
                pv = bestLine(node, kar);
                int k = -1;
                int d = -1;
                String from = null;
                Node cur = node;
                Node pvFirst = null;
                try {
                    for (int i = 0; i < pv.size(); i++) {
                        String m = pv.get(i);
                        Point p = Intersection.gtp2point(m.substring(2));
                        if (!cur.legalMove(p)) break;
                        String before = zoneKey(cur);
                        cur = cur.addBasicMove(p.x, p.y);
                        if (pvFirst == null) pvFirst = cur;
                        if (!m.startsWith(favored) && cur.getKo() != null) {
                            k = i;
                            from = before;
                            break;
                        }
                        if (!dead.containsAll(deadKos(cur, favoredColor))) {
                            d = i;
                            break;
                        }
                    }
                } finally {
                    if (pvFirst != null) node.removeChildNode(pvFirst);
                }
                if (d >= 0) {
                    // play to there and search again, with that ko barred
                    line.addAll(pv.subList(0, d + 1));
                    continue;
                }
                if (k < 0) break; // no ko the other side keeps: this is where the line settles

                if (!taken.add(from + "|" + pv.get(k))) {
                    // taking it again from the same position: the favored side would take it
                    // back every time, so the other side has to play something else there
                    line.addAll(pv.subList(0, k));
                    forbidden.computeIfAbsent(from, x -> new HashSet<>()).add(pv.get(k).substring(2));
                    continue;
                }
                List<String> through = new ArrayList<>(line);
                through.addAll(pv.subList(0, k + 1));
                List<String> ex = exchange(through, favoredColor);
                if (ex == null) break; // out of neutral points: the line stays where it is
                String back = koPoint(through);
                line = through;
                line.addAll(ex);
                exchanges++;
                retake(line, favored, back);
            } finally {
                if (first != null) iso.problem.removeChildNode(first);
            }
        }
        return new Walk(status, line, pv, kar, exchanges, judge.margin(kar));
    }

    // whether a move is played inside the problem area, rather than an exchange in the fill
    public boolean inZone(String move) {
        Point p = Intersection.gtp2point(move.substring(2));
        return p.x >= 0 && p.y >= 0 && p.x < 19 && p.y < 19 && zone[p.x][p.y];
    }

    // how many of the moves, from the start, are legal here: katago's line can go on past a
    // move the board here does not allow
    public int legalPrefix(List<String> moves) throws Exception {
        Node first = null;
        try {
            Node node = iso.problem;
            for (int i = 0; i < moves.size(); i++) {
                Point p = Intersection.gtp2point(moves.get(i).substring(2));
                String color = moves.get(i).substring(0, 1);
                if (!color.equals(IsolationRecord.colorLetter(node.getToMove())) || !node.legalMove(p)) return i;
                node = node.addBasicMove(p.x, p.y);
                if (first == null) first = node;
            }
            return moves.size();
        } finally {
            if (first != null) iso.problem.removeChildNode(first);
        }
    }

    // katago's look at the position after the moves, for the moves it would play there
    public KataAnalysisResult analyze(List<String> moves, int visits) throws Exception {
        return analyze(moves, visits, null);
    }

    // the same with favoredColor winning every ko (null for neither): a dead ko is not taken
    public KataAnalysisResult analyze(List<String> moves, int visits, Integer favoredColor) throws Exception {
        Node first = null;
        try {
            Node node = iso.problem;
            for (String m : moves) {
                Point p = Intersection.gtp2point(m.substring(2));
                node = node.addBasicMove(p.x, p.y);
                if (first == null) first = node;
            }
            if (favoredColor == null) return analyzeAt(node, moves, visits, Set.of(), Set.of(), Intersection.BLACK);
            return analyzeAt(node, moves, visits, Set.of(), deadKos(node, favoredColor), favoredColor);
        } finally {
            if (first != null) iso.problem.removeChildNode(first);
        }
    }

    // dead: the dead kos there, which the side not favored may not take anywhere in the search
    private KataAnalysisResult analyzeAt(Node node, List<String> line, int visits, Set<String> avoid, Set<String> dead,
                                         int favoredColor) throws Exception {
        String key = String.join(",", line) + "|" + visits + "|" + new TreeSet<>(avoid) + "|" + new TreeSet<>(dead);
        KataAnalysisResult kar = analyses.get(key);
        if (kar == null) {
            AllowMove deep = null;
            if (!dead.isEmpty()) {
                deep = new AllowMove();
                deep.player = Intersection.color2katagoname(favoredColor == Intersection.BLACK ? Intersection.WHITE : Intersection.BLACK);
                deep.untilDepth = WHOLE_SEARCH;
                deep.moves = new ArrayList<>(new TreeSet<>(dead));
            }
            kar = extractor.analyzeNear(node, visits, avoid, deep);
            fresh++;
            if (!kar.isError()) analyses.put(key, kar);
        }
        return kar;
    }

    // how many analyses katago has made for the walker, not counting the ones it had already
    public int fresh() {
        return fresh;
    }

    // whether a ko is open after the moves: the other side, were it its turn, could take one
    public boolean koOpen(List<String> moves) throws Exception {
        Node first = null;
        try {
            Node node = iso.problem;
            for (String m : moves) {
                Point p = Intersection.gtp2point(m.substring(2));
                node = node.addBasicMove(p.x, p.y);
                if (first == null) first = node;
            }
            Node pass = node.addBasicMove(19, 19);
            try {
                return !koTakes(pass).isEmpty();
            } finally {
                node.removeChildNode(pass);
            }
        } finally {
            if (first != null) iso.problem.removeChildNode(first);
        }
    }

    // the moves for the side to play after the moves that leave the other side a ko to take it
    // did not have before, within dist of a near point. on a board without threats katago
    // expects a ko to go to whoever takes it first, so it rarely offers one, but the side that
    // wins every ko loses nothing by it: a throw-in that makes a ko, say
    public List<String> koOffers(List<String> moves, List<Point> near, int dist) throws Exception {
        List<String> out = new ArrayList<>();
        Node first = null;
        try {
            Node node = iso.problem;
            for (String m : moves) {
                Point p = Intersection.gtp2point(m.substring(2));
                node = node.addBasicMove(p.x, p.y);
                if (first == null) first = node;
            }
            String color = IsolationRecord.colorLetter(node.getToMove());
            // the kos the other side could take already, were it its turn
            Node pass = node.addBasicMove(19, 19);
            Set<String> before = koTakes(pass);
            node.removeChildNode(pass);
            for (int x = 0; x < 19; x++)
                for (int y = 0; y < 19; y++) {
                    if (!zone[x][y] || node.board.board[x][y].stone != Intersection.EMPTY) continue;
                    boolean close = false;
                    for (Point s : near) {
                        if (Math.max(Math.abs(s.x - x), Math.abs(s.y - y)) <= dist) close = true;
                    }
                    Point p = new Point(x, y);
                    if (!close || !node.legalMove(p)) continue;
                    Node child = node.addBasicMove(x, y);
                    try {
                        for (String take : koTakes(child)) {
                            if (!before.contains(take)) {
                                out.add(color + " " + Intersection.toGTPloc(x, y, 19));
                                break;
                            }
                        }
                    } finally {
                        node.removeChildNode(child);
                    }
                }
            return out;
        } finally {
            if (first != null) iso.problem.removeChildNode(first);
        }
    }

    // the points where the side to play at the node would take a ko
    private Set<String> koTakes(Node node) throws Exception {
        Set<String> out = new HashSet<>();
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (!zone[x][y] || node.board.board[x][y].stone != Intersection.EMPTY) continue;
                if (!node.legalMove(new Point(x, y))) continue;
                Node child = node.addBasicMove(x, y);
                if (child.getKo() != null) out.add(Intersection.toGTPloc(x, y, 19));
                node.removeChildNode(child);
            }
        return out;
    }

    // the dead kos at the node: the kos the side not favored could take, were it its turn,
    // that the favored side could take back but never end. filled, the point taken from would
    // leave the favored side's stones there a single liberty, and none of the taker's stones
    // next to the ko could be captured at once. the stones such a ko decides are dead as they
    // stand: after B5 W B4 B A6 in gp-62169, white can retake at B6 forever but never fill B5.
    // only kos whose taking can never capture more than the one stone count (onlyAKo), since
    // a dead ko is barred by its point
    Set<String> deadKos(Node node, int favoredColor) throws Exception {
        Node at = node;
        Node pass = null;
        if (node.getToMove() == favoredColor) {
            pass = node.addBasicMove(19, 19);
            at = pass;
        }
        try {
            Set<String> out = new HashSet<>();
            for (String take : koTakes(at)) {
                Point k = Intersection.gtp2point(take);
                if (neverEnds(at, k) && onlyAKo(at, k)) out.add(take);
            }
            return out;
        } finally {
            if (pass != null) node.removeChildNode(pass);
        }
    }

    // whether, once the side to play at the node takes the ko at k, the other side could take
    // it back but never fill it, nor capture a stone next to it at once
    static boolean neverEnds(Node node, Point k) throws Exception {
        int taker = node.getToMove();
        Node take = node.addBasicMove(k.x, k.y);
        try {
            Point r = take.getKo();
            if (r == null) return false;
            // the other side takes it back once the ban is over
            Node back = take.addBasicMove(19, 19).addBasicMove(19, 19);
            if (!back.legalMove(r)) return false;
            Node retaken = back.addBasicMove(r.x, r.y);
            for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = r.x + dir[0], y = r.y + dir[1];
                if (x < 0 || y < 0 || x >= 19 || y >= 19) continue;
                if (retaken.board.board[x][y].stone == taker && liberties(retaken.board, x, y).size() == 1) return false;
            }
            Node waits = retaken.addBasicMove(19, 19);
            if (!waits.legalMove(k)) return true;
            Node filled = waits.addBasicMove(k.x, k.y);
            return liberties(filled.board, k.x, k.y).size() <= 1;
        } finally {
            node.removeChildNode(take);
        }
    }

    // whether the side to play at the node can only ever take the ko at k there: the other
    // stones next to k have no liberty but k the taker could fill, only eyes it may not play
    // in, so taking there never captures more than the one stone. in gp-32711, after B T16,
    // black takes T17 at T18, but once it fills S19, T18 captures T19 and S18 as well
    static boolean onlyAKo(Node node, Point k) {
        for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int x = k.x + dir[0], y = k.y + dir[1];
            if (x < 0 || y < 0 || x >= 19 || y >= 19) continue;
            int stone = node.board.board[x][y].stone;
            if (stone == Intersection.EMPTY || stone == node.getToMove()) continue;
            Set<Point> libs = liberties(node.board, x, y);
            if (libs.size() == 1) continue; // the stone the ko takes
            for (Point l : libs) {
                if (!l.equals(k) && node.legalMove(l)) return false;
            }
        }
        return true;
    }

    // the liberties of the group with a stone at (x0, y0)
    static Set<Point> liberties(Board board, int x0, int y0) {
        int color = board.board[x0][y0].stone;
        boolean[][] seen = new boolean[19][19];
        Set<Point> libs = new HashSet<>();
        Deque<Point> todo = new ArrayDeque<>();
        todo.add(new Point(x0, y0));
        seen[x0][y0] = true;
        while (!todo.isEmpty()) {
            Point p = todo.poll();
            for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = p.x + dir[0], y = p.y + dir[1];
                if (x < 0 || y < 0 || x >= 19 || y >= 19 || seen[x][y]) continue;
                int stone = board.board[x][y].stone;
                if (stone == Intersection.EMPTY) {
                    libs.add(new Point(x, y));
                } else if (stone == color) {
                    seen[x][y] = true;
                    todo.add(new Point(x, y));
                }
            }
        }
        return libs;
    }

    // katago's best line from the node, as moves with color
    private static List<String> bestLine(Node node, KataAnalysisResult kar) {
        List<String> out = new ArrayList<>();
        if (kar.moveInfos == null || kar.moveInfos.isEmpty()) return out;
        MoveInfo top = kar.moveInfos.get(0);
        if (top.pv == null) return out;
        String color = IsolationRecord.colorLetter(node.getToMove());
        for (String m : top.pv) {
            if (m.equalsIgnoreCase("pass")) break;
            out.add(color + " " + m);
            color = color.equals("B") ? "W" : "B";
        }
        return out;
    }

    // where the ko the last move of the line took can be taken back, or null
    private String koPoint(List<String> line) throws Exception {
        Node first = null;
        try {
            Node node = iso.problem;
            for (String m : line) {
                Point p = Intersection.gtp2point(m.substring(2));
                node = node.addBasicMove(p.x, p.y);
                if (first == null) first = node;
            }
            Point ko = node.getKo();
            return ko == null ? null : Intersection.toGTPloc(ko.x, ko.y, 19);
        } finally {
            if (first != null) iso.problem.removeChildNode(first);
        }
    }

    // the favored side takes the ko back at the point, when it is its turn and the move is legal
    private void retake(List<String> line, String favored, String point) throws Exception {
        if (point == null) return;
        Node first = null;
        try {
            Node node = iso.problem;
            for (String m : line) {
                Point p = Intersection.gtp2point(m.substring(2));
                node = node.addBasicMove(p.x, p.y);
                if (first == null) first = node;
            }
            if (!IsolationRecord.colorLetter(node.getToMove()).equals(favored)) return;
            if (node.legalMove(Intersection.gtp2point(point))) line.add(favored + " " + point);
        } finally {
            if (first != null) iso.problem.removeChildNode(first);
        }
    }

    // whether the last move of the line takes a ko
    private boolean takesKo(List<String> line) throws Exception {
        Node first = null;
        try {
            Node node = iso.problem;
            for (String m : line) {
                Point p = Intersection.gtp2point(m.substring(2));
                node = node.addBasicMove(p.x, p.y);
                if (first == null) first = node;
            }
            return node.getKo() != null;
        } finally {
            if (first != null) iso.problem.removeChildNode(first);
        }
    }

    // the favored side's free exchange after the line: a move in its own fill, then one in
    // the other side's, at points still empty. null when either side has none left
    private List<String> exchange(List<String> line, int favoredColor) {
        Set<String> played = new HashSet<>();
        for (String m : line) played.add(m.substring(2));
        Point mine = firstFree(favoredColor == Intersection.BLACK ? neutralBlack : neutralWhite, played);
        Point theirs = firstFree(favoredColor == Intersection.BLACK ? neutralWhite : neutralBlack, played);
        if (mine == null || theirs == null) return null;
        String f = IsolationRecord.colorLetter(favoredColor);
        String o = f.equals("B") ? "W" : "B";
        return List.of(f + " " + Intersection.toGTPloc(mine.x, mine.y, 19),
                o + " " + Intersection.toGTPloc(theirs.x, theirs.y, 19));
    }

    private static Point firstFree(List<Point> points, Set<String> played) {
        for (Point p : points) {
            if (!played.contains(Intersection.toGTPloc(p.x, p.y, 19))) return p;
        }
        return null;
    }

    // the problem area after the moves, as zoneKey has it, or null when they cannot be played
    String zoneKeyAfter(List<String> moves) throws Exception {
        if (legalPrefix(moves) < moves.size()) return null;
        Node first = null;
        try {
            Node node = iso.problem;
            for (String m : moves) {
                Point p = Intersection.gtp2point(m.substring(2));
                node = node.addBasicMove(p.x, p.y);
                if (first == null) first = node;
            }
            return zoneKey(node);
        } finally {
            if (first != null) iso.problem.removeChildNode(first);
        }
    }

    // the line's kos the favored side was given threats for: the walker's exchanges on it
    public int kos(List<String> line) throws Exception {
        return exchanges(line).size() / 2;
    }

    // the walker's own exchanges in a line, by index: right after a ko taken in the problem
    // area, a move of the other side in an eye of its own fill, then one of the taker's in its
    // own. katago's moves away from the problem are not among them, before a ko on its line
    // as after one: those are turns it passed up there
    public Set<Integer> exchanges(List<String> line) throws Exception {
        boolean[] tookKo = new boolean[line.size()];
        Node first = null;
        try {
            Node node = iso.problem;
            for (int i = 0; i < line.size(); i++) {
                Point p = Intersection.gtp2point(line.get(i).substring(2));
                if (!node.legalMove(p)) break;
                node = node.addBasicMove(p.x, p.y);
                if (first == null) first = node;
                tookKo[i] = node.getKo() != null;
            }
        } finally {
            if (first != null) iso.problem.removeChildNode(first);
        }
        Set<Integer> out = new HashSet<>();
        for (int i = 0; i + 2 < line.size(); i++) {
            String take = line.get(i), mine = line.get(i + 1), theirs = line.get(i + 2);
            if (tookKo[i] && inZone(take) && mine.charAt(0) != take.charAt(0) && theirs.charAt(0) == take.charAt(0)
                    && fillsOwnEye(mine) && fillsOwnEye(theirs)) {
                out.add(i + 1);
                out.add(i + 2);
            }
        }
        return out;
    }

    // whether a move fills an eye of its own color's fill, where the exchanges are played
    private boolean fillsOwnEye(String move) {
        Point p = Intersection.gtp2point(move.substring(2));
        return (move.startsWith("B") ? neutralBlack : neutralWhite).contains(p);
    }

    // the problem area as stones and side to move, without the fill: two positions that
    // differ only in exchanges are the same position for the problem
    String zoneKey(Node node) {
        StringBuilder sb = new StringBuilder(200);
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (zone[x][y]) sb.append((char) ('0' + node.board.board[x][y].stone));
            }
        return sb.append(node.getToMove()).toString();
    }

    // the points reachable from the problem's stones without crossing a fill stone
    static boolean[][] zone(ProblemIsolator.IsolatedProblem iso) {
        boolean[][] in = new boolean[19][19];
        Deque<Point> todo = new ArrayDeque<>();
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (iso.problem.board.board[x][y].stone != Intersection.EMPTY && !iso.isFilled(x, y)) {
                    in[x][y] = true;
                    todo.add(new Point(x, y));
                }
            }
        while (!todo.isEmpty()) {
            Point p = todo.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = p.x + d[0], y = p.y + d[1];
                if (x < 0 || y < 0 || x >= 19 || y >= 19 || in[x][y] || iso.isFilled(x, y)) continue;
                in[x][y] = true;
                todo.add(new Point(x, y));
            }
        }
        return in;
    }

    // eyes of a color's own fill, outside the problem area, farthest from it first: points
    // whose every neighbor is a fill stone of that color, so filling one costs nothing
    static List<Point> neutralPoints(ProblemIsolator.IsolatedProblem iso, boolean[][] zone, int color) {
        List<Point> out = new ArrayList<>();
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (zone[x][y] || iso.problem.board.board[x][y].stone != Intersection.EMPTY) continue;
                boolean eye = true;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx < 0 || ny < 0 || nx >= 19 || ny >= 19) continue;
                    if (!iso.isFilled(nx, ny) || iso.problem.board.board[nx][ny].stone != color) eye = false;
                }
                if (eye) out.add(new Point(x, y));
            }
        out.sort(Comparator.comparingInt((Point p) -> -distanceToZone(p, zone)));
        return out;
    }

    private static int distanceToZone(Point p, boolean[][] zone) {
        int best = 100;
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (zone[x][y]) best = Math.min(best, Math.max(Math.abs(x - p.x), Math.abs(y - p.y)));
            }
        return best;
    }
}
