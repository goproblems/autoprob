package autoprob.validate;

import java.awt.Point;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.google.gson.Gson;

import autoprob.ApiClient;
import autoprob.KataBrain;
import autoprob.api.Problem;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.CommentAction;
import autoprob.go.action.MoveAction;
import autoprob.go.parse.Parser;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;

// cmd=missing-variations: looks for correct lines a problem is missing, and stops at the
// first one. it walks every position on the author's correct lines where the solver is to
// play, root first, and finds every move that works there the way redo does: katago's
// plausible moves and natural human moves are each played and analyzed, and a move works
// when its lead after is within redo.alt_score_tolerance of the best reliable move's and
// it is near the fight. a working move the author's tree does not accept, and at least as
// good as the author's own move, is looked at again with a deeper search. if it holds, its
// line is generated with the redo generator, and the command stops and shows it, writing
// the problem with the line added to missing.dir.
//
// sources: path=<sgf file or directory>, id=<goproblems ids, comma separated>, or neither
// to scan the goproblems database by id from where the last scan stopped. problems from
// the site are only checked when they are life and death and marked standard.
public class MissingVariationFinder {
    private static final DecimalFormat df = new DecimalFormat("+0.00;-0.00");

    private final Properties props;
    private final ChoiceParams params;
    private final File dir;
    private final int visits;
    private final int visitsRoot;
    private final int verifyVisits;
    private final double tolerance;
    private final boolean opponentThreats;
    private final boolean allowKo;
    private final boolean skipKo;
    private KataBrain brain;

    // a position on the author's correct lines where the solver is to play, and the moves
    // the author accepts there
    // (the author's lines from there too)
    record Position(List<String> at, Set<String> correct, List<List<String>> lines) {}

    // a deeper search after some moves: the solver's lead, katago's own lines from there, and
    // the analysis itself, whose ownership gives the life status
    record Deep(double lead, List<List<String>> lines, KataAnalysisResult kar) {}

    // what a scan of the site remembers between runs
    static class State {
        int next = 1;     // the next problem id to try
        int checked;      // problems checked
        int skipped;      // ids passed over: no problem, other genres, not standard, no correct line
        String lastFound; // the last problem a missing variation was found in
        String updated;
    }

    // what checking one problem came to
    static class Outcome {
        String problem;
        String status; // found, clean, skipped or error
        String reason;
        int positions; // positions on the author's lines checked
        int koSkipped; // of those, passed over because the author's line goes through a ko
        long seconds;
        Finding finding;
    }

    // the first position found with working moves the problem lacks
    static class Finding {
        String problem;
        String difficulty;
        List<String> at;                               // moves from the root
        List<String> accepted = new ArrayList<>();     // the moves the problem accepts there
        List<FoundMove> moves = new ArrayList<>();
        String sgf;                                    // the problem with the new lines added
    }

    static class FoundMove {
        String move;
        String source;          // katago, human or scan: how the move came up
        String authorMove;      // the author's best move at the position, for comparison
        double lead;            // the solver's lead after the move, at redo.visits
        double authorLead;      // the same for the author's move
        double deepLead;        // both again at missing.verify_visits
        double deepAuthorLead;
        Double prior;
        Integer visits;
        Double humanPolicy;
        Integer stakeDist;
        boolean inFileAsWrong;  // the problem has the move, on a line that is not correct
        String ko;              // the move that takes a ko on the generated lines, or on katago's own
        boolean koOnKatagoLine; // only katago's own lines take it
        boolean authorKo;       // the author's lines from the position take a ko too
        boolean koWithThreats;  // the board gives the opponent ko threats, and the move works anyway
        // the problem's own answers to the move on its wrong line, with the solver's lead
        // after each (deeper search): none of them refutes it
        Map<String, Double> refutations = new LinkedHashMap<>();
        List<List<String>> lines = new ArrayList<>(); // generated lines from the position, starting with the move
    }

    public MissingVariationFinder(Properties props) {
        this.props = new Properties();
        this.props.putAll(props);
        // every working move has to be measured, whatever redo is set to, including moves
        // katago and the human policy both rate low: every point near the stakes gets a look
        this.props.setProperty("redo.solver_alternatives", "true");
        if (!props.containsKey("redo.scan_dist")) this.props.setProperty("redo.scan_dist", "2");
        this.params = ChoiceParams.fromProps(this.props);
        this.dir = new File(props.getProperty("missing.dir", "missing_variations"));
        this.visits = Integer.parseInt(props.getProperty("redo.visits", "1000"));
        this.visitsRoot = Integer.parseInt(props.getProperty("redo.visits_root", "2000"));
        this.verifyVisits = Integer.parseInt(props.getProperty("missing.verify_visits", String.valueOf(4 * visits)));
        this.tolerance = params.altScoreTolerance;
        // a problem whose own line takes no ko is checked on a board with ko threats for the
        // opponent (katrain's tsumego frame), where a move that needs to win a ko fails
        this.opponentThreats = Boolean.parseBoolean(props.getProperty("missing.opponent_ko_threats", "true"));
        // on a board without threats for the opponent, a move that only works through a ko
        // can look as good as an unconditional one: dropped by its lines unless the author's
        // line needs a ko too
        this.allowKo = Boolean.parseBoolean(props.getProperty("missing.allow_ko", "false"));
        // a problem whose own line goes through a ko is checked on a board with ko threats for
        // the solver, or with skip_ko its ko positions are passed over
        this.skipKo = Boolean.parseBoolean(props.getProperty("missing.skip_ko", "false"));
    }

    public void run() throws Exception {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        brain = new KataBrain(props);
        try {
            String path = props.getProperty("path");
            String ids = props.getProperty("id");
            if (path != null) {
                files(new File(path));
            } else if (ids != null) {
                ids(ids);
            } else {
                scan();
            }
        } finally {
            brain.stopKataBrain();
        }
    }

    private void files(File f) throws Exception {
        if (!f.exists()) {
            throw new IOException("no such file or directory: " + f);
        }
        List<File> files = new ArrayList<>();
        if (f.isDirectory()) {
            File[] listing = f.listFiles((d, name) -> name.endsWith(".sgf"));
            if (listing != null) files.addAll(Arrays.asList(listing));
            files.sort(Comparator.comparing(File::getName));
        } else {
            files.add(f);
        }
        for (File file : files) {
            String sgf = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Outcome o = check(file.getName().replaceAll("\\.sgf$", ""), sgf, null);
            log(o);
            if (o.finding != null) {
                show(o.finding);
                return;
            }
        }
        System.out.println("no missing variation found in " + files.size() + " problem" + (files.size() == 1 ? "" : "s"));
    }

    private void ids(String list) throws Exception {
        for (String s : list.split(",")) {
            Outcome o = site(Integer.parseInt(s.trim()));
            log(o);
            if (o.finding != null) {
                show(o.finding);
                return;
            }
        }
        System.out.println("no missing variation found");
    }

    // walk the site's problems by id, starting where the last scan stopped, until one has a
    // missing variation. the state is saved after every problem, so a stopped scan resumes
    // where it was and nothing is checked twice
    private void scan() throws Exception {
        File stateFile = new File(dir, "state.json");
        State state = stateFile.exists()
                ? RunRecorder.GSON.fromJson(Files.readString(stateFile.toPath()), State.class) : new State();
        if (props.getProperty("start") != null) state.next = Integer.parseInt(props.getProperty("start"));
        int maxChecks = Integer.parseInt(props.getProperty("scan.max", "0"));
        int maxMisses = Integer.parseInt(props.getProperty("scan.max_misses", "500"));
        long delay = Long.parseLong(props.getProperty("api.delay_ms", "100"));
        System.out.println("scanning goproblems.com from problem " + state.next
                + " for missing variations (life and death problems marked standard)");
        int misses = 0, checks = 0;
        while (true) {
            int id = state.next;
            Outcome o = site(id);
            log(o);
            state.next = id + 1;
            if ("skipped".equals(o.status)) state.skipped++;
            else state.checked++;
            if (o.finding != null) state.lastFound = o.problem;
            state.updated = LocalDateTime.now().toString();
            RunRecorder.writeAtomically(stateFile, RunRecorder.GSON.toJson(state));
            if (o.finding != null) {
                show(o.finding);
                System.out.println("the next scan starts at problem " + state.next);
                return;
            }
            misses = "no such problem".equals(o.reason) ? misses + 1 : 0;
            if (!"skipped".equals(o.status)) checks++;
            if (maxChecks > 0 && checks >= maxChecks) {
                System.out.println("checked " + checks + " problem" + (checks == 1 ? "" : "s")
                        + " without finding a missing variation; the next scan starts at problem " + state.next);
                return;
            }
            if (misses >= maxMisses) {
                System.out.println("no problems at the last " + misses + " ids, so this looks like the end of the"
                        + " database; the next scan starts at problem " + state.next);
                return;
            }
            if (delay > 0) Thread.sleep(delay);
        }
    }

    // fetch a problem from the site and check it, if it qualifies
    private Outcome site(int id) throws Exception {
        Outcome o = new Outcome();
        o.problem = "gp-" + id;
        Problem p = fetch(id);
        String why = p == null ? "no such problem" : unsuitable(p);
        if (why != null) {
            o.status = "skipped";
            o.reason = why;
            System.out.println(o.problem + ": skipped, " + why);
            return o;
        }
        return check(o.problem, p.sgf, p.rank == null ? null : p.rank.value + " " + p.rank.unit);
    }

    // why a problem from the site is not checked, or null when it is
    static String unsuitable(Problem p) {
        String genre = p.genre != null ? p.genre : p.specificGenre;
        if (!"life and death".equalsIgnoreCase(genre)) return "genre " + genre;
        if (!Boolean.TRUE.equals(p.isStandard)) return "not marked standard";
        if (p.sgf == null || p.sgf.isBlank()) return "no sgf";
        return null;
    }

    // a problem from the site, or null when there is none to have at that id
    private Problem fetch(int id) throws Exception {
        ApiClient api = new ApiClient();
        String url = api.buildUrl("api.problem.details", Map.of("id", String.valueOf(id)), null, props);
        String key = props.getProperty("apikey");
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setRequestProperty("Accept", "application/json");
                if (key != null && !key.isBlank()) conn.setRequestProperty("X-Api-Key", key);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                int code = conn.getResponseCode();
                if (code == 404 || code == 403 || code == 410) return null;
                if (code >= 200 && code < 300) {
                    return RunRecorder.GSON.fromJson(api.readResponseContent(conn, code), Problem.class);
                }
                last = new IOException("problem " + id + ": http " + code);
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(2000L * attempt);
        }
        throw last;
    }

    // check one problem; a problem that cannot be checked is an outcome, not an exception
    Outcome check(String name, String sgf, String difficulty) {
        Outcome o = new Outcome();
        o.problem = name;
        long start = System.currentTimeMillis();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                o.finding = find(name, sgf, difficulty, o);
                if (o.status == null) o.status = o.finding != null ? "found" : "clean";
                break;
            } catch (Exception e) {
                e.printStackTrace();
                o.status = "error";
                o.reason = e.getMessage() == null ? e.toString() : e.getMessage();
                if (brain.isAlive()) break;
                // katago died: restart it and try the problem once more
                System.out.println("katago died, restarting the engine");
                brain.stopKataBrain();
                try {
                    brain = new KataBrain(props);
                } catch (Exception restart) {
                    throw new RuntimeException("cannot restart katago", restart);
                }
                o.positions = 0;
                o.status = null;
                o.reason = null;
            }
        }
        o.seconds = (System.currentTimeMillis() - start) / 1000;
        System.out.println(name + ": " + o.status + (o.reason != null ? " (" + o.reason + ")" : "")
                + ", " + o.positions + " position" + (o.positions == 1 ? "" : "s") + " checked"
                + (o.koSkipped > 0 ? " (" + o.koSkipped + " not judged for a ko)" : "") + " in " + o.seconds + " s");
        return o;
    }

    private Finding find(String name, String sgf, String difficulty, Outcome o) throws Exception {
        System.out.println();
        System.out.println("=== " + name + (difficulty != null ? " (" + difficulty + ")" : "") + " ===");
        Node root = new Parser().parse(sgf);
        if (difficulty == null) difficulty = ProblemRedoer.difficulty(root);
        List<MovePath> refs = MovePath.extractRightPaths(root);
        if (refs.isEmpty()) {
            o.status = "skipped";
            o.reason = "no correct line";
            return null;
        }
        // a board without ko threats cannot judge a ko the way a game would. a problem whose
        // own line goes through one gets threats for the solver, so the author's ko answer
        // counts as won; any other gets threats for the opponent, so a move that needs to win
        // a ko fails and a clean answer comes out ahead of it, as on the site
        Properties isolation = new Properties();
        isolation.putAll(props);
        List<List<String>> authorLines = new ArrayList<>();
        for (MovePath ref : refs) authorLines.add(ProblemRecord.moves(ref));
        boolean koProblem = koIn(root, authorLines) != null;
        if (koProblem && !skipKo) {
            isolation.setProperty("redo.ko_threats", "true");
            isolation.setProperty("redo.ko_threats_for", "solver");
            System.out.println("the problem's line goes through a ko, so the board gets ko threats for the solver");
        } else if (opponentThreats) {
            isolation.setProperty("redo.ko_threats", "true");
            isolation.setProperty("redo.ko_threats_for", "opponent");
            System.out.println("the board gets ko threats for the opponent, so a move that needs a ko fails");
        }
        ProblemIsolator.IsolatedProblem iso = new ProblemIsolator(isolation, brain).isolate(root);
        DecisionFeatureExtractor extractor = new DecisionFeatureExtractor(props, brain, iso, params);
        for (Position pos : authorPositions(refs, IsolationRecord.colorLetter(iso.solverColor))) {
            o.positions++;
            if (skipKo) {
                Node at;
                try {
                    at = Positions.play(iso, pos.at());
                } catch (IllegalArgumentException e) {
                    at = null; // findAt reports it
                }
                if (at != null && koIn(at, pos.lines()) != null) {
                    System.out.println("  " + (pos.at().isEmpty() ? "at the start" : "after " + String.join(" ", pos.at()))
                            + ": ko, not judged (the problem's line goes through a ko)");
                    o.koSkipped++;
                    continue;
                }
            }
            Finding f = findAt(name, sgf, root, iso, extractor, pos);
            if (f != null) {
                f.difficulty = difficulty;
                return f;
            }
        }
        return null;
    }

    // the working moves the problem lacks at one position, or null
    private Finding findAt(String name, String sgf, Node root, ProblemIsolator.IsolatedProblem iso,
                           DecisionFeatureExtractor extractor, Position pos) throws Exception {
        String where = pos.at().isEmpty() ? "at the start" : "after " + String.join(" ", pos.at());
        Node node;
        try {
            node = Positions.play(iso, pos.at());
        } catch (IllegalArgumentException e) {
            System.out.println("  " + where + ": cannot replay the position (" + e.getMessage() + ")");
            return null;
        }
        DecisionRecord rec = extractor.solver(node, pos.at(), pos.at().isEmpty() ? visitsRoot : visits);
        double best = rec.error == null ? ChoiceRule.bestAfterLead(rec) : Double.NaN;
        if (Double.isNaN(best)) {
            System.out.println("  " + where + ": no moves measured" + (rec.error != null ? " (" + rec.error + ")" : ""));
            return null;
        }
        // the author's moves, measured the same way: the one that comes closest to katago's
        // best move's life status, then the best lead
        CandidateFeatures author = null;
        for (String m : pos.correct()) {
            CandidateFeatures a = extractor.measure(node, rec, m);
            if (a != null && (author == null || closer(a, author))) author = a;
        }
        if (author == null) {
            System.out.println("  " + where + ": cannot measure the author's moves");
            return null;
        }
        String authorMove = author.move;
        double authorLead = author.afterLead;
        // when the author's own move does not reach the life status katago's best does, the
        // isolation misreads the position and no comparison is safe
        if (!ChoiceRule.works(author, best, params, iso.stakes)) {
            System.out.println("  " + where + ": skipped, katago thinks the author's " + pt(authorMove) + " ("
                    + df.format(authorLead) + ") does not reach the result of its best move (" + df.format(best) + ")"
                    + (author.statusDeltas == null ? "" : changedFate(author.statusDeltas)));
            return null;
        }
        CandidateFeatures top = rec.candidates.get(0);
        Set<String> threatPoints = new HashSet<>();
        for (Point t : iso.threats) threatPoints.add(Intersection.toGTPloc(t.x, t.y, 19));
        List<CandidateFeatures> missing = new ArrayList<>();
        for (CandidateFeatures c : rec.candidates) {
            if (pos.correct().contains(c.move) || c.point().equals("pass")) continue;
            if (threatPoints.contains(c.point())) continue; // a ko threat, not an answer to the problem
            if (c != top && !ChoiceRule.nearFight(c, params)) continue;
            if (!ChoiceRule.works(c, best, params, iso.stakes)) continue;
            // without a status (older measurements), points against the author's move
            if (c.statusDeltas == null && c.afterLead < authorLead - tolerance) continue;
            missing.add(c);
        }
        if (missing.isEmpty()) {
            System.out.println("  " + where + ": nothing missing (the problem accepts "
                    + String.join(", ", pos.correct().stream().map(MissingVariationFinder::pt).toList()) + ")");
            return null;
        }

        // a deeper look at each, against the author's move: the same life status (a stone or
        // two aside), and a lead within the status margin of the author's
        Deep authorDeep = deepLook(extractor, node, List.of(authorMove));
        double deepAuthor = authorDeep.lead();
        Set<String> inFile = sgfMoves(root, pos.at());
        boolean authorKo = koIn(node, pos.lines()) != null;
        // with ko threats for the opponent on the board, a move that needs to win a ko already
        // fails above, and a ko taken on its lines is one it can do without (or for points):
        // only a board without them needs the lines checked for a ko
        boolean koByLines = !allowKo && !(!iso.threats.isEmpty() && iso.threatColor != iso.solverColor);
        Finding f = null;
        for (CandidateFeatures c : missing) {
            Deep deep = deepLook(extractor, node, List.of(c.move));
            String why = fallsShort(node, iso, extractor, deep, authorDeep);
            if (why != null) {
                System.out.println("  " + where + ": " + pt(c.move) + " seemed to work (" + df.format(c.afterLead)
                        + ") but a deeper look says no: " + why);
                continue;
            }
            List<List<String>> katagoLines = new ArrayList<>(deep.lines());
            // the problem has the move on a wrong line: each of its answers there must leave
            // the solver a way through, or the author is right
            List<String> afterMove = new ArrayList<>(pos.at());
            afterMove.add(c.move);
            Map<String, Double> refutations = new LinkedHashMap<>();
            String refutedBy = null;
            String refutedHow = null;
            for (String answer : sgfMoves(root, afterMove)) {
                Deep after = deepLook(extractor, node, List.of(c.move, answer));
                refutations.put(answer, after.lead());
                katagoLines.addAll(after.lines());
                refutedHow = fallsShort(node, iso, extractor, after, authorDeep);
                if (refutedHow != null) {
                    refutedBy = answer;
                    break;
                }
            }
            if (refutedBy != null) {
                System.out.println("  " + where + ": " + pt(c.move) + " seemed to work, but the problem's answer "
                        + pt(refutedBy) + " refutes it: " + refutedHow);
                continue;
            }
            // katago's own lines are checked for a ko before any are generated: the generator
            // follows its own picks of answers and can miss the one that makes a ko
            String katagoKo = koIn(node, katagoLines);
            if (katagoKo != null && koByLines && !authorKo) {
                System.out.println("  " + where + ": " + pt(c.move) + " only works through a ko (on katago's line "
                        + pt(katagoKo) + " takes it) and the problem's own line needs none, so it is not counted (missing.allow_ko=true keeps it)");
                continue;
            }
            FoundMove m = new FoundMove();
            m.move = c.move;
            m.source = c.source;
            m.authorMove = authorMove;
            m.lead = c.afterLead;
            m.authorLead = authorLead;
            m.deepLead = deep.lead();
            m.deepAuthorLead = deepAuthor;
            m.prior = c.prior;
            m.visits = c.visits;
            m.humanPolicy = c.humanPolicy;
            m.stakeDist = c.stakeDist;
            m.inFileAsWrong = inFile.contains(c.move);
            m.refutations = refutations;
            System.out.println("  " + where + ": " + pt(c.move) + " works too, generating its line");
            m.lines.addAll(linesFrom(iso, node, List.of(), c.move));
            // and the solver's way through each of the problem's own answers to it
            for (String answer : refutations.keySet()) {
                boolean covered = m.lines.stream().anyMatch(l -> l.size() > 1 && l.get(1).equals(answer));
                if (!covered) m.lines.addAll(linesFrom(iso, node, List.of(c.move, answer), null));
            }
            m.ko = koIn(node, m.lines);
            if (m.ko == null && katagoKo != null) {
                m.ko = katagoKo;
                m.koOnKatagoLine = true;
            }
            m.authorKo = m.ko != null && authorKo;
            m.koWithThreats = m.ko != null && !m.authorKo && !iso.threats.isEmpty() && iso.threatColor != iso.solverColor;
            if (m.ko != null && koByLines && !m.authorKo) {
                System.out.println("  " + where + ": " + pt(c.move) + " only works through a ko (" + pt(m.ko)
                        + " takes it) and the problem's own line needs none, so it is not counted (missing.allow_ko=true keeps it)");
                continue;
            }
            if (f == null) {
                f = new Finding();
                f.problem = name;
                f.at = pos.at();
                f.accepted.addAll(pos.correct());
            }
            f.moves.add(m);
        }
        if (f != null) {
            f.sgf = writeSgf(name, sgf, f).getPath();
            Files.writeString(new File(dir, name + "_missing.json").toPath(), RunRecorder.GSON.toJson(f) + "\n");
        }
        return f;
    }

    // every position on the author's correct lines where the solver is to play, root first,
    // with the moves the author accepts there
    static List<Position> authorPositions(List<MovePath> refs, String solver) {
        Map<String, Position> byKey = new LinkedHashMap<>();
        for (MovePath ref : refs) {
            List<String> moves = ProblemRecord.moves(ref);
            for (int i = 0; i < moves.size(); i++) {
                if (!moves.get(i).startsWith(solver + " ")) continue;
                List<String> at = new ArrayList<>(moves.subList(0, i));
                Position pos = byKey.computeIfAbsent(DecisionRecord.key(at),
                        k -> new Position(at, new LinkedHashSet<>(), new ArrayList<>()));
                pos.correct().add(moves.get(i));
                pos.lines().add(new ArrayList<>(moves.subList(i, moves.size())));
            }
        }
        List<Position> out = new ArrayList<>(byKey.values());
        out.sort(Comparator.comparingInt(p -> p.at().size()));
        return out;
    }

    // the move that takes a ko on any of the lines played from the position, or null
    static String koIn(Node from, List<List<String>> lines) throws Exception {
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

    // the moves the problem file has at a position, on correct lines or not
    static Set<String> sgfMoves(Node root, List<String> at) {
        Node cur = root;
        for (String m : at) {
            cur = cur.getChildWithMove(Intersection.gtp2point(m.substring(2)));
            if (cur == null) return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Node child : cur.babies) {
            MoveAction ma = child.getMoveAction();
            if (ma == null || ma.loc.x < 0 || ma.loc.x >= 19 || ma.loc.y < 0 || ma.loc.y >= 19) continue;
            out.add(IsolationRecord.colorLetter(ma.stone) + " " + Intersection.toGTPloc(ma.loc.x, ma.loc.y, 19));
        }
        return out;
    }

    // is a closer to the life status of katago's best move than b: fewer stones changing
    // fate, then the better lead
    private boolean closer(CandidateFeatures a, CandidateFeatures b) {
        int fa = a.statusDeltas == null ? 0 : ChoiceRule.fateChanges(a.statusDeltas, params);
        int fb = b.statusDeltas == null ? 0 : ChoiceRule.fateChanges(b.statusDeltas, params);
        return fa != fb ? fa < fb : a.afterLead > b.afterLead;
    }

    // why a deeper look puts a move short of the author's, or null: more stones change fate
    // than a stone or two, or the lead falls more than the status margin behind
    private String fallsShort(Node node, ProblemIsolator.IsolatedProblem iso, DecisionFeatureExtractor extractor,
                              Deep deep, Deep author) {
        Map<String, Double> deltas = extractor.statusDeltas(node, deep.kar(), author.kar());
        if (ChoiceRule.fateChanges(deltas, params) > ChoiceRule.allowedChanges(params, iso.stakes)) {
            return "a different life status from the author's" + changedFate(deltas) + " (lead " + df.format(deep.lead())
                    + " against " + df.format(author.lead()) + ")";
        }
        if (deep.lead() < author.lead() - params.statusScoreMargin) {
            return "its lead " + df.format(deep.lead()) + " is more than " + params.statusScoreMargin + " behind the author's "
                    + df.format(author.lead());
        }
        return null;
    }

    // the stones a status change is made of, for the report
    private String changedFate(Map<String, Double> statusDeltas) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Double> e : statusDeltas.entrySet()) {
            if (Math.abs(e.getValue()) > params.statusThreshold) changed.add(e.getKey());
        }
        return changed.isEmpty() ? "" : ": " + String.join(" ", changed) + " change fate";
    }

    // the solver's lead after the moves, with a deeper search, and katago's lines from there.
    // the first move is the solver's
    private Deep deepLook(DecisionFeatureExtractor extractor, Node node, List<String> moves) throws Exception {
        Node cur = node;
        Node first = null;
        try {
            for (String move : moves) {
                Point p = Intersection.gtp2point(move.substring(2));
                if (!cur.legalMove(p)) throw new IOException("illegal move " + move);
                cur = cur.addBasicMove(p.x, p.y);
                if (first == null) first = cur;
            }
            KataAnalysisResult kar = extractor.analyzeNear(cur, verifyVisits);
            if (kar.isError()) throw new IOException("deeper analysis failed: " + kar.error);
            return new Deep(sign(moves.get(0)) * kar.blackScore(), katagoLines(moves, kar), kar);
        } finally {
            if (first != null) node.removeChildNode(first);
        }
    }

    // katago's own lines after the moves: the moves, then the principal variation of its best
    // answer and of every other answer with at least a tenth of the best one's visits. an
    // answer that makes a ko the opponent cannot win on a board without threats scores
    // poorly, but it is still the opponent's try in a game
    static List<List<String>> katagoLines(List<String> moves, KataAnalysisResult kar) {
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

    // lines from a position, generated by the redo generator so they answer and end the way
    // redo's would: from after the given moves (the solver to play), with a first move
    // forced or not. the lines start with the given moves
    private List<List<String>> linesFrom(ProblemIsolator.IsolatedProblem iso, Node node, List<String> moves,
                                         String forced) throws Exception {
        Node cur = node;
        Node first = null;
        ProblemIsolator.IsolatedProblem from;
        try {
            for (String move : moves) {
                Point p = Intersection.gtp2point(move.substring(2));
                if (!cur.legalMove(p)) return List.of();
                cur = cur.addBasicMove(p.x, p.y);
                if (first == null) first = cur;
            }
            IsolationRecord sub = new IsolationRecord();
            sub.solver = IsolationRecord.colorLetter(iso.solverColor);
            sub.komi = iso.komi;
            sub.targetLead = iso.targetLead;
            for (Point s : iso.stakes) {
                if (cur.board.board[s.x][s.y].stone != Intersection.EMPTY) {
                    sub.stakes.add(Intersection.toGTPloc(s.x, s.y, 19));
                }
            }
            sub.rows = Positions.rows(cur, iso);
            if (!iso.threats.isEmpty()) {
                sub.threats = new ArrayList<>();
                for (Point t : iso.threats) sub.threats.add(Intersection.toGTPloc(t.x, t.y, 19));
            }
            from = sub.toIsolated();
        } finally {
            if (first != null) node.removeChildNode(first);
        }
        from.solvedKar = iso.solvedKar; // the stones at stake end up the same way from here
        // the scan of every point near the stakes is for the positions being checked; at every
        // turn of a generated line it would multiply the time a line takes
        Properties lineProps = new Properties();
        lineProps.putAll(props);
        lineProps.setProperty("redo.scan_dist", "-1");
        SolutionPathGenerator generator = new SolutionPathGenerator(lineProps, brain, from);
        if (forced != null) generator.setForcedFirstMove(Intersection.gtp2point(forced.substring(2)));
        generator.generate();
        // a line that goes on into the ko threats is cut where the ko starts, the way a
        // problem's own ko line ends
        Set<String> threatPoints = new HashSet<>();
        for (Point t : from.threats) threatPoints.add(Intersection.toGTPloc(t.x, t.y, 19));
        List<List<String>> lines = new ArrayList<>();
        for (MovePath line : MovePath.extractRightPaths(from.problem)) {
            List<String> full = new ArrayList<>(moves);
            for (String m : ProblemRecord.moves(line)) {
                if (threatPoints.contains(m.substring(2))) break;
                full.add(m);
            }
            if (!lines.contains(full)) lines.add(full);
        }
        if (lines.isEmpty()) {
            List<String> only = new ArrayList<>(moves);
            if (forced != null) only.add(forced);
            if (!only.isEmpty()) lines.add(only);
        }
        return lines;
    }

    // the problem with the found lines added at their position, for a look in any sgf editor:
    // the first new move carries a MISSING VARIATION comment and every line ends RIGHT
    private File writeSgf(String name, String sgf, Finding f) throws Exception {
        Node root = new Parser().parse(sgf);
        Node at = root;
        for (String m : f.at) {
            at = at.getChildWithMove(Intersection.gtp2point(m.substring(2)));
            if (at == null) throw new IOException("the position is not in the problem file: " + String.join(" ", f.at));
        }
        for (FoundMove m : f.moves) {
            Map<String, Node> added = new HashMap<>();
            for (List<String> line : m.lines) {
                Node cur = at;
                List<String> path = new ArrayList<>();
                for (String move : line) {
                    path.add(move);
                    String key = String.join(" ", path);
                    Node next = added.get(key);
                    if (next == null) {
                        Point p = Intersection.gtp2point(move.substring(2));
                        // a move the file already has (on a wrong line) is reused, not doubled,
                        // and is no longer wrong
                        next = cur.getChildWithMove(p);
                        if (next == null) next = cur.addBasicMove(p.x, p.y);
                        if (next.result == Intersection.WRONG) next.result = 0;
                        if (path.size() == 1) addComment(next, comment(m));
                        added.put(key, next);
                    }
                    cur = next;
                }
                cur.result = Intersection.RIGHT;
            }
        }
        File out = new File(dir, name + "_missing.sgf");
        Files.writeString(out.toPath(), "(" + root.outputSGF(true) + ")\n");
        return out;
    }

    // a node keeps a single comment: new text goes in front of any it has
    private static void addComment(Node node, String text) {
        for (Object a : node.acts) {
            if (a instanceof CommentAction ca) {
                ca.comment = ca.comment == null || ca.comment.isBlank() ? text : text + "\n\n" + ca.comment;
                return;
            }
        }
        node.addAct(new CommentAction(text));
    }

    private String comment(FoundMove m) {
        return "MISSING VARIATION (autoprob): " + pt(m.move) + " also works here. Lead after it "
                + df.format(m.lead) + ", after the author's " + pt(m.authorMove) + " " + df.format(m.authorLead)
                + "; at " + verifyVisits + " visits " + df.format(m.deepLead) + " and " + df.format(m.deepAuthorLead) + ".";
    }

    private void show(Finding f) {
        String bar = "=".repeat(72);
        String side = f.moves.get(0).move.startsWith("B") ? "black" : "white";
        System.out.println();
        System.out.println(bar);
        System.out.println("MISSING VARIATION in " + f.problem + (f.difficulty != null ? " (" + f.difficulty + ")" : ""));
        System.out.println("position: " + (f.at.isEmpty() ? "the start" : "after " + String.join(" ", f.at))
                + ", " + side + " to play");
        System.out.println("the problem accepts: " + String.join(", ", f.accepted.stream().map(MissingVariationFinder::pt).toList()));
        for (FoundMove m : f.moves) {
            System.out.println();
            System.out.println("also works: " + pt(m.move) + "   lead after it " + df.format(m.lead) + ", after the author's "
                    + pt(m.authorMove) + " " + df.format(m.authorLead));
            System.out.println("  deeper look at " + verifyVisits + " visits: " + df.format(m.deepLead) + " against "
                    + df.format(m.deepAuthorLead));
            System.out.println("  came up " + ("scan".equals(m.source) ? "in the scan of points near the stakes"
                    : "as a " + m.source + " move") + ": katago prior "
                    + (m.prior == null ? "-" : new DecimalFormat("0.000").format(m.prior))
                    + (m.visits == null ? "" : ", " + m.visits + " visits")
                    + ", human policy " + (m.humanPolicy == null ? "-" : new DecimalFormat("0.00").format(m.humanPolicy))
                    + (m.stakeDist == null ? "" : ", " + m.stakeDist + " from the stones at stake"));
            if (m.inFileAsWrong) {
                System.out.println("  the problem has " + pt(m.move) + ", but on a line that does not end RIGHT, so it is marked wrong");
                for (Map.Entry<String, Double> r : m.refutations.entrySet()) {
                    System.out.println("  the problem answers it with " + pt(r.getKey()) + "; after that the solver still leads "
                            + df.format(r.getValue()) + " (deeper look), and the lines below show the way through");
                }
            }
            if (m.ko != null) {
                System.out.println("  " + (m.koOnKatagoLine ? "katago's own line" : "its line") + " goes through a ko ("
                        + pt(m.ko) + " takes it)" + (m.authorKo
                        ? ", as the problem's own line does"
                        : m.koWithThreats
                        ? ", but the move works with the opponent holding ko threats: check that ko is not needed"
                        : ": the isolated board has no ko threats, so check the problem allows a ko here"));
            }
            for (List<String> line : m.lines) {
                System.out.println("  line: " + String.join(" ", line.stream().map(MissingVariationFinder::pt).toList()));
            }
        }
        System.out.println();
        System.out.println("wrote " + f.sgf + ": the problem with the line added, its first move commented MISSING VARIATION");
        System.out.println(bar);
    }

    private void log(Outcome o) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", LocalDateTime.now().toString());
        row.put("problem", o.problem);
        row.put("status", o.status);
        if (o.reason != null) row.put("reason", o.reason);
        row.put("positions", o.positions);
        if (o.koSkipped > 0) row.put("koSkipped", o.koSkipped);
        row.put("seconds", o.seconds);
        if (o.finding != null) {
            row.put("at", String.join(" ", o.finding.at));
            row.put("found", o.finding.moves.stream().map(m -> m.move).toList());
        }
        Files.writeString(new File(dir, "log.jsonl").toPath(), new Gson().toJson(row) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static int sign(String move) {
        return move.startsWith("B") ? 1 : -1;
    }

    private static String pt(String move) {
        return move.substring(2);
    }
}
