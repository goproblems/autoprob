package autoprob.validate.review;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import autoprob.validate.DecisionRecord;
import autoprob.validate.Positions;
import autoprob.validate.ProblemRecord;
import autoprob.validate.RunRecorder;
import autoprob.validate.cases.CaseFile;
import autoprob.validate.cases.CaseStore;
import autoprob.validate.cases.DecisionCase;

// cmd=review: a local web page for judging a redo run's decisions. each judgment is
// saved straight into the case library (cases/<problem>.json), with the frozen
// isolation and the candidates the generator saw, so it becomes a replayable test case.
//
// run=<id|dir|latest> picks the run, review.port the port (default 8765), review.by
// the name stored on judgments (default the login name). no engine needed.
public class ReviewServer {
    private final Properties props;
    private final File runDir;
    private final RunRecorder.Manifest manifest;
    private final CaseStore store;
    private final String by;

    // the request body for saving a case
    static class CaseUpdate {
        List<String> at;
        DecisionCase.Expect expect;
        List<String> tags;
        String note;
    }

    public ReviewServer(Properties props) throws IOException {
        this.props = props;
        this.runDir = RunRecorder.resolveRun(props);
        this.manifest = RunRecorder.readManifest(runDir);
        this.store = new CaseStore(props);
        this.by = props.getProperty("review.by", System.getProperty("user.name", "unknown"));
    }

    public void run() throws Exception {
        int port = Integer.parseInt(props.getProperty("review.port", "8765"));
        // under WSL only wildcard binds are forwarded to windows localhost; WSL's NAT keeps
        // them off the LAN. elsewhere stay on loopback.
        String host = props.getProperty("review.host", System.getenv("WSL_DISTRO_NAME") != null ? "0.0.0.0" : "127.0.0.1");
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/run", ex -> handle(ex, () -> runSummary()));
        server.createContext("/api/problem", ex -> handle(ex, () -> problem(query(ex).get("name"))));
        server.createContext("/api/case", ex -> handle(ex, () -> saveCase(query(ex).get("name"), body(ex))));
        server.createContext("/api/case-delete", ex -> handle(ex, () ->
                deleteCase(query(ex).get("name"), query(ex).get("key"))));
        server.createContext("/api/accept-line", ex -> handle(ex, () -> acceptLine(query(ex).get("name"), true)));
        server.createContext("/api/unaccept-line", ex -> handle(ex, () -> acceptLine(query(ex).get("name"), false)));
        server.createContext("/", this::serveStatic);
        server.start();
        System.out.println("reviewing run " + manifest.id + " (" + manifest.problems.size() + " problems), saving cases to "
                + store.getDir().getPath());
        System.out.println("open http://localhost:" + port + "/  (ctrl-c to stop)");
    }

    private interface Handler {
        Object get() throws Exception;
    }

    private void handle(HttpExchange ex, Handler h) throws IOException {
        try {
            byte[] out = RunRecorder.GSON.toJson(h.get()).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        } catch (Exception e) {
            e.printStackTrace();
            byte[] out = RunRecorder.GSON.toJson(Map.of("error", String.valueOf(e.getMessage())))
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(400, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        }
    }

    // the run and, per problem, its scores and how much of it has been judged
    private Object runSummary() throws Exception {
        List<Map<String, Object>> problems = new ArrayList<>();
        for (String name : manifest.problems) {
            ProblemRecord rec = RunRecorder.readProblem(runDir, name);
            CaseFile cf = store.load(name);
            int judged = 0, failing = 0;
            for (DecisionRecord d : rec.decisions) {
                DecisionCase c = cf == null ? null : cf.find(d.key());
                if (c == null) continue;
                judged++;
                if (!c.passes(d)) failing++;
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("problem", name);
            p.put("error", rec.error);
            p.put("metrics", rec.metrics);
            p.put("decisions", rec.decisions.size());
            p.put("judged", judged);
            p.put("failing", failing);
            p.put("accepted", cf != null && !lines(rec).isEmpty() && cf.acceptedLines.containsAll(lines(rec)));
            p.put("tree", rec.tree);
            problems.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("manifest", manifest);
        out.put("problems", problems);
        out.put("by", by);
        return out;
    }

    // a problem's record, the board at every position of the generated and author trees
    // (keyed like decisions: the moves from the root, or "root"), and its cases
    private Object problem(String name) throws Exception {
        ProblemRecord rec = RunRecorder.readProblem(runDir, check(name));
        Map<String, Object> out = new LinkedHashMap<>();
        rec.generatedLines = lines(rec);
        out.put("record", rec);
        if (rec.isolation != null) {
            Map<String, List<String>> boards = new LinkedHashMap<>();
            List<List<String>> all = new ArrayList<>(rec.generatedLines);
            all.addAll(rec.refs);
            for (DecisionRecord d : rec.decisions) all.add(d.at);
            for (List<String> line : all) {
                List<List<String>> steps = Positions.boards(rec.isolation.toIsolated(), line);
                for (int i = 0; i < steps.size(); i++) {
                    boards.putIfAbsent(DecisionRecord.key(line.subList(0, i)), steps.get(i));
                }
            }
            out.put("boards", boards);
        }
        CaseFile cf = store.load(name);
        out.put("cases", cf == null ? emptyCases(name) : cf);
        return out;
    }

    // the generated tree's lines; runs from before trees only have the main line
    private static List<List<String>> lines(ProblemRecord rec) {
        if (rec.generatedLines != null && !rec.generatedLines.isEmpty()) return rec.generatedLines;
        List<List<String>> out = new ArrayList<>();
        if (rec.generated != null && !rec.generated.isEmpty()) out.add(rec.generated);
        return out;
    }

    private Object saveCase(String name, String body) throws Exception {
        ProblemRecord rec = RunRecorder.readProblem(runDir, check(name));
        CaseUpdate update = RunRecorder.GSON.fromJson(body, CaseUpdate.class);
        String key = DecisionRecord.key(update.at);
        DecisionRecord decision = null;
        for (DecisionRecord d : rec.decisions) {
            if (d.key().equals(key)) decision = d;
        }
        if (decision == null) throw new IllegalArgumentException("no decision at " + key + " in this run");

        CaseFile cf = caseFile(rec);
        DecisionCase c = cf.find(key);
        if (c == null) {
            c = new DecisionCase();
            c.at = new ArrayList<>(update.at);
            c.created = LocalDate.now().toString();
            cf.decisions.add(c);
        }
        c.toMove = decision.toMove;
        c.role = decision.role;
        c.expect = update.expect == null ? new DecisionCase.Expect() : update.expect;
        c.tags = update.tags == null ? new ArrayList<>() : update.tags;
        c.note = update.note == null || update.note.isBlank() ? null : update.note;
        c.source = DecisionCase.REVIEW;
        c.by = by;
        // the judgment was made looking at this run's decision, so keep its context
        c.isolation = rec.isolation;
        c.observed = observed(decision);
        store.save(cf);
        return cf;
    }

    private Object deleteCase(String name, String key) throws Exception {
        CaseFile cf = store.load(check(name));
        if (cf == null) return emptyCases(name);
        cf.decisions.removeIf(c -> c.key().equals(key));
        store.save(cf);
        return cf;
    }

    // accept (or un-accept) the generated tree as a whole: its lines become acceptable,
    // and every decision in it that has no case yet is marked right as generated
    private Object acceptLine(String name, boolean accept) throws Exception {
        ProblemRecord rec = RunRecorder.readProblem(runDir, check(name));
        CaseFile cf = caseFile(rec);
        if (!accept) {
            cf.acceptedLines.removeAll(lines(rec));
            store.save(cf);
            return cf;
        }
        for (List<String> line : lines(rec)) {
            if (!cf.acceptedLines.contains(line)) cf.acceptedLines.add(new ArrayList<>(line));
        }
        for (DecisionRecord d : rec.decisions) {
            if (cf.find(d.key()) != null) continue;
            DecisionCase c = new DecisionCase();
            c.at = new ArrayList<>(d.at);
            c.toMove = d.toMove;
            c.role = d.role;
            c.expect.choose.addAll(d.chosenMoves());
            c.source = DecisionCase.REVIEW;
            c.by = by;
            c.created = LocalDate.now().toString();
            c.isolation = rec.isolation;
            c.observed = observed(d);
            cf.decisions.add(c);
        }
        store.save(cf);
        return cf;
    }

    private CaseFile caseFile(ProblemRecord rec) throws IOException {
        CaseFile cf = store.loadOrCreate(rec.problem);
        if (cf.problemFile == null) {
            cf.problemFile = rec.file;
            cf.problemSha = rec.sha;
        }
        return cf;
    }

    private DecisionCase.Observed observed(DecisionRecord d) {
        DecisionCase.Observed o = new DecisionCase.Observed();
        o.run = manifest.id;
        o.commit = manifest.commit;
        o.model = manifest.model;
        o.decision = d;
        return o;
    }

    private CaseFile emptyCases(String name) {
        CaseFile cf = new CaseFile();
        cf.problem = name;
        return cf;
    }

    private String check(String name) {
        if (name == null || !manifest.problems.contains(name)) {
            throw new IllegalArgumentException("no problem " + name + " in run " + manifest.id);
        }
        return name;
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

    // the page itself: index.html, app.js and style.css from the review resources.
    // when run from the repo the source files are served directly, so edits show on reload.
    private void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        String name = path.substring(1);
        String ext = name.substring(name.lastIndexOf('.') + 1);
        byte[] data = null;
        if (!name.contains("/") && !name.contains("..") && TYPES.containsKey(ext)) {
            File src = new File("src/main/resources/review/" + name);
            if (src.isFile()) {
                data = Files.readAllBytes(src.toPath());
            } else {
                try (InputStream in = ReviewServer.class.getResourceAsStream("/review/" + name)) {
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
