package autoprob.validate;

import java.awt.Point;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import autoprob.go.Intersection;
import autoprob.go.parse.SgfEdit;
import autoprob.go.parse.SmallBoard;

// a suggested edit of a problem, made from the finder's findings and kept until it is
// reviewed and sent: the problem's sgf as the site had it, the same with the missing lines
// added, and the comment. moves are on the problem's own board, as sgf ("B ka"); the finder
// plays smaller boards in a corner of 19x19 (boardSize, dx, dy say where)
public class SuggestionDraft {
    private static final String COLUMNS = "ABCDEFGHJKLMNOPQRST";

    // a position the suggestion adds lines at
    public static class Place {
        public List<String> at = new ArrayList<>();          // the moves to it from the start
        public List<String> moves = new ArrayList<>();       // the correct moves missing there
        public List<String> markedWrong = new ArrayList<>(); // of those, the ones the problem has as wrong
        public List<String> accepted = new ArrayList<>();    // the moves the problem accepts there
        public List<List<String>> lines = new ArrayList<>(); // the lines added, each from the position
        public String level;                                 // what the moves reach, the best there
        public Confidence confidence;                        // the least sure of its moves'
        public Map<String, Confidence> confidences = new LinkedHashMap<>(); // each move's
    }

    public int problemId;
    public String problem;
    public String difficulty;
    public int boardSize;
    public int dx, dy;
    public String originalSgf;
    public String suggestedSgf;
    public String comment;
    public int addedNodes;
    public List<Place> places = new ArrayList<>();
    public int version;   // the judging version the findings came from
    public String created;

    // the suggestion the findings make on the problem's sgf as the site has it, or null when
    // they have no missing move the problem lacks yet
    public static SuggestionDraft build(int problemId, String originalSgf, List<MissingVariationFinder.Finding> findings,
                                        int version) {
        return build(problemId, originalSgf, findings, version, new ArrayList<>());
    }

    // the same, saying in notes why a move or line was left out. a move's longest lines go in
    // first, so a shorter one that ends on a move a longer one goes through is there already
    public static SuggestionDraft build(int problemId, String originalSgf, List<MissingVariationFinder.Finding> findings,
                                        int version, List<String> notes) {
        SmallBoard.Embedded board = SmallBoard.toNineteen(originalSgf);
        SgfEdit edit = SgfEdit.parse(originalSgf);
        if (!SgfEdit.parse(edit.write()).write().equals(edit.write())) throw new IllegalStateException("the sgf does not read back the same");
        SuggestionDraft d = new SuggestionDraft();
        d.problemId = problemId;
        d.problem = "gp-" + problemId;
        d.boardSize = board.size();
        d.dx = board.dx();
        d.dy = board.dy();
        d.originalSgf = originalSgf;
        d.version = version;
        for (MissingVariationFinder.Finding f : findings) {
            if (f.moves == null || f.moves.isEmpty()) continue;
            if (d.difficulty == null) d.difficulty = f.difficulty;
            Place p = new Place();
            p.at = onBoard(f.at, board);
            if (f.accepted != null) p.accepted = onBoard(f.accepted, board);
            p.level = f.level == null ? null : f.level.name();
            for (MissingVariationFinder.FoundMove m : f.moves) {
                List<List<String>> lines = new ArrayList<>(m.lines == null || m.lines.isEmpty() ? List.of(List.of(m.move)) : m.lines);
                lines.sort((a, b) -> Integer.compare(b.size(), a.size()));
                boolean adds = false;
                for (List<String> l : lines) {
                    try {
                        List<String> line = onBoard(l, board);
                        int added = edit.addLine(p.at, line);
                        if (added == 0) continue;
                        d.addedNodes += added;
                        p.lines.add(line);
                        adds = true;
                    } catch (IllegalArgumentException e) {
                        notes.add(String.join(" ", l) + ": " + e.getMessage());
                    }
                }
                if (!adds) continue;
                String move = onBoard(List.of(m.move), board).get(0);
                p.moves.add(move);
                if (m.inFileAsWrong) p.markedWrong.add(move);
                if (m.confidence != null) p.confidences.put(move, m.confidence);
                if (m.confidence != null && (p.confidence == null || m.confidence.low < p.confidence.low)) p.confidence = m.confidence;
            }
            if (!p.moves.isEmpty()) d.places.add(p);
        }
        if (d.places.isEmpty()) return null;
        d.suggestedSgf = edit.write();
        d.comment = d.comment();
        d.created = LocalDateTime.now().toString();
        return d;
    }

    // the least sure of the suggestion's moves' confidences
    public Confidence confidence() {
        Confidence least = null;
        for (Place p : places) {
            if (p.confidence != null && (least == null || p.confidence.low < least.low)) least = p.confidence;
        }
        return least;
    }

    // what the suggestion says, for whoever reviews it, and in the problem's history once it is
    // approved: each position, its move and the lines added, and how far to trust them
    public String comment() {
        StringBuilder sb = new StringBuilder();
        String solver = solver();
        for (Place p : places) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(p.at.isEmpty() ? "At the start" : "After " + String.join(", ", points(p.at)));
            String side = p.moves.get(0).startsWith("B") ? "Black" : "White";
            List<String> moves = bare(points(p.moves));
            // a line added by hand can start with the opponent's move: an answer to it
            sb.append(", ").append(side).append("'s ").append(String.join(" and ", moves)).append(p.moves.get(0).startsWith(solver)
                    ? (moves.size() == 1 ? " works too" : " work too") : (moves.size() == 1 ? " is answered too" : " are answered too"));
            if (!p.markedWrong.isEmpty()) {
                sb.append(" (the problem has ").append(String.join(" and ", bare(points(p.markedWrong)))).append(" as wrong)");
            }
            if (!p.accepted.isEmpty()) sb.append(", with the same result as ").append(String.join(" and ", bare(points(p.accepted))));
            sb.append(". Line").append(p.lines.size() == 1 ? "" : "s").append(" added: ");
            List<String> shown = new ArrayList<>();
            for (List<String> l : p.lines) shown.add(String.join(", ", points(l)));
            sb.append(String.join("; ", shown)).append(".");
        }
        sb.append(" Found by autoprob, an automated checker");
        Confidence c = confidence();
        if (c != null) sb.append(" (confidence: ").append(c.label).append(", ").append(c.low).append("-").append(c.high).append("%)");
        return sb.append(".").toString();
    }

    // the color the problem has the solver play, B or W, from its first move
    String solver() {
        for (SgfEdit.SgfNode c : SgfEdit.parse(originalSgf).root().children()) {
            if (c.move() != null) return c.move().substring(0, 1);
        }
        return "B";
    }

    // the suggestion as edited by hand in review: the sgf given, with its places worked out
    // again from the lines it adds, each a path from the start through a node the problem
    // lacks to a node marked right. a line goes to the deepest of the draft's own places on
    // its way whose missing move it starts with, keeping what the finder knew there (the moves
    // the problem accepts, the level, how sure), or else to the position before its first
    // added node
    public SuggestionDraft edited(String sgf) {
        SuggestionDraft d = new SuggestionDraft();
        d.problemId = problemId;
        d.problem = problem;
        d.difficulty = difficulty;
        d.boardSize = boardSize;
        d.dx = dx;
        d.dy = dy;
        d.originalSgf = originalSgf;
        d.suggestedSgf = sgf;
        d.version = version;
        d.created = created;
        SgfEdit was = SgfEdit.parse(originalSgf);
        List<List<String>> lines = new ArrayList<>();
        List<Integer> firsts = new ArrayList<>();
        d.addedNodes = addedLines(SgfEdit.parse(sgf).root(), was.root(), new ArrayList<>(), -1, lines, firsts);
        // the draft's places first, in their order, then any new ones
        Map<List<String>, Place> byAt = new LinkedHashMap<>();
        for (Place p : places) {
            Place q = new Place();
            q.at = p.at;
            q.accepted = p.accepted;
            q.level = p.level;
            byAt.put(p.at, q);
        }
        for (int i = 0; i < lines.size(); i++) {
            List<String> path = lines.get(i);
            int first = firsts.get(i);
            Place home = null;
            for (Place p : places) {
                int n = p.at.size();
                if (n <= first && path.subList(0, n).equals(p.at) && p.moves.contains(path.get(n))
                        && (home == null || n > home.at.size())) home = p;
            }
            List<String> at = home != null ? home.at : new ArrayList<>(path.subList(0, first));
            Place q = byAt.computeIfAbsent(at, a -> {
                Place p = new Place();
                p.at = a;
                return p;
            });
            List<String> line = new ArrayList<>(path.subList(at.size(), path.size()));
            q.lines.add(line);
            if (!q.moves.contains(line.get(0))) q.moves.add(line.get(0));
        }
        String solver = solver();
        for (Place q : byAt.values()) {
            if (q.lines.isEmpty()) continue;
            // the draft's own moves and lines in its order, the ones added by hand after them
            for (Place p : places) {
                if (!p.at.equals(q.at)) continue;
                q.moves.sort(Comparator.comparingInt(m -> rank(p.moves, m)));
                q.lines.sort(Comparator.comparingInt(l -> rank(p.lines, l)));
                // how sure: the least sure of the draft's own moves still there. a draft made
                // before each move's was kept has only the place's, which holds while its moves do
                if (p.confidences == null || p.confidences.isEmpty()) {
                    if (new HashSet<>(q.moves).equals(new HashSet<>(p.moves))) q.confidence = p.confidence;
                    continue;
                }
                for (String m : q.moves) if (p.confidences.containsKey(m)) q.confidences.put(m, p.confidences.get(m));
                q.confidence = q.confidences.values().stream().min(Comparator.comparingInt(c -> c.low)).orElse(null);
            }
            // a solver's move the problem has there, on a line that is not correct, it has as wrong
            SgfEdit.SgfNode at = was.at(q.at);
            for (String m : q.moves) {
                SgfEdit.SgfNode c = at == null ? null : at.child(m);
                if (m.startsWith(solver) && c != null && !c.leadsRight()) q.markedWrong.add(m);
            }
            d.places.add(q);
        }
        d.comment = d.comment();
        return d;
    }

    private static <T> int rank(List<T> list, T x) {
        int i = list.indexOf(x);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    // each path from the start to a node marked right through a node the original lacks, with
    // where on it the first such node is; how many nodes the original lacks
    private static int addedLines(SgfEdit.SgfNode now, SgfEdit.SgfNode was, List<String> path, int first,
                                  List<List<String>> lines, List<Integer> firsts) {
        int added = 0;
        for (SgfEdit.SgfNode c : now.children()) {
            SgfEdit.SgfNode w = was == null ? null : was.child(c.move());
            path.add(c.move());
            int f = first >= 0 ? first : w == null ? path.size() - 1 : -1;
            if (w == null) added++;
            if (f >= 0 && c.right()) {
                lines.add(new ArrayList<>(path));
                firsts.add(f);
            }
            added += addedLines(c, w, path, f, lines, firsts);
            path.remove(path.size() - 1);
        }
        return added;
    }

    // what the site takes, with the comment as it stands after review
    public JsonObject payload(String comment) {
        JsonObject body = new JsonObject();
        body.addProperty("problemId", problemId);
        body.addProperty("comment", comment);
        body.addProperty("originalSgf", originalSgf);
        body.addProperty("suggestedSgf", suggestedSgf);
        return body;
    }

    // why the site's suggestions on the problem stand in the way of this one, or null: one
    // that is open, since approving either would undo the other (unless force), or one made
    // before, approved or rejected, that has a line of this one already
    public String blockedBy(GoproblemsSite site, boolean force) throws Exception {
        JsonArray entries = site.suggestions(problemId);
        for (JsonElement e : entries) {
            JsonObject s = e.getAsJsonObject();
            String sid = s.get("id").getAsString();
            String status = s.has("status") && !s.get("status").isJsonNull() ? s.get("status").getAsString() : "?";
            if (status.equals("open") && !force) return "suggestion #" + sid + " is open on the problem";
            JsonObject full = site.suggestion(Integer.parseInt(sid));
            JsonElement sgf = full == null ? null : full.get("suggestedSgf");
            if (sgf == null || sgf.isJsonNull()) continue;
            SgfEdit theirs = SgfEdit.parse(sgf.getAsString());
            for (Place p : places) {
                for (List<String> l : p.lines) {
                    if (theirs.has(p.at, l)) return "suggestion #" + sid + " (" + status + ") has " + String.join(" ", points(l)) + " already";
                }
            }
        }
        return null;
    }

    // whether two sgfs are the same problem: the same tree, node for node, but for the
    // difficulty our downloads add to the root
    static boolean sameProblem(String site, String local) {
        return SgfEdit.parse(site).write().equals(SgfEdit.parse(local.replaceFirst("DI\\[[^\\]]*\\]", "")).write());
    }

    // moves the finder played on 19x19 ("B R19"), as sgf moves on the problem's own board ("B ka")
    static List<String> onBoard(List<String> moves, SmallBoard.Embedded board) {
        List<String> out = new ArrayList<>();
        for (String m : moves) {
            Point p = Intersection.gtp2point(m.substring(2));
            int x = p.x - board.dx(), y = p.y - board.dy();
            if (x < 0 || y < 0 || x >= board.size() || y >= board.size()) {
                throw new IllegalArgumentException(m + " is off the problem's " + board.size() + "x" + board.size() + " board");
            }
            out.add(m.charAt(0) + " " + (char) ('a' + x) + (char) ('a' + y));
        }
        return out;
    }

    // sgf moves as a player reads them on the problem's board ("B L13")
    public List<String> points(List<String> moves) {
        List<String> out = new ArrayList<>();
        for (String m : moves) {
            int x = m.charAt(2) - 'a', y = m.charAt(3) - 'a';
            out.add(m.charAt(0) + " " + COLUMNS.charAt(x) + (boardSize - y));
        }
        return out;
    }

    private static List<String> bare(List<String> points) {
        return points.stream().map(p -> p.substring(2)).toList();
    }
}
