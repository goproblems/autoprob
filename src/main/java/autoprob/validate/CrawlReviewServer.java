package autoprob.validate;

import java.awt.Point;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.MoveAction;
import autoprob.go.parse.Parser;
import autoprob.go.parse.SmallBoard;

// cmd=crawl-review: a local page for going through the crawl's suggestions (crawl.dir) before
// any goes to the site: each with the problem's tree as it would leave it, its positions on the
// problem's own board, the lines it adds to step through, how far to trust it and why, and its
// comment to edit. approving or rejecting one writes approvals/gp-<id>.json, which
// cmd=crawl-submit sends from. review.port (8770), review.by the name kept with each decision
// (the login name). no engine needed.
public class CrawlReviewServer {
    private static final String COLUMNS = "ABCDEFGHJKLMNOPQRST";

    private final Properties props;
    private final File dir;
    private final String by;

    // the request body for a decision
    static class Decision {
        String decision; // approved, rejected, or pending to take one back
        String comment;
        String note;
    }

    public CrawlReviewServer(Properties props) {
        this.props = props;
        this.dir = new File(props.getProperty("crawl.dir", "crawl"));
        this.by = props.getProperty("review.by", System.getProperty("user.name", "unknown"));
    }

    public void run() throws Exception {
        int port = Integer.parseInt(props.getProperty("review.port", "8770"));
        // under WSL only wildcard binds are forwarded to windows localhost
        String host = props.getProperty("review.host", System.getenv("WSL_DISTRO_NAME") != null ? "0.0.0.0" : "127.0.0.1");
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/list", ex -> handle(ex, this::list));
        server.createContext("/api/item", ex -> handle(ex, () -> item(query(ex).get("name"))));
        server.createContext("/api/decide", ex -> handle(ex, () -> decide(query(ex).get("name"), body(ex))));
        server.createContext("/", this::serveStatic);
        server.start();
        System.out.println("reviewing the suggestions in " + dir + ": open http://localhost:" + port + "/  (ctrl-c to stop)");
    }

    // every suggestion with where it stands: waiting for review, approved, rejected, or sent
    Object list() throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        String[] names = new File(dir, "suggestions").list();
        if (names != null) {
            for (String n : names) {
                if (!n.matches("gp-\\d+\\.json")) continue;
                String name = n.substring(0, n.length() - 5);
                SuggestionDraft d = draft(name);
                CrawlSubmitter.Approval a = approval(name);
                CrawlSubmitter.Submitted s = submitted(name);
                String state = s != null ? "sent" : a == null ? "pending" : a.decision;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", name);
                e.put("problemId", d.problemId);
                e.put("difficulty", d.difficulty);
                e.put("state", state);
                e.put("confidence", d.confidence());
                List<String> moves = new ArrayList<>();
                for (SuggestionDraft.Place p : d.places) for (String m : d.points(p.moves)) moves.add(m.substring(2));
                e.put("moves", moves);
                e.put("positions", d.places.size());
                if (s != null) e.put("sent", s);
                entries.add(e);
                counts.merge(state, 1, Integer::sum);
            }
        }
        // the surest first, then the newest problem
        entries.sort(Comparator.comparingInt((Map<String, Object> e) -> {
            Confidence c = (Confidence) e.get("confidence");
            return c == null ? 0 : -c.low;
        }).thenComparingInt(e -> -(Integer) e.get("problemId")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir", dir.getPath());
        out.put("counts", counts);
        out.put("entries", entries);
        return out;
    }

    // one suggestion with its boards: at each position the board there, and each line added
    // move by move
    Object item(String name) throws Exception {
        check(name);
        SuggestionDraft d = draft(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("draft", d);
        out.put("approval", approval(name));
        out.put("submitted", submitted(name));
        out.put("url", "https://www.goproblems.com/problems/" + d.problemId);
        SmallBoard.Embedded board = SmallBoard.toNineteen(d.originalSgf);
        Node root = new Parser().parse(board.sgf());
        List<Map<String, Object>> places = new ArrayList<>();
        for (SuggestionDraft.Place p : d.places) {
            Map<String, Object> place = new LinkedHashMap<>();
            Node at = play(root, p.at, d);
            place.put("at", d.points(p.at));
            place.put("rows", rows(at, d));
            place.put("moves", coords(p.moves));
            place.put("accepted", coords(p.accepted));
            place.put("level", p.level);
            place.put("confidence", p.confidence);
            List<Map<String, Object>> lines = new ArrayList<>();
            for (List<String> l : p.lines) {
                List<Map<String, Object>> steps = new ArrayList<>();
                Node cur = at;
                for (String m : l) {
                    cur = play(cur, List.of(m), d);
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("move", d.points(List.of(m)).get(0));
                    step.put("x", m.charAt(2) - 'a');
                    step.put("y", m.charAt(3) - 'a');
                    step.put("rows", rows(cur, d));
                    steps.add(step);
                }
                lines.add(Map.of("moves", d.points(l), "steps", steps));
            }
            place.put("lines", lines);
            places.add(place);
        }
        out.put("places", places);
        out.put("columns", COLUMNS.substring(0, d.boardSize));
        try {
            List<Map<String, Object>> tree = new ArrayList<>();
            tree(new Parser().parse(d.suggestedSgf), new Parser().parse(d.originalSgf), -1, d, tree);
            out.put("tree", tree);
        } catch (Exception e) {
            out.put("treeError", String.valueOf(e.getMessage()));
        }
        return out;
    }

    // the problem's tree as the suggestion leaves it, on the problem's own board, node by node
    // with each node's parent before it: its move, whether the suggestion adds it (was, the
    // same node in the problem as the site has it, is null), whether it ends a correct line,
    // its comment and the board there
    private static void tree(Node node, Node was, int parent, SuggestionDraft d, List<Map<String, Object>> out) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("parent", parent);
        MoveAction m = node.getMoveAction();
        if (m != null) {
            String color = m.stone == Intersection.BLACK ? "B " : m.stone == Intersection.WHITE ? "W " : "";
            if (node.board.inBoard(m.loc.x, m.loc.y)) {
                n.put("move", color + COLUMNS.charAt(m.loc.x) + (d.boardSize - m.loc.y));
                n.put("x", m.loc.x);
                n.put("y", m.loc.y);
            } else {
                n.put("move", color + "pass");
            }
        }
        n.put("added", was == null);
        n.put("right", node.result == Intersection.RIGHT);
        if (node.comment != null && !node.comment.isBlank()) n.put("comment", node.comment.strip());
        n.put("rows", rows(node, d.boardSize, 0, 0));
        int index = out.size();
        out.add(n);
        for (Node c : node.babies) tree(c, was == null ? null : sameMove(was, c), index, d, out);
    }

    // the child of the node that plays the move the other node plays, or null
    private static Node sameMove(Node node, Node other) {
        for (Node c : node.babies) {
            if (Objects.equals(moveKey(c), moveKey(other))) return c;
        }
        return null;
    }

    private static String moveKey(Node node) {
        MoveAction m = node.getMoveAction();
        return m == null ? null : m.stone + " " + m.loc.x + " " + m.loc.y;
    }

    Object decide(String name, String body) throws Exception {
        check(name);
        if (submitted(name) != null) throw new IllegalStateException(name + " went to the site already");
        Decision dec = RunRecorder.GSON.fromJson(body, Decision.class);
        File f = new File(dir, "approvals/" + name + ".json");
        if ("pending".equals(dec.decision)) {
            Files.deleteIfExists(f.toPath());
            return Map.of("ok", true);
        }
        if (!"approved".equals(dec.decision) && !"rejected".equals(dec.decision)) {
            throw new IllegalArgumentException("a decision is approved, rejected or pending");
        }
        CrawlSubmitter.Approval a = new CrawlSubmitter.Approval();
        a.decision = dec.decision;
        a.comment = dec.comment;
        a.note = dec.note;
        a.by = by;
        a.time = LocalDateTime.now().toString();
        f.getParentFile().mkdirs();
        RunRecorder.writeAtomically(f, RunRecorder.GSON.toJson(a));
        return Map.of("ok", true);
    }

    // the moves (sgf, on the problem's board) played on from the node, on the 19x19 board the
    // problem sits in
    private static Node play(Node from, List<String> moves, SuggestionDraft d) throws Exception {
        Node node = from;
        for (String m : moves) {
            Point p = new Point(m.charAt(2) - 'a' + d.dx, m.charAt(3) - 'a' + d.dy);
            if (!node.legalMove(p)) throw new IllegalStateException("illegal " + m + " in " + d.problem);
            node = node.addBasicMove(p.x, p.y);
        }
        return node;
    }

    // the problem's board at the node, a row of X, O and . each, top first
    private static List<String> rows(Node node, SuggestionDraft d) {
        return rows(node, d.boardSize, d.dx, d.dy);
    }

    // the same for a board of the size that sits at dx, dy on the node's
    private static List<String> rows(Node node, int size, int dx, int dy) {
        List<String> out = new ArrayList<>();
        for (int y = 0; y < size; y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < size; x++) {
                int s = node.board.board[x + dx][y + dy].stone;
                sb.append(s == Intersection.BLACK ? 'X' : s == Intersection.WHITE ? 'O' : '.');
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static List<int[]> coords(List<String> moves) {
        List<int[]> out = new ArrayList<>();
        for (String m : moves) out.add(new int[]{m.charAt(2) - 'a', m.charAt(3) - 'a'});
        return out;
    }

    private void check(String name) {
        if (name == null || !name.matches("gp-\\d+")) throw new IllegalArgumentException("no such suggestion: " + name);
    }

    private SuggestionDraft draft(String name) throws IOException {
        return RunRecorder.GSON.fromJson(Files.readString(new File(dir, "suggestions/" + name + ".json").toPath()), SuggestionDraft.class);
    }

    private CrawlSubmitter.Approval approval(String name) throws IOException {
        File f = new File(dir, "approvals/" + name + ".json");
        return f.exists() ? RunRecorder.GSON.fromJson(Files.readString(f.toPath()), CrawlSubmitter.Approval.class) : null;
    }

    private CrawlSubmitter.Submitted submitted(String name) throws IOException {
        File f = new File(dir, "submitted/" + name + ".json");
        return f.exists() ? RunRecorder.GSON.fromJson(Files.readString(f.toPath()), CrawlSubmitter.Submitted.class) : null;
    }

    private interface Handler {
        Object get() throws Exception;
    }

    private void handle(HttpExchange ex, Handler h) throws IOException {
        int code = 200;
        Object result;
        try {
            result = h.get();
        } catch (Exception e) {
            e.printStackTrace();
            code = 400;
            result = Map.of("error", String.valueOf(e.getMessage()));
        }
        byte[] out = RunRecorder.GSON.toJson(result).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return out;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8");

    // the page: index.html, app.js and style.css from the crawl resources; from the repo the
    // source files are served, so edits show on reload
    private void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        String name = path.substring(1);
        String ext = name.substring(name.lastIndexOf('.') + 1);
        byte[] data = null;
        if (!name.contains("/") && !name.contains("..") && TYPES.containsKey(ext)) {
            File src = new File("src/main/resources/crawl/" + name);
            if (src.isFile()) {
                data = Files.readAllBytes(src.toPath());
            } else {
                try (InputStream in = CrawlReviewServer.class.getResourceAsStream("/crawl/" + name)) {
                    if (in != null) data = in.readAllBytes();
                }
            }
        }
        if (data == null) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        ex.getResponseHeaders().set("Content-Type", TYPES.get(ext));
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }
}
